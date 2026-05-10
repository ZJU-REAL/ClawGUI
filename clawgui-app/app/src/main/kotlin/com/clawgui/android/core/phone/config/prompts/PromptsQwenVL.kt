package com.clawgui.android.core.phone.config.prompts

/**
 * Qwen2.5-VL / Qwen3-VL 系统提示(官方 tool_call 格式)。
 * 对应 Python `phone_agent/config/prompts_qwenvl.py`。
 */
object PromptsQwenVL {

    private val MOBILE_USE_TOOL_SCHEMA = """{
  "type": "function",
  "function": {
    "name": "mobile_use",
    "description": "Use a touchscreen to interact with a mobile device, and take screenshots.\n* This is an interface to a mobile device with touchscreen. You can perform actions like clicking, typing, swiping, etc.\n* Some applications may take time to start or process actions, so you may need to wait and take successive screenshots to see the results of your actions.\n* The screen's resolution is {width}x{height}.\n* Make sure to click any buttons, links, icons, etc with the cursor tip in the center of the element. Don't click boxes on their edges unless asked.",
    "parameters": {
      "properties": {
        "action": {
          "description": "The action to perform. The available actions are:\n* `click`: Click the point on the screen with coordinate (x, y).\n* `long_press`: Press the point on the screen with coordinate (x, y) for specified seconds.\n* `swipe`: Swipe from the starting point with coordinate (x, y) to the end point with coordinates2 (x2, y2).\n* `type`: Input the specified text into the activated input box.\n* `answer`: Output the answer.\n* `system_button`: Press the system button.\n* `open_app`: Open the specified application.\n* `wait`: Wait specified seconds for the change to happen.\n* `terminate`: Terminate the current task and report its completion status.",
          "enum": ["click", "long_press", "swipe", "type", "answer", "system_button", "open_app", "wait", "terminate"],
          "type": "string"
        },
        "coordinate": {
          "description": "(x, y): The x (pixels from the left edge) and y (pixels from the top edge) coordinates to move the mouse to. Required only by `action=click`, `action=long_press`, and `action=swipe`.",
          "type": "array"
        },
        "coordinate2": {
          "description": "(x, y): The x (pixels from the left edge) and y (pixels from the top edge) coordinates to move the mouse to. Required only by `action=swipe`.",
          "type": "array"
        },
        "text": {
          "description": "Required only by `action=type` and `action=answer`.",
          "type": "string"
        },
        "time": {
          "description": "The seconds to wait. Required only by `action=long_press` and `action=wait`.",
          "type": "number"
        },
        "button": {
          "description": "Back means returning to the previous interface, Home means returning to the desktop, Menu means opening the application background menu, and Enter means pressing the enter. Required only by `action=system_button`",
          "enum": ["Back", "Home", "Menu", "Enter"],
          "type": "string"
        },
        "status": {
          "description": "The status of the task. Required only by `action=terminate`.",
          "type": "string",
          "enum": ["success", "failure"]
        },
        "app_name": {
          "description": "The name of the app to open. Required only by `action=open_app`.",
          "type": "string"
        }
      },
      "required": ["action"],
      "type": "object"
    }
  }
}"""

    private const val PHONE_USE_QWEN_CN = """# 工具

你可以调用一个或多个函数来辅助完成用户的请求。

可用的函数签名定义在 <tools></tools> XML 标签中：
<tools>
{tool_schema}
</tools>

每次调用函数时，请在 <tool_call></tool_call> XML 标签中返回一个包含函数名和参数的 JSON 对象：
<tool_call>
{"name": <函数名>, "arguments": <参数JSON对象>}
</tool_call>

# 响应格式

每一步的响应格式：
1) Thought（思考）：用一句简洁的话解释下一步操作（不要进行多步推理）。
2) Action（动作）：用一句简短的祈使句描述要在界面上执行的操作。
3) 一个 <tool_call>...</tool_call> 代码块，其中只包含 JSON：{"name": <函数名>, "arguments": <参数JSON对象>}。

规则：
- 严格按照 Thought、Action、<tool_call> 的顺序输出。
- 保持简洁：Thought 一句话，Action 一句话。
- 不要在这三部分之外输出任何其他内容。
- 如果任务完成，请在 tool_call 中使用 action=terminate。"""

    private const val PHONE_USE_QWEN_EN = """# Tools

You can call one or more functions to assist with the user's request.

The available function signatures are defined within <tools></tools> XML tags:
<tools>
{tool_schema}
</tools>

Each time you call a function, return a JSON object containing the function name and arguments within <tool_call></tool_call> XML tags:
<tool_call>
{"name": <function_name>, "arguments": <arguments_json_object>}
</tool_call>

# Response Format

Response format for each step:
1) Thought: Explain the next action in one concise sentence (do not perform multi-step reasoning).
2) Action: Describe the UI operation to perform in one short imperative sentence.
3) A <tool_call>...</tool_call> code block containing only JSON: {"name": <function_name>, "arguments": <arguments_json_object>}.

Rules:
- Strictly output in the order of Thought, Action, <tool_call>.
- Keep it concise: one sentence for Thought, one sentence for Action.
- Do not output anything beyond these three parts.
- If the task is completed, use action=terminate in the tool_call."""

    fun systemPrompt(lang: String = "cn", width: Int = 999, height: Int = 999): String {
        val schema = MOBILE_USE_TOOL_SCHEMA.replace("{width}", "$width").replace("{height}", "$height")
        val template = if (lang == "cn") PHONE_USE_QWEN_CN else PHONE_USE_QWEN_EN
        return template.replace("{tool_schema}", schema)
    }

    fun userQuery(instruction: String, history: List<String>): String {
        val sb = StringBuilder("The user query: $instruction.\n")
        if (history.isNotEmpty()) {
            val progress = buildString {
                history.forEachIndexed { idx, raw ->
                    val clean = raw.replace("\n", "").replace("\"", "")
                    append("Step ${idx + 1}: $clean; ")
                }
            }
            sb.append("Task progress (You have done the following operation on the current device): $progress\n")
        }
        return sb.toString()
    }
}
