# Copyright 2025 Nanyang Technological University (NTU), Singapore
# and the verl-agent (GiGPO) team.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import torch
import numpy as np
from verl import DataProto
from verl.utils.dataset.rl_dataset import collate_fn
from verl.utils.model import compute_position_id_with_mask
import verl.utils.torch_functional as verl_F
from transformers import PreTrainedTokenizer
import uuid
from agent_system.multi_turn_rollout.utils import process_image, to_list_of_dict, torch_to_numpy, filter_group_data, save_episode_to_json, update_episode_result
from agent_system.environments import EnvironmentManagerBase
from typing import List, Dict
from verl.protocol import pad_dataproto_to_divisor, unpad_dataproto

class TrajectoryCollector:
    def __init__(self, config, tokenizer: PreTrainedTokenizer, processor=None):
        """
        Initialize the TrajectoryProcessor class.
        
        Parameters:
            config: Configuration object containing data processing settings
            tokenizer (PreTrainedTokenizer): Tokenizer for text encoding and decoding
            processor: Image processor for multimodal inputs
        """
        self.config = config
        self.tokenizer = tokenizer
        self.processor = processor

    def preprocess_single_sample(
        self,
        item: int,
        gen_batch: DataProto,
        obs: Dict,
    ):
        """
        Process a single observation sample, organizing environment observations (text and/or images) 
        into a format processable by the model.
        
        Parameters:
            item (int): Sample index in the batch
            gen_batch (DataProto): Batch data containing original prompts
            obs (Dict): Environment observation, may contain 'text', 'image', 'anchor' keys
        
        Returns:
            dict: Contains processed input data such as input_ids, attention_mask, etc.
        """

        raw_prompt = gen_batch.non_tensor_batch['raw_prompt'][item]
        data_source = gen_batch.non_tensor_batch['data_source'][item]
        apply_chat_template_kwargs = self.config.data.get("apply_chat_template_kwargs", {})
        
        # Get observation components
        obs_texts = obs.get('text', None)
        obs_images = obs.get('image', None)
        obs_anchors = obs.get('anchor', None)
        obs_text = obs_texts[item] if obs_texts is not None else None
        obs_image = obs_images[item] if obs_images is not None else None
        obs_anchor = obs_anchors[item] if obs_anchors is not None else None
        is_multi_modal = obs_image is not None

        _obs_anchor = torch_to_numpy(obs_anchor, is_object=True) if isinstance(obs_anchor, torch.Tensor) else obs_anchor

        # Build chat structure
        # obs_content = raw_prompt[0]['content']
        # if '<image>' in obs_content: 
        #     obs_content = obs_content.replace('<image>', '')

        # Build chat structure
        # Handle MobileWorld's special data format
        if isinstance(obs_text, dict):
            # MobileWorld returns dict with 'system_prompt' + 'user_instruction' or 'messages'
            if 'messages' in obs_text:
                # Multi-turn: use messages directly
                chat = np.array(obs_text['messages'])
                prompt_with_chat_template = self.tokenizer.apply_chat_template(
                    chat,
                    add_generation_prompt=True,
                    tokenize=False,
                    **apply_chat_template_kwargs
                )
                # Extract images list from obs_text
                images_list = obs_text.get('images', [])
            elif 'system_prompt' in obs_text and 'user_instruction' in obs_text:
                # Initial turn: system prompt + instruction (text) + screenshot (<image>)
                # Aligned with mai_ui_agent._build_messages:
                #   messages[0] = system
                #   messages[1] = user text (instruction)
                #   messages[2] = user image (<image> placeholder)
                chat = np.array([
                    {"role": "system", "content": obs_text['system_prompt']},
                    {"role": "user", "content": obs_text['user_instruction']},
                    {"role": "user", "content": "<image>"}
                ])
                prompt_with_chat_template = self.tokenizer.apply_chat_template(
                    chat,
                    add_generation_prompt=True,
                    tokenize=False,
                    **apply_chat_template_kwargs
                )
                # Extract images list from obs_text
                images_list = obs_text.get('images', [])
            else:
                raise ValueError(f"Unknown MobileWorld obs_text format: {obs_text.keys()}")
        else:
            # Standard format: plain text observation
            obs_content = ''
            if obs_text is not None:
                obs_content += obs_text
            else:
                print(f"Warning: No text observation found!")

            
            chat = np.array([{
                "content": obs_content,
                "role": "user",
            }])
            
            # Apply chat template
            prompt_with_chat_template = self.tokenizer.apply_chat_template(
                chat,
                add_generation_prompt=True,
                tokenize=False,
                **apply_chat_template_kwargs
            )
            # For standard format, use single image if available
            images_list = [obs_image] if obs_image is not None else []

        # Initialize return dict
        row_dict = {}
        
        # Process multimodal data
        if is_multi_modal:
            # Process all images in the list (history + current)
            processed_images = [process_image(img) for img in images_list if img]
            # _processed_sizes = [img.size for img in processed_images]
            # print(f"[DEBUG process_image] after resize: {_processed_sizes}")
            num_image_placeholders = prompt_with_chat_template.count('<image>')
            
            # Replace image placeholder with vision tokens
            raw_prompt = prompt_with_chat_template.replace('<image>', '<|vision_start|><|image_pad|><|vision_end|>')
            row_dict['multi_modal_data'] = {'image': processed_images}
            image_inputs = self.processor.image_processor(row_dict['multi_modal_data']['image'], return_tensors='pt')
            image_grid_thw = image_inputs['image_grid_thw']
            row_dict['multi_modal_inputs'] = {key: val for key, val in image_inputs.items()}
            
            if image_grid_thw is not None:
                merge_length = self.processor.image_processor.merge_size**2
                
                # Verify alignment: <image> count == image_grid_thw count == processed_images count
                assert num_image_placeholders == len(processed_images) == image_grid_thw.shape[0], (
                    f"MISMATCH! <image> in prompt: {num_image_placeholders}, "
                    f"processed_images: {len(processed_images)}, "
                    f"image_grid_thw rows: {image_grid_thw.shape[0]}"
                )
                
                # Collect per-image vision token info for logging
                vision_token_details = []
                index = 0
                while '<image>' in prompt_with_chat_template:
                    num_vision_tokens = image_grid_thw[index].prod() // merge_length
                    vision_token_details.append(f"img{index}:{image_grid_thw[index].tolist()}->{num_vision_tokens}tok")
                    prompt_with_chat_template = prompt_with_chat_template.replace(
                        '<image>',
                        '<|vision_start|>' + '<|placeholder|>' * (num_vision_tokens) +
                        '<|vision_end|>',
                        1,
                    )
                    index += 1
                
                total_vision_tokens = sum(image_grid_thw[i].prod() // merge_length for i in range(image_grid_thw.shape[0]))
                # print(f"[VisionEncode] item={item} | images={len(processed_images)} | "
                #       f"placeholders={num_image_placeholders} | total_vision_tokens={total_vision_tokens} | "
                #       f"details=[{', '.join(vision_token_details)}]")

                prompt_with_chat_template = prompt_with_chat_template.replace('<|placeholder|>',
                                                                                self.processor.image_token)

        else:
            raw_prompt = prompt_with_chat_template

        input_ids, attention_mask = verl_F.tokenize_and_postprocess_data(prompt=prompt_with_chat_template,
                                                                            tokenizer=self.tokenizer,
                                                                            max_length=self.config.data.max_prompt_length,
                                                                            pad_token_id=self.tokenizer.pad_token_id,
                                                                            left_pad=True,
                                                                            truncation=self.config.data.truncation,)
        
        if is_multi_modal:

            if "Qwen3VLProcessor" in self.processor.__class__.__name__:
                from verl.models.transformers.qwen3_vl import get_rope_index
            else:
                from verl.models.transformers.qwen2_vl import get_rope_index

            vision_position_ids = get_rope_index(
                self.processor,
                input_ids=input_ids[0],
                image_grid_thw=image_grid_thw,
                attention_mask=attention_mask[0],
            )  # (3, seq_length)
            valid_mask = attention_mask[0].bool()
            text_position_ids = torch.ones((1, len(input_ids[0])), dtype=torch.long)
            text_position_ids[0, valid_mask] = torch.arange(valid_mask.sum().item())
            position_ids = [torch.cat((text_position_ids, vision_position_ids), dim=0)]  # (1, 4, seq_length)
        else:
            position_ids = compute_position_id_with_mask(attention_mask)

        raw_prompt_ids = self.tokenizer.encode(raw_prompt, add_special_tokens=False)
        if len(raw_prompt_ids) > self.config.data.max_prompt_length:
            if self.config.data.truncation == "left":
                raw_prompt_ids = raw_prompt_ids[-self.config.data.max_prompt_length :]
            elif self.config.data.truncation == "right":
                raw_prompt_ids = raw_prompt_ids[: self.config.data.max_prompt_length]
            elif self.config.data.truncation == "middle":
                left_half = self.config.data.max_prompt_length // 2
                right_half = self.config.data.max_prompt_length - left_half
                raw_prompt_ids = raw_prompt_ids[:left_half] + raw_prompt_ids[-right_half:]
            elif self.config.data.truncation == "error":
                raise RuntimeError(f"Prompt length {len(raw_prompt_ids)} is longer than {self.config.data.max_prompt_length}.")

        # Build final output dict
        row_dict.update({
            'input_ids': input_ids[0],
            'attention_mask': attention_mask[0],
            'position_ids': position_ids[0],
            'raw_prompt_ids': raw_prompt_ids,
            'anchor_obs': _obs_anchor,
            'index': item,
            'data_source': data_source
        })

        if self.config.data.get('return_raw_chat', False):
            row_dict['raw_prompt'] = chat.tolist()
        
        return row_dict

    def preprocess_batch(
        self,
        gen_batch: DataProto, 
        obs: Dict, 
    ) -> DataProto:
        """
        Process a batch of observation samples, converting environment observations into model-processable format.
        
        Parameters:
            gen_batch (DataProto): Batch data containing original prompts
            obs (Dict): Environment observation dictionary
                - 'text' (None or List[str]): Text observation data
                - 'image' (np.ndarray or torch.Tensor): Image observation data
                - 'anchor' (None or Any): Anchor observation without any histories or additional info. (for GiGPO only).
        
        Returns:
            DataProto: Contains processed batch data with preserved metadata
        """
        batch_size = len(gen_batch.batch['input_ids'])
        processed_samples = []
        
        # Process each sample in parallel
        for item in range(batch_size):
            # Extract per-sample observations
            processed = self.preprocess_single_sample(
                item=item,
                gen_batch=gen_batch,
                obs=obs,
            )
            processed_samples.append(processed)
        
        # Aggregate batch data
        batch = collate_fn(processed_samples)
        
        # Create DataProto with preserved metadata
        new_batch = DataProto.from_single_dict(
            data=batch,
            meta_info=gen_batch.meta_info
        )

        return new_batch


    def gather_rollout_data(
            self,
            total_batch_list: List[List[Dict]],
            episode_rewards: np.ndarray,
            episode_lengths: np.ndarray,
            success: Dict[str, np.ndarray],
            traj_uid: np.ndarray,
            tool_callings: np.ndarray,
            ) -> DataProto:
        """
        Collect and organize trajectory data, handling batch size adjustments to meet parallel training requirements.
        
        Parameters:
            total_batch_list (List[List[Dict]): List of trajectory data for each environment
            episode_rewards (np.ndarray): Total rewards for each environment
            episode_lengths (np.ndarray): Total steps for each environment
            success (Dict[str, np.ndarray]): Success samples for each environment
            traj_uid (np.ndarray): Trajectory unique identifiers
            tool_callings (np.ndarray): Number of tool callings for each environment
        Returns:
            DataProto: Collected and organized trajectory data
        """
        batch_size = len(total_batch_list)
        
        success_rate = {}
        for key, value in success.items():
            success_rate[key] = np.mean(value)
        
        effective_batch = []
        for bs in range(batch_size):
            # sum the rewards for each data in total_batch_list[bs]
            for step_idx, data in enumerate(total_batch_list[bs]):
                assert traj_uid[bs] == data['traj_uid'], "data is not from the same trajectory"
                if data['active_masks']:
                    # episode_rewards
                    data['episode_rewards'] = episode_rewards[bs]
                    # episode_lengths
                    data['episode_lengths'] = episode_lengths[bs]
                    # tool_callings
                    data['tool_callings'] = tool_callings[bs]
                    # success_rate
                    for key, value in success_rate.items():
                        data[key] = value

                    effective_batch.append(data)
        
        # Convert trajectory data to DataProto format
        gen_batch_output = DataProto.from_single_dict(
            data=collate_fn(effective_batch)
        )
        return gen_batch_output

    def vanilla_multi_turn_loop(
            self,
            gen_batch: DataProto, 
            actor_rollout_wg, 
            envs: EnvironmentManagerBase,
            ) -> DataProto:
        """
        Collects trajectories through parallel agent-environment agent_loop.
        Parameters:
            gen_batch (DataProto): Initial batch with prompts to start the agent_loop
            actor_rollout_wg (WorkerGroup): Worker group containing the actor model for policy decisions
            envs (EnvironmentManagerBase): Environment manager containing parallel environment instances
        
        Returns:
            total_batch_list (List[Dict]): List of trajectory data for each environment
            episode_rewards (np.ndarray): Total rewards for each environment
            episode_lengths (np.ndarray): Total steps for each environment
            success (Dict[str, np.ndarray]): Success samples for each environment
            traj_uid (np.ndarray): Trajectory unique identifiers
        """

        batch_size = len(gen_batch.batch)

        # Initial observations from the environment
        obs, infos = envs.reset(kwargs=gen_batch.non_tensor_batch.pop('env_kwargs', None))

        lenght_obs = len(obs['text']) if obs['text'] is not None else len(obs['image'])
        assert len(gen_batch.batch) == lenght_obs, f"gen_batch size {len(gen_batch.batch)} does not match obs size {lenght_obs}"
        
        if self.config.env.rollout.n > 0: # env grouping
            uid_batch = []
            for i in range(batch_size):
                if i % self.config.env.rollout.n == 0:
                    uid = str(uuid.uuid4())
                uid_batch.append(uid)
            uid_batch = np.array(uid_batch, dtype=object)
        else: # no env grouping, set all to the same uid
            uid = str(uuid.uuid4())
            uid_batch = np.array([uid for _ in range(len(gen_batch.batch))], dtype=object)
        is_done = np.zeros(batch_size, dtype=bool)
        
        traj_uid = np.array([str(uuid.uuid4()) for _ in range(batch_size)], dtype=object)
        total_batch_list = [[] for _ in range(batch_size)]
        total_infos = [[] for _ in range(batch_size)]
        episode_lengths = np.zeros(batch_size, dtype=np.float32)
        episode_rewards = np.zeros(batch_size, dtype=np.float32)
        tool_callings = np.zeros(batch_size, dtype=np.float32)
        
        # Save the first observation (before any step) for MobileWorld
        first_obs = obs  # Save the initial observation
        
        max_steps = self.config.env.max_steps
        env_name = self.config.env.get('env_name', 'unknown')

        # Trajectory collection loop
        for _step in range(max_steps):
            active_masks = np.logical_not(is_done)

            active_count = int(active_masks.sum())
            done_count = int(is_done.sum())

            # Save current observation for this step before processing
            current_obs = obs

            batch = self.preprocess_batch(gen_batch=gen_batch, obs=obs)

            batch_keys_to_pop = ["input_ids", "attention_mask", "position_ids"]
            non_tensor_batch_keys_to_pop = ["raw_prompt_ids"]
            if "multi_modal_data" in batch.non_tensor_batch:
                non_tensor_batch_keys_to_pop.append("multi_modal_data")
            if "raw_prompt" in batch.non_tensor_batch:
                non_tensor_batch_keys_to_pop.append("raw_prompt")
            if "tools_kwargs" in batch.non_tensor_batch:
                non_tensor_batch_keys_to_pop.append("tools_kwargs")
            batch_input = batch.pop(
                batch_keys=batch_keys_to_pop,
                non_tensor_batch_keys=non_tensor_batch_keys_to_pop,
            )

            batch_input.meta_info = gen_batch.meta_info

            # pad to be divisible by dp_size
            batch_input_padded, pad_size = pad_dataproto_to_divisor(batch_input, actor_rollout_wg.world_size)
            batch_output_padded = actor_rollout_wg.generate_sequences(batch_input_padded)
            # # unpad
            batch_output = unpad_dataproto(batch_output_padded, pad_size=pad_size)

            batch.non_tensor_batch['uid'] = uid_batch
            batch.non_tensor_batch['traj_uid'] = traj_uid

            batch = batch.union(batch_output)
            
            text_actions = self.tokenizer.batch_decode(batch.batch['responses'], skip_special_tokens=True)
            next_obs, rewards, dones, infos = envs.step(text_actions)

            # ── Per-worker print: step info, model output, env feedback ──
            for i in range(batch_size):
                task_name = infos[i].get('task_name', None) or gen_batch.non_tensor_batch.get('data_source', ['unknown'] * batch_size)[i]
                status = "ACTIVE" if active_masks[i] else "DONE"
                reward_i = rewards[i] if hasattr(rewards, '__getitem__') else rewards
                done_i = dones[i] if hasattr(dones, '__getitem__') else dones
                print(f"\n{'='*25} Worker {i} | {env_name} | Step {_step + 1}/{max_steps} {'='*25}")
                print(f"  Task  : {task_name}")
                print(f"  Status: {status}")
                print(f"  Action:")
                print(f"{text_actions[i]}")
                print(f"  Reward: {reward_i}  |  Done: {done_i}")
                print(f"{'='*80}")

            if len(rewards.shape) == 2:
                rewards = rewards.squeeze(1)
            if len(dones.shape) == 2:
                # dones is numpy, delete a dimension
                dones = dones.squeeze(1)

            if 'is_action_valid' in infos[0]:
                batch.non_tensor_batch['is_action_valid'] = np.array([info['is_action_valid'] for info in infos], dtype=bool)
            else:
                batch.non_tensor_batch['is_action_valid'] = np.ones(batch_size, dtype=bool)

            if 'tool_calling' in infos[0]:
                tool_callings[active_masks] += np.array([info['tool_calling'] for info in infos], dtype=np.float32)[active_masks]
            # Create reward tensor, only assign rewards for active environments
            # episode_rewards += torch_to_numpy(rewards) * torch_to_numpy(active_masks)
            episode_rewards[active_masks] += torch_to_numpy(rewards)[active_masks]
            episode_lengths[active_masks] += 1
            
    

            assert len(rewards) == batch_size, f"env should return rewards for all environments, got {len(rewards)} rewards for {batch_size} environments"
            batch.non_tensor_batch['rewards'] = torch_to_numpy(rewards, is_object=True)
            batch.non_tensor_batch['active_masks'] = torch_to_numpy(active_masks, is_object=True)
            
            
            # Update episode lengths for active environments
            batch_list: list[dict] = to_list_of_dict(batch)

            for i in range(batch_size):
                total_batch_list[i].append(batch_list[i])
                total_infos[i].append(infos[i])

            # Save episode data for MobileWorld environment (if applicable)
            if self.config.env.env_name and self.config.env.env_name.lower() in ("mobileworld", "realdevice"):
                # Use current_obs (before step) instead of obs (after step)
                obs_images_current = current_obs.get('image', None)
                if obs_images_current is not None:
                    for i in range(batch_size):
                        if active_masks[i]:  # Only save for active environments
                            # Get task info from the current step's info
                            # infos[i] contains task_name and task_goal from the environment
                            task_name = infos[i].get('task_name') or 'unknown_task'
                            task_goal = infos[i].get('task_goal') or 'No goal specified'
                            
                            episode_id = str(traj_uid[i])
                            
                            # Save current step (single image and action)
                            # image: current observation BEFORE the action
                            # action: text_actions[i] is the action taken on this observation
                            try:
                                # Extract step reward info from infos if available
                                _step_reward = infos[i].get('step_reward', None)
                                _step_reward_reason = infos[i].get('step_reward_reason', None)
                                
                                save_episode_to_json(
                                    obs_images=[obs_images_current[i]] if obs_images_current[i] else [],
                                    text_actions=[text_actions[i]],
                                    task_name=task_name,
                                    task_goal=task_goal,
                                    episode_id=episode_id,
                                    step_number=_step,
                                    step_reward=_step_reward,
                                    step_reward_reason=_step_reward_reason,
                                )
                            except Exception as e:
                                print(f"Warning: Failed to save episode data for env {i} at step {_step}: {e}")


            # Update done states
            is_done = np.logical_or(is_done, dones)
                
            # Update observations for next step
            obs = next_obs

            # Break if all environments are done  不是异步的，rollout还是同步了，所有环境rollout完了才可以继续
            if is_done.all():
                break
        
        # ── Normalize step rewards ──
        # When step_reward_judge is enabled, normalize the intermediate step rewards
        # so that their sum equals mean(step_rewards) instead of sum(step_rewards).
        # This prevents longer episodes from getting disproportionately higher rewards.
        # After normalization:
        #   - Each intermediate step's reward = original_step_reward / num_intermediate_steps
        #   - The last step's eval reward remains unchanged
        #   - episode_rewards = mean(step_rewards) + eval_reward, range ~ [0, 2]
        step_reward_judge_enabled = getattr(self.config.env, 'step_reward_judge', False)
        if step_reward_judge_enabled:
            print(f"\n{'='*30} STEP REWARD NORMALIZATION {'='*30}")
            # Recompute episode_rewards with normalized step rewards
            episode_rewards = np.zeros(batch_size, dtype=np.float32)
            for i in range(batch_size):
                steps = total_batch_list[i]
                num_active_steps = sum(1 for s in steps if s['active_masks'])
                if num_active_steps <= 1:
                    # Only 1 step (or none): no intermediate steps to normalize
                    for s in steps:
                        if s['active_masks']:
                            episode_rewards[i] += float(s['rewards'])
                    continue
                
                # num_intermediate_steps = total active steps - 1 (last step is eval reward)
                num_intermediate_steps = num_active_steps - 1
                
                # Find the last active step index
                last_active_idx = -1
                for idx in range(len(steps) - 1, -1, -1):
                    if steps[idx]['active_masks']:
                        last_active_idx = idx
                        break
                
                for idx, s in enumerate(steps):
                    if not s['active_masks']:
                        continue
                    if idx == last_active_idx:
                        # Last step: eval reward, keep unchanged
                        episode_rewards[i] += float(s['rewards'])
                    else:
                        # Intermediate step: normalize by dividing by num_intermediate_steps
                        original_reward = float(s['rewards'])
                        normalized_reward = original_reward / num_intermediate_steps
                        s['rewards'] = np.float64(normalized_reward)  # Update in-place
                        episode_rewards[i] += normalized_reward
                
                print(f"  Worker {i}: {num_active_steps} steps, "
                      f"{num_intermediate_steps} intermediate steps normalized, "
                      f"episode_reward={episode_rewards[i]:.4f}")
            print(f"{'='*80}")

        # ── Centralized tear_down: clean up all workers' tasks after rollout loop ──
        # This avoids the race condition where per-step tear_down resets initialized=False
        # before eval retries can complete on the server side.
        if hasattr(envs, 'tear_down_all'):
            try:
                envs.tear_down_all()
            except Exception as e:
                print(f"[RolloutLoop] WARNING: tear_down_all failed: {e}")
        
        success: Dict[str, np.ndarray] = envs.success_evaluator(
                    total_infos=total_infos,
                    total_batch_list=total_batch_list,
                    episode_rewards=episode_rewards, 
                    episode_lengths=episode_lengths,
                    )
        
        # ── Print: evaluation summary ──
        print(f"\n{'='*30} EVALUATION SUMMARY {'='*30}")
        for key, value in success.items():
            print(f"  {key}: {value}")
        print(f"  Episode Rewards : {episode_rewards}")
        print(f"  Episode Lengths : {episode_lengths}")
        print(f"  Avg Reward: {np.mean(episode_rewards):.4f}  |  Avg Length: {np.mean(episode_lengths):.2f}")
        print(f"{'='*80}")

        # ── Update episode JSON with eval_score/eval_reason ──
        if self.config.env.env_name and self.config.env.env_name.lower() in ("mobileworld", "realdevice"):
            for i in range(batch_size):
                task_name_i = 'unknown_task'
                eval_score_i = 0.0
                eval_reason_i = ''
                # Get eval info from the last active step's info
                for step_infos in reversed(total_infos[i]):
                    if step_infos.get('task_name'):
                        task_name_i = step_infos['task_name']
                    if 'eval_score' in step_infos:
                        eval_score_i = step_infos['eval_score']
                        eval_reason_i = step_infos.get('eval_reason', '')
                        break
                episode_id_i = str(traj_uid[i])
                try:
                    update_episode_result(
                        task_name=task_name_i,
                        episode_id=episode_id_i,
                        eval_score=float(eval_score_i),
                        eval_reason=str(eval_reason_i),
                        episode_reward=float(episode_rewards[i]),
                        episode_length=int(episode_lengths[i]),
                    )
                except Exception as e:
                    print(f"Warning: Failed to update episode result for env {i}: {e}")

        return total_batch_list, episode_rewards, episode_lengths, success, traj_uid, tool_callings
    
    def dynamic_multi_turn_loop(
            self,
            gen_batch: DataProto, 
            actor_rollout_wg, 
            envs: EnvironmentManagerBase,
            ) -> DataProto:
        """
        Conduct dynamic rollouts until a target batch size is met. 
        Keeps sampling until the desired number of effective trajectories is collected.
        Adopted from DAPO (https://arxiv.org/abs/2503.14476)

        Args:
            gen_batch (DataProto): Initial batch for rollout.
            actor_rollout_wg: Actor model workers for generating responses.
            envs (EnvironmentManagerBase): Environment manager instance.

        Returns:
            total_batch_list (List[Dict]): Complete set of rollout steps.
            total_episode_rewards (np.ndarray): Accumulated rewards.
            total_episode_lengths (np.ndarray): Lengths per episode.
            total_success (Dict[str, np.ndarray]): Success metrics.
            total_traj_uid (np.ndarray): Trajectory IDs.
        """
        total_batch_list = []
        total_episode_rewards = []
        total_episode_lengths = []
        total_success = []
        total_traj_uid = []
        total_tool_callings = []
        try_count: int = 0
        max_try_count = self.config.algorithm.filter_groups.max_num_gen_batches

        while len(total_batch_list) < self.config.data.train_batch_size * self.config.env.rollout.n and try_count < max_try_count:

            if len(total_batch_list) > 0:
                print(f"valid num={len(total_batch_list)} < target num={self.config.data.train_batch_size * self.config.env.rollout.n}. Keep generating... ({try_count}/{max_try_count})")
            try_count += 1

            batch_list, episode_rewards, episode_lengths, success, traj_uid, tool_callings = self.vanilla_multi_turn_loop(
                gen_batch=gen_batch,
                actor_rollout_wg=actor_rollout_wg,
                envs=envs,
            )
            batch_list, episode_rewards, episode_lengths, success, traj_uid, tool_callings = filter_group_data(batch_list=batch_list, 
                                                                                                episode_rewards=episode_rewards, 
                                                                                                episode_lengths=episode_lengths, 
                                                                                                success=success, 
                                                                                                traj_uid=traj_uid, 
                                                                                                tool_callings=tool_callings, 
                                                                                                config=self.config,
                                                                                                last_try=(try_count == max_try_count),
                                                                                                )
            
            total_batch_list += batch_list
            total_episode_rewards.append(episode_rewards)
            total_episode_lengths.append(episode_lengths)
            total_success.append(success)
            total_traj_uid.append(traj_uid)
            total_tool_callings.append(tool_callings)

        total_episode_rewards = np.concatenate(total_episode_rewards, axis=0)
        total_episode_lengths = np.concatenate(total_episode_lengths, axis=0)
        total_success = {key: np.concatenate([success[key] for success in total_success], axis=0) for key in total_success[0].keys()}
        total_traj_uid = np.concatenate(total_traj_uid, axis=0)
        total_tool_callings = np.concatenate(total_tool_callings, axis=0)

        return total_batch_list, total_episode_rewards, total_episode_lengths, total_success, total_traj_uid, total_tool_callings

    def multi_turn_loop(
            self,
            gen_batch: DataProto, 
            actor_rollout_wg, 
            envs: EnvironmentManagerBase,
            is_train: bool = True,
            ) -> DataProto:
        """
        Select and run the appropriate rollout loop (dynamic or vanilla).

        Args:
            gen_batch (DataProto): Initial prompt batch.
            actor_rollout_wg: Actor model workers.
            envs (EnvironmentManagerBase): Environment manager for interaction.
            is_train (bool): Whether in training mode (affects dynamic sampling).

        Returns:
            DataProto: Final collected trajectory data with metadata.
        """
        if is_train:
            gen_batch = gen_batch.repeat(repeat_times=self.config.env.rollout.n, interleave=True)
            
        # Initial observations from the environment
        if self.config.algorithm.filter_groups.enable and is_train:
            # Dynamic Sampling (for DAPO and Dynamic GiGPO)
            total_batch_list, total_episode_rewards, total_episode_lengths, total_success, total_traj_uid, totoal_tool_callings = \
                self.dynamic_multi_turn_loop(
                gen_batch=gen_batch,
                actor_rollout_wg=actor_rollout_wg,
                envs=envs,
            )
        else:
            # Vanilla Sampling   
            total_batch_list, total_episode_rewards, total_episode_lengths, total_success, total_traj_uid, totoal_tool_callings = \
                self.vanilla_multi_turn_loop(
                gen_batch=gen_batch,
                actor_rollout_wg=actor_rollout_wg,
                envs=envs,
            )
        assert len(total_batch_list) == len(total_episode_rewards)
        assert len(total_batch_list) == len(total_episode_lengths)
        assert len(total_batch_list) == len(total_traj_uid)
        assert len(total_batch_list) == len(totoal_tool_callings)
        

        # Create trajectory data
        gen_batch_output: DataProto = self.gather_rollout_data(
            total_batch_list=total_batch_list,
            episode_rewards=total_episode_rewards,
            episode_lengths=total_episode_lengths,
            success=total_success,
            traj_uid=total_traj_uid,
            tool_callings=totoal_tool_callings,
        )
        
        return gen_batch_output