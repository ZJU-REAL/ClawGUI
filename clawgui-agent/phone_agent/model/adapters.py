"""
模型适配器模块

支持不同模型的 prompt 模板和响应解析
"""

from enum import Enum
from dataclasses import dataclass
from typing import Any


class ModelType(Enum):
    """支持的模型类型"""
    AUTOGLM = "autoglm"      # Open-AutoGLM 模型
    UITARS = "uitars"        # Doubao-1.5-UI-TARS 模型
    QWENVL = "qwenvl"        # Qwen2.5-VL / Qwen3-VL 模型
    MAIUI = "maiui"          # MAI-UI 模型 (阿里云通义)
    GUIOWL = "guiowl"        # GUI-Owl 模型 (mPLUG/GUI-Owl-7B/32B/1.5)


@dataclass
class ModelAdapter:
    """模型适配器基类"""
    model_type: ModelType
    model_name_pattern: str  # 用于自动检测模型类型的名称模式
    
    def get_system_prompt(self, task: str, lang: str = "cn") -> str:
        """获取系统提示"""
        raise NotImplementedError
    
    def parse_response(self, response: str) -> tuple[str, str]:
        """
        解析模型响应
        
        Returns:
            (thinking, action_str) 元组
        """
        raise NotImplementedError
    
    def build_messages(
        self, 
        task: str, 
        image_base64: str,
        current_app: str,
        context: list[dict],
        lang: str = "cn",
        screen_width: int = 0,
        screen_height: int = 0,
    ) -> list[dict]:
        """
        构建消息列表
        
        Args:
            task: 用户任务
            image_base64: 截图的 base64 编码
            current_app: 当前应用名称
            context: 上下文消息列表
            lang: 语言
            screen_width: 屏幕宽度（像素）
            screen_height: 屏幕高度（像素）
        """
        raise NotImplementedError


class AutoGLMAdapter(ModelAdapter):
    """AutoGLM 模型适配器"""
    
    def __init__(self):
        super().__init__(
            model_type=ModelType.AUTOGLM,
            model_name_pattern="autoglm"
        )
    
    def get_system_prompt(self, task: str, lang: str = "cn") -> str:
        from phone_agent.config import get_system_prompt
        return get_system_prompt(lang)
    
    def parse_response(self, response: str) -> tuple[str, str]:
        """解析 AutoGLM 响应"""
        if "finish(message=" in response:
            parts = response.split("finish(message=", 1)
            thinking = parts[0].strip()
            action = "finish(message=" + parts[1]
            return thinking, action
        
        if "do(action=" in response:
            parts = response.split("do(action=", 1)
            thinking = parts[0].strip()
            action = "do(action=" + parts[1]
            return thinking, action
        
        if "<answer>" in response:
            parts = response.split("<answer>", 1)
            thinking = parts[0].replace("<think>", "").replace("</think>", "").strip()
            action = parts[1].replace("</answer>", "").strip()
            return thinking, action
        
        return "", response
    
    def build_messages(
        self,
        task: str,
        image_base64: str,
        current_app: str,
        context: list[dict],
        lang: str = "cn",
        screen_width: int = 0,
        screen_height: int = 0,
    ) -> list[dict]:
        from phone_agent.model.client import MessageBuilder
        
        messages = context.copy()
        
        if not messages:
            # 第一轮：添加系统消息
            messages.append(
                MessageBuilder.create_system_message(self.get_system_prompt(task, lang))
            )
            screen_info = MessageBuilder.build_screen_info(current_app)
            text_content = f"{task}\n\n{screen_info}"
            messages.append(
                MessageBuilder.create_user_message(
                    text=text_content, image_base64=image_base64
                )
            )
        else:
            # 后续轮次
            screen_info = MessageBuilder.build_screen_info(current_app)
            text_content = f"** Screen Info **\n\n{screen_info}"
            messages.append(
                MessageBuilder.create_user_message(
                    text=text_content, image_base64=image_base64
                )
            )
        
        return messages


class UITarsAdapter(ModelAdapter):
    """Doubao-1.5-UI-TARS 模型适配器"""
    
    def __init__(self):
        super().__init__(
            model_type=ModelType.UITARS,
            model_name_pattern="ui-tars|uitars|tars"
        )
    
    def get_system_prompt(self, task: str, lang: str = "cn") -> str:
        from phone_agent.config.prompts_uitars import get_uitars_system_prompt
        language = "Chinese" if lang == "cn" else "English"
        return get_uitars_system_prompt(task, language, scene="phone")
    
    def parse_response(self, response: str) -> tuple[str, str]:
        """解析 UI-TARS 响应"""
        thinking = ""
        action = ""
        
        lines = response.strip().split('\n')
        for line in lines:
            line = line.strip()
            if line.startswith('Thought:'):
                thinking = line[8:].strip()
            elif line.startswith('Action:'):
                action = line[7:].strip()
        
        # 如果没找到 Action: 前缀，查找 action 函数
        if not action:
            import re
            action_patterns = [
                r'(click|long_press|type|scroll|open_app|drag|press_home|press_back|finished|wait)\s*\(',
            ]
            for pattern in action_patterns:
                match = re.search(pattern, response)
                if match:
                    start = match.start()
                    action = response[start:]
                    paren_count = 0
                    for i, c in enumerate(action):
                        if c == '(':
                            paren_count += 1
                        elif c == ')':
                            paren_count -= 1
                            if paren_count == 0:
                                action = action[:i+1]
                                break
                    break
        
        return thinking, action
    
    def build_messages(
        self,
        task: str,
        image_base64: str,
        current_app: str,
        context: list[dict],
        lang: str = "cn",
        screen_width: int = 0,
        screen_height: int = 0,
    ) -> list[dict]:
        """
        构建 UI-TARS 格式的消息
        
        UI-TARS 使用特殊的消息格式：
        - 第一轮: user (system prompt + 任务指令 + 截图)
        - 后续轮: assistant (由 agent.py 添加，模型全部输出) + user (新截图)
        - 保留近 5 张图片（由 limit_context 处理）
        """
        messages = context.copy()
        
        if not messages:
            # 第一轮：系统提示 + 任务指令 + 截图（作为一条用户消息）
            messages.append({
                "role": "user",
                "content": [
                    {
                        "type": "text",
                        "text": f"{self.get_system_prompt(task, lang)}\n\nTask: {task}"
                    },
                    {
                        "type": "image_url",
                        "image_url": {
                            "url": f"data:image/png;base64,{image_base64}"
                        }
                    }
                ]
            })
        else:
            # 后续轮：只添加新的 user message (截图)
            # assistant 消息由 agent.py 添加
            messages.append({
                "role": "user",
                "content": [
                    {
                        "type": "image_url",
                        "image_url": {
                            "url": f"data:image/png;base64,{image_base64}"
                        }
                    }
                ]
            })
        
        return messages
    
    def limit_context(self, messages: list[dict], max_images: int = 5) -> list[dict]:
        """
        限制上下文中的图片数量
        
        UI-TARS 建议最多保留 5 张截图
        
        注意：第一条消息包含 system prompt + 截图，不能整条删除，
        只移除其中的图片部分，保留 system prompt 文本。
        """
        # 找出所有包含图片的消息索引
        image_indices = []
        for i, msg in enumerate(messages):
            content = msg.get("content", "")
            if isinstance(content, list):
                for item in content:
                    if isinstance(item, dict) and item.get("type") == "image_url":
                        image_indices.append(i)
                        break
        
        # 如果图片数量超过限制，删除早期的图片
        if len(image_indices) > max_images:
            indices_to_remove = set(image_indices[:-max_images])
            new_messages = []
            for i, msg in enumerate(messages):
                if i in indices_to_remove:
                    # 检查这条消息是否包含文本内容（如第一条带 system prompt 的消息）
                    content = msg.get("content", "")
                    if isinstance(content, list):
                        text_items = [item for item in content 
                                      if isinstance(item, dict) and item.get("type") == "text"]
                        if text_items:
                            # 保留消息但只移除图片，保留文本（system prompt）
                            new_content = [item for item in content 
                                           if not (isinstance(item, dict) and item.get("type") == "image_url")]
                            new_messages.append({**msg, "content": new_content})
                            continue
                    # 纯图片消息，直接删除
                    continue
                new_messages.append(msg)
            messages = new_messages
        
        return messages


class QwenVLAdapter(ModelAdapter):
    """Qwen2.5-VL / Qwen3-VL 模型适配器
    
    基于 Qwen 官方推荐的 tool_call 格式：
    - system prompt 中定义 mobile_use 工具（<tools> XML 标签）
    - 模型输出 Thought / Action / <tool_call> JSON
    - 坐标使用 0-999 归一化（system prompt 中 resolution = 999x999）
    - 用户消息按照官方格式构建 user_query + 操作历史
    """
    
    def __init__(self):
        super().__init__(
            model_type=ModelType.QWENVL,
            model_name_pattern="qwen.*vl|qwen2.5-vl|qwen3-vl"
        )
        # 操作历史，用于构建 user_query 中的 task progress
        self._action_history: list[str] = []
    
    def get_system_prompt(self, task: str, lang: str = "cn") -> str:
        from phone_agent.config.prompts_qwenvl import get_qwenvl_system_prompt
        language = "Chinese" if lang == "cn" else "English"
        return get_qwenvl_system_prompt(task, language)
    
    def parse_response(self, response: str) -> tuple[str, str]:
        """
        解析 Qwen-VL 响应
        
        支持两种格式：
        1. <tool_call>{"name": "mobile_use", "arguments": {...}}</tool_call> (官方格式)
        2. tap(x, y) / finish(message) 等旧格式 (fallback)
        """
        import re
        
        thinking = ""
        action = ""
        
        # 提取 Thought
        lines = response.strip().split('\n')
        for line in lines:
            line_stripped = line.strip()
            if line_stripped.startswith('Thought:'):
                thinking = line_stripped[8:].strip()
                break
        
        # 优先提取 <tool_call> 格式
        tool_call_match = re.search(
            r'<tool_call>\s*(.*?)\s*</tool_call>', 
            response, 
            re.DOTALL
        )
        if tool_call_match:
            action = tool_call_match.group(0)  # 包含 <tool_call> 标签
            return thinking, action
        
        # Fallback: 提取 Action: 行
        for line in lines:
            line_stripped = line.strip()
            if line_stripped.startswith('Action:'):
                action = line_stripped[7:].strip()
                break
        
        # 如果还没找到，查找旧格式的函数调用
        if not action:
            action_patterns = [
                r'(tap|click|long_press|double_tap|swipe|type|type_name|open_app|back|home|wait|finish|terminate)\s*\(',
            ]
            for pattern in action_patterns:
                match = re.search(pattern, response, re.IGNORECASE)
                if match:
                    start = match.start()
                    action = response[start:]
                    paren_count = 0
                    for i, c in enumerate(action):
                        if c == '(':
                            paren_count += 1
                        elif c == ')':
                            paren_count -= 1
                            if paren_count == 0:
                                action = action[:i+1]
                                break
                    break
        
        return thinking, action
    
    def add_history(self, description: str):
        """添加操作历史记录"""
        self._action_history.append(description)
    
    def clear_history(self):
        """清空操作历史"""
        self._action_history.clear()
    
    def build_messages(
        self,
        task: str,
        image_base64: str,
        current_app: str,
        context: list[dict],
        lang: str = "cn",
        screen_width: int = 0,
        screen_height: int = 0,
    ) -> list[dict]:
        """
        构建 Qwen-VL 格式的消息
        
        QwenVL 的特点:
        - 始终只保留 1 个 system message + 1 个 user message
        - user message 包含: user_query (任务 + 操作历史) + 当前截图
        - 不在 context 中添加 assistant 消息
        - 操作历史通过 self._action_history 维护，每轮重新构建 user_query
        
        消息结构:
        [
          {"role": "system", "content": [{"type": "text", "text": system_prompt}]},
          {"role": "user", "content": [
              {"type": "text", "text": user_query},
              {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64,..."}},
          ]}
        ]
        """
        from phone_agent.config.prompts_qwenvl import build_qwenvl_user_query
        
        # QwenVL 始终重新构建完整的 messages（不累积）
        messages = []
        
        # 添加系统消息（始终存在）
        messages.append({
            "role": "system",
            "content": [
                {"type": "text", "text": self.get_system_prompt(task, lang)}
            ],
        })
        
        # 构建 user_query（任务 + 操作历史）
        user_query = build_qwenvl_user_query(
            instruction=task,
            history=self._action_history if self._action_history else None,
        )
        
        # 添加用户消息（包含 user_query 和当前截图）
        messages.append({
            "role": "user",
            "content": [
                {"type": "text", "text": user_query},
                {
                    "type": "image_url",
                    "image_url": {
                        "url": f"data:image/jpeg;base64,{image_base64}"
                    }
                },
            ],
        })
        
        return messages
    
    def limit_context(self, messages: list[dict], max_images: int = 8) -> list[dict]:
        """
        限制上下文中的图片数量
        
        Qwen-VL 支持较长上下文，建议保留最近 8 张截图
        """
        # 找出所有包含图片的消息索引（跳过系统消息）
        image_indices = []
        for i, msg in enumerate(messages):
            if msg.get("role") == "system":
                continue
            content = msg.get("content", "")
            if isinstance(content, list):
                for item in content:
                    if isinstance(item, dict) and item.get("type") == "image_url":
                        image_indices.append(i)
                        break
        
        # 如果图片数量超过限制，删除早期的图片消息
        if len(image_indices) > max_images:
            indices_to_remove = image_indices[:-max_images]
            messages = [msg for i, msg in enumerate(messages) if i not in indices_to_remove]
        
        return messages


class MAIUIAdapter(ModelAdapter):
    """MAI-UI 模型适配器 (阿里云通义)
    
    基于: https://github.com/Tongyi-MAI/MAI-UI
    
    特点:
    - 使用 0-999 归一化坐标系统
    - 输出格式: <thinking>...</thinking><tool_call>...</tool_call>
    - 支持 click, long_press, type, swipe, open, drag, system_button, wait, terminate, answer 动作
    """
    
    def __init__(self):
        super().__init__(
            model_type=ModelType.MAIUI,
            model_name_pattern="mai[-_]?ui|mai[-_]?mobile"
        )
    
    def get_system_prompt(self, task: str, lang: str = "cn") -> str:
        from phone_agent.config.prompts_maiui import get_maiui_system_prompt
        language = "Chinese" if lang == "cn" else "English"
        return get_maiui_system_prompt(task, language)
    
    def parse_response(self, response: str) -> tuple[str, str]:
        """
        解析 MAI-UI 响应
        
        格式: <thinking>...</thinking><tool_call>{"name": "mobile_use", "arguments": {...}}</tool_call>
        """
        import re
        import json
        
        thinking = ""
        action_str = ""
        
        # 处理 thinking model 输出格式
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
        
        # 提取 tool_call（支持缺失 </tool_call> 结束标签的情况）
        tool_call_match = re.search(r'<tool_call>\s*(.*?)(?:</tool_call>|$)', response, re.DOTALL)
        if tool_call_match:
            action_str = tool_call_match.group(1).strip()
            # 清理可能残留的 \n 转义序列和不完整的标签
            action_str = action_str.replace('\\n', '').strip()
            action_str = re.sub(r'</?tool_call[^>]*$', '', action_str).strip()
        else:
            # 尝试直接查找 JSON
            json_match = re.search(r'\{[^{}]*"action"\s*:\s*"[^"]+"\s*[^{}]*\}', response)
            if json_match:
                action_str = json_match.group()
        
        return thinking, action_str
    
    def build_messages(
        self,
        task: str,
        image_base64: str,
        current_app: str,
        context: list[dict],
        lang: str = "cn",
        screen_width: int = 0,
        screen_height: int = 0,
    ) -> list[dict]:
        """
        构建 MAI-UI 格式的消息（严格对齐 MAI-UI 官方格式）
        
        消息结构：
        第一轮:
          [system(纯字符串), user(text:任务指令), user(image:截图)]
        后续轮:
          [...历史..., assistant(纯字符串), user(image:截图)]
        
        关键格式要求：
        - system.content 是纯字符串，不是列表
        - 第一轮的任务指令和截图分成两条 user message
        - 后续轮的 user message 只包含截图（image_url）
        - assistant.content 是纯字符串（由 agent.py 添加）
        - 保留近 3 张图片（由 limit_context 处理）
        """
        messages = context.copy()
        
        if not messages:
            # 第一轮：
            # 1. system message（纯字符串）
            messages.append({
                "role": "system",
                "content": self.get_system_prompt("", lang)
            })
            # 2. user message: 任务指令（纯文本）
            messages.append({
                "role": "user",
                "content": [
                    {"type": "text", "text": task}
                ]
            })
            # 3. user message: 截图
            messages.append({
                "role": "user",
                "content": [
                    {
                        "type": "image_url",
                        "image_url": {
                            "url": f"data:image/png;base64,{image_base64}"
                        }
                    }
                ]
            })
        else:
            # 后续轮：只添加新的 user message (仅截图)
            # assistant 消息由 agent.py 添加
            messages.append({
                "role": "user",
                "content": [
                    {
                        "type": "image_url",
                        "image_url": {
                            "url": f"data:image/png;base64,{image_base64}"
                        }
                    }
                ]
            })
        
        return messages
    
    def limit_context(self, messages: list[dict], max_images: int = 3) -> list[dict]:
        """
        限制上下文中的图片数量
        
        MAI-UI 建议保留最近 3 张截图 (history_n)
        """
        image_indices = []
        for i, msg in enumerate(messages):
            if msg.get("role") == "system":
                continue
            content = msg.get("content", "")
            if isinstance(content, list):
                for item in content:
                    if isinstance(item, dict) and item.get("type") == "image_url":
                        image_indices.append(i)
                        break
        
        if len(image_indices) > max_images:
            indices_to_remove = image_indices[:-max_images]
            messages = [msg for i, msg in enumerate(messages) if i not in indices_to_remove]
        
        return messages


class GUIOwlAdapter(ModelAdapter):
    """GUI-Owl 模型适配器 (mPLUG/GUI-Owl-7B/32B/1.5 系列)
    
    基于: https://github.com/X-PLUG/MobileAgent
    
    官方消息格式特点：
    - system prompt: 纯字符串，包含 <tools> XML 定义 mobile_use 工具
    - 坐标使用 0-999 归一化（分辨率 1000x1000）
    - 输出格式: Action: <description>\n<tool_call>{"name": "mobile_use", "arguments": {...}}</tool_call>
    - 消息结构: 始终只有 system + 1 条 user message
    - 操作历史通过 "Previous actions: Step1: ... Step2: ..." 文本注入 user message
    - user message: text(指令+历史) + image(当前截图)
    """
    
    def __init__(self):
        super().__init__(
            model_type=ModelType.GUIOWL,
            model_name_pattern="gui[-_]?owl|guiowl"
        )
        # 操作历史（存储每步的 conclusion 描述文本）
        self._action_history: list[str] = []
    
    def get_system_prompt(self, task: str, lang: str = "cn") -> str:
        from phone_agent.config.prompts_guiowl import get_guiowl_system_prompt
        language = "Chinese" if lang == "cn" else "English"
        return get_guiowl_system_prompt(task, language)
    
    def parse_response(self, response: str) -> tuple[str, str]:
        """
        解析 GUI-Owl 响应
        
        支持两种格式:
        1. 官方 tool_call 格式:
           Action: <description>
           <tool_call>{"name": "mobile_use", "arguments": {...}}</tool_call>
        2. 旧格式 (fallback):
           ### Thought ### ... ### Action ### {JSON} ### Description ### ...
        """
        import re
        
        thinking = ""
        action_str = ""
        
        # 优先提取 <tool_call> 格式
        tool_call_match = re.search(
            r'<tool_call>\s*(.*?)\s*</tool_call>',
            response,
            re.DOTALL
        )
        if tool_call_match:
            action_str = tool_call_match.group(0)  # 包含 <tool_call> 标签
            # 提取 Action: 行作为 thinking
            lines = response.strip().split('\n')
            for line in lines:
                line_stripped = line.strip()
                if line_stripped.startswith('Action:'):
                    thinking = line_stripped[7:].strip()
                    break
            return thinking, action_str
        
        # Fallback: 旧的 ### Thought ### / ### Action ### 格式
        thought_match = re.search(
            r'###\s*Thought\s*###\s*(.*?)(?=###\s*Action\s*###|$)',
            response, re.DOTALL
        )
        if thought_match:
            thinking = thought_match.group(1).strip()
        
        action_match = re.search(
            r'###\s*Action\s*###\s*(.*?)(?=###\s*Description\s*###|$)',
            response, re.DOTALL
        )
        if action_match:
            action_str = action_match.group(1).strip()
            action_str = action_str.replace("```", "").replace("json", "").strip()
        
        # 如果没找到结构化格式，尝试查找 JSON
        if not action_str:
            import json
            json_match = re.search(
                r'\{[^{}]*"action"\s*:\s*"[^"]+"\s*[^{}]*\}',
                response
            )
            if json_match:
                action_str = json_match.group()
        
        return thinking, action_str
    
    def add_history(self, description: str):
        """添加操作历史记录"""
        self._action_history.append(description)
    
    def clear_history(self):
        """清空操作历史"""
        self._action_history.clear()
    
    def build_messages(
        self,
        task: str,
        image_base64: str,
        current_app: str,
        context: list[dict],
        lang: str = "cn",
        screen_width: int = 0,
        screen_height: int = 0,
    ) -> list[dict]:
        """
        构建 GUI-Owl 官方格式的消息
        
        官方格式特点:
        - 始终只保留 1 个 system message + 1 个 user message
        - system message: 纯字符串（<tools> XML 工具定义 + 回复格式规则）
        - user message: text(指令 + 操作历史) + image(当前截图)
        - 不在 context 中添加 assistant 消息
        - 操作历史通过 self._action_history 维护，每轮重新构建 user_query
        
        消息结构:
        [
          {"role": "system", "content": "<tools>...</tools>...（纯字符串）"},
          {"role": "user", "content": [
              {"type": "text", "text": "Please generate the next move...\\nInstruction: ...\\nPrevious actions: ..."},
              {"type": "image_url", "image_url": {"url": "data:image/png;base64,..."}},
          ]}
        ]
        """
        from phone_agent.config.prompts_guiowl import build_guiowl_user_query
        
        language = "Chinese" if lang == "cn" else "English"
        
        # GUI-Owl 始终重新构建完整的 messages（不累积）
        messages = []
        
        # 1. System message（纯字符串）
        messages.append({
            "role": "system",
            "content": self.get_system_prompt(task, lang)
        })
        
        # 2. User message（text: 指令 + 操作历史 + image: 当前截图）
        user_query = build_guiowl_user_query(
            instruction=task,
            history=self._action_history if self._action_history else None,
            language=language,
        )
        
        messages.append({
            "role": "user",
            "content": [
                {
                    "type": "text",
                    "text": user_query
                },
                {
                    "type": "image_url",
                    "image_url": {
                        "url": f"data:image/png;base64,{image_base64}"
                    }
                },
            ]
        })
        
        return messages
    
    def limit_context(self, messages: list[dict], max_images: int = 1) -> list[dict]:
        """
        限制上下文中的图片数量
        
        GUI-Owl 官方格式每次只发送 1 张截图（当前），无需额外裁剪
        """
        return messages


# 模型适配器注册表
_adapters: dict[ModelType, ModelAdapter] = {
    ModelType.AUTOGLM: AutoGLMAdapter(),
    ModelType.UITARS: UITarsAdapter(),
    ModelType.QWENVL: QwenVLAdapter(),
    ModelType.MAIUI: MAIUIAdapter(),
    ModelType.GUIOWL: GUIOwlAdapter(),
}


def get_adapter(model_type: ModelType) -> ModelAdapter:
    """获取模型适配器"""
    return _adapters[model_type]


def detect_model_type(model_name: str) -> ModelType:
    """
    根据模型名称自动检测模型类型
    
    Args:
        model_name: 模型名称
        
    Returns:
        检测到的模型类型
    """
    import re
    
    model_name_lower = model_name.lower()
    
    # GUI-Owl 检测 (GUI-Owl-7B, GUI-Owl-32B, GUI-Owl-1.5 等)
    if re.search(r'gui[-_]?owl|guiowl', model_name_lower):
        return ModelType.GUIOWL
    
    # UI-TARS 检测 (UI-TARS, seed 等)
    if re.search(r'ui[-_]?tars|tars|doubao.*ui|seed', model_name_lower):
        return ModelType.UITARS
    
    # Qwen-VL 检测 (Qwen2.5-VL, Qwen3-VL, Qwen3.5, Qwen-VL 等)
    if re.search(r'qwen.*vl|qwen2\.?5.*vl|qwen3.*vl|qwen3\.?5', model_name_lower):
        return ModelType.QWENVL
    
    # MAI-UI 检测 (MAI-UI, MAI-Mobile 等)
    if re.search(r'mai[-_]?ui|mai[-_]?mobile|mai[-_]?navigation', model_name_lower):
        return ModelType.MAIUI
    
    # GLM-4V 系列 (GLM-4.6V-flash, GLM-4.1V-9B-thinking 等) -> 使用 AutoGLM 逻辑
    # autoglm 也走 AutoGLM
    if re.search(r'autoglm|glm[-_]?4\.?\d*v|glm4v|glm-4v', model_name_lower):
        return ModelType.AUTOGLM
    
    # 默认使用 AutoGLM
    return ModelType.AUTOGLM


def get_adapter_for_model(model_name: str) -> ModelAdapter:
    """
    根据模型名称获取适配器
    
    Args:
        model_name: 模型名称
        
    Returns:
        对应的模型适配器
    """
    model_type = detect_model_type(model_name)
    return get_adapter(model_type)





