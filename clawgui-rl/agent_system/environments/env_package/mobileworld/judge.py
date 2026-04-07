"""
Step-level reward judge for MobileWorld environment.
Uses a VLM (Vision-Language Model) to evaluate whether each step 
contributes positively toward completing the task.
"""

import base64
import json
import re
import traceback
from io import BytesIO
from typing import Dict, List, Optional, Tuple

from PIL import Image, ImageDraw


STEP_REWARD_JUDGE_PROMPT = """You are an expert in evaluating mobile operation agents. Your task is to analyze the task goal, action history, current action, and the visual changes in screenshots to determine if the current step provides a positive contribution toward completing the task.

## Task Goal
{task_goal}

## Action History (From most recent to earliest)
{action_history}

## Current Action
{current_action}

## Visual Input
- [Image 1] Screenshot BEFORE the action. (For click actions, a red dot indicates the interaction point to help you identify the element).
- [Image 2] Screenshot AFTER the action.

## Analysis Process
Please analyze following these steps:
1. **Goal Understanding**: Clarify what the task requires for successful completion.
2. **Progress Assessment**: Based on the action history, determine the current progress stage.
3. **Action Analysis**: Identify exactly what was done in the current step (clicked element, input text, or scroll direction).
4. **Screenshot Comparison**: Compare Image 1 and Image 2 to observe if any meaningful UI changes occurred.
5. **Final Judgment**: Synthesize the above analysis to determine if the action was helpful.

## Evaluation Standards

### Helpful (reward=1)
The action moves the interface state toward the task goal. Examples:
- Opened the correct app related to the task.
- Clicked a button or link that advances the task workflow.
- Entered the correct content as required by the task.
- Navigated to a page necessary for completing the task.

### Unhelpful (reward=0)
The action failed to advance the task. Examples:
- Screenshots before and after are nearly identical (action was ineffective or failed to trigger).
- The current action is a complete repetition of the previous step (stuck in a loop).
- Clicked on an irrelevant area or element.
- Mis-touch caused the UI to deviate from the goal direction.
- Opened the wrong application or page.

## Output Format
Output strictly in the following JSON format, with no additional text:
{{"reason": "Brief explanation of the judgment", "reward": 0 or 1}}""".strip()


# Action types that have coordinate info (need red dot visualization)
COORD_ACTION_TYPES = {"click", "long_press", "double_tap", "drag", "swipe"}


def _b64_to_pil(b64_str: str) -> Image.Image:
    """Convert base64 string to PIL Image."""
    image_data = base64.b64decode(b64_str)
    image = Image.open(BytesIO(image_data))
    image.load()
    return image


def _pil_to_b64(image: Image.Image, fmt: str = "PNG") -> str:
    """Convert PIL Image to base64 string."""
    buf = BytesIO()
    image.save(buf, format=fmt)
    return base64.b64encode(buf.getvalue()).decode("utf-8")


def draw_red_dot_on_image(
    b64_image: str, 
    action_dict: dict,
    device_width: int = 1080,
    device_height: int = 2400,
    dot_radius: int = 15,
) -> str:
    """
    Draw a red dot on the image at the action coordinate position.
    
    The action coordinates are in device pixel space (e.g., 1080x2400).
    The image size is read directly from the actual image. We need to scale.
    
    Args:
        b64_image: Base64-encoded image string
        action_dict: Parsed action dictionary from projection.py
                     (already converted to absolute device coords)
        device_width: Width of the device screen
        device_height: Height of the device screen
        dot_radius: Radius of the red dot
    
    Returns:
        Base64-encoded image with red dot drawn
    """
    action_type = action_dict.get("action_type", "")
    
    # Only draw for actions that have coordinates
    if action_type not in COORD_ACTION_TYPES:
        return b64_image
    
    try:
        image = _b64_to_pil(b64_image)
        # Read actual image dimensions
        image_width, image_height = image.size
        
        draw = ImageDraw.Draw(image)
        
        # Scale factor from device coords to image coords
        scale_x = image_width / device_width
        scale_y = image_height / device_height
        
        if action_type in ("click", "long_press", "double_tap"):
            x = action_dict.get("x", 0)
            y = action_dict.get("y", 0)
            # Convert device coords to image coords
            img_x = int(x * scale_x)
            img_y = int(y * scale_y)
            # Draw red filled circle
            draw.ellipse(
                [img_x - dot_radius, img_y - dot_radius, 
                 img_x + dot_radius, img_y + dot_radius],
                fill="red",
                outline="red"
            )
        
        elif action_type == "drag":
            start_x = int(action_dict.get("start_x", 0) * scale_x)
            start_y = int(action_dict.get("start_y", 0) * scale_y)
            end_x = int(action_dict.get("end_x", 0) * scale_x)
            end_y = int(action_dict.get("end_y", 0) * scale_y)
            # Draw start dot (red) and end dot (blue) with line
            draw.ellipse(
                [start_x - dot_radius, start_y - dot_radius,
                 start_x + dot_radius, start_y + dot_radius],
                fill="red", outline="red"
            )
            draw.ellipse(
                [end_x - dot_radius, end_y - dot_radius,
                 end_x + dot_radius, end_y + dot_radius],
                fill="blue", outline="blue"
            )
            draw.line([(start_x, start_y), (end_x, end_y)], fill="red", width=3)
        
        elif action_type == "swipe":
            # Swipe may have optional coordinate
            x = action_dict.get("x", None)
            y = action_dict.get("y", None)
            if x is not None and y is not None:
                img_x = int(x * scale_x)
                img_y = int(y * scale_y)
                draw.ellipse(
                    [img_x - dot_radius, img_y - dot_radius,
                     img_x + dot_radius, img_y + dot_radius],
                    fill="red", outline="red"
                )
        
        return _pil_to_b64(image)
    
    except Exception as e:
        print(f"[StepRewardJudge] Warning: Failed to draw red dot on image: {e}")
        return b64_image


def _build_action_history_text(memory_records: list, current_step_idx: int) -> str:
    """
    Build action history text from memory records.
    Only include action text (thinking + tool_call), from most recent to earliest.
    We only use the previous step's action (not the full history beyond that).
    
    Args:
        memory_records: List of memory records from MobileWorldMemory
        current_step_idx: The current step index (0-based, corresponds to len(memory) after store)
    
    Returns:
        Formatted action history string
    """
    if not memory_records or current_step_idx <= 0:
        return "No previous actions."
    
    # memory_records includes all records up to current step
    # We want previous actions: records[0] to records[current_step_idx - 1]
    # Show from most recent to earliest
    history_lines = []
    # Only show up to the last 5 steps to keep prompt manageable
    start_idx = max(0, current_step_idx - 5)
    for j in range(current_step_idx - 1, start_idx - 1, -1):
        record = memory_records[j]
        action_text = record.get("action", "")
        step_num = j + 1
        history_lines.append(f"Step {step_num}: {action_text}")
    
    return "\n".join(history_lines) if history_lines else "No previous actions."


def call_step_reward_judge(
    task_goal: str,
    prev_image_b64: str,
    curr_image_b64: str,
    current_action_text: str,
    action_dict: dict,
    memory_records: list,
    current_step_idx: int,
    base_url: str,
    model_name: str,
    api_key: str,
    timeout: int = 60,
) -> Tuple[int, str]:
    """
    Call the VLM judge to evaluate whether the current step is helpful.
    Uses OpenAI SDK (same as test_rubric_reward.py).
    
    Args:
        task_goal: The task goal description
        prev_image_b64: Base64-encoded screenshot BEFORE the action
        curr_image_b64: Base64-encoded screenshot AFTER the action
        current_action_text: The raw text action from the model
        action_dict: Parsed action dictionary (with absolute device coords)
        memory_records: All memory records for this worker
        current_step_idx: Current step index (0-based)
        base_url: OpenAI-compatible API base URL (e.g. "https://api.moonshot.cn/v1")
        model_name: Model name to use
        api_key: API key
        timeout: Request timeout in seconds
    
    Returns:
        Tuple of (reward, reason)
        - reward: 0 or 1
        - reason: Judge's explanation
    """
    from openai import OpenAI
    
    # Build action history text
    action_history = _build_action_history_text(memory_records, current_step_idx)
    
    # Draw red dot on the BEFORE image for coordinate-based actions
    annotated_prev_image = draw_red_dot_on_image(prev_image_b64, action_dict)
    
    # Build the prompt
    prompt_text = STEP_REWARD_JUDGE_PROMPT.format(
        task_goal=task_goal,
        action_history=action_history,
        current_action=current_action_text,
    )
    
    # Build the messages with images (same format as test_rubric_reward.py)
    messages = [
        {"role": "system", "content": "You are an expert in evaluating mobile operation agents."},
        {
            "role": "user",
            "content": [
                {
                    "type": "image_url",
                    "image_url": {
                        "url": f"data:image/png;base64,{annotated_prev_image}"
                    },
                },
                {
                    "type": "image_url",
                    "image_url": {
                        "url": f"data:image/png;base64,{curr_image_b64}"
                    },
                },
                {
                    "type": "text",
                    "text": prompt_text,
                },
            ],
        },
    ]
    
    try:
        client = OpenAI(
            api_key=api_key,
            base_url=base_url,
            timeout=timeout,
        )
        
        completion = client.chat.completions.create(
            model=model_name,
            messages=messages,
            temperature=0.1,
            extra_body={"chat_template_kwargs": {"enable_thinking": False}},
        )
        
        content = completion.choices[0].message.content.strip()
        print(f"[StepRewardJudge] Raw response: {content}")
        
        # Parse the JSON response
        reward, reason = _parse_judge_response(content)
        return reward, reason
        
    except Exception as e:
        print(f"[StepRewardJudge] API call failed: {e}")
        traceback.print_exc()
        # Default to reward=1 on failure (optimistic fallback)
        return 1, f"judge_api_error: {str(e)}"


def _parse_judge_response(content: str) -> Tuple[int, str]:
    """
    Parse the judge model's response to extract reward and reason.
    
    Args:
        content: Raw response content from the model
    
    Returns:
        Tuple of (reward, reason)
    """
    # Try to find JSON in the response
    # First try direct JSON parse
    try:
        data = json.loads(content)
        reward = int(data.get("reward", 0))
        reason = data.get("reason", "")
        return min(max(reward, 0), 1), reason
    except (json.JSONDecodeError, ValueError):
        pass
    
    # Try to extract JSON from within the text
    json_match = re.search(r'\{[^{}]*"reward"\s*:\s*[01][^{}]*\}', content, re.DOTALL)
    if json_match:
        try:
            data = json.loads(json_match.group())
            reward = int(data.get("reward", 0))
            reason = data.get("reason", "")
            return min(max(reward, 0), 1), reason
        except (json.JSONDecodeError, ValueError):
            pass
    
    # Try regex extraction as last resort
    reward_match = re.search(r'"reward"\s*:\s*(\d)', content)
    reason_match = re.search(r'"reason"\s*:\s*"([^"]*)"', content)
    
    if reward_match:
        reward = int(reward_match.group(1))
        reason = reason_match.group(1) if reason_match else "parsed_via_regex"
        return min(max(reward, 0), 1), reason
    
    # Complete parse failure
    print(f"[StepRewardJudge] Warning: Failed to parse judge response: {content[:200]}")
    return 1, "parse_failed_optimistic_fallback"
