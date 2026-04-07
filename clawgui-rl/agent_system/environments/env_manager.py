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

from typing import List, Tuple, Dict, Union, Any
from collections import defaultdict
import torch
import time
import numpy as np
from functools import partial
import os
from agent_system.environments.prompts import *
from agent_system.environments.base import EnvironmentManagerBase, to_numpy
from agent_system.memory import SimpleMemory, SearchMemory, MobileWorldMemory
from omegaconf import OmegaConf

def parse_gamefile(infos):
    gamefile = []
    for info in infos:
        if 'extra.gamefile' in info:
            gamefile.append(info['extra.gamefile'])
        else:
            gamefile.append(None)
    return gamefile

def set_gamefile(infos, gamefile):
    for i in range(len(infos)):
        if 'extra.gamefile' in infos[i]:
            infos[i]['extra.gamefile'] = gamefile[i]
        else:
            infos[i]['extra.gamefile'] = None
    return infos




class MobileWorldEnvironmentManager(EnvironmentManagerBase):
    """
    EnvironmentManager for MobileWorld.
    Handles multi-modal observation (text + screenshot images).
    """
    def __init__(self, envs, projection_f, config):
        self.memory = MobileWorldMemory()
        super().__init__(envs, projection_f, config)
        
        # Determine model type: "gui_owl" or "mai_ui" (default)
        self.model_type = getattr(config.env, 'model_type', 'mai_ui')
        if self.model_type == 'gui_owl':
            self.system_prompt = GUI_OWL_SYSTEM_PROMPT
            print(f"[MobileWorld] Using GUI-Owl system prompt (model_type={self.model_type})")
        else:
            self.system_prompt = MOBILE_WORLD_TEMPLATE
            print(f"[MobileWorld] Using MAI-UI system prompt (model_type={self.model_type})")
        
        self._last_valid_images = None  # Track last valid images for fallback
        
        # Step reward judge configuration
        self._step_reward_judge_enabled = getattr(config.env, 'step_reward_judge', False)
        if self._step_reward_judge_enabled:
            self._step_reward_judge_base_url = getattr(config.env, 'step_reward_judge_base_url', '')
            self._step_reward_judge_model_name = getattr(config.env, 'step_reward_judge_model_name', '')
            self._step_reward_judge_api_key = getattr(config.env, 'step_reward_judge_api_key', '')
            print(f"[MobileWorld] Step reward judge ENABLED: model={self._step_reward_judge_model_name}, "
                  f"base_url={self._step_reward_judge_base_url}")
            from agent_system.environments.env_package.mobileworld.judge import call_step_reward_judge
            self._call_step_reward_judge = call_step_reward_judge
        else:
            print("[MobileWorld] Step reward judge DISABLED")
    
    def restart_all_containers(self, **kwargs) -> bool:
        """
        Proxy to MobileWorldEnvs.restart_all_containers().
        Restarts all Docker containers across all hosts.
        """
        return self.envs.restart_all_containers(**kwargs)
    
    def tear_down_all(self):
        """
        Tear down all workers' current tasks.
        Called centrally after the rollout loop finishes.
        Proxies to MobileWorldEnvs.tear_down_all().
        """
        return self.envs.tear_down_all()
    
    def _get_screenshots_with_retry(self, prefix: str, return_b64: bool = True, wait_before: float = 2.0, max_retries: int = 2, auto_replace_server: bool = True) -> Tuple[list, List[int]]:
        """
        Get screenshots with retry logic.
        
        New behavior:
        - Only retry workers that returned empty/corrupted screenshots (not HTTP errors)
        - HTTP errors are treated as server failures — no retry, mark for failover
        - After all retries, use last valid image as fallback for failed workers
        - Returns a list of failed worker indices so the caller can mark them as done
        
        Args:
            prefix: Prefix for screenshot filenames
            return_b64: Whether to return base64-encoded PNG
            wait_before: Seconds to wait before first screenshot (for UI stabilization)
            max_retries: Maximum number of retry rounds for empty/corrupted screenshots
            auto_replace_server: If True, automatically call replace_worker_server for HTTP errors.
                                Set to False during reset() so the caller can handle failover
                                (switch server → re-reset → re-screenshot) instead.
            
        Returns:
            Tuple of (image_obs list, failed_indices list)
            - image_obs: List of base64-encoded PNG strings (fallback images for failures)
            - failed_indices: List of worker indices that permanently failed (need done + failover)
        """
        time.sleep(wait_before)
        
        screenshots = self.envs.get_screenshots(prefix=prefix, return_b64=return_b64)
        image_obs = [s.get('b64_png', '') for s in screenshots]
        
        # Categorize failures
        # HTTP errors (server broken) — don't retry, mark for failover immediately
        http_error_indices = set()
        # Empty/corrupted — can retry
        retryable_indices = []
        
        for i, s in enumerate(screenshots):
            error_type = s.get('error_type', '')
            if error_type == 'http':
                http_error_indices.add(i)
                print(f"[MobileWorld] Worker {i}: HTTP server error, marking for failover")
            elif not image_obs[i]:
                retryable_indices.append(i)
        
        # If all good, return immediately
        if not http_error_indices and not retryable_indices:
            self._last_valid_images = image_obs.copy()
            return image_obs, []
        
        if retryable_indices:
            print(f"[MobileWorld] {len(retryable_indices)} empty/corrupted screenshots, retrying indices: {retryable_indices}")
        
        # Retry only for retryable (empty/corrupted) workers
        for attempt in range(1, max_retries + 1):
            if not retryable_indices:
                break
            
            time.sleep(1.5 * attempt)
            
            retry_results = self.envs.get_screenshots_for_indices(
                retryable_indices, prefix=f"{prefix}_retry{attempt}", return_b64=return_b64
            )
            
            for idx in list(retryable_indices):
                result = retry_results.get(idx, {})
                # If this retry also returned HTTP error, move to http_error set
                if result.get('error_type') == 'http':
                    http_error_indices.add(idx)
                    retryable_indices.remove(idx)
                    print(f"[MobileWorld] Worker {idx}: Retry got HTTP error, marking for failover")
                    continue
                
                b64 = result.get('b64_png', '')
                if b64 and len(b64) > 0:
                    image_obs[idx] = b64
                    retryable_indices.remove(idx)
            
            if not retryable_indices:
                print(f"[MobileWorld] All retryable screenshots recovered after retry {attempt}")
                break
            print(f"[MobileWorld] Still {len(retryable_indices)} empty after retry {attempt}, indices: {retryable_indices}")
        
        # All indices that permanently failed (HTTP errors + still-empty after retries)
        all_failed = list(http_error_indices) + retryable_indices
        
        # Fallback: use last valid image for all failed workers
        for idx in all_failed:
            if self._last_valid_images is not None and idx < len(self._last_valid_images) and self._last_valid_images[idx]:
                image_obs[idx] = self._last_valid_images[idx]
                print(f"[MobileWorld] Worker {idx}: Using last valid image as fallback")
            else:
                # Try any other valid image from the batch
                valid_img = next((img for img in image_obs if img), None)
                if valid_img:
                    image_obs[idx] = valid_img
                    print(f"[MobileWorld] Worker {idx}: Using another worker's image as fallback")
                else:
                    print(f"[MobileWorld] Worker {idx}: WARNING - No valid image available at all!")
        
        # Update last valid images cache
        updated_valid = [img for img in image_obs if img]
        if updated_valid:
            self._last_valid_images = image_obs.copy()
        
        # Try to replace bad servers with spares
        # During reset stage (auto_replace_server=False), the caller handles failover
        # (switch server → re-reset → re-screenshot) instead of replacing here.
        if auto_replace_server:
            for idx in list(http_error_indices):
                replaced = self.envs.replace_worker_server(idx)
                if replaced:
                    print(f"[MobileWorld] Worker {idx}: Server replaced with spare for future use")
        
        return image_obs, all_failed
    
    def reset(self, kwargs):
        """
        Reset the environment with task information from kwargs.
        
        Args:
            kwargs: List of dicts, each containing 'task_name' and optionally 'goal'
                   Can be None (will use default sampling from server)
        
        Returns:
            observations: Dict with 'text', 'image', 'anchor'
            infos: List of info dicts
        
        Note: 
            - env.reset() returns (text_obs, infos) where text_obs is list of goal strings
            - We extract task goals from text_obs and store them in self.tasks
            - Screenshots are fetched separately via get_screenshots()
            - If screenshot fails for a worker, that worker's trajectory is pre-marked as done
        """
        # kwargs is the env_kwargs_list itself (or None)
        env_kwargs_list = kwargs
        
        # Call environment reset with env_kwargs_list
        # Returns: (text_obs, infos) where text_obs = list of goal strings
        text_obs, infos = self.envs.reset(env_kwargs_list=env_kwargs_list)
        
        # Store task goals from text_obs
        self.tasks = text_obs.copy()
        
        # Initialize memory
        self.memory.reset(batch_size=len(text_obs))
        
        # Track which workers are pre-done due to screenshot failure
        self._screenshot_failed_indices = set()
        
        # Get initial screenshots with retry and validation
        # auto_replace_server=False: reset handles failover itself (switch → re-reset → re-screenshot)
        image_obs, failed_indices = self._get_screenshots_with_retry(
            prefix="reset", wait_before=2.0, auto_replace_server=False
        )
        
        # ── Reset-stage failover: switch server → re-reset → re-screenshot ──
        # During reset, the episode hasn't started yet, so we can safely switch
        # to a spare server, re-run task/init, and take a fresh screenshot.
        # This is different from step-stage failover where we can only use fallback.
        #
        # Strategy per failed worker (2 rounds of full spare-pool iteration):
        #   Round 1 & 2: iterate through ALL spare servers
        #     1. Try replace_worker_server (which health-checks before replacing)
        #     2. Re-reset + re-screenshot on new server
        #     3. If failed, put bad server back to spare tail and try next
        #     Wait 10s between rounds for servers to recover
        #   Only give up after exhausting 2 full rounds → fallback
        if failed_indices:
            num_rounds = 2
            print(f"[MobileWorld] Reset screenshot failed for workers {failed_indices}, "
                  f"attempting server failover + re-reset ({num_rounds} rounds of spare pool iteration)...")
            
            still_failed = []
            for idx in failed_indices:
                recovered = False
                
                for round_num in range(1, num_rounds + 1):
                    spare_pool_size = len(self.envs.spare_servers)
                    # Try each spare server in the pool (+ the one we're replacing = spare_pool_size + 1)
                    max_attempts_this_round = spare_pool_size + 1
                    print(f"[MobileWorld] Worker {idx}: Failover round {round_num}/{num_rounds}, "
                          f"spare pool size: {spare_pool_size}")
                    
                    for attempt in range(1, max_attempts_this_round + 1):
                        # Try to replace the server with a healthy spare
                        replaced = self.envs.replace_worker_server(idx)
                        if not replaced:
                            print(f"[MobileWorld] Worker {idx}: No healthy spare server available "
                                  f"(round {round_num}, attempt {attempt}), ending this round")
                            break
                        
                        new_server_url = self.envs.active_servers[idx]
                        
                        # Re-reset this worker on the new server
                        try:
                            env_kw = env_kwargs_list[idx] if env_kwargs_list is not None else {"task_name": self.tasks[idx]}
                            new_obs, new_info = self.envs.reset_single_worker(idx, env_kw)
                            
                            # Check if task_init actually succeeded (worker returns error string on failure)
                            if new_info.get('error'):
                                print(f"[MobileWorld] Worker {idx}: Re-reset on {new_server_url} "
                                      f"task_init failed (round {round_num}, attempt {attempt}): "
                                      f"{new_info['error']}, returning to spare tail and trying next...")
                                self.envs.replace_bad_server_to_spare_tail(idx)
                                continue
                            
                            # Update text_obs and infos for this worker
                            text_obs[idx] = new_obs
                            infos[idx] = new_info
                            self.tasks[idx] = new_obs  # update task goal
                            
                            # Try screenshot on new server
                            time.sleep(2.0)  # Wait for UI stabilization
                            screenshot_results = self.envs.get_screenshots_for_indices(
                                [idx], prefix=f"reset_failover_r{round_num}a{attempt}", return_b64=True
                            )
                            result = screenshot_results.get(idx, {})
                            b64 = result.get('b64_png', '')
                            
                            if b64 and len(b64) > 0:
                                image_obs[idx] = b64
                                print(f"[MobileWorld] Worker {idx}: Server replaced + re-reset + "
                                      f"screenshot OK ✅ (round {round_num}, attempt {attempt})")
                                recovered = True
                                break
                            else:
                                # Screenshot failed on this server — put it back to spare tail
                                print(f"[MobileWorld] Worker {idx}: Server {new_server_url} re-reset OK "
                                      f"but screenshot failed (round {round_num}, attempt {attempt}), "
                                      f"returning to spare tail and trying next...")
                                self.envs.replace_bad_server_to_spare_tail(idx)
                        except Exception as e:
                            # Re-reset failed on this server — put it back to spare tail
                            print(f"[MobileWorld] Worker {idx}: Re-reset on {new_server_url} failed "
                                  f"(round {round_num}, attempt {attempt}): {e}, "
                                  f"returning to spare tail and trying next...")
                            self.envs.replace_bad_server_to_spare_tail(idx)
                    
                    if recovered:
                        break
                    
                    # Round failed — wait 10s before next round for servers to recover
                    if round_num < num_rounds:
                        print(f"[MobileWorld] Worker {idx}: Failover round {round_num} exhausted, "
                              f"waiting 10s before round {round_num + 1}...")
                        time.sleep(10)
                
                if not recovered:
                    still_failed.append(idx)
            
            # Update failed_indices to only those that truly couldn't recover
            failed_indices = still_failed
        
        # Log workers that still failed after failover attempts
        for idx in failed_indices:
            self._screenshot_failed_indices.add(idx)
            infos[idx]['screenshot_failed_on_reset'] = True
            print(f"[MobileWorld] Worker {idx}: Screenshot failed on reset after all failover attempts, "
                  f"using fallback image (trajectory continues)")
        
        # Save initial screenshots so build_text_obs can include them in multi-turn history
        self._init_images = image_obs.copy()
        # Track current observation images (the screenshot the model sees before acting)
        self._current_images = image_obs.copy()
        
        # Log init failures
        for i, info in enumerate(infos):
            if info.get('error'):
                print(f"[MobileWorld] Worker {i} failed to init task '{info.get('task_name', '?')}': {info['error']}")
        
        # Build text observations using system prompt + task goal + screenshot
        full_text_obs = self.build_text_obs(image_obs, init=True)
        
        return {'text': full_text_obs, 'image': image_obs, 'anchor': text_obs}, infos
    
    def step(self, text_actions: List[str]):
        """
        Execute text actions in the environment.
        
        The correct temporal order is:
            1. Model sees screenshot (self._current_images) and produces text_actions
            2. We store (screenshot_before_action, text_action) into memory
            3. Execute action in environment
            4. Get new screenshot (next observation for the model)
            5. If screenshot fails → use fallback image, trajectory continues (not marked as done)
            6. (Optional) Step reward judge: evaluate whether the action was helpful
        
        Args:
            text_actions: List of raw text actions from agent
            
        Returns:
            next_observations: Dict with 'text', 'image', 'anchor'
            rewards: Numpy array of rewards
            dones: Numpy array of done flags
            infos: List of info dicts
        """
        # Save the screenshot BEFORE the action for step reward judge
        prev_images = self._current_images.copy() if self._current_images else None
        
        # Project text actions to API actions
        actions, valids = self.projection_f(text_actions)
        
        # Execute actions in environment
        # Returns: (text_obs, rewards, dones, infos) where text_obs = list of result strings
        text_obs, rewards, dones, infos = self.envs.step(actions)
        
        # Now store history with the correct temporal relationship:
        #   image_obs = screenshot BEFORE the action (what the model saw)
        #   action = what the model decided
        #   text_obs = environment feedback after the action
        self.memory.store({
            'text_obs': text_obs, 
            'action': text_actions, 
            'image_obs': self._current_images
        })
        
        # Get screenshots AFTER actions (this is the next observation)
        image_obs, failed_indices = self._get_screenshots_with_retry(
            prefix=f"step_{len(self.memory[0])}", 
            wait_before=2.0
        )
        
        # For workers that failed screenshot: log warning, use fallback image, but do NOT mark done
        for idx in failed_indices:
            self._screenshot_failed_indices.add(idx)
            infos[idx]['screenshot_failed_on_step'] = True
            print(f"[MobileWorld] Worker {idx}: Screenshot failed during step, "
                  f"using fallback image (trajectory continues)")
        
        # Update current images for the next step
        self._current_images = image_obs.copy()
        
        # ── Step Reward Judge ──
        # When enabled, evaluate each step's contribution using VLM judge.
        # Logic:
        #   - If episode is done (done=True), skip step judge, use eval reward directly.
        #   - If this is the first step (memory has only 1 record), step_reward = 1.
        #   - Otherwise, call VLM judge with prev_image, curr_image, action history.
        if self._step_reward_judge_enabled:
            rewards_np = to_numpy(rewards)
            batch_size = len(text_actions)
            
            for i in range(batch_size):
                # Skip if episode ended (done=True): use eval reward directly, no judge needed
                if dones[i]:
                    eval_score = float(infos[i].get('eval_score', rewards_np[i]))
                    infos[i]['step_reward'] = eval_score
                    infos[i]['step_reward_reason'] = "episode_done_use_eval_reward"
                    rewards_np[i] = eval_score
                    print(f"[StepRewardJudge] Worker {i}: Episode done, using eval reward={eval_score}")
                    continue
                
                # Current step index: len(memory[i]) after store
                current_step_idx = len(self.memory[i])
                
                # First step: reward = 1 (no previous image to compare)
                if current_step_idx <= 1:
                    infos[i]['step_reward'] = 1.0
                    infos[i]['step_reward_reason'] = "first_step_default_reward_1"
                    # Override the outcome reward with step reward
                    rewards_np[i] = 1.0
                    print(f"[StepRewardJudge] Worker {i}: First step, default reward=1")
                    continue
                
                # Call VLM judge for step >= 2 (intermediate steps, episode not done)
                try:
                    step_reward, step_reason = self._call_step_reward_judge(
                        task_goal=self.tasks[i],
                        prev_image_b64=prev_images[i] if prev_images else "",
                        curr_image_b64=image_obs[i],
                        current_action_text=text_actions[i],
                        action_dict=actions[i],
                        memory_records=self.memory[i],
                        current_step_idx=current_step_idx,
                        base_url=self._step_reward_judge_base_url,
                        model_name=self._step_reward_judge_model_name,
                        api_key=self._step_reward_judge_api_key,
                    )
                    infos[i]['step_reward'] = float(step_reward)
                    infos[i]['step_reward_reason'] = step_reason
                    # Override the outcome reward with step reward
                    rewards_np[i] = float(step_reward)
                    print(f"[StepRewardJudge] Worker {i} Step {current_step_idx}: "
                          f"reward={step_reward}, reason={step_reason}")
                except Exception as e:
                    print(f"[StepRewardJudge] Worker {i}: Judge failed, defaulting to reward=1. Error: {e}")
                    infos[i]['step_reward'] = 1.0
                    infos[i]['step_reward_reason'] = f"judge_exception: {str(e)}"
                    rewards_np[i] = 1.0
            
            # Use the step-reward-modified rewards
            rewards = rewards_np
        
        # Build text observations using the NEW screenshot as current observation
        full_text_obs = self.build_text_obs(image_obs)
        
        # Add action validity to infos
        for i, info in enumerate(infos):
            info['is_action_valid'] = to_numpy(valids[i])
        
        next_observations = {'text': full_text_obs, 'image': image_obs, 'anchor': text_obs}
        rewards = to_numpy(rewards)
        dones = to_numpy(dones)
        
        return next_observations, rewards, dones, infos
    
    def _hide_history_images(self, messages: List[dict], images: List, history_n: int):
        """
        Limit the number of images by removing older image user messages.
        Keep only the most recent history_n images (including current observation).
        
        This mirrors MobileWorld evaluation logic in mai_ui_agent.py:
        all assistant responses (thinking + tool_call) are preserved,
        but only the most recent history_n screenshots are kept.
        
        The images list corresponds 1-to-1 with the <image> placeholders in messages
        (in order of appearance). When we remove an image message, we must also remove
        the corresponding entry from the images list.
        
        Args:
            messages: List of message dicts (will be modified in-place)
            images: List of image data corresponding to <image> placeholders (in order)
            history_n: Number of images to keep (including current image)
            
        Returns:
            Tuple of (filtered_messages, filtered_images)
        """
        if history_n <= 0 or len(images) <= history_n:
            return messages, images
        
        # Collect indices of user messages that contain <image> (in forward order)
        # Each such message corresponds to images[image_order] where image_order
        # is its position among all image messages (0-indexed, forward)
        image_msg_indices_forward = []
        for idx in range(len(messages)):
            msg = messages[idx]
            if msg["role"] == "user" and "<image>" in msg.get("content", ""):
                image_msg_indices_forward.append(idx)
        
        total_images = len(image_msg_indices_forward)
        if total_images <= history_n:
            return messages, images
        
        # We want to keep the LAST history_n image messages, remove the earlier ones
        num_to_remove = total_images - history_n
        # Indices of message entries to remove (the oldest num_to_remove image messages)
        msg_indices_to_remove = image_msg_indices_forward[:num_to_remove]
        # Corresponding image list indices to remove (0, 1, ..., num_to_remove-1)
        
        # Remove messages from back to front to preserve indices
        for idx in sorted(msg_indices_to_remove, reverse=True):
            del messages[idx]
        
        # Remove corresponding images from the front
        images = images[num_to_remove:]
        
        return messages, images

    def _format_previous_steps_guiowl(self, records: list, start_idx: int, end_idx: int) -> str:
        """
        Render history steps [start_idx, end_idx) as plain text for GUI-Owl.
        
        Each line: Step<N>: <conclusion>.
        
        In training:
        - conclusion: extracted from the model's "Action:" line (e.g. "点击主屏幕上的淘店app图标")
        
        Args:
            records: List of memory records (each has 'action', 'text_obs', 'image_obs')
            start_idx: Start index (inclusive)
            end_idx: End index (exclusive)
            
        Returns:
            Formatted string of previous steps
        """
        previous_steps = []
        for i in range(start_idx, end_idx):
            step_num = i + 1
            record = records[i]
            raw_action = record.get("action", "")
            
            # Extract "Action:" line from model output as conclusion
            # GUI-Owl output format: "Action: <description>\n<tool_call>...</tool_call>"
            conclusion = raw_action
            if "Action:" in raw_action:
                action_parts = raw_action.split("Action:")
                if len(action_parts) > 1:
                    # Get just the action description (before <tool_call>)
                    action_content = action_parts[1]
                    tool_parts = action_content.split("<tool_call>")
                    conclusion = tool_parts[0].strip().strip('"')
            
            # Add period at the end of conclusion if not present
            # (mirrors add_period_robustly in gui_owl inference)
            if conclusion and not conclusion.endswith(('.', '。', '!', '！', '?', '？')):
                conclusion = conclusion + '。'
            
            step_info = f"Step{step_num}: {conclusion}"
            
            previous_steps.append(step_info)
        
        return "\n".join(previous_steps)

    def _build_text_obs_guiowl(self, image_obs: List[str], init: bool = False) -> List:
        """
        Build text observations for GUI-Owl model.
        
        GUI-Owl always uses a flat structure: system + ONE user message.
        All completed turns are collapsed into plain-text "previous_steps".
        The most recent `history_length` screenshots are appended as images
        inside that single user message.
        
        Conversation structure:
            system: <system_prompt>
            user: [text: instruction + (optional previous_steps)]
                  [image: screenshot_N-2] [image: screenshot_N-1] [image: current_screenshot]
        
        When history_length=1 (default): only current screenshot (matches inference).
        When history_length=3: up to 3 screenshots (recent history images + current).
        
        Args:
            image_obs: List of base64-encoded PNG strings (current observation images)
            init: Whether this is initial observation
            
        Returns:
            List of dictionaries with 'messages' + 'images' keys
        """
        postprocess_text_obs = []
        history_length = getattr(self.config.env, 'history_length', 1)
        # Number of images to keep (including current screenshot). At least 1.
        num_images = max(history_length, 1)
        
        for i in range(len(image_obs)):
            if init:
                # Initial: system + user (instruction text + 1 screenshot)
                user_text = GUI_OWL_USER_PROMPT_TEMPLATE.format(instruction=self.tasks[i])
                obs_data = {
                    "messages": [
                        {"role": "system", "content": self.system_prompt},
                        {"role": "user", "content": user_text + "\n<image>"},
                    ],
                    "images": [image_obs[i]]
                }
            else:
                all_records = self.memory[i]
                total_history_count = len(all_records)  # completed assistant turns
                
                # ── All history → plain text previous_steps ──
                if total_history_count > 0:
                    previous_steps_text = self._format_previous_steps_guiowl(
                        all_records, 0, total_history_count
                    )
                    user_text = GUI_OWL_USER_PROMPT_WITH_HISTSTEPS_TEMPLATE.format(
                        instruction=self.tasks[i],
                        previous_steps=previous_steps_text,
                    )
                else:
                    user_text = GUI_OWL_USER_PROMPT_TEMPLATE.format(
                        instruction=self.tasks[i]
                    )
                
                # ── Collect the most recent num_images screenshots ──
                # Available history images (in chronological order):
                #   index 0: init_image (screenshot before action 0)
                #   index k (1..total_history_count-1): all_records[k].image_obs
                #       (screenshot before action k, i.e. result of action k-1)
                #   current: image_obs[i] (screenshot after last action)
                #
                # We want the most recent `num_images` from this sequence.
                # Total available = total_history_count + 1 (history images + current)
                #   but history images start from init_image, then records[1..N-1].image_obs
                #
                # Build full chronological image list, then take tail.
                all_images = [self._init_images[i]]  # index 0: init screenshot
                for k in range(1, total_history_count):
                    all_images.append(
                        all_records[k].get("image_obs", self._init_images[i])
                    )
                all_images.append(image_obs[i])  # current screenshot (always last)
                
                # Take the most recent num_images
                recent_images = all_images[-num_images:]
                
                # ── Build user content: text + N × <image> ──
                image_placeholders = "\n".join(["<image>"] * len(recent_images))
                user_content = user_text + "\n" + image_placeholders
                
                obs_data = {
                    "messages": [
                        {"role": "system", "content": self.system_prompt},
                        {"role": "user", "content": user_content},
                    ],
                    "images": recent_images
                }
            
            postprocess_text_obs.append(obs_data)
        
        return postprocess_text_obs

    def build_text_obs(self, image_obs: List[str], init: bool = False) -> List:
        """
        Build text observations for multi-modal input.
        Dispatches to GUI-Owl or MAI-UI logic based on self.model_type.
        """
        # Dispatch to GUI-Owl if model_type is gui_owl
        if self.model_type == 'gui_owl':
            return self._build_text_obs_guiowl(image_obs, init)
        
        # ── Original MAI-UI logic below (unchanged) ──
        #
        # Mirrors MobileWorld evaluation (mai_ui_agent.py) logic:
        # 1. Build FULL conversation history (all assistant responses preserved)
        # 2. Use _hide_history_images to keep only the most recent history_length
        #    images (including current observation image)
        #
        # Conversation structure (aligned with mai_ui_agent._build_messages):
        #   system: <system_prompt>
        #   user: <task_instruction>                (text-only instruction)
        #   user: <image>                           (initial screenshot)
        #   assistant: <action_0>                   (model's first response)
        #   user: <image>                           (screenshot after action_0)
        #   assistant: <action_1>
        #   ...
        #   user: <image>                           (current screenshot)
        #
        # After _hide_history_images, older <image> user messages are removed,
        # but the instruction message and ALL assistant messages remain.
        #
        # The images list is maintained in 1-to-1 correspondence with <image>
        # placeholders in messages (in order of appearance), which is required
        # by the downstream vision token replacement logic.
        postprocess_text_obs = []
        
        history_length = getattr(self.config.env, 'history_length', 0)
        
        for i in range(len(image_obs)):
            if init:
                # Initial observation: system prompt + instruction (text) + screenshot (image)
                # Aligned with mai_ui_agent._build_messages:
                #   messages[0] = system prompt
                #   messages[1] = user text (instruction)
                #   messages[2] = user image (initial screenshot)
                obs_data = {
                    "system_prompt": self.system_prompt,
                    "user_instruction": self.tasks[i],  # text-only, no <image>
                    "images": [image_obs[i]]  # Only current image
                }
            else:
                # Use ALL history records (not truncated)
                all_records = self.memory[i]
                
                # Build messages list for full conversation
                messages = [
                    {"role": "system", "content": self.system_prompt}
                ]
                images = []
                
                # First: user text message (instruction only, no image)
                messages.append({
                    "role": "user",
                    "content": self.tasks[i]
                })
                
                # Second: user image message (initial screenshot)
                messages.append({
                    "role": "user",
                    "content": "<image>"
                })
                images.append(self._init_images[i])
                
                # Add ALL conversation history
                # Each record stores: image_obs (screenshot BEFORE action), action, text_obs
                # Conversation flow:
                #   assistant: action_0
                #   user: <image> (screenshot before action_1 = record[1].image_obs)
                #   assistant: action_1
                #   ...
                for j, record in enumerate(all_records):
                    action = record.get("action", "")
                    
                    # Assistant responds with action
                    messages.append({
                        "role": "assistant",
                        "content": action
                    })
                    
                    # After assistant acts, show the next screenshot
                    if j < len(all_records) - 1:
                        next_image = all_records[j + 1].get("image_obs")
                        if next_image:
                            messages.append({
                                "role": "user",
                                "content": "<image>"
                            })
                            images.append(next_image)
                
                # Add current observation screenshot
                messages.append({
                    "role": "user",
                    "content": "<image>"
                })
                images.append(image_obs[i])
                
                # Apply image limiting: keep only the most recent history_length images
                # This mirrors mai_ui_agent._hide_history_images behavior
                if history_length > 0:
                    messages, images = self._hide_history_images(messages, images, history_length)
                
                # Verify: number of <image> in messages must equal len(images)
                image_count_in_messages = sum(
                    1 for msg in messages 
                    if msg["role"] == "user" and "<image>" in msg.get("content", "")
                )
                assert image_count_in_messages == len(images), (
                    f"Mismatch: {image_count_in_messages} <image> placeholders in messages "
                    f"but {len(images)} images in list"
                )
                
                obs_data = {
                    "messages": messages,
                    "images": images
                }
            
            postprocess_text_obs.append(obs_data)
        
        return postprocess_text_obs



class RealDeviceEnvironmentManager(EnvironmentManagerBase):
    """
    EnvironmentManager for Real Device (physical Android phone).
    
    Simplified from MobileWorldEnvironmentManager:
    - No task_init / tear_down / eval (no predefined tasks on server)
    - No spare server / failover / container restart logic
    - Shares the same multi-modal observation building logic (screenshots + text)
    - Reuses MobileWorld projection (same action space)
    - Step reward judge is optional (same as MobileWorld)
    """
    def __init__(self, envs, projection_f, config):
        self.memory = MobileWorldMemory()
        super().__init__(envs, projection_f, config)
        
        # Determine model type
        self.model_type = getattr(config.env, 'model_type', 'mai_ui')
        if self.model_type == 'gui_owl':
            self.system_prompt = GUI_OWL_SYSTEM_PROMPT
            print(f"[RealDevice] Using GUI-Owl system prompt (model_type={self.model_type})")
        else:
            self.system_prompt = MOBILE_WORLD_TEMPLATE
            print(f"[RealDevice] Using MAI-UI system prompt (model_type={self.model_type})")
        
        self._last_valid_images = None
        
        # Step reward judge configuration (same as MobileWorld)
        self._step_reward_judge_enabled = getattr(config.env, 'step_reward_judge', False)
        if self._step_reward_judge_enabled:
            self._step_reward_judge_base_url = getattr(config.env, 'step_reward_judge_base_url', '')
            self._step_reward_judge_model_name = getattr(config.env, 'step_reward_judge_model_name', '')
            self._step_reward_judge_api_key = getattr(config.env, 'step_reward_judge_api_key', '')
            print(f"[RealDevice] Step reward judge ENABLED: model={self._step_reward_judge_model_name}")
            from agent_system.environments.env_package.mobileworld.judge import call_step_reward_judge
            self._call_step_reward_judge = call_step_reward_judge
        else:
            print("[RealDevice] Step reward judge DISABLED")

        # Task eval judge configuration (VLM-based task completion evaluation)
        # Since real devices have no server-side /task/eval, we use a VLM to judge task completion
        self._task_eval_judge_enabled = getattr(config.env, 'task_eval_judge', False)
        if self._task_eval_judge_enabled:
            self._task_eval_judge_base_url = getattr(config.env, 'task_eval_judge_base_url', '')
            self._task_eval_judge_model_name = getattr(config.env, 'task_eval_judge_model_name', '')
            self._task_eval_judge_api_key = getattr(config.env, 'task_eval_judge_api_key', '')
            print(f"[RealDevice] Task eval judge ENABLED: model={self._task_eval_judge_model_name}")
            from agent_system.environments.env_package.realdevice.judge import call_task_eval_judge
            self._call_task_eval_judge = call_task_eval_judge
        else:
            print("[RealDevice] Task eval judge DISABLED")

    def _get_screenshots_with_retry(self, prefix: str, return_b64: bool = True,
                                     wait_before: float = 2.0, max_retries: int = 2) -> Tuple[list, List[int]]:
        """
        Get screenshots with retry logic. Simplified — no failover.
        """
        time.sleep(wait_before)
        
        screenshots = self.envs.get_screenshots(prefix=prefix, return_b64=return_b64)
        image_obs = [s.get('b64_png', '') for s in screenshots]
        
        # Find failed indices
        retryable_indices = [i for i, img in enumerate(image_obs) if not img]
        
        if not retryable_indices:
            self._last_valid_images = image_obs.copy()
            return image_obs, []
        
        print(f"[RealDevice] {len(retryable_indices)} empty screenshots, retrying...")
        
        for attempt in range(1, max_retries + 1):
            if not retryable_indices:
                break
            time.sleep(1.5 * attempt)
            
            retry_results = self.envs.get_screenshots_for_indices(
                retryable_indices, prefix=f"{prefix}_retry{attempt}", return_b64=return_b64
            )
            for idx in list(retryable_indices):
                b64 = retry_results.get(idx, {}).get('b64_png', '')
                if b64:
                    image_obs[idx] = b64
                    retryable_indices.remove(idx)
        
        # Fallback for still-failed
        for idx in retryable_indices:
            if self._last_valid_images and idx < len(self._last_valid_images) and self._last_valid_images[idx]:
                image_obs[idx] = self._last_valid_images[idx]
                print(f"[RealDevice] Worker {idx}: Using last valid image as fallback")
        
        if any(img for img in image_obs):
            self._last_valid_images = image_obs.copy()
        
        return image_obs, retryable_indices

    def reset(self, kwargs):
        """
        Reset environment with task goals from kwargs.
        """
        env_kwargs_list = kwargs
        text_obs, infos = self.envs.reset(env_kwargs_list=env_kwargs_list)
        
        self.tasks = text_obs.copy()
        self.memory.reset(batch_size=len(text_obs))
        
        # Get initial screenshots
        image_obs, failed_indices = self._get_screenshots_with_retry(prefix="reset", wait_before=2.0)
        
        for idx in failed_indices:
            infos[idx]['screenshot_failed_on_reset'] = True
            print(f"[RealDevice] Worker {idx}: Screenshot failed on reset, using fallback")
        
        self._init_images = image_obs.copy()
        self._current_images = image_obs.copy()
        
        full_text_obs = self.build_text_obs(image_obs, init=True)
        
        return {'text': full_text_obs, 'image': image_obs, 'anchor': text_obs}, infos

    def step(self, text_actions: List[str]):
        """
        Execute actions. Same logic as MobileWorldEnvironmentManager.step().
        """
        prev_images = self._current_images.copy() if self._current_images else None
        
        actions, valids = self.projection_f(text_actions)
        text_obs, rewards, dones, infos = self.envs.step(actions)
        
        self.memory.store({
            'text_obs': text_obs,
            'action': text_actions,
            'image_obs': self._current_images
        })
        
        image_obs, failed_indices = self._get_screenshots_with_retry(
            prefix=f"step_{len(self.memory[0])}", wait_before=2.0
        )
        
        for idx in failed_indices:
            infos[idx]['screenshot_failed_on_step'] = True
        
        self._current_images = image_obs.copy()
        
        # Step Reward Judge (same logic as MobileWorld)
        if self._step_reward_judge_enabled:
            rewards_np = to_numpy(rewards)
            batch_size = len(text_actions)
            
            for i in range(batch_size):
                if dones[i]:
                    # Episode done — skip step reward, task_eval handles final reward below
                    infos[i]['step_reward'] = 0.0
                    infos[i]['step_reward_reason'] = "episode_done_eval_below"
                    rewards_np[i] = 0.0
                    continue
                
                current_step_idx = len(self.memory[i])
                
                # For real device, ALL steps (including first) go through judge
                try:
                    step_reward, step_reason = self._call_step_reward_judge(
                        task_goal=self.tasks[i],
                        prev_image_b64=prev_images[i] if prev_images else "",
                        curr_image_b64=image_obs[i],
                        current_action_text=text_actions[i],
                        action_dict=actions[i],
                        memory_records=self.memory[i],
                        current_step_idx=current_step_idx,
                        base_url=self._step_reward_judge_base_url,
                        model_name=self._step_reward_judge_model_name,
                        api_key=self._step_reward_judge_api_key,
                    )
                    infos[i]['step_reward'] = float(step_reward)
                    infos[i]['step_reward_reason'] = step_reason
                    rewards_np[i] = float(step_reward)
                except Exception as e:
                    infos[i]['step_reward'] = 1.0
                    infos[i]['step_reward_reason'] = f"judge_exception: {str(e)}"
                    rewards_np[i] = 1.0
            
            rewards = rewards_np
        
        # Task Eval Judge — called when episode is done (agent said answer/terminate/status)
        # This replaces server-side /task/eval that doesn't exist for real devices
        if self._task_eval_judge_enabled:
            rewards_np = to_numpy(rewards)
            batch_size = len(text_actions)
            
            for i in range(batch_size):
                if not dones[i]:
                    continue
                
                # Episode is done — evaluate task completion via VLM
                try:
                    eval_score, eval_reason = self._call_task_eval_judge(
                        task_goal=self.tasks[i],
                        prev_image_b64=prev_images[i] if prev_images else "",
                        final_image_b64=image_obs[i],
                        last_action_text=text_actions[i],
                        num_steps=len(self.memory[i]),
                        base_url=self._task_eval_judge_base_url,
                        model_name=self._task_eval_judge_model_name,
                        api_key=self._task_eval_judge_api_key,
                    )
                    rewards_np[i] = eval_score
                    infos[i]['eval_score'] = eval_score
                    infos[i]['eval_reason'] = eval_reason
                    infos[i]['won'] = (eval_score == 1.0)
                    print(f"[RealDevice] Worker {i} task eval: score={eval_score}, "
                          f"reason={eval_reason[:100]}")
                except Exception as e:
                    print(f"[RealDevice] Worker {i} task eval failed: {e}")
                    rewards_np[i] = 0.0
                    infos[i]['eval_score'] = 0.0
                    infos[i]['eval_reason'] = f"eval_exception: {str(e)}"
                    infos[i]['won'] = False
            
            rewards = rewards_np
        
        full_text_obs = self.build_text_obs(image_obs)
        
        for i, info in enumerate(infos):
            info['is_action_valid'] = to_numpy(valids[i])
        
        next_observations = {'text': full_text_obs, 'image': image_obs, 'anchor': text_obs}
        rewards = to_numpy(rewards)
        dones = to_numpy(dones)
        
        return next_observations, rewards, dones, infos

    def build_text_obs(self, image_obs: List[str], init: bool = False) -> List:
        """
        Build text observations. Same multi-modal format as MobileWorld.
        Reuses MobileWorldEnvironmentManager's logic (MAI-UI style).
        """
        postprocess_text_obs = []
        history_length = getattr(self.config.env, 'history_length', 0)
        
        for i in range(len(image_obs)):
            if init:
                obs_data = {
                    "system_prompt": self.system_prompt,
                    "user_instruction": self.tasks[i],
                    "images": [image_obs[i]]
                }
            else:
                all_records = self.memory[i]
                
                messages = [
                    {"role": "system", "content": self.system_prompt}
                ]
                images = []
                
                messages.append({"role": "user", "content": self.tasks[i]})
                messages.append({"role": "user", "content": "<image>"})
                images.append(self._init_images[i])
                
                for j, record in enumerate(all_records):
                    action = record.get("action", "")
                    messages.append({"role": "assistant", "content": action})
                    
                    if j < len(all_records) - 1:
                        next_image = all_records[j + 1].get("image_obs")
                        if next_image:
                            messages.append({"role": "user", "content": "<image>"})
                            images.append(next_image)
                
                messages.append({"role": "user", "content": "<image>"})
                images.append(image_obs[i])
                
                if history_length > 0:
                    messages, images = self._hide_history_images(messages, images, history_length)
                
                obs_data = {"messages": messages, "images": images}
            
            postprocess_text_obs.append(obs_data)
        
        return postprocess_text_obs

    def _hide_history_images(self, messages: List[dict], images: List, history_n: int):
        """Same as MobileWorldEnvironmentManager._hide_history_images."""
        if history_n <= 0 or len(images) <= history_n:
            return messages, images
        
        image_msg_indices = [
            idx for idx in range(len(messages))
            if messages[idx]["role"] == "user" and "<image>" in messages[idx].get("content", "")
        ]
        
        total_images = len(image_msg_indices)
        if total_images <= history_n:
            return messages, images
        
        num_to_remove = total_images - history_n
        msg_indices_to_remove = image_msg_indices[:num_to_remove]
        
        for idx in sorted(msg_indices_to_remove, reverse=True):
            del messages[idx]
        
        images = images[num_to_remove:]
        return messages, images

    def _process_batch(self, batch_idx, total_batch_list, total_infos, success):
        """Process batch results for logging."""
        for i in reversed(range(len(total_batch_list[batch_idx]))):
            batch_item = total_batch_list[batch_idx][i]
            if batch_item['active_masks']:
                info = total_infos[batch_idx][i]
                won_value = float(info.get('won', False))
                success['success_rate'].append(won_value)
                return
        print(f"Warning: No active_masks found for batch {batch_idx} (RealDevice), defaulting success_rate to 0.0")
        success['success_rate'].append(0.0)


def make_envs(config):
    """
    Create enviroments 
    """ 
    # check if config.env.rollout.n is an integer
    if not isinstance(config.env.rollout.n, int):
        raise ValueError("config.env.rollout.n should be an integer")
    group_n = config.env.rollout.n if config.env.rollout.n > 0 else 1
    resources_per_worker = OmegaConf.to_container(config.env.resources_per_worker, resolve=True)

    if "mobileworld" in config.env.env_name.lower():
        from agent_system.environments.env_package.mobileworld import build_mobileworld_envs, mobileworld_projection, guiowl_mobileworld_projection
        
        # Select projection function based on model_type
        model_type = getattr(config.env, 'model_type', 'mai_ui')
        if model_type == 'gui_owl':
            projection_f = partial(guiowl_mobileworld_projection)
            print(f"[make_envs] Using GUI-Owl projection (model_type={model_type})")
        else:
            projection_f = partial(mobileworld_projection)
            print(f"[make_envs] Using MAI-UI projection (model_type={model_type})")
        
        # Read server_file from config (None = use default mobileworld_server.txt in package dir)
        server_file = config.env.get('server_file', None)
        
        # Build training and validation environments
        _envs = build_mobileworld_envs(
            dataset_name='train',
            max_interactions=config.env.get('max_steps', 50),
            seed=config.env.seed,
            env_num=config.data.train_batch_size,
            group_n=group_n,
            start_server_id=0,
            resources_per_worker=resources_per_worker,
            server_file=server_file,
            device=config.env.get('device', 'emulator-5554')
        )
        
        # Validation environments - reuse servers starting from 0
        _val_envs = build_mobileworld_envs(
            dataset_name='test',
            max_interactions=config.env.get('max_steps', 50),
            seed=config.env.seed + 1000,
            env_num=config.data.val_batch_size,
            group_n=1,
            start_server_id=0,  # Changed: reuse servers from 0 to avoid index out of bounds
            resources_per_worker=resources_per_worker,
            server_file=server_file,
            device=config.env.get('device', 'emulator-5554')
        )
        
        envs = MobileWorldEnvironmentManager(_envs, projection_f, config)
        val_envs = MobileWorldEnvironmentManager(_val_envs, projection_f, config)
        return envs, val_envs
    elif "realdevice" in config.env.env_name.lower():
        from agent_system.environments.env_package.realdevice import build_realdevice_envs, realdevice_projection, guiowl_realdevice_projection
        
        # Select projection function based on model_type
        model_type = getattr(config.env, 'model_type', 'mai_ui')
        if model_type == 'gui_owl':
            projection_f = partial(guiowl_realdevice_projection)
            print(f"[make_envs] RealDevice: Using GUI-Owl projection (model_type={model_type})")
        else:
            projection_f = partial(realdevice_projection)
            print(f"[make_envs] RealDevice: Using MAI-UI projection (model_type={model_type})")
        
        server_file = config.env.get('server_file', None)
        device = config.env.get('device', None)
        
        # device can be comma-separated list, e.g., "dev1,dev2"
        # For val_envs, we only need val_batch_size devices (subset of train devices)
        val_device = device
        if device and device.strip().lower() != 'auto':
            device_list = [d.strip() for d in device.split(',') if d.strip()]
            val_batch_size = config.data.val_batch_size
            if len(device_list) > val_batch_size:
                val_device = ','.join(device_list[:val_batch_size])
        
        _envs = build_realdevice_envs(
            max_interactions=config.env.get('max_steps', 50),
            seed=config.env.seed,
            env_num=config.data.train_batch_size,
            group_n=group_n,
            resources_per_worker=resources_per_worker,
            server_file=server_file,
            device=device,
        )
        
        _val_envs = build_realdevice_envs(
            max_interactions=config.env.get('max_steps', 50),
            seed=config.env.seed + 1000,
            env_num=config.data.val_batch_size,
            group_n=1,
            resources_per_worker=resources_per_worker,
            server_file=server_file,
            device=val_device,
        )
        
        envs = RealDeviceEnvironmentManager(_envs, projection_f, config)
        val_envs = RealDeviceEnvironmentManager(_val_envs, projection_f, config)
        return envs, val_envs
    else:
        print("Environment not supported")
        exit(1)