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

import json
import re
from typing import List, Tuple


# Default fallback action when parsing fails completely
FALLBACK_ACTION = {"action_type": "wait"}

# Model outputs coordinates in [0, SCALE_FACTOR] normalized space.
# Device screen resolution for coordinate conversion.
SCALE_FACTOR = 999
SCREEN_WIDTH = 1080
SCREEN_HEIGHT = 2400


def _to_absolute_coords(action: dict) -> dict:
    """
    Convert model's normalized coordinates [0, SCALE_FACTOR] to absolute
    device pixel coordinates [0, SCREEN_WIDTH] / [0, SCREEN_HEIGHT].
    """
    if "x" in action and "y" in action:
        action["x"] = int(action["x"] / SCALE_FACTOR * SCREEN_WIDTH)
        action["y"] = int(action["y"] / SCALE_FACTOR * SCREEN_HEIGHT)
    if "start_x" in action and "start_y" in action:
        action["start_x"] = int(action["start_x"] / SCALE_FACTOR * SCREEN_WIDTH)
        action["start_y"] = int(action["start_y"] / SCALE_FACTOR * SCREEN_HEIGHT)
    if "end_x" in action and "end_y" in action:
        action["end_x"] = int(action["end_x"] / SCALE_FACTOR * SCREEN_WIDTH)
        action["end_y"] = int(action["end_y"] / SCALE_FACTOR * SCREEN_HEIGHT)
    return action


def _normalize_text(text: str) -> str:
    """
    Normalize common character issues from model output.
    Fixes Chinese/fullwidth punctuation that breaks JSON parsing.
    """
    replacements = {
        '\uff3b': '[',   # ［ fullwidth left bracket
        '\uff3d': ']',   # ］ fullwidth right bracket
        '\u3010': '[',   # 【
        '\u3011': ']',   # 】
        '\uff5b': '{',   # ｛
        '\uff5d': '}',   # ｝
        '\uff08': '(',   # （
        '\uff09': ')',   # ）
        '\uff0c': ',',   # ，
        '\uff1a': ':',   # ：
        '\u201c': '"',   # "
        '\u201d': '"',   # "
        '\u2018': "'",   # '
        '\u2019': "'",   # '
    }
    for old, new in replacements.items():
        text = text.replace(old, new)
    return text


def _regex_extract_action(text: str) -> dict:
    """
    Regex-based extraction when JSON parsing fails.
    Tries to extract action type and parameters directly from the text.
    """
    # Normalize for regex matching
    text_norm = _normalize_text(text)
    text_lower = text_norm.lower()

    # click / long_press / double_tap with coordinate
    for action_type in ["click", "long_press", "double_tap"]:
        patterns = [
            # JSON-like: "action": "click", ... "coordinate": [376, 365]
            rf'"action"\s*:\s*"{action_type}".*?"coordinate"\s*:\s*\[\s*(\d+)\s*,\s*(\d+)\s*\]',
            # Relaxed quotes: 'action': 'click', ... 'coordinate': [376, 365]
            rf"""['"]action['"]\s*:\s*['"]({action_type})['"].*?['"]coordinate['"]\s*:\s*\[\s*(\d+)\s*,\s*(\d+)\s*\]""",
            # Function-like: click(376, 365)
            rf'\b{action_type}\s*\(\s*(\d+)\s*,\s*(\d+)\s*\)',
            # Natural language: click at (376, 365) or click at 376, 365
            rf'\b{action_type}\s+(?:at\s+)?(?:\(\s*)?(\d+)\s*,\s*(\d+)(?:\s*\))?',
        ]
        for pat in patterns:
            m = re.search(pat, text_lower, re.DOTALL)
            if m:
                groups = m.groups()
                if len(groups) == 3:
                    x, y = int(groups[1]), int(groups[2])
                else:
                    x, y = int(groups[0]), int(groups[1])
                return {"action_type": action_type, "x": x, "y": y}

    # swipe with direction
    m = re.search(r'"action"\s*:\s*"swipe".*?"direction"\s*:\s*"(up|down|left|right)"', text_lower, re.DOTALL)
    if not m:
        m = re.search(r'\bswipe\s+(up|down|left|right)\b', text_lower)
    if m:
        direction = m.group(1)
        result = {"action_type": "swipe", "direction": direction}
        # Optionally extract coordinate for swipe
        cm = re.search(r'"coordinate"\s*:\s*\[\s*(\d+)\s*,\s*(\d+)\s*\]', text_lower)
        if cm:
            result["x"] = int(cm.group(1))
            result["y"] = int(cm.group(2))
        return result

    # type/input text
    m = re.search(r'"action"\s*:\s*"type".*?"text"\s*:\s*"([^"]*)"', text_norm, re.DOTALL)
    if m:
        return {"action_type": "input_text", "text": m.group(1)}

    # open app
    m = re.search(r'"action"\s*:\s*"open".*?"text"\s*:\s*"([^"]*)"', text_norm, re.DOTALL)
    if m:
        return {"action_type": "open_app", "app_name": m.group(1)}

    # drag
    m = re.search(
        r'"action"\s*:\s*"drag".*?"start_coordinate"\s*:\s*\[\s*(\d+)\s*,\s*(\d+)\s*\].*?"end_coordinate"\s*:\s*\[\s*(\d+)\s*,\s*(\d+)\s*\]',
        text_lower, re.DOTALL
    )
    if m:
        return {
            "action_type": "drag",
            "start_x": int(m.group(1)), "start_y": int(m.group(2)),
            "end_x": int(m.group(3)), "end_y": int(m.group(4))
        }

    # system_button
    m = re.search(r'"action"\s*:\s*"system_button".*?"button"\s*:\s*"(back|home|menu|enter)"', text_lower, re.DOTALL)
    if m:
        btn_map = {"back": "navigate_back", "home": "navigate_home", "menu": "app_switch", "enter": "keyboard_enter"}
        return {"action_type": btn_map[m.group(1)]}

    # wait
    if re.search(r'"action"\s*:\s*"wait"', text_lower):
        return {"action_type": "wait"}

    # terminate
    m = re.search(r'"action"\s*:\s*"terminate".*?"status"\s*:\s*"(success|fail)"', text_lower, re.DOTALL)
    if m:
        return {"action_type": "status", "goal_status": m.group(1)}

    # answer
    m = re.search(r'"action"\s*:\s*"answer".*?"text"\s*:\s*"([^"]*)"', text_norm, re.DOTALL)
    if m:
        return {"action_type": "answer", "text": m.group(1)}

    return {}


def mobileworld_projection(actions: List[str]) -> Tuple[List[dict], List[int]]:
    """
    Process raw text actions from the agent and extract structured action commands.
    
    Args:
        actions: List of raw text actions from the agent
        
    Returns:
        processed_actions: List of processed action dictionaries for MobileWorld API
        valids: List of binary flags indicating whether each action is valid (1) or invalid (0)
    
    Expected input format:
        <thinking>reasoning process</thinking>
        <tool_call>
        {"name":"mobile_use","arguments":{"action":"click","coordinate":[x,y]}}
        </tool_call>
    
    Supported actions:
        - click: {"action": "click", "coordinate": [x, y]}
        - long_press: {"action": "long_press", "coordinate": [x, y]}
        - double_tap: {"action": "double_tap", "coordinate": [x, y]}
        - type: {"action": "type", "text": "xxx"}
        - swipe: {"action": "swipe", "direction": "up/down/left/right", "coordinate": [x, y]}
        - open: {"action": "open", "text": "app_name"}
        - drag: {"action": "drag", "start_coordinate": [x1, y1], "end_coordinate": [x2, y2]}
        - system_button: {"action": "system_button", "button": "back/home/menu/enter"}
        - wait: {"action": "wait"}
        - terminate: {"action": "terminate", "status": "success/fail"}
        - answer: {"action": "answer", "text": "xxx"}
    """
    processed_actions = []
    valids = []
    
    # System button mapping
    SYSTEM_BUTTON_MAP = {
        "back": "navigate_back",
        "home": "navigate_home",
        "menu": "app_switch",  # Typically used for recent apps/task switcher
        "enter": "keyboard_enter",
    }
    
    for action_text in actions:
        if not action_text or not isinstance(action_text, str) or not action_text.strip():
            processed_actions.append(FALLBACK_ACTION.copy())
            valids.append(0)
            continue

        # ---- Step 0: Normalize text (fix Chinese/fullwidth punctuation) ----
        normalized_text = _normalize_text(action_text)

        # ---- Step 1: Check for <thinking> tag (not strictly required) ----
        think_start = normalized_text.find("<thinking>")
        think_end = normalized_text.find("</thinking>")
        
        # ---- Step 2: Check for <tool_call> tag ----
        tool_call_start = normalized_text.find("<tool_call>")
        tool_call_end = normalized_text.find("</tool_call>")

        # ---- Step 3: Try JSON extraction from <tool_call> ----
        parsed_ok = False
        mobileworld_action = {}

        if tool_call_start != -1 and tool_call_end != -1:
            tool_call_json_str = normalized_text[tool_call_start + 11:tool_call_end].strip()
            try:
                tool_call_data = json.loads(tool_call_json_str)
                
                if "arguments" in tool_call_data:
                    arguments = tool_call_data["arguments"]
                    # Handle double-encoded JSON string
                    if isinstance(arguments, str):
                        arguments = json.loads(arguments)
                elif "action" in tool_call_data:
                    # Top-level action dict
                    arguments = tool_call_data
                else:
                    arguments = {}
                
                action_name = arguments.get("action", "").lower()
                
                # Build MobileWorld action dictionary
                if action_name == "click":
                    coordinate = arguments.get("coordinate", [])
                    if len(coordinate) == 2:
                        mobileworld_action = {
                            "action_type": "click",
                            "x": int(coordinate[0]),
                            "y": int(coordinate[1])
                        }
                        parsed_ok = True
                        
                elif action_name == "long_press":
                    coordinate = arguments.get("coordinate", [])
                    if len(coordinate) == 2:
                        mobileworld_action = {
                            "action_type": "long_press",
                            "x": int(coordinate[0]),
                            "y": int(coordinate[1])
                        }
                        parsed_ok = True
                        
                elif action_name == "double_tap":
                    coordinate = arguments.get("coordinate", [])
                    if len(coordinate) == 2:
                        mobileworld_action = {
                            "action_type": "double_tap",
                            "x": int(coordinate[0]),
                            "y": int(coordinate[1])
                        }
                        parsed_ok = True
                        
                elif action_name == "type":
                    mobileworld_action = {
                        "action_type": "input_text",
                        "text": arguments.get("text", "")
                    }
                    parsed_ok = True
                    
                elif action_name == "swipe":
                    direction = arguments.get("direction", "up").lower()
                    mobileworld_action = {
                        "action_type": "swipe",
                        "direction": direction
                    }
                    # Coordinate is optional for swipe
                    coordinate = arguments.get("coordinate")
                    if coordinate and len(coordinate) == 2:
                        mobileworld_action["x"] = int(coordinate[0])
                        mobileworld_action["y"] = int(coordinate[1])
                    parsed_ok = True
                    
                elif action_name == "open":
                    app_name = arguments.get("text", "")
                    if app_name:
                        mobileworld_action = {
                            "action_type": "open_app",
                            "app_name": app_name
                        }
                        parsed_ok = True
                        
                elif action_name == "drag":
                    start_coord = arguments.get("start_coordinate", [])
                    end_coord = arguments.get("end_coordinate", [])
                    if len(start_coord) == 2 and len(end_coord) == 2:
                        mobileworld_action = {
                            "action_type": "drag",
                            "start_x": int(start_coord[0]),
                            "start_y": int(start_coord[1]),
                            "end_x": int(end_coord[0]),
                            "end_y": int(end_coord[1])
                        }
                        parsed_ok = True
                        
                elif action_name == "system_button":
                    button_name = arguments.get("button", "").lower()
                    if button_name in SYSTEM_BUTTON_MAP:
                        mobileworld_action = {
                            "action_type": SYSTEM_BUTTON_MAP[button_name]
                        }
                        parsed_ok = True
                        
                elif action_name == "wait":
                    mobileworld_action = {
                        "action_type": "wait"
                    }
                    parsed_ok = True
                    
                elif action_name == "terminate":
                    status = arguments.get("status", "").lower()
                    if status in ["success", "fail"]:
                        mobileworld_action = {
                            "action_type": "status",
                            "goal_status": status
                        }
                        parsed_ok = True
                        
                elif action_name == "answer":
                    mobileworld_action = {
                        "action_type": "answer",
                        "text": arguments.get("text", "")
                    }
                    parsed_ok = True
                    
            except (json.JSONDecodeError, KeyError, ValueError, IndexError, TypeError) as e:
                # JSON parsing failed, will fall through to regex
                pass
        
        # ---- Step 4: Regex fallback if JSON parsing failed ----
        if not parsed_ok:
            regex_result = _regex_extract_action(normalized_text)
            if regex_result:
                mobileworld_action = regex_result
                parsed_ok = True

        # ---- Step 5: Convert normalized coords to absolute pixel coords ----
        if parsed_ok and mobileworld_action:
            mobileworld_action = _to_absolute_coords(mobileworld_action)
            processed_actions.append(mobileworld_action)
            valids.append(1)
        else:
            # All parsing failed => fallback to wait (avoid empty action causing server 500)
            print(f"[projection] WARNING: Failed to parse action, falling back to 'wait'. Raw text: {action_text[:300]}...")
            processed_actions.append(FALLBACK_ACTION.copy())
            valids.append(0)
    
    return processed_actions, valids


def _guiowl_regex_extract_action(text: str) -> dict:
    """
    Regex-based extraction for GUI-Owl when JSON parsing fails.
    Similar to _regex_extract_action but handles GUI-Owl specific actions
    (swipe with coordinate+coordinate2, interact, terminate with success/failure, key).
    """
    text_norm = _normalize_text(text)
    text_lower = text_norm.lower()

    # click / long_press with coordinate
    for action_type in ["click", "long_press"]:
        m = re.search(
            rf'"action"\s*:\s*"{action_type}".*?"coordinate"\s*:\s*\[\s*(\d+)\s*,\s*(\d+)\s*\]',
            text_lower, re.DOTALL
        )
        if m:
            return {"action_type": action_type, "x": int(m.group(1)), "y": int(m.group(2))}

    # swipe with coordinate + coordinate2
    m = re.search(
        r'"action"\s*:\s*"swipe".*?"coordinate"\s*:\s*\[\s*(\d+)\s*,\s*(\d+)\s*\].*?"coordinate2"\s*:\s*\[\s*(\d+)\s*,\s*(\d+)\s*\]',
        text_lower, re.DOTALL
    )
    if m:
        return {
            "action_type": "drag",
            "start_x": int(m.group(1)), "start_y": int(m.group(2)),
            "end_x": int(m.group(3)), "end_y": int(m.group(4))
        }

    # type/input text
    m = re.search(r'"action"\s*:\s*"type".*?"text"\s*:\s*"([^"]*)"', text_norm, re.DOTALL)
    if m:
        return {"action_type": "input_text", "text": m.group(1)}

    # open app
    m = re.search(r'"action"\s*:\s*"open".*?"text"\s*:\s*"([^"]*)"', text_norm, re.DOTALL)
    if m:
        return {"action_type": "open_app", "app_name": m.group(1)}

    # system_button
    m = re.search(r'"action"\s*:\s*"system_button".*?"button"\s*:\s*"(back|home|menu|enter)"', text_lower, re.DOTALL)
    if m:
        btn_map = {"back": "navigate_back", "home": "navigate_home", "menu": "app_switch", "enter": "keyboard_enter"}
        return {"action_type": btn_map[m.group(1)]}

    # wait
    if re.search(r'"action"\s*:\s*"wait"', text_lower):
        return {"action_type": "wait"}

    # interact → fallback to wait
    if re.search(r'"action"\s*:\s*"interact"', text_lower):
        return {"action_type": "wait"}

    # key → fallback to wait
    if re.search(r'"action"\s*:\s*"key"', text_lower):
        return {"action_type": "wait"}

    # terminate (success/failure)
    m = re.search(r'"action"\s*:\s*"terminate".*?"status"\s*:\s*"(success|failure)"', text_lower, re.DOTALL)
    if m:
        status = m.group(1)
        # Map "failure" → "fail" for MobileWorld API compatibility
        goal_status = "fail" if status == "failure" else "success"
        return {"action_type": "status", "goal_status": goal_status}

    # answer
    m = re.search(r'"action"\s*:\s*"answer".*?"text"\s*:\s*"([^"]*)"', text_norm, re.DOTALL)
    if m:
        return {"action_type": "answer", "text": m.group(1)}

    return {}


def guiowl_mobileworld_projection(actions: List[str]) -> Tuple[List[dict], List[int]]:
    """
    Process raw text actions from GUI-Owl agent and extract structured action commands.
    
    Key differences from mobileworld_projection (MAI-UI):
    - swipe uses coordinate + coordinate2 (two points → mapped to drag)
    - interact action → fallback to wait
    - key action → fallback to wait
    - terminate status: "success"/"failure" (not "success"/"fail")
    
    Args:
        actions: List of raw text actions from the agent
        
    Returns:
        processed_actions: List of processed action dicts for MobileWorld API
        valids: List of binary flags (1=valid, 0=invalid)
    """
    processed_actions = []
    valids = []
    
    SYSTEM_BUTTON_MAP = {
        "back": "navigate_back",
        "home": "navigate_home",
        "menu": "app_switch",
        "enter": "keyboard_enter",
    }
    
    for action_text in actions:
        if not action_text or not isinstance(action_text, str) or not action_text.strip():
            processed_actions.append(FALLBACK_ACTION.copy())
            valids.append(0)
            continue

        normalized_text = _normalize_text(action_text)

        # ---- Try JSON extraction from <tool_call> ----
        tool_call_start = normalized_text.find("<tool_call>")
        tool_call_end = normalized_text.find("</tool_call>")

        parsed_ok = False
        mobileworld_action = {}

        if tool_call_start != -1 and tool_call_end != -1:
            tool_call_json_str = normalized_text[tool_call_start + 11:tool_call_end].strip()
            try:
                tool_call_data = json.loads(tool_call_json_str)
                
                if "arguments" in tool_call_data:
                    arguments = tool_call_data["arguments"]
                    if isinstance(arguments, str):
                        arguments = json.loads(arguments)
                elif "action" in tool_call_data:
                    arguments = tool_call_data
                else:
                    arguments = {}
                
                action_name = arguments.get("action", "").lower()
                
                if action_name == "click":
                    coordinate = arguments.get("coordinate", [])
                    if len(coordinate) >= 2:
                        mobileworld_action = {
                            "action_type": "click",
                            "x": int(coordinate[0]),
                            "y": int(coordinate[1])
                        }
                        parsed_ok = True
                        
                elif action_name == "long_press":
                    coordinate = arguments.get("coordinate", [])
                    if len(coordinate) >= 2:
                        mobileworld_action = {
                            "action_type": "long_press",
                            "x": int(coordinate[0]),
                            "y": int(coordinate[1])
                        }
                        parsed_ok = True
                        
                elif action_name == "swipe":
                    # GUI-Owl swipe: coordinate (start) + coordinate2 (end) → map to drag
                    start_coord = arguments.get("coordinate", [])
                    end_coord = arguments.get("coordinate2", [])
                    if len(start_coord) >= 2 and len(end_coord) >= 2:
                        mobileworld_action = {
                            "action_type": "drag",
                            "start_x": int(start_coord[0]),
                            "start_y": int(start_coord[1]),
                            "end_x": int(end_coord[0]),
                            "end_y": int(end_coord[1])
                        }
                        parsed_ok = True
                    
                elif action_name == "type":
                    mobileworld_action = {
                        "action_type": "input_text",
                        "text": arguments.get("text", "")
                    }
                    parsed_ok = True
                    
                elif action_name == "open":
                    app_name = arguments.get("text", "")
                    if app_name:
                        mobileworld_action = {
                            "action_type": "open_app",
                            "app_name": app_name
                        }
                        parsed_ok = True
                        
                elif action_name == "system_button":
                    button_name = arguments.get("button", "").lower()
                    if button_name in SYSTEM_BUTTON_MAP:
                        mobileworld_action = {
                            "action_type": SYSTEM_BUTTON_MAP[button_name]
                        }
                        parsed_ok = True
                        
                elif action_name == "wait":
                    mobileworld_action = {"action_type": "wait"}
                    parsed_ok = True
                    
                elif action_name == "interact":
                    # GUI-Owl interact → fallback to wait
                    mobileworld_action = {"action_type": "wait"}
                    parsed_ok = True
                    print(f"[guiowl_projection] 'interact' action mapped to 'wait'")
                    
                elif action_name == "key":
                    # GUI-Owl key action → fallback to wait
                    mobileworld_action = {"action_type": "wait"}
                    parsed_ok = True
                    print(f"[guiowl_projection] 'key' action mapped to 'wait'")
                    
                elif action_name == "terminate":
                    status = arguments.get("status", "").lower()
                    # GUI-Owl uses "success"/"failure" (not "fail")
                    if status in ["success", "failure"]:
                        goal_status = "fail" if status == "failure" else "success"
                        mobileworld_action = {
                            "action_type": "status",
                            "goal_status": goal_status
                        }
                        parsed_ok = True
                        
                elif action_name == "answer":
                    mobileworld_action = {
                        "action_type": "answer",
                        "text": arguments.get("text", "")
                    }
                    parsed_ok = True
                    
            except (json.JSONDecodeError, KeyError, ValueError, IndexError, TypeError) as e:
                pass
        
        # ---- Regex fallback ----
        if not parsed_ok:
            regex_result = _guiowl_regex_extract_action(normalized_text)
            if regex_result:
                mobileworld_action = regex_result
                parsed_ok = True

        # ---- Convert coords to absolute pixels ----
        if parsed_ok and mobileworld_action:
            mobileworld_action = _to_absolute_coords(mobileworld_action)
            processed_actions.append(mobileworld_action)
            valids.append(1)
        else:
            print(f"[guiowl_projection] WARNING: Failed to parse action, falling back to 'wait'. Raw text: {action_text[:300]}...")
            processed_actions.append(FALLBACK_ACTION.copy())
            valids.append(0)
    
    return processed_actions, valids
