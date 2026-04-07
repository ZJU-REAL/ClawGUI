"""
GUI-Owl 模型的系统提示模板

基于 mPLUG/GUI-Owl 项目: https://github.com/X-PLUG/MobileAgent
GUI-Owl 是阿里巴巴通义实验室开发的端到端 GUI 自动化基础模型，
基于 Qwen2.5-VL 构建，支持跨平台 GUI 操作。

支持 GUI-Owl-7B / GUI-Owl-32B / GUI-Owl-1.5 系列

官方消息格式：
- system prompt 使用 <tools> XML 标签定义 mobile_use 工具
- 模型输出 Action: <description> + <tool_call> JSON
- 坐标使用 0-999 归一化（resolution = 1000x1000）
- 操作历史以 "Previous actions: Step1: ... Step2: ..." 纯文本方式注入 user message
"""

import json

SCALE_FACTOR = 999

# ==================== 工具 Schema ====================
# 官方 mobile_use 工具定义
GUIOWL_TOOL_SCHEMA = {
    "type": "function",
    "function": {
        "name_for_human": "mobile_use",
        "name": "mobile_use",
        "description": (
            "Use a touchscreen to interact with a mobile device, and take screenshots.\n"
            "* This is an interface to a mobile device with touchscreen. "
            "You can perform actions like clicking, typing, swiping, etc.\n"
            "* Some applications may take time to start or process actions, "
            "so you may need to wait and take successive screenshots to see the results of your actions.\n"
            "* The screen's resolution is {width}x{height}.\n"
            "* Make sure to click any buttons, links, icons, etc with the cursor tip "
            "in the center of the element. Don't click boxes on their edges unless asked."
        ),
        "parameters": {
            "properties": {
                "action": {
                    "description": (
                        "The action to perform. The available actions are:\n"
                        '* `key`: Perform a key event on the mobile device.\n'
                        '    - This supports adb\'s `keyevent` syntax.\n'
                        '    - Examples: "volume_up", "volume_down", "power", "camera", "clear".\n'
                        "* `click`: Click the point on the screen with coordinate (x, y).\n"
                        "* `long_press`: Press the point on the screen with coordinate (x, y) for specified seconds.\n"
                        "* `swipe`: Swipe from the starting point with coordinate (x, y) "
                        "to the end point with coordinates2 (x2, y2).\n"
                        "* `type`: Input the specified text into the activated input box.\n"
                        "* `system_button`: Press the system button.\n"
                        "* `open`: Open an app on the device.\n"
                        "* `wait`: Wait specified seconds for the change to happen.\n"
                        "* `answer`: Terminate the current task and output the answer.\n"
                        "* `interact`: Resolve the blocking window by interacting with the user.\n"
                        "* `terminate`: Terminate the current task and report its completion status."
                    ),
                    "enum": [
                        "key", "click", "long_press", "swipe", "type",
                        "system_button", "open", "wait", "answer", "interact", "terminate"
                    ],
                    "type": "string"
                },
                "coordinate": {
                    "description": (
                        "(x, y): The x (pixels from the left edge) and y (pixels from the top edge) "
                        "coordinates to move the mouse to. Required only by `action=click`, "
                        "`action=long_press`, and `action=swipe`."
                    ),
                    "type": "array"
                },
                "coordinate2": {
                    "description": (
                        "(x, y): The x (pixels from the left edge) and y (pixels from the top edge) "
                        "coordinates to move the mouse to. Required only by `action=swipe`."
                    ),
                    "type": "array"
                },
                "text": {
                    "description": (
                        'Required only by `action=key`, `action=type`, `action=open`, '
                        '`action=answer`, and `action=interact`.'
                    ),
                    "type": "string"
                },
                "time": {
                    "description": "The seconds to wait. Required only by `action=long_press` and `action=wait`.",
                    "type": "number"
                },
                "button": {
                    "description": (
                        "Back means returning to the previous interface, "
                        "Home means returning to the desktop, "
                        "Menu means opening the application background menu, "
                        "and Enter means pressing the enter. "
                        "Required only by `action=system_button`"
                    ),
                    "enum": ["Back", "Home", "Menu", "Enter"],
                    "type": "string"
                },
                "status": {
                    "description": 'The status of the task. Required only by `action=terminate`.',
                    "type": "string",
                    "enum": ["success", "failure"]
                }
            },
            "required": ["action"],
            "type": "object"
        },
        "args_format": "Format the arguments as a JSON object."
    }
}


def _build_tool_schema_str(width: int = 1000, height: int = 1000) -> str:
    """构建工具 schema 字符串，注入分辨率"""
    schema = json.loads(json.dumps(GUIOWL_TOOL_SCHEMA))
    schema["function"]["description"] = schema["function"]["description"].format(
        width=width, height=height
    )
    return json.dumps(schema, ensure_ascii=False)


# ==================== 英文 System Prompt 模板 ====================
GUIOWL_SYSTEM_PROMPT_EN = '''# Tools

You may call one or more functions to assist with the user query.

You are provided with function signatures within <tools></tools> XML tags:
<tools>
{tool_schema}
</tools>

For each function call, return a json object with function name and arguments within <tool_call></tool_call> XML tags:
<tool_call>
{{"name": <function-name>, "arguments": <args-json-object>}}
</tool_call>

# Response format

Response format for every step:
1) Action: a short imperative describing what to do in the UI.
2) A single <tool_call>...</tool_call> block containing only the JSON: {{"name": <function-name>, "arguments": <args-json-object>}}.

Rules:
- Output exactly in the order: Action, <tool_call>.
- Be brief: one for Action.
- Do not output anything else outside those two parts.
- If finishing, use mobile_use with action=terminate in the tool call.'''


# ==================== 中文 System Prompt 模板 ====================
GUIOWL_SYSTEM_PROMPT_CN = '''# 工具

你可以调用一个或多个函数来完成用户的请求。

可用的函数定义在 <tools></tools> XML 标签内：
<tools>
{tool_schema}
</tools>

每次调用函数时，请在 <tool_call></tool_call> XML 标签内返回一个包含函数名和参数的 JSON 对象：
<tool_call>
{{"name": <函数名>, "arguments": <参数JSON对象>}}
</tool_call>

# 回复格式

每一步的回复格式：
1) Action: 一句简短的祈使句，描述在UI上要执行的操作。
2) 一个 <tool_call>...</tool_call> 代码块，只包含 JSON：{{"name": <函数名>, "arguments": <参数JSON对象>}}。

规则：
- 严格按照 Action、<tool_call> 的顺序输出。
- 保持简洁：Action 只需一句话。
- 不要输出这两部分以外的任何内容。
- 如果任务完成，在 tool_call 中使用 mobile_use 的 action=terminate。'''


# ==================== User Prompt 模板 ====================
GUIOWL_USER_PROMPT_TEMPLATE = (
    "Please generate the next move according to the UI screenshot, "
    "instruction and previous actions.\n\n"
    "Instruction: {instruction}"
)

GUIOWL_USER_PROMPT_WITH_HISTORY_TEMPLATE = (
    "Please generate the next move according to the UI screenshot, "
    "instruction and previous actions.\n\n"
    "Instruction: {instruction}\n\n"
    "Previous actions: \n{previous_steps}"
)

GUIOWL_USER_PROMPT_CN_TEMPLATE = (
    "请根据UI截图、指令和之前的操作，生成下一步操作。\n\n"
    "指令: {instruction}"
)

GUIOWL_USER_PROMPT_CN_WITH_HISTORY_TEMPLATE = (
    "请根据UI截图、指令和之前的操作，生成下一步操作。\n\n"
    "指令: {instruction}\n\n"
    "之前的操作: \n{previous_steps}"
)


def get_guiowl_system_prompt(
    instruction: str,
    language: str = "Chinese",
    screen_width: int = 1000,
    screen_height: int = 1000,
) -> str:
    """
    获取 GUI-Owl 模型的系统提示（不包含 instruction）

    Args:
        instruction: 用户任务指令（保留参数兼容性，实际不注入 system prompt）
        language: 语言选择 (Chinese/English)
        screen_width: 屏幕分辨率宽度（归一化坐标空间），默认 1000
        screen_height: 屏幕分辨率高度（归一化坐标空间），默认 1000

    Returns:
        格式化后的系统提示
    """
    tool_schema = _build_tool_schema_str(screen_width, screen_height)

    if language.lower() in ("english", "en"):
        return GUIOWL_SYSTEM_PROMPT_EN.format(tool_schema=tool_schema)
    else:
        return GUIOWL_SYSTEM_PROMPT_CN.format(tool_schema=tool_schema)


def build_guiowl_user_query(
    instruction: str,
    history: list[str] | None = None,
    language: str = "Chinese",
) -> str:
    """
    构建 GUI-Owl 的 user query（包含任务指令 + 操作历史）

    官方格式:
    ```
    Please generate the next move according to the UI screenshot,
    instruction and previous actions.

    Instruction: <task>

    Previous actions:
    Step1: <conclusion1>. Tool response: None
    Step2: <conclusion2>. Tool response: None
    ```

    Args:
        instruction: 用户任务指令
        history: 操作历史列表（每一步的 conclusion 描述文本）
        language: 语言 (Chinese/English)

    Returns:
        格式化后的 user query 字符串
    """
    is_en = language.lower() in ("english", "en")

    if history:
        # 构建 Previous actions 文本
        steps = []
        for i, step_desc in enumerate(history, 1):
            steps.append(f"Step{i}: {step_desc} Tool response: None")
        previous_steps = "\n".join(steps)

        if is_en:
            return GUIOWL_USER_PROMPT_WITH_HISTORY_TEMPLATE.format(
                instruction=instruction,
                previous_steps=previous_steps,
            )
        else:
            return GUIOWL_USER_PROMPT_CN_WITH_HISTORY_TEMPLATE.format(
                instruction=instruction,
                previous_steps=previous_steps,
            )
    else:
        if is_en:
            return GUIOWL_USER_PROMPT_TEMPLATE.format(instruction=instruction)
        else:
            return GUIOWL_USER_PROMPT_CN_TEMPLATE.format(instruction=instruction)
