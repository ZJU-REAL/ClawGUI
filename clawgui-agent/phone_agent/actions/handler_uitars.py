"""
Doubao-1.5-UI-TARS 模型的 Action 处理器

将 UI-TARS 的 action 格式转换为通用格式并执行
"""

import math
import re
import time
from dataclasses import dataclass
from typing import Any, Callable

from phone_agent.actions.handler import ActionResult
from phone_agent.config.timing import TIMING_CONFIG
from phone_agent.device_factory import get_device_factory


# ==================== smart_resize 相关常量和函数 ====================
IMAGE_FACTOR = 28
MIN_PIXELS = 100 * 28 * 28
MAX_PIXELS = 16384 * 28 * 28
MAX_RATIO = 200


def _round_by_factor(number: int, factor: int) -> int:
    """Returns the closest integer to 'number' that is divisible by 'factor'."""
    return round(number / factor) * factor


def _ceil_by_factor(number: int, factor: int) -> int:
    """Returns the smallest integer >= 'number' that is divisible by 'factor'."""
    return math.ceil(number / factor) * factor


def _floor_by_factor(number: int, factor: int) -> int:
    """Returns the largest integer <= 'number' that is divisible by 'factor'."""
    return math.floor(number / factor) * factor


def smart_resize(
    height: int,
    width: int,
    factor: int = IMAGE_FACTOR,
    min_pixels: int = MIN_PIXELS,
    max_pixels: int = MAX_PIXELS,
) -> tuple[int, int]:
    """
    Rescale image dimensions so that:
    1. Both dimensions are divisible by 'factor'.
    2. Total pixels are within [min_pixels, max_pixels].
    3. Aspect ratio is maintained as closely as possible.

    Returns:
        (new_height, new_width)
    """
    if max(height, width) / min(height, width) > MAX_RATIO:
        raise ValueError(
            f"absolute aspect ratio must be smaller than {MAX_RATIO}, "
            f"got {max(height, width) / min(height, width)}"
        )
    h_bar = max(factor, _round_by_factor(height, factor))
    w_bar = max(factor, _round_by_factor(width, factor))
    if h_bar * w_bar > max_pixels:
        beta = math.sqrt((height * width) / max_pixels)
        h_bar = _floor_by_factor(height / beta, factor)
        w_bar = _floor_by_factor(width / beta, factor)
    elif h_bar * w_bar < min_pixels:
        beta = math.sqrt(min_pixels / (height * width))
        h_bar = _ceil_by_factor(height * beta, factor)
        w_bar = _ceil_by_factor(width * beta, factor)
    return h_bar, w_bar


@dataclass 
class UITarsAction:
    """UI-TARS 解析后的动作结构"""
    action_type: str
    params: dict[str, Any]
    thinking: str = ""


class UITarsActionHandler:
    """
    处理 UI-TARS 模型输出的 Action
    
    UI-TARS 使用不同的 action 格式：
    - click(point='<point>x y</point>')
    - long_press(point='<point>x y</point>')
    - type(content='text')
    - scroll(point='<point>x y</point>', direction='down')
    - open_app(app_name='微信')
    - drag(start_point='<point>x1 y1</point>', end_point='<point>x2 y2</point>')
    - press_home()
    - press_back()
    - finished(content='xxx')
    
    坐标基于 smart_resize 后的图像尺寸空间（通过 smart_resize 转换回原始像素坐标）
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
    
    def parse_response(self, response: str) -> UITarsAction:
        """
        解析 UI-TARS 模型的响应
        
        响应格式:
        Thought: ...
        Action: action_name(params)
        
        Args:
            response: 模型原始响应
            
        Returns:
            解析后的 UITarsAction 对象
        """
        thinking = ""
        action_str = ""
        
        # 提取 Thought 和 Action
        lines = response.strip().split('\n')
        for line in lines:
            line = line.strip()
            if line.startswith('Thought:'):
                thinking = line[8:].strip()
            elif line.startswith('Action:'):
                action_str = line[7:].strip()
        
        # 如果没有找到 Action: 前缀，尝试直接解析整个响应
        if not action_str:
            # 查找 action 函数调用模式
            action_patterns = [
                r'(click|long_press|type|scroll|open_app|drag|press_home|press_back|finished|wait)\s*\(',
            ]
            for pattern in action_patterns:
                match = re.search(pattern, response)
                if match:
                    # 找到匹配的action，提取完整的调用
                    start = match.start()
                    action_str = response[start:]
                    # 截取到匹配的右括号
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
            return UITarsAction(
                action_type="unknown",
                params={"raw": response},
                thinking=thinking
            )
        
        # 解析 action 类型和参数
        action_type, params = self._parse_action_call(action_str)
        
        return UITarsAction(
            action_type=action_type,
            params=params,
            thinking=thinking
        )
    
    def _parse_action_call(self, action_str: str) -> tuple[str, dict[str, Any]]:
        """
        解析 action 函数调用
        
        Args:
            action_str: 如 "click(point='<point>500 300</point>')"
            
        Returns:
            (action_type, params) 元组
        """
        # 提取函数名
        match = re.match(r'(\w+)\s*\((.*)\)$', action_str, re.DOTALL)
        if not match:
            return "unknown", {"raw": action_str}
        
        action_type = match.group(1)
        params_str = match.group(2).strip()
        
        params = {}
        
        if not params_str:
            return action_type, params
        
        # 解析参数
        # 处理 key='value' 或 key="value" 格式
        param_pattern = r"(\w+)\s*=\s*['\"](.+?)['\"]"
        for m in re.finditer(param_pattern, params_str):
            key = m.group(1)
            value = m.group(2)
            params[key] = value
        
        return action_type, params
    
    def _parse_point(self, point_str: str) -> tuple[int, int]:
        """
        解析点坐标
        
        Args:
            point_str: 支持多种格式:
                - "<point>500 300</point>"
                - "500 300"
                - "(500, 300)" 或 "(500,300)" - UI-TARS-1.5 格式
                - "[500, 300]" - 边界框格式（取中心点）
                
        Returns:
            (x, y) 坐标（smart_resize 后图像空间中的坐标）
        """
        # 尝试从 <point> 标签中提取
        match = re.search(r'<point>\s*(\d+)\s+(\d+)\s*</point>', point_str)
        if match:
            return int(match.group(1)), int(match.group(2))
        
        # 尝试解析 (x, y) 或 (x,y) 格式 - UI-TARS-1.5-7B 常用格式
        match = re.search(r'\(\s*(\d+)\s*,\s*(\d+)\s*\)', point_str)
        if match:
            return int(match.group(1)), int(match.group(2))
        
        # 尝试解析 [x1, y1, x2, y2] 边界框格式，取中心点
        match = re.search(r'\[\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*\]', point_str)
        if match:
            x1, y1, x2, y2 = int(match.group(1)), int(match.group(2)), int(match.group(3)), int(match.group(4))
            return (x1 + x2) // 2, (y1 + y2) // 2
        
        # 尝试解析 [x, y] 格式
        match = re.search(r'\[\s*(\d+)\s*,\s*(\d+)\s*\]', point_str)
        if match:
            return int(match.group(1)), int(match.group(2))
        
        # 尝试直接解析空格分隔的坐标
        parts = point_str.strip().split()
        if len(parts) >= 2:
            try:
                return int(parts[0]), int(parts[1])
            except ValueError:
                pass
        
        return 500, 500  # 默认中心点
    
    
    def _convert_relative_to_absolute(
        self, x: int, y: int, screen_width: int, screen_height: int
    ) -> tuple[int, int]:
        """
        Convert model output coordinates to absolute pixel coordinates.

        UI-TARS 模型输出的坐标是基于 smart_resize 后的图像尺寸的绝对坐标。
        转换公式: actual_pixel = model_coord / smart_resize_dim * original_dim

        例如：原始图像 1080x2340, smart_resize 后变为 1008x2184,
        模型输出 (197, 525), 实际坐标 = (197/1008*1080, 525/2184*2340)
        """
        screen_width = max(1, screen_width)
        screen_height = max(1, screen_height)

        def clamp(val: float, low: float, high: float) -> float:
            return max(low, min(high, val))

        # 计算 smart_resize 后的图像尺寸
        # smart_resize 输入 (height, width), 返回 (new_height, new_width)
        resized_h, resized_w = smart_resize(screen_height, screen_width)

        # 模型输出坐标是 smart_resize 后图像空间中的绝对坐标
        # 转换为原始屏幕像素坐标
        abs_x = int(x / resized_w * screen_width)
        abs_y = int(y / resized_h * screen_height)

        # clamp 确保在屏幕范围内
        abs_x = int(clamp(abs_x, 0, screen_width - 1))
        abs_y = int(clamp(abs_y, 0, screen_height - 1))

        return abs_x, abs_y

    def execute(
        self, 
        action: UITarsAction, 
        screen_width: int, 
        screen_height: int
    ) -> ActionResult:
        """
        执行解析后的 action
        
        Args:
            action: 解析后的 UITarsAction
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
                message=f"Unknown UI-TARS action: {action_type}"
            )
        
        try:
            return handler_method(params, screen_width, screen_height)
        except Exception as e:
            return ActionResult(
                success=False,
                should_finish=False,
                message=f"Action failed: {e}"
            )
    
    def _get_handler(self, action_type: str) -> Callable | None:
        """获取 action 处理器"""
        handlers = {
            "click": self._handle_click,
            "long_press": self._handle_long_press,
            "type": self._handle_type,
            "scroll": self._handle_scroll,
            "open_app": self._handle_open_app,
            "drag": self._handle_drag,
            "press_home": self._handle_press_home,
            "press_back": self._handle_press_back,
            "finished": self._handle_finished,
            "wait": self._handle_wait,
        }
        return handlers.get(action_type)
    
    def _handle_click(self, params: dict, width: int, height: int) -> ActionResult:
        """处理点击操作"""
        # 支持多种参数名: point, start_box, bbox 等
        point_str = params.get("point") or params.get("start_box") or params.get("bbox") or "<point>500 500</point>"
        x, y = self._parse_point(point_str)
        abs_x, abs_y = self._convert_relative_to_absolute(x, y, width, height)
        
        device_factory = get_device_factory()
        device_factory.tap(abs_x, abs_y, self.device_id)
        
        # 返回坐标转换信息用于调试
        resized_h, resized_w = smart_resize(height, width)
        return ActionResult(True, False, f"点击 ({x},{y}) [smart_resize: {resized_w}x{resized_h}] → 屏幕坐标 ({abs_x},{abs_y})")
    
    def _handle_long_press(self, params: dict, width: int, height: int) -> ActionResult:
        """处理长按操作"""
        # 支持多种参数名: point, start_box, bbox 等
        point_str = params.get("point") or params.get("start_box") or params.get("bbox") or "<point>500 500</point>"
        x, y = self._parse_point(point_str)
        abs_x, abs_y = self._convert_relative_to_absolute(x, y, width, height)
        
        device_factory = get_device_factory()
        device_factory.long_press(abs_x, abs_y, device_id=self.device_id)
        return ActionResult(True, False)
    
    def _handle_type(self, params: dict, width: int, height: int) -> ActionResult:
        """处理文本输入"""
        content = params.get("content", "")
        
        # 处理转义字符
        content = content.replace("\\n", "\n")
        content = content.replace("\\'", "'")
        content = content.replace('\\"', '"')
        
        device_factory = get_device_factory()
        
        # 切换到 ADB 键盘
        original_ime = device_factory.detect_and_set_adb_keyboard(self.device_id)
        time.sleep(TIMING_CONFIG.action.keyboard_switch_delay)
        
        # 清除现有文本并输入新文本
        device_factory.clear_text(self.device_id)
        time.sleep(TIMING_CONFIG.action.text_clear_delay)
        
        device_factory.type_text(content, self.device_id)
        time.sleep(TIMING_CONFIG.action.text_input_delay)
        
        # 恢复原始键盘
        device_factory.restore_keyboard(original_ime, self.device_id)
        time.sleep(TIMING_CONFIG.action.keyboard_restore_delay)
        
        return ActionResult(True, False)
    
    def _handle_scroll(self, params: dict, width: int, height: int) -> ActionResult:
        """处理滚动操作"""
        # 支持多种参数名: point, start_box, bbox 等
        point_str = params.get("point") or params.get("start_box") or params.get("bbox") or "<point>500 500</point>"
        direction = params.get("direction", "down")
        
        x, y = self._parse_point(point_str)
        abs_x, abs_y = self._convert_relative_to_absolute(x, y, width, height)
        
        # 根据方向计算滚动终点
        scroll_distance = int(height * 0.3)  # 滚动屏幕高度的 30%
        
        if direction == "down":
            end_x, end_y = abs_x, abs_y - scroll_distance
        elif direction == "up":
            end_x, end_y = abs_x, abs_y + scroll_distance
        elif direction == "left":
            end_x, end_y = abs_x + int(width * 0.3), abs_y
        elif direction == "right":
            end_x, end_y = abs_x - int(width * 0.3), abs_y
        else:
            end_x, end_y = abs_x, abs_y - scroll_distance
        
        device_factory = get_device_factory()
        device_factory.swipe(abs_x, abs_y, end_x, end_y, device_id=self.device_id)
        return ActionResult(True, False)
    
    def _handle_open_app(self, params: dict, width: int, height: int) -> ActionResult:
        """处理打开应用"""
        app_name = params.get("app_name", "")
        
        if not app_name:
            return ActionResult(False, False, "No app name specified")
        
        device_factory = get_device_factory()
        success = device_factory.launch_app(app_name, self.device_id)
        
        if success:
            return ActionResult(True, False)
        return ActionResult(False, False, f"App not found: {app_name}")
    
    def _handle_drag(self, params: dict, width: int, height: int) -> ActionResult:
        """处理拖拽操作"""
        # 支持多种参数名: start_point/start_box 和 end_point/end_box
        start_point_str = params.get("start_point") or params.get("start_box") or "<point>500 500</point>"
        end_point_str = params.get("end_point") or params.get("end_box") or "<point>500 500</point>"
        
        start_x, start_y = self._parse_point(start_point_str)
        end_x, end_y = self._parse_point(end_point_str)
        
        abs_start_x, abs_start_y = self._convert_relative_to_absolute(start_x, start_y, width, height)
        abs_end_x, abs_end_y = self._convert_relative_to_absolute(end_x, end_y, width, height)
        
        device_factory = get_device_factory()
        device_factory.swipe(abs_start_x, abs_start_y, abs_end_x, abs_end_y, device_id=self.device_id)
        return ActionResult(True, False)
    
    def _handle_press_home(self, params: dict, width: int, height: int) -> ActionResult:
        """处理按 Home 键"""
        device_factory = get_device_factory()
        device_factory.home(self.device_id)
        return ActionResult(True, False)
    
    def _handle_press_back(self, params: dict, width: int, height: int) -> ActionResult:
        """处理按返回键"""
        device_factory = get_device_factory()
        device_factory.back(self.device_id)
        return ActionResult(True, False)
    
    def _handle_finished(self, params: dict, width: int, height: int) -> ActionResult:
        """处理任务完成"""
        content = params.get("content", "Task completed")
        return ActionResult(
            success=True,
            should_finish=True,
            message=content
        )
    
    def _handle_wait(self, params: dict, width: int, height: int) -> ActionResult:
        """处理等待操作"""
        time.sleep(5)  # UI-TARS 的 wait 固定等待 5 秒
        return ActionResult(True, False)
    
    @staticmethod
    def _default_confirmation(message: str) -> bool:
        """默认确认回调"""
        response = input(f"Sensitive operation: {message}\nConfirm? (Y/N): ")
        return response.upper() == "Y"
    
    @staticmethod
    def _default_takeover(message: str) -> None:
        """默认接管回调"""
        input(f"{message}\nPress Enter after completing manual operation...")


def convert_uitars_to_autoglm(uitars_action: UITarsAction) -> dict[str, Any]:
    """
    将 UI-TARS action 转换为 AutoGLM 格式
    
    用于兼容现有的 ActionHandler
    
    Args:
        uitars_action: UI-TARS 格式的 action
        
    Returns:
        AutoGLM 格式的 action 字典
    """
    action_type = uitars_action.action_type
    params = uitars_action.params
    
    # 映射表
    type_mapping = {
        "click": "Tap",
        "long_press": "Long Press",
        "type": "Type",
        "scroll": "Swipe",
        "open_app": "Launch",
        "drag": "Swipe",
        "press_home": "Home",
        "press_back": "Back",
        "finished": "finish",
        "wait": "Wait",
    }
    
    autoglm_action = {
        "_metadata": "finish" if action_type == "finished" else "do",
    }
    
    if action_type == "finished":
        autoglm_action["message"] = params.get("content", "Task completed")
    else:
        autoglm_action["action"] = type_mapping.get(action_type, action_type)
        
        # 转换参数
        if action_type == "click" or action_type == "long_press":
            point_str = params.get("point", "<point>500 500</point>")
            # 提取坐标
            match = re.search(r'<point>\s*(\d+)\s+(\d+)\s*</point>', point_str)
            if match:
                autoglm_action["element"] = [int(match.group(1)), int(match.group(2))]
        
        elif action_type == "type":
            autoglm_action["text"] = params.get("content", "")
        
        elif action_type == "open_app":
            autoglm_action["app"] = params.get("app_name", "")
        
        elif action_type == "scroll" or action_type == "drag":
            # scroll 和 drag 都映射为 Swipe
            if action_type == "scroll":
                point_str = params.get("point", "<point>500 500</point>")
                match = re.search(r'<point>\s*(\d+)\s+(\d+)\s*</point>', point_str)
                if match:
                    x, y = int(match.group(1)), int(match.group(2))
                    direction = params.get("direction", "down")
                    
                    # 计算滚动距离
                    distance = 300
                    if direction == "down":
                        autoglm_action["start"] = [x, y]
                        autoglm_action["end"] = [x, y - distance]
                    elif direction == "up":
                        autoglm_action["start"] = [x, y]
                        autoglm_action["end"] = [x, y + distance]
                    elif direction == "left":
                        autoglm_action["start"] = [x, y]
                        autoglm_action["end"] = [x + distance, y]
                    elif direction == "right":
                        autoglm_action["start"] = [x, y]
                        autoglm_action["end"] = [x - distance, y]
            else:
                # drag
                start_str = params.get("start_point", "<point>500 500</point>")
                end_str = params.get("end_point", "<point>500 500</point>")
                
                start_match = re.search(r'<point>\s*(\d+)\s+(\d+)\s*</point>', start_str)
                end_match = re.search(r'<point>\s*(\d+)\s+(\d+)\s*</point>', end_str)
                
                if start_match and end_match:
                    autoglm_action["start"] = [int(start_match.group(1)), int(start_match.group(2))]
                    autoglm_action["end"] = [int(end_match.group(1)), int(end_match.group(2))]
        
        elif action_type == "wait":
            autoglm_action["duration"] = "5 seconds"
    
    return autoglm_action








