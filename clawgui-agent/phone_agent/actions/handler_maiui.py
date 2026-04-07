"""
MAI-UI 模型的 Action Handler

基于阿里云通义 MAI-UI 项目: https://github.com/Tongyi-MAI/MAI-UI

MAI-UI 使用以下 action space:
- {"action": "click", "coordinate": [x, y]}
- {"action": "long_press", "coordinate": [x, y]}
- {"action": "type", "text": ""}
- {"action": "swipe", "direction": "up|down|left|right", "coordinate": [x, y]}
- {"action": "open", "text": "app_name"}
- {"action": "drag", "start_coordinate": [x1, y1], "end_coordinate": [x2, y2]}
- {"action": "system_button", "button": "back|home|menu|enter"}
- {"action": "wait"}
- {"action": "terminate", "status": "success|fail"}
- {"action": "answer", "text": "xxx"}

坐标使用 0-999 归一化坐标系统
"""

import json
import re
import time
from dataclasses import dataclass
from typing import Any, Callable, Dict, Optional, Tuple

from phone_agent.device_factory import get_device_factory
from phone_agent.config.timing import TIMING_CONFIG


# MAI-UI 使用的坐标缩放因子
SCALE_FACTOR = 999


@dataclass
class ActionResult:
    """动作执行结果"""
    success: bool
    should_finish: bool
    message: str = ""


@dataclass
class MAIUIAction:
    """解析后的 MAI-UI 动作"""
    action_type: str  # click, long_press, type, swipe, open, drag, system_button, wait, terminate, answer
    params: Dict[str, Any]
    raw_response: str = ""
    thinking: str = ""


class MAIUIActionHandler:
    """
    MAI-UI 模型的 Action Handler
    
    处理 MAI-UI 模型输出的动作指令，执行设备操作。
    
    Args:
        device_id: 可选的设备 ID
        confirmation_callback: 敏感操作确认回调
        takeover_callback: 人工接管回调
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
    
    def parse_response(self, response: str) -> MAIUIAction:
        """
        解析 MAI-UI 模型的响应
        
        MAI-UI 输出格式:
        <thinking>
        ...思考过程...
        </thinking>
        <tool_call>
        {"name": "mobile_use", "arguments": {"action": "click", "coordinate": [x, y]}}
        </tool_call>
        
        Args:
            response: 模型原始响应
            
        Returns:
            MAIUIAction 对象
        """
        response = response.strip()
        
        # 处理 thinking model 输出格式 (使用 </think> 而不是 </thinking>)
        if "</think>" in response and "</thinking>" not in response:
            response = response.replace("</think>", "</thinking>")
            if "<thinking>" not in response:
                response = "<thinking>" + response
        
        # 🔧 处理嵌套的 <think> 和 <tool_call> 标签
        # 如果 <thinking> 里面还有 <tool_call>，从 <tool_call> 里面提取 thinking 内容
        # 例如: <think>```html<tool_call>实际思考内容</think>answer>...</tool_call>
        thinking = ""
        if "<tool_call>" in response and "<thinking>" in response:
            # 检查是否是嵌套格式（thinking 里面包含 tool_call）
            thinking_match = re.search(r'<thinking>(.*?)</thinking>', response, re.DOTALL)
            if thinking_match:
                thinking_content = thinking_match.group(1).strip()
                # 如果 thinking 内容中包含 <tool_call>，从 tool_call 中提取真正的思考内容
                if "<tool_call>" in thinking_content:
                    # 尝试提取 <tool_call>...</ think> 之间的内容
                    inner_thinking_match = re.search(r'<tool_call>(.*?)(?:</thinking>|$)', thinking_content, re.DOTALL)
                    if inner_thinking_match:
                        # 提取到的内容，去掉可能的标记
                        raw_thinking = inner_thinking_match.group(1).strip()
                        # 清理格式噪声（```html, ```, answer> 等）
                        raw_thinking = raw_thinking.replace('```html', '').replace('```', '').strip()
                        raw_thinking = raw_thinking.split('answer>')[0].strip()  # 去掉 answer> 之后的内容
                        thinking = raw_thinking
                    else:
                        # 没有找到匹配，使用完整的 thinking_content（去掉 <tool_call> 标签）
                        thinking = re.sub(r'<tool_call>|</tool_call>', '', thinking_content).strip()
                else:
                    thinking = thinking_content
            else:
                thinking = ""
        else:
            # 正常提取 thinking 内容
            thinking_match = re.search(r'<thinking>(.*?)</thinking>', response, re.DOTALL)
            if thinking_match:
                thinking = thinking_match.group(1).strip()
        
        # 清理 thinking 中的格式噪声
        if thinking:
            thinking = thinking.replace('```html', '').replace('```', '').strip()
        
        # 提取 tool_call 内容
        # 🔧 处理可能缺失 </tool_call> 标签的情况（被 \n 截断）
        tool_call_match = re.search(r'<tool_call>\s*(.*?)(?:</tool_call>|$)', response, re.DOTALL)
        
        if not tool_call_match:
            # 尝试直接查找 JSON 对象
            json_match = re.search(r'\{[^{}]*"action"\s*:\s*"[^"]+"\s*[^{}]*\}', response)
            if json_match:
                try:
                    action_json = json.loads(json_match.group())
                    return MAIUIAction(
                        action_type=action_json.get("action", ""),
                        params=action_json,
                        raw_response=response,
                        thinking=thinking
                    )
                except json.JSONDecodeError:
                    pass
            
            # 如果没有找到，返回空动作
            return MAIUIAction(
                action_type="",
                params={},
                raw_response=response,
                thinking=thinking
            )
        
        tool_call_str = tool_call_match.group(1).strip()
        
        # 🔧 清理可能残留的 \n 转义序列和不完整的标签
        # 例如: \n{...}\n</tool_call -> {...}
        tool_call_str = tool_call_str.replace('\\n', '').strip()
        # 移除可能的不完整结束标签 (如 </tool_call 没有 >)
        tool_call_str = re.sub(r'</?tool_call[^>]*$', '', tool_call_str).strip()
        
        try:
            tool_call = json.loads(tool_call_str)
            
            # MAI-UI 格式: {"name": "mobile_use", "arguments": {...}}
            if "arguments" in tool_call:
                action_json = tool_call["arguments"]
            else:
                action_json = tool_call
            
            action_type = action_json.get("action", "")
            
            return MAIUIAction(
                action_type=action_type,
                params=action_json,
                raw_response=response,
                thinking=thinking
            )
            
        except json.JSONDecodeError as e:
            print(f"JSON 解析错误: {e}, 原始内容: {tool_call_str}")
            return MAIUIAction(
                action_type="",
                params={},
                raw_response=response,
                thinking=thinking
            )
    
    def _convert_coordinate_to_absolute(
        self, coord: list, screen_width: int, screen_height: int
    ) -> Tuple[int, int]:
        """
        将 MAI-UI 的归一化坐标转换为绝对像素坐标
        
        MAI-UI 使用 0-999 范围的归一化坐标
        
        Args:
            coord: [x, y] 坐标，可能是 0-999 归一化或 0-1 归一化
            screen_width: 屏幕宽度
            screen_height: 屏幕高度
            
        Returns:
            (abs_x, abs_y) 绝对像素坐标
        """
        if not coord or len(coord) < 2:
            return screen_width // 2, screen_height // 2
        
        x, y = coord[0], coord[1]
        
        # 处理边界框格式 [x1, y1, x2, y2]，取中心点
        if len(coord) == 4:
            x = (coord[0] + coord[2]) / 2
            y = (coord[1] + coord[3]) / 2
        
        # 判断坐标类型并转换
        if 0 <= x <= 1.0 and 0 <= y <= 1.0:
            # 0-1 归一化坐标（MAI-UI 解析后的格式）
            abs_x = int(x * screen_width)
            abs_y = int(y * screen_height)
        elif 0 <= x <= SCALE_FACTOR + 100 and 0 <= y <= SCALE_FACTOR + 100:
            # 0-999 归一化坐标（MAI-UI 原始输出）
            abs_x = int(min(x, SCALE_FACTOR) / SCALE_FACTOR * screen_width)
            abs_y = int(min(y, SCALE_FACTOR) / SCALE_FACTOR * screen_height)
        else:
            # 像素坐标，直接使用
            abs_x = int(max(0, min(x, screen_width - 1)))
            abs_y = int(max(0, min(y, screen_height - 1)))
        
        # 确保在屏幕范围内
        abs_x = max(0, min(abs_x, screen_width - 1))
        abs_y = max(0, min(abs_y, screen_height - 1))
        
        return abs_x, abs_y
    
    def execute(
        self, 
        action: MAIUIAction, 
        screen_width: int, 
        screen_height: int
    ) -> ActionResult:
        """
        执行解析后的 MAI-UI 动作
        
        Args:
            action: 解析后的 MAIUIAction
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
                message=f"Unknown MAI-UI action: {action_type}"
            )
        
        return handler_method(params, screen_width, screen_height)
    
    def _get_handler(self, action_type: str):
        """获取动作处理方法"""
        handlers = {
            "click": self._handle_click,
            "long_press": self._handle_long_press,
            "type": self._handle_type,
            "swipe": self._handle_swipe,
            "open": self._handle_open,
            "drag": self._handle_drag,
            "system_button": self._handle_system_button,
            "wait": self._handle_wait,
            "terminate": self._handle_terminate,
            "answer": self._handle_answer,
        }
        return handlers.get(action_type)
    
    def _handle_click(self, params: dict, width: int, height: int) -> ActionResult:
        """处理点击操作"""
        coord = params.get("coordinate", [width // 2, height // 2])
        abs_x, abs_y = self._convert_coordinate_to_absolute(coord, width, height)
        
        device_factory = get_device_factory()
        device_factory.tap(abs_x, abs_y, self.device_id)
        
        return ActionResult(True, False, f"点击 {coord} → 屏幕坐标 ({abs_x},{abs_y})")
    
    def _handle_long_press(self, params: dict, width: int, height: int) -> ActionResult:
        """处理长按操作"""
        coord = params.get("coordinate", [width // 2, height // 2])
        abs_x, abs_y = self._convert_coordinate_to_absolute(coord, width, height)
        
        device_factory = get_device_factory()
        device_factory.long_press(abs_x, abs_y, device_id=self.device_id)
        
        return ActionResult(True, False, f"长按 {coord} → 屏幕坐标 ({abs_x},{abs_y})")
    
    def _handle_type(self, params: dict, width: int, height: int) -> ActionResult:
        """处理文本输入"""
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
        
        return ActionResult(True, False, f"输入文本: {text[:50]}...")
    
    def _handle_swipe(self, params: dict, width: int, height: int) -> ActionResult:
        """处理滑动操作"""
        direction = params.get("direction", "down")
        coord = params.get("coordinate")
        
        # 如果没有指定坐标，使用屏幕中心
        if coord:
            abs_x, abs_y = self._convert_coordinate_to_absolute(coord, width, height)
        else:
            abs_x, abs_y = width // 2, height // 2
        
        # 根据方向计算滑动终点
        swipe_distance = int(min(width, height) * 0.3)
        
        if direction == "down":
            end_x, end_y = abs_x, abs_y - swipe_distance
        elif direction == "up":
            end_x, end_y = abs_x, abs_y + swipe_distance
        elif direction == "left":
            end_x, end_y = abs_x + swipe_distance, abs_y
        elif direction == "right":
            end_x, end_y = abs_x - swipe_distance, abs_y
        else:
            end_x, end_y = abs_x, abs_y - swipe_distance
        
        # 确保在屏幕范围内
        end_x = max(0, min(end_x, width - 1))
        end_y = max(0, min(end_y, height - 1))
        
        device_factory = get_device_factory()
        device_factory.swipe(abs_x, abs_y, end_x, end_y, device_id=self.device_id)
        
        return ActionResult(True, False, f"滑动 {direction} 从 ({abs_x},{abs_y}) 到 ({end_x},{end_y})")
    
    def _handle_open(self, params: dict, width: int, height: int) -> ActionResult:
        """处理打开应用"""
        app_name = params.get("text", "")
        
        if not app_name:
            return ActionResult(False, False, "未指定应用名称")
        
        device_factory = get_device_factory()
        success = device_factory.launch_app(app_name, self.device_id)
        
        if success:
            return ActionResult(True, False, f"打开应用: {app_name}")
        return ActionResult(False, False, f"无法打开应用: {app_name}")
    
    def _handle_drag(self, params: dict, width: int, height: int) -> ActionResult:
        """处理拖拽操作"""
        start_coord = params.get("start_coordinate", [width // 2, height // 2])
        end_coord = params.get("end_coordinate", [width // 2, height // 2])
        
        start_x, start_y = self._convert_coordinate_to_absolute(start_coord, width, height)
        end_x, end_y = self._convert_coordinate_to_absolute(end_coord, width, height)
        
        device_factory = get_device_factory()
        device_factory.swipe(start_x, start_y, end_x, end_y, device_id=self.device_id)
        
        return ActionResult(True, False, f"拖拽 ({start_x},{start_y}) → ({end_x},{end_y})")
    
    def _handle_system_button(self, params: dict, width: int, height: int) -> ActionResult:
        """处理系统按钮"""
        button = params.get("button", "")
        
        device_factory = get_device_factory()
        
        if button == "back":
            device_factory.back(self.device_id)
            return ActionResult(True, False, "按下返回键")
        elif button == "home":
            device_factory.home(self.device_id)
            return ActionResult(True, False, "按下 Home 键")
        elif button == "menu":
            # 菜单键通常使用 recent apps
            device_factory.recent_apps(self.device_id)
            return ActionResult(True, False, "按下菜单键")
        elif button == "enter":
            device_factory.press_enter(self.device_id)
            return ActionResult(True, False, "按下回车键")
        else:
            return ActionResult(False, False, f"未知的系统按钮: {button}")
    
    def _handle_wait(self, params: dict, width: int, height: int) -> ActionResult:
        """处理等待操作"""
        time.sleep(2)  # 默认等待 2 秒
        return ActionResult(True, False, "等待 2 秒")
    
    def _handle_terminate(self, params: dict, width: int, height: int) -> ActionResult:
        """处理终止操作"""
        status = params.get("status", "success")
        
        if status == "success":
            return ActionResult(True, True, "任务成功完成")
        else:
            return ActionResult(True, True, "任务失败终止")
    
    def _handle_answer(self, params: dict, width: int, height: int) -> ActionResult:
        """处理回答操作"""
        text = params.get("text", "")
        return ActionResult(True, True, f"回答: {text}")
    
    @staticmethod
    def _default_confirmation(message: str) -> bool:
        """默认确认回调"""
        response = input(f"{message} (Y/n): ")
        return response.upper() == "Y"
    
    @staticmethod
    def _default_takeover(message: str) -> None:
        """默认接管回调"""
        input(f"{message}\n完成人工操作后按 Enter 继续...")


def convert_maiui_to_autoglm(action: MAIUIAction, screen_width: int, screen_height: int) -> dict:
    """
    将 MAI-UI 动作转换为 AutoGLM 格式
    
    用于统一日志显示和调试
    """
    action_type = action.action_type
    params = action.params
    
    # 动作类型映射
    type_mapping = {
        "click": "Tap",
        "long_press": "Long Press",
        "type": "Type",
        "swipe": "Swipe",
        "open": "Open App",
        "drag": "Swipe",
        "system_button": "System",
        "wait": "Wait",
        "terminate": "finish",
        "answer": "finish",
    }
    
    autoglm_action = {
        "_metadata": "finish" if action_type in ["terminate", "answer"] else "do",
    }
    
    if action_type in ["terminate", "answer"]:
        text = params.get("text", "") or params.get("status", "completed")
        autoglm_action["message"] = text
    else:
        autoglm_action["action"] = type_mapping.get(action_type, action_type)
        
        if action_type in ["click", "long_press"]:
            coord = params.get("coordinate", [500, 500])
            autoglm_action["element"] = coord
        elif action_type == "type":
            autoglm_action["text"] = params.get("text", "")
        elif action_type == "swipe":
            autoglm_action["direction"] = params.get("direction", "down")
        elif action_type == "open":
            autoglm_action["app_name"] = params.get("text", "")
        elif action_type == "system_button":
            button = params.get("button", "")
            if button == "back":
                autoglm_action["action"] = "Back"
            elif button == "home":
                autoglm_action["action"] = "Home"
    
    return autoglm_action



