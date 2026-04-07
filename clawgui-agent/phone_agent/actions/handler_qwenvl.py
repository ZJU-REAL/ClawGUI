"""
Qwen-VL (Qwen2.5-VL / Qwen3-VL) 模型的 Action 处理器

解析 Qwen 官方 tool_call 格式：
<tool_call>
{"name": "mobile_use", "arguments": {"action": "click", "coordinate": [x, y]}}
</tool_call>

同时兼容旧的 tap(x, y) 自定义格式作为 fallback。
"""

import json
import re
import time
from dataclasses import dataclass
from typing import Any, Callable

from phone_agent.actions.handler import ActionResult
from phone_agent.config.timing import TIMING_CONFIG
from phone_agent.device_factory import get_device_factory


@dataclass 
class QwenVLAction:
    """Qwen-VL 解析后的动作结构"""
    action_type: str
    params: dict[str, Any]
    thinking: str = ""
    action_desc: str = ""  # 模型输出的 Action 原文，用于构建操作历史


class QwenVLActionHandler:
    """
    处理 Qwen-VL 模型输出的 Action
    
    支持两种格式：
    
    1. 官方 tool_call 格式（推荐）：
       <tool_call>
       {"name": "mobile_use", "arguments": {"action": "click", "coordinate": [500, 300]}}
       </tool_call>
       
       支持的 action:
       - click: 点击 (coordinate)
       - long_press: 长按 (coordinate, time)
       - swipe: 滑动 (coordinate, coordinate2)
       - type: 输入文本 (text)
       - answer: 输出答案 (text)
       - system_button: 系统按键 (button: Back/Home/Menu/Enter)
       - open_app: 打开应用 (app_name)
       - wait: 等待 (time)
       - terminate: 结束任务 (status: success/failure)
    
    2. 旧的自定义格式（fallback）：
       - tap(x, y)
       - long_press(x, y)
       - swipe(x1, y1, x2, y2)
       - type(text)
       - finish(message)
       等
    
    坐标系统：使用 0-999 归一化坐标（Qwen 官方规范）
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
        # 存储操作历史（用于构建消息上下文）
        self.action_history: list[str] = []
    
    def parse_response(self, response: str) -> QwenVLAction:
        """
        解析 Qwen-VL 模型的响应
        
        优先尝试解析 <tool_call> 格式，fallback 到旧格式。
        
        Args:
            response: 模型原始响应
            
        Returns:
            解析后的 QwenVLAction 对象
        """
        thinking = ""
        action_desc = ""
        
        # 提取 Thought 和 Action（两种格式通用）
        lines = response.strip().split('\n')
        for line in lines:
            line_stripped = line.strip()
            if line_stripped.startswith('Thought:'):
                thinking = line_stripped[8:].strip()
            elif line_stripped.startswith('Action:'):
                action_desc = line_stripped[7:].strip()
                # 去除可能的引号包裹
                if action_desc.startswith('"') and action_desc.endswith('"'):
                    action_desc = action_desc[1:-1].strip()
        
        # ======== 尝试解析 <tool_call> 格式 ========
        tool_call_match = re.search(
            r'<tool_call>\s*(.*?)\s*</tool_call>', 
            response, 
            re.DOTALL
        )
        if tool_call_match:
            try:
                tool_call_str = tool_call_match.group(1).strip()
                tool_call_data = json.loads(tool_call_str)
                
                arguments = tool_call_data.get("arguments", {})
                action_type = arguments.get("action", "unknown")
                
                # 构建 params
                params = {}
                for key, value in arguments.items():
                    if key != "action":
                        params[key] = value
                
                return QwenVLAction(
                    action_type=action_type,
                    params=params,
                    thinking=thinking,
                    action_desc=action_desc,
                )
            except (json.JSONDecodeError, KeyError, TypeError) as e:
                # JSON 解析失败，fallback 到旧格式
                pass
        else:
            # 没有匹配到 <tool_call>，尝试直接解析 JSON（某些模型不输出标签）
            json_match = re.search(
                r'\{\s*"name"\s*:\s*"mobile_use"\s*,\s*"arguments"\s*:\s*\{.*?\}\s*\}',
                response,
                re.DOTALL,
            )
            if json_match:
                try:
                    tool_call_data = json.loads(json_match.group())
                    arguments = tool_call_data.get("arguments", {})
                    action_type = arguments.get("action", "unknown")
                    params = {k: v for k, v in arguments.items() if k != "action"}
                    return QwenVLAction(
                        action_type=action_type,
                        params=params,
                        thinking=thinking,
                        action_desc=action_desc,
                    )
                except (json.JSONDecodeError, KeyError, TypeError):
                    pass
        
        # ======== Fallback：解析旧的自定义格式 ========
        result = self._parse_legacy_format(response, thinking, action_desc)
        return result
    
    def _parse_legacy_format(self, response: str, thinking: str, action_desc: str = "") -> QwenVLAction:
        """
        解析旧的自定义格式 (tap(x,y) / swipe(x1,y1,x2,y2) 等)
        
        作为 fallback，保持向后兼容。
        """
        action_str = ""
        
        # 提取 Action: 行
        lines = response.strip().split('\n')
        for line in lines:
            line_stripped = line.strip()
            if line_stripped.startswith('Action:'):
                action_str = line_stripped[7:].strip()
                break
        
        # 如果没有找到 Action: 前缀，尝试直接查找函数调用
        if not action_str:
            action_patterns = [
                r'(tap|click|long_press|double_tap|swipe|type|type_name|open_app|back|home|wait|finish|terminate)\s*\(',
            ]
            for pattern in action_patterns:
                match = re.search(pattern, response, re.IGNORECASE)
                if match:
                    start = match.start()
                    action_str = response[start:]
                    paren_count = 0
                    for i, c in enumerate(action_str):
                        if c == '(':
                            paren_count += 1
                        elif c == ')':
                            paren_count -= 1
                            if paren_count == 0:
                                action_str = action_str[:i+1]
                                break
                    break
        
        if not action_str:
            return QwenVLAction(
                action_type="unknown",
                params={"raw": response},
                thinking=thinking,
                action_desc=action_desc,
            )
        
        # 解析函数调用
        action_type, params = self._parse_action_call(action_str)
        
        # 将旧格式的 action_type 映射到官方格式
        # 注意：legacy 格式的 params 是 {"x": ..., "y": ...}，需要转换为 {"coordinate": [x, y]}
        legacy_to_official = {
            "tap": "click",
            "double_tap": "click",  # 映射到 click
            "back": "system_button",
            "home": "system_button",
            "finish": "terminate",
        }
        
        if action_type in legacy_to_official:
            new_type = legacy_to_official[action_type]
            if action_type == "tap" or action_type == "double_tap":
                # tap(x, y) -> click, coordinate: [x, y]
                params = {"coordinate": [params.get("x", 500), params.get("y", 500)]}
            elif action_type == "back":
                params = {"button": "Back"}
                action_type = "system_button"
            elif action_type == "home":
                params = {"button": "Home"}
                action_type = "system_button"
            elif action_type == "finish":
                params = {"status": "success"}
                action_type = "terminate"
            else:
                action_type = new_type
        elif action_type == "click" and "x" in params:
            # click(x, y) legacy 格式 -> 转换 params 为 coordinate 格式
            params = {"coordinate": [params.get("x", 500), params.get("y", 500)]}
        elif action_type == "swipe" and "x1" in params:
            # swipe(x1,y1,x2,y2) -> swipe, coordinate + coordinate2
            params = {
                "coordinate": [params.get("x1", 500), params.get("y1", 700)],
                "coordinate2": [params.get("x2", 500), params.get("y2", 300)],
            }
        elif action_type == "long_press" and "x" in params:
            params = {
                "coordinate": [params.get("x", 500), params.get("y", 500)],
                "time": 2,
            }
        
        return QwenVLAction(
            action_type=action_type,
            params=params,
            thinking=thinking,
            action_desc=action_desc,
        )
    
    def _parse_action_call(self, action_str: str) -> tuple[str, dict[str, Any]]:
        """
        解析旧格式的 action 函数调用
        
        Args:
            action_str: 如 "tap(500, 300)" 或 "type('hello world')"
            
        Returns:
            (action_type, params) 元组
        """
        # 去掉首尾空白，避免正则匹配失败
        action_str = action_str.strip()
        match = re.match(r'(\w+)\s*\((.*)\)\s*$', action_str, re.DOTALL)
        if not match:
            return "unknown", {"raw": action_str}
        
        action_type = match.group(1).lower()
        params_str = match.group(2).strip()
        
        params = {}
        
        if not params_str:
            return action_type, params
        
        if action_type in ("tap", "click", "long_press", "double_tap"):
            coords = re.findall(r'(\d+)', params_str)
            if len(coords) >= 2:
                params["x"] = int(coords[0])
                params["y"] = int(coords[1])
        
        elif action_type == "swipe":
            coords = re.findall(r'(\d+)', params_str)
            if len(coords) >= 4:
                params["x1"] = int(coords[0])
                params["y1"] = int(coords[1])
                params["x2"] = int(coords[2])
                params["y2"] = int(coords[3])
        
        elif action_type in ("type", "type_name"):
            text_match = re.search(r'["\'](.+?)["\']', params_str, re.DOTALL)
            if text_match:
                params["text"] = text_match.group(1)
            else:
                params["text"] = params_str.strip("'\"")
        
        elif action_type == "open_app":
            app_match = re.search(r'["\'](.+?)["\']', params_str)
            if app_match:
                params["app_name"] = app_match.group(1)
            else:
                params["app_name"] = params_str.strip("'\"")
        
        elif action_type == "wait":
            seconds_match = re.search(r'(\d+)', params_str)
            if seconds_match:
                params["time"] = int(seconds_match.group(1))
            else:
                params["time"] = 2
        
        elif action_type in ("finish", "terminate"):
            msg_match = re.search(r'["\'](.+?)["\']', params_str, re.DOTALL)
            if msg_match:
                params["status"] = "success"
            else:
                params["status"] = "success"
        
        return action_type, params
    
    def _convert_coordinate_to_absolute(
        self, coord: list, screen_width: int, screen_height: int
    ) -> tuple[int, int]:
        """
        将 Qwen 坐标转换为绝对像素坐标
        
        Qwen 使用 0-999 归一化坐标系统（system prompt 中指定 screen resolution = 999x999）
        也支持 0-1000 和 0-1 归一化坐标作为兼容
        
        Args:
            coord: [x, y] 坐标数组
            screen_width: 实际屏幕宽度
            screen_height: 实际屏幕高度
            
        Returns:
            (abs_x, abs_y) 绝对像素坐标
        """
        if not coord or len(coord) < 2:
            return screen_width // 2, screen_height // 2
        
        x, y = coord[0], coord[1]
        screen_width = max(1, screen_width)
        screen_height = max(1, screen_height)
        
        def clamp(val: float, low: float, high: float) -> float:
            return max(low, min(high, val))
        
        # 判断坐标系统
        if x <= 1 and y <= 1:
            # 0-1 归一化
            abs_x = int(clamp(x, 0, 1) * screen_width)
            abs_y = int(clamp(y, 0, 1) * screen_height)
        elif x <= 1000 and y <= 1000:
            # 0-999 或 0-1000 归一化（Qwen 官方使用 999）
            # 映射方式: pixel = coord / 999 * screen_size
            abs_x = int(clamp(x, 0, 999) / 999 * screen_width)
            abs_y = int(clamp(y, 0, 999) / 999 * screen_height)
        else:
            # 已经是绝对坐标
            abs_x = int(x)
            abs_y = int(y)
        
        abs_x = int(clamp(abs_x, 0, screen_width - 1))
        abs_y = int(clamp(abs_y, 0, screen_height - 1))
        return abs_x, abs_y

    def execute(
        self, 
        action: QwenVLAction, 
        screen_width: int, 
        screen_height: int
    ) -> ActionResult:
        """
        执行解析后的 action
        
        Args:
            action: 解析后的 QwenVLAction
            screen_width: 屏幕宽度
            screen_height: 屏幕高度
            
        Returns:
            ActionResult
        """
        action_type = action.action_type
        params = action.params
        
        handler_method = self._get_handler(action_type)
        
        if handler_method is None:
            return ActionResult(
                success=False,
                should_finish=False,
                message=f"Unknown Qwen-VL action: {action_type}"
            )
        
        try:
            result = handler_method(params, screen_width, screen_height)
            # 记录成功的操作到历史（直接使用模型输出的 Action 原文）
            if result.success:
                history_desc = action.action_desc if action.action_desc else self._describe_action(action_type, params)
                if history_desc:
                    self.action_history.append(history_desc)
            return result
        except Exception as e:
            return ActionResult(
                success=False,
                should_finish=False,
                message=f"Action failed: {e}"
            )
    
    def _describe_action(self, action_type: str, params: dict) -> str:
        """生成操作的文本描述（用于历史记录）"""
        if action_type == "click":
            coord = params.get("coordinate", [0, 0])
            return f"Clicked at ({coord[0]}, {coord[1]})"
        elif action_type == "long_press":
            coord = params.get("coordinate", [0, 0])
            return f"Long pressed at ({coord[0]}, {coord[1]})"
        elif action_type == "swipe":
            coord1 = params.get("coordinate", [0, 0])
            coord2 = params.get("coordinate2", [0, 0])
            return f"Swiped from ({coord1[0]}, {coord1[1]}) to ({coord2[0]}, {coord2[1]})"
        elif action_type == "type":
            text = params.get("text", "")
            return f"Typed: {text}"
        elif action_type == "system_button":
            button = params.get("button", "")
            return f"Pressed system button: {button}"
        elif action_type == "open_app":
            app = params.get("app_name", "")
            return f"Opened app: {app}"
        elif action_type == "wait":
            return f"Waited {params.get('time', 1)} seconds"
        elif action_type == "terminate":
            return f"Task terminated with status: {params.get('status', 'unknown')}"
        elif action_type == "answer":
            return f"Answered: {params.get('text', '')}"
        return ""
    
    def _get_handler_map(self) -> dict[str, Callable]:
        """获取所有 action handler 的映射"""
        return {
            # 官方 tool_call 格式的 action
            "click": self._handle_click,
            "long_press": self._handle_long_press,
            "swipe": self._handle_swipe,
            "type": self._handle_type,
            "answer": self._handle_answer,
            "system_button": self._handle_system_button,
            "open_app": self._handle_open_app,
            "wait": self._handle_wait,
            "terminate": self._handle_terminate,
            # 兼容旧格式的 action（fallback 解析后已映射，但保留以防万一）
            "tap": self._handle_click,
            "double_tap": self._handle_click,
            "type_name": self._handle_type,
            "back": self._handle_back_compat,
            "home": self._handle_home_compat,
            "finish": self._handle_terminate,
        }
    
    def _get_handler(self, action_type: str) -> Callable | None:
        """获取 action 处理器"""
        handlers = self._get_handler_map()
        return handlers.get(action_type)
    
    def _handle_click(self, params: dict, width: int, height: int) -> ActionResult:
        """处理 click 操作"""
        coord = params.get("coordinate", [500, 500])
        abs_x, abs_y = self._convert_coordinate_to_absolute(coord, width, height)
        
        device_factory = get_device_factory()
        device_factory.tap(abs_x, abs_y, self.device_id)
        return ActionResult(True, False)
    
    def _handle_long_press(self, params: dict, width: int, height: int) -> ActionResult:
        """处理 long_press 操作"""
        coord = params.get("coordinate", [500, 500])
        press_time = params.get("time", 2)
        abs_x, abs_y = self._convert_coordinate_to_absolute(coord, width, height)
        
        device_factory = get_device_factory()
        device_factory.long_press(abs_x, abs_y, device_id=self.device_id)
        return ActionResult(True, False)
    
    def _handle_swipe(self, params: dict, width: int, height: int) -> ActionResult:
        """处理 swipe 操作"""
        coord1 = params.get("coordinate", [500, 700])
        coord2 = params.get("coordinate2", [500, 300])
        
        abs_x1, abs_y1 = self._convert_coordinate_to_absolute(coord1, width, height)
        abs_x2, abs_y2 = self._convert_coordinate_to_absolute(coord2, width, height)
        
        device_factory = get_device_factory()
        device_factory.swipe(abs_x1, abs_y1, abs_x2, abs_y2, device_id=self.device_id)
        return ActionResult(True, False)
    
    def _handle_type(self, params: dict, width: int, height: int) -> ActionResult:
        """处理 type 操作"""
        text = params.get("text", "")
        
        # 处理转义字符
        text = text.replace("\\n", "\n")
        text = text.replace("\\'", "'")
        text = text.replace('\\"', '"')
        
        device_factory = get_device_factory()
        
        # 切换到 ADB 键盘
        original_ime = device_factory.detect_and_set_adb_keyboard(self.device_id)
        time.sleep(TIMING_CONFIG.action.keyboard_switch_delay)
        
        # 清除现有文本并输入新文本
        device_factory.clear_text(self.device_id)
        time.sleep(TIMING_CONFIG.action.text_clear_delay)
        
        device_factory.type_text(text, self.device_id)
        time.sleep(TIMING_CONFIG.action.text_input_delay)
        
        # 恢复原始键盘
        device_factory.restore_keyboard(original_ime, self.device_id)
        time.sleep(TIMING_CONFIG.action.keyboard_restore_delay)
        
        return ActionResult(True, False)
    
    def _handle_answer(self, params: dict, width: int, height: int) -> ActionResult:
        """处理 answer 操作（输出答案）"""
        text = params.get("text", "")
        return ActionResult(
            success=True,
            should_finish=True,
            message=text or "Answer provided"
        )
    
    def _handle_system_button(self, params: dict, width: int, height: int) -> ActionResult:
        """处理 system_button 操作"""
        button = params.get("button", "Back")
        
        device_factory = get_device_factory()
        
        if button == "Back":
            device_factory.back(self.device_id)
        elif button == "Home":
            device_factory.home(self.device_id)
        elif button == "Menu":
            # Menu 键 → 通常映射为 recent apps
            device_factory.back(self.device_id)  # fallback
        elif button == "Enter":
            # Enter 键
            import subprocess
            from phone_agent.device_factory import DeviceType
            if device_factory.device_type == DeviceType.HDC:
                from phone_agent.hdc.connection import _run_hdc_command
                hdc_prefix = ["hdc", "-t", self.device_id] if self.device_id else ["hdc"]
                _run_hdc_command(
                    hdc_prefix + ["shell", "uitest", "uiInput", "keyEvent", "2054"],
                    capture_output=True, text=True,
                )
            else:
                cmd_prefix = ["adb", "-s", self.device_id] if self.device_id else ["adb"]
                subprocess.run(
                    cmd_prefix + ["shell", "input", "keyevent", "KEYCODE_ENTER"],
                    capture_output=True, text=True,
                )
        else:
            return ActionResult(False, False, f"Unknown system button: {button}")
        
        return ActionResult(True, False)
    
    def _handle_open_app(self, params: dict, width: int, height: int) -> ActionResult:
        """处理 open_app 操作"""
        app_name = params.get("app_name", "")
        
        if not app_name:
            return ActionResult(False, False, "No app name specified")
        
        device_factory = get_device_factory()
        success = device_factory.launch_app(app_name, self.device_id)
        
        if success:
            return ActionResult(True, False)
        return ActionResult(False, False, f"App not found: {app_name}")
    
    def _handle_wait(self, params: dict, width: int, height: int) -> ActionResult:
        """处理 wait 操作"""
        seconds = params.get("time", 2)
        if isinstance(seconds, str):
            try:
                seconds = float(seconds)
            except ValueError:
                seconds = 2
        time.sleep(seconds)
        return ActionResult(True, False)
    
    def _handle_terminate(self, params: dict, width: int, height: int) -> ActionResult:
        """处理 terminate 操作（任务完成）"""
        status = params.get("status", "success")
        message = params.get("message", f"Task {status}")
        return ActionResult(
            success=(status == "success"),
            should_finish=True,
            message=message
        )
    
    # ========== 兼容旧格式的处理器 ==========
    
    def _handle_back_compat(self, params: dict, width: int, height: int) -> ActionResult:
        """兼容旧格式的 back() 操作"""
        return self._handle_system_button({"button": "Back"}, width, height)
    
    def _handle_home_compat(self, params: dict, width: int, height: int) -> ActionResult:
        """兼容旧格式的 home() 操作"""
        return self._handle_system_button({"button": "Home"}, width, height)
    
    @staticmethod
    def _default_confirmation(message: str) -> bool:
        """默认确认回调"""
        response = input(f"Sensitive operation: {message}\nConfirm? (Y/N): ")
        return response.upper() == "Y"
    
    @staticmethod
    def _default_takeover(message: str) -> None:
        """默认接管回调"""
        input(f"{message}\nPress Enter after completing manual operation...")
    
    def clear_history(self):
        """清空操作历史"""
        self.action_history.clear()


def convert_qwenvl_to_autoglm(qwenvl_action: QwenVLAction) -> dict[str, Any]:
    """
    将 Qwen-VL action 转换为 AutoGLM 格式
    
    用于兼容现有的 ActionHandler
    
    Args:
        qwenvl_action: Qwen-VL 格式的 action
        
    Returns:
        AutoGLM 格式的 action 字典
    """
    action_type = qwenvl_action.action_type
    params = qwenvl_action.params
    
    # 官方 tool_call 格式 → AutoGLM 格式映射
    type_mapping = {
        "click": "Tap",
        "long_press": "Long Press",
        "swipe": "Swipe",
        "type": "Type",
        "answer": "Type",
        "system_button": None,  # 需要特殊处理
        "open_app": "Launch",
        "wait": "Wait",
        "terminate": None,  # 需要特殊处理
        # 旧格式兼容
        "tap": "Tap",
        "double_tap": "Double Tap",
        "type_name": "Type_Name",
        "back": "Back",
        "home": "Home",
        "finish": None,
    }
    
    autoglm_action = {}
    
    if action_type == "terminate" or action_type == "finish":
        autoglm_action["_metadata"] = "finish"
        autoglm_action["message"] = params.get("message", f"Task {params.get('status', 'completed')}")
    elif action_type == "system_button":
        button = params.get("button", "Back")
        autoglm_action["_metadata"] = "do"
        autoglm_action["action"] = button  # Back / Home / Menu / Enter
    else:
        autoglm_action["_metadata"] = "do"
        autoglm_action["action"] = type_mapping.get(action_type, action_type)
        
        # 转换参数
        if action_type == "click":
            coord = params.get("coordinate", [500, 500])
            autoglm_action["element"] = coord
        
        elif action_type in ("type", "type_name", "answer"):
            autoglm_action["text"] = params.get("text", "")
        
        elif action_type == "open_app":
            autoglm_action["app"] = params.get("app_name", "")
        
        elif action_type == "swipe":
            autoglm_action["start"] = params.get("coordinate", [500, 700])
            autoglm_action["end"] = params.get("coordinate2", [500, 300])
        
        elif action_type == "long_press":
            coord = params.get("coordinate", [500, 500])
            autoglm_action["element"] = coord
        
        elif action_type == "wait":
            autoglm_action["duration"] = f"{params.get('time', 2)} seconds"
    
    return autoglm_action
