"""
Task-level evaluation judge for RealDevice environment.
Uses a VLM (Vision-Language Model) to determine whether the agent 
has successfully completed the given task, by comparing the previous and final screenshots.

Since real devices have no server-side /task/eval, we use a VLM as a substitute.
This is called when the agent outputs "answer", "terminate", or "status" action.
"""

import base64
import json
import re
import traceback
from io import BytesIO
from typing import Tuple

from PIL import Image


TASK_EVAL_JUDGE_PROMPT = """You are an expert evaluator for mobile device task completion. Your job is to determine whether the agent has successfully completed the given task on the phone.

## Task Goal
{task_goal}

## Action History Summary
The agent performed {num_steps} steps to attempt this task.
Last action: {last_action}

## Visual Input
- [Image 1] The PREVIOUS screenshot (before the last action was executed).
- [Image 2] The FINAL screenshot (after the last action — the current screen state).

By comparing the two screenshots, you can see what changed as a result of the last action and whether progress was made.

## Evaluation Criteria

You must determine if the task goal has been **fully achieved** based on the final screenshot (Image 2). Use the previous screenshot (Image 1) for context to understand what changed.

### Success (score=1)
The final screen state clearly shows the task has been completed. Examples:
- If the task was to "open Settings", the Settings app is visible and active.
- If the task was to "search for X", the search results for X are displayed.
- If the task was to "send a message to Y", the message appears in the chat.
- If the task was to "set alarm for 7:00 AM", the alarm is visible in the alarm list.
- If the task was to "turn on WiFi", WiFi is shown as enabled.

### Failure (score=0)
The final screen state does NOT confirm task completion. Examples:
- The screen shows a different app or page than expected.
- The task required a specific action but the screen doesn't reflect it.
- The agent appears to be stuck on an intermediate step.
- The screen shows an error or unexpected state.
- There is no clear evidence that the task was accomplished.

## Important Notes
- Be strict: only give score=1 if you can clearly see evidence of task completion.
- Consider partial completion as failure (score=0) unless the core objective is met.
- If the screenshot is unclear or corrupted, default to score=0.

## Output Format
Output strictly in the following JSON format, with no additional text:
{{"reason": "Brief explanation of why the task succeeded or failed", "score": 0 or 1}}""".strip()


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


def call_task_eval_judge(
    task_goal: str,
    prev_image_b64: str,
    final_image_b64: str,
    last_action_text: str,
    num_steps: int,
    base_url: str,
    model_name: str,
    api_key: str,
    timeout: int = 60,
) -> Tuple[float, str]:
    """
    Call the VLM judge to evaluate whether the task has been completed.
    
    Sends TWO screenshots to the VLM:
    - Previous screenshot (before the last action)
    - Final screenshot (current screen state after the last action)
    
    This gives the VLM better context to understand what changed.
    
    Args:
        task_goal: The task goal description
        prev_image_b64: Base64-encoded previous screenshot (before last action)
        final_image_b64: Base64-encoded final screenshot after episode ends
        last_action_text: The last action text from the agent
        num_steps: Total number of steps taken
        base_url: OpenAI-compatible API base URL
        model_name: Model name to use
        api_key: API key
        timeout: Request timeout in seconds
    
    Returns:
        Tuple of (score, reason)
        - score: 0.0 or 1.0
        - reason: Judge's explanation
    """
    from openai import OpenAI
    
    # Build the prompt
    prompt_text = TASK_EVAL_JUDGE_PROMPT.format(
        task_goal=task_goal,
        num_steps=num_steps,
        last_action=last_action_text,
    )
    
    # Build image content: [Image 1] previous screenshot, [Image 2] final screenshot
    image_content = []
    
    # Image 1: Previous screenshot
    if prev_image_b64:
        image_content.append({
            "type": "image_url",
            "image_url": {
                "url": f"data:image/png;base64,{prev_image_b64}"
            },
        })
    
    # Image 2: Final screenshot (always present)
    image_content.append({
        "type": "image_url",
        "image_url": {
            "url": f"data:image/png;base64,{final_image_b64}"
        },
    })
    
    # Add the text prompt after images
    image_content.append({
        "type": "text",
        "text": prompt_text,
    })
    
    messages = [
        {"role": "system", "content": "You are an expert evaluator for mobile device task completion."},
        {
            "role": "user",
            "content": image_content,
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
        print(f"[TaskEvalJudge] Raw response: {content}")
        
        # Parse the JSON response
        score, reason = _parse_eval_response(content)
        return score, reason
        
    except Exception as e:
        print(f"[TaskEvalJudge] API call failed: {e}")
        traceback.print_exc()
        # Default to score=0 on failure (pessimistic — don't reward failures)
        return 0.0, f"eval_api_error: {str(e)}"


def _parse_eval_response(content: str) -> Tuple[float, str]:
    """
    Parse the eval judge model's response to extract score and reason.
    
    Args:
        content: Raw response content from the model
    
    Returns:
        Tuple of (score, reason)
    """
    # Try direct JSON parse
    try:
        data = json.loads(content)
        score = float(data.get("score", 0))
        reason = data.get("reason", "")
        return (1.0 if score >= 0.5 else 0.0), reason
    except (json.JSONDecodeError, ValueError):
        pass
    
    # Try to extract JSON from within the text
    json_match = re.search(r'\{[^{}]*"score"\s*:\s*[01][^{}]*\}', content, re.DOTALL)
    if json_match:
        try:
            data = json.loads(json_match.group())
            score = float(data.get("score", 0))
            reason = data.get("reason", "")
            return (1.0 if score >= 0.5 else 0.0), reason
        except (json.JSONDecodeError, ValueError):
            pass
    
    # Try regex extraction as last resort
    score_match = re.search(r'"score"\s*:\s*(\d)', content)
    reason_match = re.search(r'"reason"\s*:\s*"([^"]*)"', content)
    
    if score_match:
        score = float(score_match.group(1))
        reason = reason_match.group(1) if reason_match else "parsed_via_regex"
        return (1.0 if score >= 0.5 else 0.0), reason
    
    # Complete parse failure
    print(f"[TaskEvalJudge] Warning: Failed to parse eval response: {content[:200]}")
    return 0.0, "parse_failed_pessimistic_fallback"
