package com.clawgui.android.core.phone.config.prompts

/**
 * mPLUG/GUI-Owl-7B/32B/1.5 系统提示。对应 Python
 * `phone_agent/config/prompts_guiowl.py`。
 */
object PromptsGuiOwl {

    private val GUIOWL_TOOL_SCHEMA = """{"type":"function","function":{"name_for_human":"mobile_use","name":"mobile_use","description":"Use a touchscreen to interact with a mobile device, and take screenshots.\n* This is an interface to a mobile device with touchscreen. You can perform actions like clicking, typing, swiping, etc.\n* Some applications may take time to start or process actions, so you may need to wait and take successive screenshots to see the results of your actions.\n* The screen's resolution is {width}x{height}.\n* Make sure to click any buttons, links, icons, etc with the cursor tip in the center of the element. Don't click boxes on their edges unless asked.","parameters":{"properties":{"action":{"description":"The action to perform. The available actions are:\n* `key`: Perform a key event on the mobile device.\n    - This supports adb's `keyevent` syntax.\n    - Examples: \"volume_up\", \"volume_down\", \"power\", \"camera\", \"clear\".\n* `click`: Click the point on the screen with coordinate (x, y).\n* `long_press`: Press the point on the screen with coordinate (x, y) for specified seconds.\n* `swipe`: Swipe from the starting point with coordinate (x, y) to the end point with coordinates2 (x2, y2).\n* `type`: Input the specified text into the activated input box.\n* `system_button`: Press the system button.\n* `open`: Open an app on the device.\n* `wait`: Wait specified seconds for the change to happen.\n* `answer`: Terminate the current task and output the answer.\n* `interact`: Resolve the blocking window by interacting with the user.\n* `terminate`: Terminate the current task and report its completion status.","enum":["key","click","long_press","swipe","type","system_button","open","wait","answer","interact","terminate"],"type":"string"},"coordinate":{"description":"(x, y): The x (pixels from the left edge) and y (pixels from the top edge) coordinates to move the mouse to. Required only by `action=click`, `action=long_press`, and `action=swipe`.","type":"array"},"coordinate2":{"description":"(x, y): The x (pixels from the left edge) and y (pixels from the top edge) coordinates to move the mouse to. Required only by `action=swipe`.","type":"array"},"text":{"description":"Required only by `action=key`, `action=type`, `action=open`, `action=answer`, and `action=interact`.","type":"string"},"time":{"description":"The seconds to wait. Required only by `action=long_press` and `action=wait`.","type":"number"},"button":{"description":"Back means returning to the previous interface, Home means returning to the desktop, Menu means opening the application background menu, and Enter means pressing the enter. Required only by `action=system_button`","enum":["Back","Home","Menu","Enter"],"type":"string"},"status":{"description":"The status of the task. Required only by `action=terminate`.","type":"string","enum":["success","failure"]}},"required":["action"],"type":"object"},"args_format":"Format the arguments as a JSON object."}}"""

    private const val GUIOWL_SYSTEM_PROMPT_EN = """# Tools

You may call one or more functions to assist with the user query.

You are provided with function signatures within <tools></tools> XML tags:
<tools>
{tool_schema}
</tools>

For each function call, return a json object with function name and arguments within <tool_call></tool_call> XML tags:
<tool_call>
{"name": <function-name>, "arguments": <args-json-object>}
</tool_call>

# Response format

Response format for every step:
1) Action: a short imperative describing what to do in the UI.
2) A single <tool_call>...</tool_call> block containing only the JSON: {"name": <function-name>, "arguments": <args-json-object>}.

Rules:
- Output exactly in the order: Action, <tool_call>.
- Be brief: one for Action.
- Do not output anything else outside those two parts.
- If finishing, use mobile_use with action=terminate in the tool call."""

    private const val GUIOWL_SYSTEM_PROMPT_CN = """# 工具

你可以调用一个或多个函数来完成用户的请求。

可用的函数定义在 <tools></tools> XML 标签内：
<tools>
{tool_schema}
</tools>

每次调用函数时，请在 <tool_call></tool_call> XML 标签内返回一个包含函数名和参数的 JSON 对象：
<tool_call>
{"name": <函数名>, "arguments": <参数JSON对象>}
</tool_call>

# 回复格式

每一步的回复格式：
1) Action: 一句简短的祈使句，描述在UI上要执行的操作。
2) 一个 <tool_call>...</tool_call> 代码块，只包含 JSON：{"name": <函数名>, "arguments": <参数JSON对象>}。

规则：
- 严格按照 Action、<tool_call> 的顺序输出。
- 保持简洁：Action 只需一句话。
- 不要输出这两部分以外的任何内容。
- 如果任务完成，在 tool_call 中使用 mobile_use 的 action=terminate。"""

    fun systemPrompt(lang: String = "cn", width: Int = 1000, height: Int = 1000): String {
        val schema = GUIOWL_TOOL_SCHEMA.replace("{width}", "$width").replace("{height}", "$height")
        val tmpl = if (lang == "cn") GUIOWL_SYSTEM_PROMPT_CN else GUIOWL_SYSTEM_PROMPT_EN
        return tmpl.replace("{tool_schema}", schema)
    }

    fun userQuery(instruction: String, history: List<String>, lang: String = "cn"): String {
        val isEn = lang != "cn"
        return if (history.isNotEmpty()) {
            val steps = history.mapIndexed { i, desc -> "Step${i + 1}: $desc Tool response: None" }.joinToString("\n")
            if (isEn) {
                "Please generate the next move according to the UI screenshot, instruction and previous actions.\n\nInstruction: $instruction\n\nPrevious actions: \n$steps"
            } else {
                "请根据UI截图、指令和之前的操作，生成下一步操作。\n\n指令: $instruction\n\n之前的操作: \n$steps"
            }
        } else {
            if (isEn) {
                "Please generate the next move according to the UI screenshot, instruction and previous actions.\n\nInstruction: $instruction"
            } else {
                "请根据UI截图、指令和之前的操作，生成下一步操作。\n\n指令: $instruction"
            }
        }
    }
}
