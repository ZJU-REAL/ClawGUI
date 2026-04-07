"""
GUI-Owl 模型的 Action Handler

基于 mPLUG/GUI-Owl: https://github.com/X-PLUG/MobileAgent

GUI-Owl 1.5 使用官方 tool_call 格式:
- 输出: Action: <description>\n<tool_call>{"name": "mobile_use", "arguments": {...}}</tool_call>
- 坐标使用 0-999 归一化（分辨率 1000x1000）

支持的 action space:
- {"action": "click", "coordinate": [x, y]}
- {"action": "long_press", "coordinate": [x, y], "time": N}
- {"action": "swipe", "coordinate": [x1, y1], "coordinate2": [x2, y2]}
- {"action": "type", "text": ""}
- {"action": "key", "text": "keycode"}
- {"action": "system_button", "button": "Back|Home|Menu|Enter"}
- {"action": "open", "text": "app_name"}
- {"action": "wait", "time": N}
- {"action": "answer", "text": "xxx"}
- {"action": "interact", "text": "xxx"}
- {"action": "terminate", "status": "success|failure"}
"""

import json
import re
import time
from dataclasses import dataclass
from typing import Any, Callable

from phone_agent.actions.handler import ActionResult
from phone_agent.config.timing import TIMING_CONFIG
from phone_agent.device_factory import get_device_factory

SCALE_FACTOR = 999


@dataclass
class GUIOwlAction:
    """解析后的 GUI-Owl 动作"""
    action_type: str
    params: dict[str, Any]
    thinking: str = ""
    description: str = ""       # Action: 后面的描述（用于操作历史）
    action_desc: str = ""       # 同 description，兼容 adapter 调用


def parse_tagged_text(text: str) -> dict:
    """
    解析模型输出文本为结构化组件（官方 tool_call 格式）

    期望格式:
        Action: "<conclusion>"
        <tool_call>
        {"name": ..., "arguments": ...}
        </tool_call>

    也兼容旧的 ### Thought ### / ### Action ### / ### Description ### 格式

    Returns:
        dict with keys: thinking, conclusion, tool_call
    """
    result = {"thinking": None, "conclusion": None, "tool_call": None}

    # === 优先尝试解析官方 tool_call 格式 ===
    # 提取 Action: 行（tool_call 格式中的 conclusion）
    action_parts = text.split("Action:")
    if len(action_parts) > 1:
        result["thinking"] = action_parts[0].strip()
        action_content = action_parts[1]
    else:
        action_content = text

    # 解析 <tool_call> 块
    tool_parts = action_content.split("<tool_call>")
    if len(tool_parts) > 1:
        conclusion_content = tool_parts[0].strip()
        # 去除首尾引号
        if conclusion_content.startswith('"') and conclusion_content.endswith('"'):
            conclusion_content = conclusion_content[1:-1]
        result["conclusion"] = conclusion_content

        tool_call_raw = tool_parts[1].split("</tool_call>")[0].strip()
        try:
            result["tool_call"] = json.loads(tool_call_raw)
        except json.JSONDecodeError:
            # 尝试修复常见 JSON 问题
            try:
                # 清理可能的尾部噪声
                cleaned = tool_call_raw.strip()
                if not cleaned.endswith('}'):
                    # 找到最后一个 }
                    last_brace = cleaned.rfind('}')
                    if last_brace >= 0:
                        cleaned = cleaned[:last_brace + 1]
                result["tool_call"] = json.loads(cleaned)
            except json.JSONDecodeError:
                pass

    # === Fallback：旧的 ### Thought ### / ### Action ### 格式 ===
    if result["tool_call"] is None:
        # 尝试提取 ### Action ### 部分中的 JSON
        action_match = re.search(
            r'###\s*Action\s*###\s*(.*?)(?=###\s*Description\s*###|$)',
            text, re.DOTALL
        )
        if action_match:
            action_str = action_match.group(1).strip()
            action_str = action_str.replace("```", "").replace("json", "").strip()
            try:
                action_json = json.loads(action_str)
                result["tool_call"] = {
                    "name": "mobile_use",
                    "arguments": action_json,
                }
            except json.JSONDecodeError:
                pass

            # 提取 thinking
            thought_match = re.search(
                r'###\s*Thought\s*###\s*(.*?)(?=###\s*Action\s*###|$)',
                text, re.DOTALL
            )
            if thought_match:
                result["thinking"] = thought_match.group(1).strip()

            # 提取 description
            desc_match = re.search(
                r'###\s*Description\s*###\s*(.*?)$',
                text, re.DOTALL
            )
            if desc_match:
                result["conclusion"] = desc_match.group(1).strip()

    # === 最终 Fallback：直接查找 JSON ===
    if result["tool_call"] is None:
        json_match = re.search(
            r'\{[^{}]*"action"\s*:\s*"[^"]+"\s*[^{}]*\}',
            text
        )
        if json_match:
            try:
                action_json = json.loads(json_match.group())
                result["tool_call"] = {
                    "name": "mobile_use",
                    "arguments": action_json,
                }
            except json.JSONDecodeError:
                pass

    return result


class GUIOwlActionHandler:
    """
    处理 GUI-Owl 模型输出的 Action

    GUI-Owl 1.5 官方输出格式：
    Action: <操作描述>
    <tool_call>
    {"name": "mobile_use", "arguments": {"action": "click", "coordinate": [x, y]}}
    </tool_call>

    坐标模式：
    - 官方格式使用 0-999 归一化坐标（分辨率 1000x1000）
    - 也支持旧的绝对像素坐标（通过 use_normalized_coords 控制）
    """

    def __init__(
        self,
        device_id: str | None = None,
        confirmation_callback: Callable[[str], bool] | None = None,
        takeover_callback: Callable[[str], None] | None = None,
        use_normalized_coords: bool = True,  # 官方格式默认使用归一化坐标
    ):
        self.device_id = device_id
        self.confirmation_callback = confirmation_callback or self._default_confirmation
        self.takeover_callback = takeover_callback or self._default_takeover
        self.use_normalized_coords = use_normalized_coords
        # 操作历史（存储每步的 conclusion 描述）
        self.action_history: list[str] = []

    def parse_response(self, response: str) -> GUIOwlAction:
        """
        解析 GUI-Owl 模型的响应

        支持两种格式:
        1. 官方 tool_call 格式:
           Action: 点击搜索栏
           <tool_call>{"name": "mobile_use", "arguments": {"action": "click", "coordinate": [540, 960]}}</tool_call>

        2. 旧格式 (fallback):
           ### Thought ### ... ### Action ### {JSON} ### Description ### ...

        Args:
            response: 模型原始响应

        Returns:
            GUIOwlAction 对象
        """
        response = response.strip()
        results = parse_tagged_text(response)

        thinking = results["thinking"] or ""
        conclusion = results["conclusion"] or ""
        tool_call = results["tool_call"]

        if tool_call is None:
            return GUIOwlAction(
                action_type="unknown",
                params={"raw": response},
                thinking=thinking,
                description=conclusion,
                action_desc=conclusion,
            )

        action = tool_call.get("arguments", {})
        action_type = action.get("action", "unknown")

        # 归一化坐标处理：将 0-999 坐标转为 0-1 比例
        if "coordinate" in action:
            coordinates = action["coordinate"]
            if len(coordinates) == 2:
                point_x, point_y = coordinates
            elif len(coordinates) == 4:
                x1, y1, x2, y2 = coordinates
                point_x = (x1 + x2) / 2
                point_y = (y1 + y2) / 2
            else:
                point_x, point_y = 500, 500
            action["coordinate"] = [point_x / SCALE_FACTOR, point_y / SCALE_FACTOR]

        if "coordinate2" in action:
            coordinates = action["coordinate2"]
            if len(coordinates) == 2:
                point_x, point_y = coordinates
            elif len(coordinates) == 4:
                x1, y1, x2, y2 = coordinates
                point_x = (x1 + x2) / 2
                point_y = (y1 + y2) / 2
            else:
                point_x, point_y = 500, 500
            action["coordinate2"] = [point_x / SCALE_FACTOR, point_y / SCALE_FACTOR]

        return GUIOwlAction(
            action_type=action_type,
            params=action,
            thinking=thinking,
            description=conclusion,
            action_desc=conclusion,
        )

    def clear_history(self):
        """清空操作历史"""
        self.action_history.clear()

    def _convert_to_absolute(
        self, x: float, y: float, screen_width: int, screen_height: int
    ) -> tuple[int, int]:
        """
        将坐标转换为绝对像素坐标

        parse_response 已经将坐标归一化到 0-1，这里只需乘以屏幕尺寸。
        也支持检测其他坐标格式（绝对像素等）。
        """
        screen_width = max(1, screen_width)
        screen_height = max(1, screen_height)

        def clamp(val: float, low: float, high: float) -> float:
            return max(low, min(high, val))

        if x <= 1.0 and y <= 1.0 and x >= 0 and y >= 0:
            # 0-1 归一化坐标（parse_response 的输出）
            abs_x = int(clamp(x, 0, 1) * screen_width)
            abs_y = int(clamp(y, 0, 1) * screen_height)
        elif self.use_normalized_coords and x <= 1000 and y <= 1000:
            # 0-1000 归一化（未经 parse_response 处理的原始坐标）
            abs_x = int(clamp(x, 0, 1000) / 1000 * screen_width)
            abs_y = int(clamp(y, 0, 1000) / 1000 * screen_height)
        else:
            # 绝对像素坐标
            abs_x = int(x)
            abs_y = int(y)

        abs_x = int(clamp(abs_x, 0, screen_width - 1))
        abs_y = int(clamp(abs_y, 0, screen_height - 1))
        return abs_x, abs_y

    def _extract_coordinate(
        self, params: dict, key: str, screen_width: int, screen_height: int
    ) -> tuple[int, int]:
        """从参数中提取并转换坐标"""
        coord = params.get(key, [screen_width // 2, screen_height // 2])
        if not coord or len(coord) < 2:
            return screen_width // 2, screen_height // 2

        x, y = float(coord[0]), float(coord[1])
        return self._convert_to_absolute(x, y, screen_width, screen_height)

    def execute(
        self,
        action: GUIOwlAction,
        screen_width: int,
        screen_height: int,
    ) -> ActionResult:
        """
        执行解析后的 GUI-Owl 动作

        Args:
            action: 解析后的 GUIOwlAction
            screen_width: 屏幕宽度
            screen_height: 屏幕高度

        Returns:
            ActionResult
        """
        handler_method = self._get_handler(action.action_type)

        if handler_method is None:
            return ActionResult(
                success=False,
                should_finish=False,
                message=f"Unknown GUI-Owl action: {action.action_type}",
            )

        try:
            result = handler_method(action.params, screen_width, screen_height)
            # 记录操作历史（使用 conclusion/description）
            if result.success and action.description:
                self.action_history.append(action.description)
            elif result.success:
                self.action_history.append(result.message or action.action_type)
            return result
        except Exception as e:
            return ActionResult(
                success=False,
                should_finish=False,
                message=f"Action failed: {e}",
            )

    def _get_handler(self, action_type: str) -> Callable | None:
        """获取动作处理方法"""
        handlers = {
            "click": self._handle_click,
            "long_press": self._handle_long_press,
            "swipe": self._handle_swipe,
            "type": self._handle_type,
            "system_button": self._handle_system_button,
            "open": self._handle_open,
            "wait": self._handle_wait,
            "answer": self._handle_answer,
            "terminate": self._handle_terminate,
            "key": self._handle_key,
            "interact": self._handle_interact,
        }
        return handlers.get(action_type)

    def _handle_click(self, params: dict, width: int, height: int) -> ActionResult:
        abs_x, abs_y = self._extract_coordinate(params, "coordinate", width, height)

        device_factory = get_device_factory()
        device_factory.tap(abs_x, abs_y, self.device_id)

        return ActionResult(True, False, f"点击 ({abs_x},{abs_y})")

    def _handle_long_press(self, params: dict, width: int, height: int) -> ActionResult:
        abs_x, abs_y = self._extract_coordinate(params, "coordinate", width, height)

        device_factory = get_device_factory()
        device_factory.long_press(abs_x, abs_y, device_id=self.device_id)

        return ActionResult(True, False, f"长按 ({abs_x},{abs_y})")

    def _handle_swipe(self, params: dict, width: int, height: int) -> ActionResult:
        """GUI-Owl 的 swipe 使用 coordinate + coordinate2"""
        start_x, start_y = self._extract_coordinate(params, "coordinate", width, height)
        end_x, end_y = self._extract_coordinate(params, "coordinate2", width, height)

        device_factory = get_device_factory()
        device_factory.swipe(start_x, start_y, end_x, end_y, device_id=self.device_id)

        return ActionResult(True, False, f"滑动 ({start_x},{start_y}) → ({end_x},{end_y})")

    def _handle_type(self, params: dict, width: int, height: int) -> ActionResult:
        text = params.get("text", "")

        text = text.replace("\\n", "\n")
        text = text.replace("\\'", "'")
        text = text.replace('\\"', '"')

        device_factory = get_device_factory()

        original_ime = device_factory.detect_and_set_adb_keyboard(self.device_id)
        time.sleep(TIMING_CONFIG.action.keyboard_switch_delay)

        device_factory.clear_text(self.device_id)
        time.sleep(TIMING_CONFIG.action.text_clear_delay)

        device_factory.type_text(text, self.device_id)
        time.sleep(TIMING_CONFIG.action.text_input_delay)

        device_factory.restore_keyboard(original_ime, self.device_id)
        time.sleep(TIMING_CONFIG.action.keyboard_restore_delay)

        return ActionResult(True, False, f"输入文本: {text[:50]}")

    def _handle_system_button(self, params: dict, width: int, height: int) -> ActionResult:
        button = params.get("button", "").lower()

        device_factory = get_device_factory()

        if button == "back":
            device_factory.back(self.device_id)
            return ActionResult(True, False, "按下返回键")
        elif button == "home":
            device_factory.home(self.device_id)
            return ActionResult(True, False, "按下 Home 键")
        elif button == "menu":
            device_factory.recent_apps(self.device_id)
            return ActionResult(True, False, "按下菜单键")
        elif button == "enter":
            device_factory.press_enter(self.device_id)
            return ActionResult(True, False, "按下回车键")
        else:
            return ActionResult(False, False, f"未知系统按钮: {button}")

    def _handle_open(self, params: dict, width: int, height: int) -> ActionResult:
        app_name = params.get("text", "")

        if not app_name:
            return ActionResult(False, False, "未指定应用名称")

        device_factory = get_device_factory()
        success = device_factory.launch_app(app_name, self.device_id)

        if success:
            return ActionResult(True, False, f"打开应用: {app_name}")
        return ActionResult(False, False, f"无法打开应用: {app_name}")

    def _handle_wait(self, params: dict, width: int, height: int) -> ActionResult:
        seconds = params.get("time", params.get("seconds", 2))
        time.sleep(int(seconds))
        return ActionResult(True, False, f"等待 {seconds} 秒")

    def _handle_answer(self, params: dict, width: int, height: int) -> ActionResult:
        text = params.get("text", "")
        return ActionResult(True, True, f"回答: {text}")

    def _handle_terminate(self, params: dict, width: int, height: int) -> ActionResult:
        status = params.get("status", "success")
        if status == "success":
            return ActionResult(True, True, "任务成功完成")
        else:
            return ActionResult(True, True, "任务失败终止")

    def _handle_key(self, params: dict, width: int, height: int) -> ActionResult:
        """处理 ADB keyevent"""
        keycode = params.get("text", params.get("keycode", ""))
        if not keycode:
            return ActionResult(False, False, "未指定 keycode")

        device_factory = get_device_factory()
        device_factory.press_key(keycode, self.device_id)
        return ActionResult(True, False, f"按键: {keycode}")

    def _handle_interact(self, params: dict, width: int, height: int) -> ActionResult:
        """处理 interact 动作（阻塞式用户交互）"""
        text = params.get("text", "需要用户交互")
        if self.takeover_callback:
            self.takeover_callback(text)
        return ActionResult(True, False, f"用户交互: {text}")

    @staticmethod
    def _default_confirmation(message: str) -> bool:
        response = input(f"{message} (Y/n): ")
        return response.upper() == "Y"

    @staticmethod
    def _default_takeover(message: str) -> None:
        input(f"{message}\n完成人工操作后按 Enter 继续...")


def convert_guiowl_to_autoglm(action: GUIOwlAction, screen_width: int, screen_height: int) -> dict:
    """
    将 GUI-Owl 动作转换为 AutoGLM 格式

    用于统一日志显示和调试
    """
    action_type = action.action_type
    params = action.params

    type_mapping = {
        "click": "Tap",
        "long_press": "Long Press",
        "swipe": "Swipe",
        "type": "Type",
        "system_button": "System",
        "open": "Launch",
        "wait": "Wait",
        "answer": "finish",
        "terminate": "finish",
        "key": "Key",
        "interact": "Interact",
    }

    autoglm_action = {
        "_metadata": "finish" if action_type in ["answer", "terminate"] else "do",
    }

    if action_type in ["answer", "terminate"]:
        text = params.get("text", "") or params.get("status", "completed")
        autoglm_action["message"] = text
    else:
        autoglm_action["action"] = type_mapping.get(action_type, action_type)

        if action_type in ["click", "long_press"]:
            coord = params.get("coordinate", [0, 0])
            autoglm_action["element"] = coord
        elif action_type == "type":
            autoglm_action["text"] = params.get("text", "")
        elif action_type == "swipe":
            autoglm_action["start"] = params.get("coordinate", [0, 0])
            autoglm_action["end"] = params.get("coordinate2", [0, 0])
        elif action_type == "open":
            autoglm_action["app"] = params.get("text", "")
        elif action_type == "system_button":
            button = params.get("button", "").lower()
            if button == "back":
                autoglm_action["action"] = "Back"
            elif button == "home":
                autoglm_action["action"] = "Home"
        elif action_type == "wait":
            autoglm_action["duration"] = f"{params.get('time', params.get('seconds', 2))} seconds"

    return autoglm_action
