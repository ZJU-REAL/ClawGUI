package com.clawgui.ng.runtime.phone.config

object I18n {

    val MESSAGES_ZH: Map<String, String> = mapOf(
        "thinking" to "思考过程",
        "action" to "执行动作",
        "task_completed" to "任务完成",
        "done" to "完成",
        "starting_task" to "开始执行任务",
        "final_result" to "最终结果",
        "task_result" to "任务结果",
        "confirmation_required" to "需要确认",
        "continue_prompt" to "是否继续？(y/n)",
        "manual_operation_required" to "需要人工操作",
        "manual_operation_hint" to "请手动完成操作...",
        "press_enter_when_done" to "完成后按回车继续",
        "connection_failed" to "连接失败",
        "connection_successful" to "连接成功",
        "step" to "步骤",
        "task" to "任务",
        "result" to "结果",
        "performance_metrics" to "性能指标",
        "time_to_first_token" to "首 Token 延迟 (TTFT)",
        "time_to_thinking_end" to "思考完成延迟",
        "total_inference_time" to "总推理时间",
    )

    val MESSAGES_EN: Map<String, String> = mapOf(
        "thinking" to "Thinking",
        "action" to "Action",
        "task_completed" to "Task Completed",
        "done" to "Done",
        "starting_task" to "Starting task",
        "final_result" to "Final Result",
        "task_result" to "Task Result",
        "confirmation_required" to "Confirmation Required",
        "continue_prompt" to "Continue? (y/n)",
        "manual_operation_required" to "Manual Operation Required",
        "manual_operation_hint" to "Please complete the operation manually...",
        "press_enter_when_done" to "Press Enter when done",
        "connection_failed" to "Connection Failed",
        "connection_successful" to "Connection Successful",
        "step" to "Step",
        "task" to "Task",
        "result" to "Result",
        "performance_metrics" to "Performance Metrics",
        "time_to_first_token" to "Time to First Token (TTFT)",
        "time_to_thinking_end" to "Time to Thinking End",
        "total_inference_time" to "Total Inference Time",
    )

    fun getMessages(lang: String = "cn"): Map<String, String> =
        if (lang == "en") MESSAGES_EN else MESSAGES_ZH

    fun getMessage(key: String, lang: String = "cn"): String =
        getMessages(lang)[key] ?: key
}
