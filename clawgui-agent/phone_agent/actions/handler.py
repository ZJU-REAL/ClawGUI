"""Action handler for processing AI model outputs."""

import ast
import re
import subprocess
import time
from dataclasses import dataclass
from typing import Any, Callable

from phone_agent.config.timing import TIMING_CONFIG
from phone_agent.device_factory import get_device_factory


@dataclass
class ActionResult:
    """Result of an action execution."""

    success: bool
    should_finish: bool
    message: str | None = None
    requires_confirmation: bool = False


class ActionHandler:
    """
    Handles execution of actions from AI model output.

    Args:
        device_id: Optional ADB device ID for multi-device setups.
        confirmation_callback: Optional callback for sensitive action confirmation.
            Should return True to proceed, False to cancel.
        takeover_callback: Optional callback for takeover requests (login, captcha).
    """

    def __init__(
        self,
        device_id: str | None = None,
        confirmation_callback: Callable[[str], bool] | None = None,
        takeover_callback: Callable[[str], None] | None = None,
    ):
        self.device_id = device_id
        self.confirmation_callback = confirmation_callback or self._default_confirmation
        self.takeover_callback = takeover_callback or self._default_takeover

    def execute(
        self, action: dict[str, Any], screen_width: int, screen_height: int
    ) -> ActionResult:
        """
        Execute an action from the AI model.

        Args:
            action: The action dictionary from the model.
            screen_width: Current screen width in pixels.
            screen_height: Current screen height in pixels.

        Returns:
            ActionResult indicating success and whether to finish.
        """
        action_type = action.get("_metadata")

        if action_type == "finish":
            return ActionResult(
                success=True, should_finish=True, message=action.get("message")
            )

        if action_type != "do":
            return ActionResult(
                success=False,
                should_finish=True,
                message=f"Unknown action type: {action_type}",
            )

        action_name = action.get("action")
        handler_method = self._get_handler(action_name)

        if handler_method is None:
            return ActionResult(
                success=False,
                should_finish=False,
                message=f"Unknown action: {action_name}",
            )

        try:
            return handler_method(action, screen_width, screen_height)
        except Exception as e:
            return ActionResult(
                success=False, should_finish=False, message=f"Action failed: {e}"
            )

    def _get_handler(self, action_name: str) -> Callable | None:
        """Get the handler method for an action."""
        handlers = {
            "Launch": self._handle_launch,
            "Tap": self._handle_tap,
            "Type": self._handle_type,
            "Type_Name": self._handle_type,
            "Swipe": self._handle_swipe,
            "Back": self._handle_back,
            "Home": self._handle_home,
            "Double Tap": self._handle_double_tap,
            "Long Press": self._handle_long_press,
            "Wait": self._handle_wait,
            "Take_over": self._handle_takeover,
            "Note": self._handle_note,
            "Call_API": self._handle_call_api,
            "Interact": self._handle_interact,
        }
        return handlers.get(action_name)

    def _convert_relative_to_absolute(
        self, element: list, screen_width: int, screen_height: int
    ) -> tuple[int, int]:
        """
        Convert relative coordinates (0-1000) to absolute pixels.
        
        Supports multiple formats:
        - Point format: [x, y] - direct coordinates
        - Bounding box format: [x1, y1, x2, y2] - uses center point
        - Nested array format: [[x1, y1, x2, y2]] - unwraps and uses center point
        """
        # Handle nested array format [[x1, y1, x2, y2]] -> [x1, y1, x2, y2]
        if len(element) == 1 and isinstance(element[0], list):
            element = element[0]
        
        if len(element) >= 4:
            # Bounding box format [x1, y1, x2, y2] - calculate center point
            x1, y1, x2, y2 = element[0], element[1], element[2], element[3]
            rel_x = (x1 + x2) / 2
            rel_y = (y1 + y2) / 2
        elif len(element) >= 2:
            # Point format [x, y]
            rel_x = element[0]
            rel_y = element[1]
        else:
            # Fallback to center of screen
            rel_x, rel_y = 500, 500
        
        # Convert from 0-1000 relative to absolute pixels
        x = int(rel_x / 1000 * screen_width)
        y = int(rel_y / 1000 * screen_height)
        return x, y

    def _handle_launch(self, action: dict, width: int, height: int) -> ActionResult:
        """Handle app launch action."""
        app_name = action.get("app")
        if not app_name:
            return ActionResult(False, False, "No app name specified")

        device_factory = get_device_factory()
        success = device_factory.launch_app(app_name, self.device_id)
        if success:
            return ActionResult(True, False)
        return ActionResult(False, False, f"App not found: {app_name}")

    def _handle_tap(self, action: dict, width: int, height: int) -> ActionResult:
        """Handle tap action."""
        element = action.get("element")
        if not element:
            return ActionResult(False, False, "No element coordinates")

        x, y = self._convert_relative_to_absolute(element, width, height)

        # Check for sensitive operation
        if "message" in action:
            if not self.confirmation_callback(action["message"]):
                return ActionResult(
                    success=False,
                    should_finish=True,
                    message="User cancelled sensitive operation",
                )

        device_factory = get_device_factory()
        device_factory.tap(x, y, self.device_id)
        return ActionResult(True, False)

    def _handle_type(self, action: dict, width: int, height: int) -> ActionResult:
        """Handle text input action."""
        text = action.get("text", "")

        device_factory = get_device_factory()

        # Switch to ADB keyboard
        original_ime = device_factory.detect_and_set_adb_keyboard(self.device_id)
        time.sleep(TIMING_CONFIG.action.keyboard_switch_delay)

        # Clear existing text and type new text
        device_factory.clear_text(self.device_id)
        time.sleep(TIMING_CONFIG.action.text_clear_delay)

        # Handle multiline text by splitting on newlines
        device_factory.type_text(text, self.device_id)
        time.sleep(TIMING_CONFIG.action.text_input_delay)

        # Restore original keyboard
        device_factory.restore_keyboard(original_ime, self.device_id)
        time.sleep(TIMING_CONFIG.action.keyboard_restore_delay)

        return ActionResult(True, False)

    def _handle_swipe(self, action: dict, width: int, height: int) -> ActionResult:
        """Handle swipe action."""
        start = action.get("start")
        end = action.get("end")

        if not start or not end:
            return ActionResult(False, False, "Missing swipe coordinates")

        start_x, start_y = self._convert_relative_to_absolute(start, width, height)
        end_x, end_y = self._convert_relative_to_absolute(end, width, height)

        device_factory = get_device_factory()
        device_factory.swipe(start_x, start_y, end_x, end_y, device_id=self.device_id)
        return ActionResult(True, False)

    def _handle_back(self, action: dict, width: int, height: int) -> ActionResult:
        """Handle back button action."""
        device_factory = get_device_factory()
        device_factory.back(self.device_id)
        return ActionResult(True, False)

    def _handle_home(self, action: dict, width: int, height: int) -> ActionResult:
        """Handle home button action."""
        device_factory = get_device_factory()
        device_factory.home(self.device_id)
        return ActionResult(True, False)

    def _handle_double_tap(self, action: dict, width: int, height: int) -> ActionResult:
        """Handle double tap action."""
        element = action.get("element")
        if not element:
            return ActionResult(False, False, "No element coordinates")

        x, y = self._convert_relative_to_absolute(element, width, height)
        device_factory = get_device_factory()
        device_factory.double_tap(x, y, self.device_id)
        return ActionResult(True, False)

    def _handle_long_press(self, action: dict, width: int, height: int) -> ActionResult:
        """Handle long press action."""
        element = action.get("element")
        if not element:
            return ActionResult(False, False, "No element coordinates")

        x, y = self._convert_relative_to_absolute(element, width, height)
        device_factory = get_device_factory()
        device_factory.long_press(x, y, device_id=self.device_id)
        return ActionResult(True, False)

    def _handle_wait(self, action: dict, width: int, height: int) -> ActionResult:
        """Handle wait action."""
        duration_str = action.get("duration", "1 seconds")
        try:
            duration = float(duration_str.replace("seconds", "").strip())
        except ValueError:
            duration = 1.0

        time.sleep(duration)
        return ActionResult(True, False)

    def _handle_takeover(self, action: dict, width: int, height: int) -> ActionResult:
        """Handle takeover request (login, captcha, etc.)."""
        message = action.get("message", "User intervention required")
        self.takeover_callback(message)
        return ActionResult(True, False)

    def _handle_note(self, action: dict, width: int, height: int) -> ActionResult:
        """Handle note action (placeholder for content recording)."""
        # This action is typically used for recording page content
        # Implementation depends on specific requirements
        return ActionResult(True, False)

    def _handle_call_api(self, action: dict, width: int, height: int) -> ActionResult:
        """Handle API call action (placeholder for summarization)."""
        # This action is typically used for content summarization
        # Implementation depends on specific requirements
        return ActionResult(True, False)

    def _handle_interact(self, action: dict, width: int, height: int) -> ActionResult:
        """Handle interaction request (user choice needed)."""
        # This action signals that user input is needed
        return ActionResult(True, False, message="User interaction required")

    def _send_keyevent(self, keycode: str) -> None:
        """Send a keyevent to the device."""
        from phone_agent.device_factory import DeviceType, get_device_factory
        from phone_agent.hdc.connection import _run_hdc_command

        device_factory = get_device_factory()

        # Handle HDC devices with HarmonyOS-specific keyEvent command
        if device_factory.device_type == DeviceType.HDC:
            hdc_prefix = ["hdc", "-t", self.device_id] if self.device_id else ["hdc"]
            
            # Map common keycodes to HarmonyOS keyEvent codes
            # KEYCODE_ENTER (66) -> 2054 (HarmonyOS Enter key code)
            if keycode == "KEYCODE_ENTER" or keycode == "66":
                _run_hdc_command(
                    hdc_prefix + ["shell", "uitest", "uiInput", "keyEvent", "2054"],
                    capture_output=True,
                    text=True,
                )
            else:
                # For other keys, try to use the numeric code directly
                # If keycode is a string like "KEYCODE_ENTER", convert it
                try:
                    # Try to extract numeric code from string or use as-is
                    if keycode.startswith("KEYCODE_"):
                        # For now, only handle ENTER, other keys may need mapping
                        if "ENTER" in keycode:
                            _run_hdc_command(
                                hdc_prefix + ["shell", "uitest", "uiInput", "keyEvent", "2054"],
                                capture_output=True,
                                text=True,
                            )
                        else:
                            # Fallback to ADB-style command for unsupported keys
                            subprocess.run(
                                hdc_prefix + ["shell", "input", "keyevent", keycode],
                                capture_output=True,
                                text=True,
                            )
                    else:
                        # Assume it's a numeric code
                        _run_hdc_command(
                            hdc_prefix + ["shell", "uitest", "uiInput", "keyEvent", str(keycode)],
                            capture_output=True,
                            text=True,
                        )
                except Exception:
                    # Fallback to ADB-style command
                    subprocess.run(
                        hdc_prefix + ["shell", "input", "keyevent", keycode],
                        capture_output=True,
                        text=True,
                    )
        else:
            # ADB devices use standard input keyevent command
            cmd_prefix = ["adb", "-s", self.device_id] if self.device_id else ["adb"]
            subprocess.run(
                cmd_prefix + ["shell", "input", "keyevent", keycode],
                capture_output=True,
                text=True,
            )

    @staticmethod
    def _default_confirmation(message: str) -> bool:
        """Default confirmation callback using console input."""
        response = input(f"Sensitive operation: {message}\nConfirm? (Y/N): ")
        return response.upper() == "Y"

    @staticmethod
    def _default_takeover(message: str) -> None:
        """Default takeover callback using console input."""
        input(f"{message}\nPress Enter after completing manual operation...")


def parse_action(response: str) -> dict[str, Any]:
    """
    Parse action from model response.

    Args:
        response: Raw response string from the model.

    Returns:
        Parsed action dictionary.

    Raises:
        ValueError: If the response cannot be parsed.
    """
    print(f"Parsing action: {response}")
    try:
        response = response.strip()
        
        # 🔧 统一清理常见的格式噪声（在所有解析之前）
        # 1. 移除 markdown 代码块标记（支持多行）
        response = response.replace('```python', '').replace('```', '')
        # 2. 移除前导和尾部的真实换行符
        response = response.strip('\n\r\t ')
        # 3. 移除行尾的转义换行符 + 特殊字符组合（如 \n```）
        response = re.sub(r'\\n[`\s]*$', '', response)
        response = re.sub(r'\\r[`\s]*$', '', response)
        # 4. 移除字符串末尾的多余空白和反引号
        response = response.rstrip('`\n\r\t ')
        
        # 防御性清理：去掉可能残留的 XML 标签（如 </answer>、</think> 等）
        response = re.sub(r'</?(answer|think|thought)>', '', response).strip()
        
        # 🔧 处理 <tool_call> 格式（作为 fallback，支持 MAI-UI/QwenVL 的 tool_call 格式）
        # 例如: <tool_call>{"name":"mobile_use","arguments":{"action":"click","coordinate":[845,383]}}</tool_call>
        # 正常情况下，MAI-UI/QwenVL 应该由各自的专用 handler 处理
        # 但作为防御性编程，这里也要能正确解析所有 tool_call 动作
        if "<tool_call>" in response:
            # 使用 \s* 来匹配可能的空白字符（包括换行符），支持缺失结束标签
            tool_call_match = re.search(r'<tool_call>\s*(.*?)(?:\s*</tool_call>|$)', response, re.DOTALL)
            if tool_call_match:
                tool_call_content = tool_call_match.group(1).strip()
                # 清理可能残留的 \n 转义序列和不完整的标签
                tool_call_content = tool_call_content.replace('\\n', '').strip()
                tool_call_content = re.sub(r'</?tool_call[^>]*$', '', tool_call_content).strip()
                try:
                    import json as _json
                    tool_call_json = _json.loads(tool_call_content)
                    
                    # MAI-UI 格式: {"name": "mobile_use", "arguments": {...}}
                    if isinstance(tool_call_json, dict) and "arguments" in tool_call_json:
                        arguments = tool_call_json["arguments"]
                        if isinstance(arguments, dict):
                            action_type = arguments.get("action", "")
                            # 结束动作: terminate/answer
                            if action_type in ["terminate", "answer"]:
                                return {
                                    "_metadata": "finish",
                                    "message": arguments.get("text", "") or arguments.get("status", "completed")
                                }
                            # 其他动作（click, type, swipe 等）：转换为通用 do 格式
                            # 这是防御性处理，确保即使走到这里也不会报错
                            result = {"_metadata": "do"}
                            result.update(arguments)
                            return result
                except (_json.JSONDecodeError, KeyError, TypeError):
                    # JSON 解析失败，继续后续处理
                    pass

        # 防御性：如果模型输出了 JSON 格式（如 {"_metadata": "do", "action": "Launch", ...}），直接解析
        if response.startswith("{"):
            try:
                import json as _json
                action = _json.loads(response)
                if isinstance(action, dict) and ("_metadata" in action or "action" in action):
                    # 确保有 _metadata 字段
                    if "_metadata" not in action:
                        action["_metadata"] = "do"
                    return action
            except (ValueError, _json.JSONDecodeError):
                pass  # 不是有效 JSON，继续其他解析

        if response.startswith('do(action="Type"') or response.startswith(
            'do(action="Type_Name"'
        ):
            # 使用正则表达式正确提取 text 参数，避免将 element 等其他参数误包含
            # 匹配 text="..." 或 text='...'，处理转义引号
            text_match = re.search(r'text=(["\'])((?:(?!\1)[^\\]|\\.)*)(\1)', response)
            if text_match:
                text = text_match.group(2)
                # 处理转义字符
                text = text.replace('\\"', '"').replace("\\'", "'").replace('\\n', '\n')
            else:
                # 后备方案：尝试 AST 解析
                text = None
            
            if text is not None:
                # 确定是 Type 还是 Type_Name
                action_type = "Type_Name" if 'action="Type_Name"' in response else "Type"
                action = {"_metadata": "do", "action": action_type, "text": text}
                return action
            # 如果正则提取失败，继续使用 AST 解析
        
        if response.startswith("do"):
            # Use AST parsing instead of eval for safety
            try:
                tree = ast.parse(response, mode="eval")
                if not isinstance(tree.body, ast.Call):
                    raise ValueError("Expected a function call")

                call = tree.body
                # Extract keyword arguments safely
                action = {"_metadata": "do"}
                for keyword in call.keywords:
                    key = keyword.arg
                    value = ast.literal_eval(keyword.value)
                    action[key] = value

                return action
            except (SyntaxError, ValueError) as e:
                raise ValueError(f"Failed to parse do() action: {e}")

        elif response.startswith("finish"):
            # 尝试使用 AST 解析（更鲁棒）
            try:
                tree = ast.parse(response, mode="eval")
                if isinstance(tree.body, ast.Call):
                    call = tree.body
                    # 提取关键字参数
                    message = ""
                    for keyword in call.keywords:
                        if keyword.arg == "message":
                            message = ast.literal_eval(keyword.value)
                            break
                    action = {
                        "_metadata": "finish",
                        "message": message,
                    }
                    return action
            except:
                pass
            
            # 如果 AST 解析失败，回退到字符串匹配（兼容旧格式）
            action = {
                "_metadata": "finish",
                "message": response.replace("finish(message=", "").strip('")\''),
            }
        else:
            raise ValueError(f"Failed to parse action: {response}")
        return action
    except Exception as e:
        raise ValueError(f"Failed to parse action: {e}")


def do(**kwargs) -> dict[str, Any]:
    """Helper function for creating 'do' actions."""
    kwargs["_metadata"] = "do"
    return kwargs


def finish(**kwargs) -> dict[str, Any]:
    """Helper function for creating 'finish' actions."""
    kwargs["_metadata"] = "finish"
    return kwargs
