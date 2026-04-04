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
import random
from typing import List, Tuple, Dict, Any
import math
from PIL import Image
from verl import DataProto
import json
import os
import base64
from io import BytesIO
from datetime import datetime

def to_list_of_dict(batch: DataProto) -> list[dict]:
    tensors = batch.batch
    non_tensor = batch.non_tensor_batch
    batch_size = len(tensors['input_ids'])
    save_list = []
    for bs in range(batch_size):
        save_dict = dict()
        for key, val in tensors.items():
            save_dict[key] = val[bs]
        for key, val in non_tensor.items():
            save_dict[key] = val[bs]
        save_list.append(save_dict)
    return save_list


def torch_to_numpy(tensor, is_object=False):
    if isinstance(tensor, torch.Tensor):
        tensor = tensor.detach().cpu().numpy()
    elif isinstance(tensor, np.ndarray):
        pass
    else:
        raise ValueError(f"Unsupported type: {type(tensor)})")

    if is_object:
        tensor = tensor.astype(object)
    return tensor

def numpy_to_torch(array, device):
    if isinstance(array, np.ndarray):
        array = torch.from_numpy(array).to(device)
    elif isinstance(array, torch.Tensor):
        array = array.to(device)
    else:
        raise ValueError(f"Unsupported type: {type(array)})")
    return array


def process_image(image, max_pixels: int = 16777216, min_pixels: int = 65536, target_width: int = 720):
    # Handle base64 encoded images (e.g., from MobileWorld)
    if isinstance(image, str):
        import base64
        from io import BytesIO
        # Decode base64 string to PIL Image
        try:
            image_data = base64.b64decode(image)
            image = Image.open(BytesIO(image_data))
            image.load()  # Force full decode to catch truncated/corrupted images early
        except Exception as e:
            print(f"Warning: Received truncated/corrupted base64 image, creating placeholder. Error: {e}")
            image = Image.new('RGB', (720, 1600), color=(0, 0, 0))  # Black placeholder
    elif isinstance(image, torch.Tensor):
        image = torch_to_numpy(image)
        if image.max() < 1:
            image = image * 255.0
        if image.dtype != np.uint8:
            image = image.astype(np.uint8)
        image = Image.fromarray(image)
    elif isinstance(image, np.ndarray):
        if image.max() < 1:
            image = image * 255.0
        if image.dtype != np.uint8:
            image = image.astype(np.uint8)
        image = Image.fromarray(image)
    elif isinstance(image, Image.Image):
        # Already a PIL Image — verify it's not truncated
        try:
            image.load()
        except Exception as e:
            print(f"Warning: PIL Image is truncated/corrupted, creating placeholder. Error: {e}")
            image = Image.new('RGB', (720, 1600), color=(0, 0, 0))
    else:
        raise ValueError(f"Unsupported image type: {type(image)}")

    if (image.width * image.height) > max_pixels:
        resize_factor = math.sqrt(max_pixels / (image.width * image.height))
        width, height = int(image.width * resize_factor), int(image.height * resize_factor)
        image = image.resize((width, height))

    if (image.width * image.height) < min_pixels:
        resize_factor = math.sqrt(min_pixels / (image.width * image.height))
        width, height = int(image.width * resize_factor), int(image.height * resize_factor)
        image = image.resize((width, height))


    if image.mode != 'RGB':
        image = image.convert('RGB')
    # image = image.resize((720, 1600))
    return image


def adjust_batch(config, data: DataProto, mode="copy") -> DataProto:
    world_size = config.trainer.n_gpus_per_node * config.trainer.nnodes
    size_divisor_rollout = config.actor_rollout_ref.rollout.log_prob_micro_batch_size_per_gpu * world_size
    if config.algorithm.use_kl_in_reward or config.actor_rollout_ref.actor.use_kl_loss:
        size_divisor_ref = config.actor_rollout_ref.ref.log_prob_micro_batch_size_per_gpu * world_size
    else:
        size_divisor_ref = size_divisor_rollout
    if "multi_modal_inputs" in data.non_tensor_batch:
        size_divisor_actor = config.actor_rollout_ref.actor.ppo_mini_batch_size
    else:
        size_divisor_actor = config.actor_rollout_ref.actor.ppo_micro_batch_size_per_gpu * world_size
    size_divisor = np.lcm.reduce(np.array([size_divisor_ref, size_divisor_rollout, size_divisor_actor])).item()

    # check if the batch size is divisible by the dp size, if not, delete the last few samples to make it divisible
    bs = len(data)
    remainder = bs % size_divisor
    if remainder == 0:
        return data
    
    if mode == "delete":
        # Generate indices to remove, rather than indices to keep
        remove_indices = np.random.choice(bs, remainder, replace=False)
        # Sort remove_indices to maintain stability when deleting
        remove_indices = np.sort(remove_indices)
        
        # Create a boolean mask for elements to keep
        keep_mask = np.ones(bs, dtype=bool)
        keep_mask[remove_indices] = False

        keep_mask_tensor = torch.tensor(keep_mask, dtype=torch.bool, device=data.batch['input_ids'].device)
        # Apply the mask to keep elements in their original order
        tensor_data = data.batch[keep_mask_tensor]
        non_tensor_data = {key: val[keep_mask] for key, val in data.non_tensor_batch.items()}
        adjusted_batch = DataProto(batch=tensor_data, non_tensor_batch=non_tensor_data, meta_info=data.meta_info)
        del data
    elif mode == "copy":
        to_add = size_divisor - remainder
        dup_indices = np.random.choice(bs, to_add, replace=False)
        dup_proto = data.select_idxs(dup_indices)

        adjusted_batch = DataProto.concat([data, dup_proto])
    else:
        raise ValueError(f"Unsupported mode: {mode}")

    return adjusted_batch


def filter_group_data(batch_list : List[Dict],
                        episode_rewards: np.ndarray,
                        episode_lengths: np.ndarray,
                        success: Dict[str, np.ndarray],
                        traj_uid: np.ndarray,
                        tool_callings: np.ndarray,
                        config,
                        last_try: bool = False,
                        ):
    """
    Dynamic Sampling:
    Over-sample and filter out episode group in which all episodes have the same rewards.
    Adopted from DAPO (https://arxiv.org/abs/2503.14476)
    """
    if last_try:
        return batch_list, episode_rewards, episode_lengths, success, traj_uid, tool_callings
    
    batch_size = config.data.train_batch_size
    group_n = config.env.rollout.n
    if group_n <= 1:
        print("Warning: group_n <= 1, no need to adopt dynamic sampling")

    # Handle each group
    keep_indices = np.array([], dtype=np.int64)
    for i in range(batch_size):
        # Get the indices of the current group
        group_indices = np.arange(i * group_n, (i + 1) * group_n)
        group_rewards = episode_rewards[group_indices]

        # check if all group_traj_uid are the same
        for index in group_indices:
            assert batch_list[index][0]['uid'] == batch_list[group_indices[0]][0]['uid']

        # Check if all rewards in the group are the same
        if not np.all(group_rewards == group_rewards[0]):
            # If so, keep the entire group, otherwise, remove it
            keep_indices = np.concatenate((keep_indices, group_indices))
    
    # Filter the batch_list, episode_rewards, episode_lengths, success, and tool_callings based on the keep_indices
    success = {
        key: value[keep_indices]
        for key, value in success.items()
        if len(value) == len(batch_list)
    }
    batch_list = [batch_list[i] for i in keep_indices]
    episode_rewards = episode_rewards[keep_indices]
    episode_lengths = episode_lengths[keep_indices]
    # success = {key: value[keep_indices] for key, value in success.items()}
    traj_uid = traj_uid[keep_indices]
    tool_callings = tool_callings[keep_indices]

    return batch_list, episode_rewards, episode_lengths, success, traj_uid, tool_callings


def save_episode_to_json(
    obs_images: List[str],
    text_actions: List[str],
    task_name: str,
    task_goal: str,
    episode_id: str,
    save_dir: str = "./opengui-rl/episode",
    step_number: int = 0,
    step_reward: float = None,
    step_reward_reason: str = None,
):
    """
    Save episode data (images and model responses) to JSON and image files.
    Each episode is saved in its own directory with structure:
    episode_dir/
        episode.json       # Episode metadata and step records
        images/            # Directory containing all screenshots
            step_0000.png
            step_0001.png
            ...
    
    Args:
        obs_images: List of base64-encoded image strings
        text_actions: List of model responses (actions)
        task_name: Name of the task
        task_goal: Goal description of the task
        episode_id: Unique identifier for this episode
        save_dir: Base directory to save episode data
        step_number: Current step number in the episode
    
    Returns:
        json_path: Absolute path to the saved JSON file
    """
    # Create base save directory if not exists
    save_dir = os.path.abspath(save_dir)
    os.makedirs(save_dir, exist_ok=True)
    
    # Create episode directory: save_dir/task_name/episode_id/
    task_name = task_name or "unknown_task"
    task_name_sanitized = task_name.replace(" ", "_").replace("/", "_")
    episode_dir = os.path.join(save_dir, task_name_sanitized, episode_id)
    os.makedirs(episode_dir, exist_ok=True)
    
    # Create images subdirectory
    images_dir = os.path.join(episode_dir, "images")
    os.makedirs(images_dir, exist_ok=True)
    
    # JSON file path
    json_path = os.path.join(episode_dir, "episode.json")
    
    # Load existing JSON if it exists, otherwise create new
    if os.path.exists(json_path):
        with open(json_path, 'r', encoding='utf-8') as f:
            episode_data = json.load(f)
    else:
        episode_data = {
            "meta_data": {
                "task_name": task_name,
                "task_goal": task_goal,
                "episode_id": episode_id,
                "created_at": datetime.now().isoformat()
            },
            "episode": []
        }
    
    # Process and save each step
    for i, (img_b64, action) in enumerate(zip(obs_images, text_actions)):
        # Save image to file
        if img_b64:
            try:
                # Decode base64 to image
                image_data = base64.b64decode(img_b64)
                image = Image.open(BytesIO(image_data))
                
                # Generate image filename
                img_filename = f"step_{step_number + i:04d}.png"
                img_path = os.path.join(images_dir, img_filename)
                
                # Save image
                image.save(img_path)
                
                # Store absolute path in JSON
                abs_img_path = os.path.abspath(img_path)
            except Exception as e:
                print(f"Warning: Failed to save image at step {step_number + i}: {e}")
                abs_img_path = None
        else:
            abs_img_path = None
        
        # Add step data to episode
        step_data = {
            "step": step_number + i,
            "image_path": abs_img_path,  # Now using absolute path
            "model_response": action,
            "timestamp": datetime.now().isoformat()
        }
        # Add step reward judge info if available
        if step_reward is not None:
            step_data["step_reward"] = step_reward
        if step_reward_reason is not None:
            step_data["step_reward_reason"] = step_reward_reason
        episode_data["episode"].append(step_data)
    
    # Save JSON file
    with open(json_path, 'w', encoding='utf-8') as f:
        json.dump(episode_data, indent=2, ensure_ascii=False, fp=f)
    
    return json_path


def update_episode_result(
    task_name: str,
    episode_id: str,
    eval_score: float,
    eval_reason: str,
    episode_reward: float,
    episode_length: int,
    save_dir: str = "./opengui-rl/episode",
):
    """
    Update an existing episode JSON with the final eval result.
    Called after success_evaluator has computed the result.
    
    Args:
        task_name: Name of the task
        episode_id: Unique identifier for this episode
        eval_score: Evaluation score from the environment
        eval_reason: Evaluation reason/explanation from the environment
        episode_reward: Total reward for the episode
        episode_length: Total steps in the episode
        save_dir: Base directory where episode data is saved
    """
    save_dir = os.path.abspath(save_dir)
    task_name = task_name or "unknown_task"
    task_name_sanitized = task_name.replace(" ", "_").replace("/", "_")
    episode_dir = os.path.join(save_dir, task_name_sanitized, episode_id)
    json_path = os.path.join(episode_dir, "episode.json")
    
    if not os.path.exists(json_path):
        print(f"Warning: Episode JSON not found at {json_path}, skipping result update.")
        return
    
    try:
        with open(json_path, 'r', encoding='utf-8') as f:
            episode_data = json.load(f)
        
        # Add result to meta_data
        episode_data["meta_data"]["success"] = bool(eval_score > 0)
        episode_data["meta_data"]["eval_score"] = float(eval_score)
        episode_data["meta_data"]["eval_reason"] = str(eval_reason) if eval_reason else ""
        episode_data["meta_data"]["episode_reward"] = float(episode_reward)
        episode_data["meta_data"]["episode_length"] = int(episode_length)
        episode_data["meta_data"]["finished_at"] = datetime.now().isoformat()
        
        with open(json_path, 'w', encoding='utf-8') as f:
            json.dump(episode_data, indent=2, ensure_ascii=False, fp=f)
    except Exception as e:
        print(f"Warning: Failed to update episode result for {episode_id}: {e}")

