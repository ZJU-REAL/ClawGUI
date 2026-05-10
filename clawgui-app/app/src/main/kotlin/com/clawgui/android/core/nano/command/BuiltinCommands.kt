package com.clawgui.android.core.nano.command

import com.clawgui.android.core.nano.bus.OutboundMessage
import com.clawgui.android.core.nano.utils.buildStatusContent

suspend fun cmdStop(ctx: CommandContext): OutboundMessage {
    // AgentLoop wires this up in M4 — stub returns confirmation
    return OutboundMessage(
        channel = ctx.msg.channel,
        chatId = ctx.msg.chatId,
        content = "停止请求已发送。",
    )
}

suspend fun cmdHelp(ctx: CommandContext): OutboundMessage {
    val lines = listOf(
        "nanobot 命令：",
        "/new — 开始新对话",
        "/stop — 停止当前任务",
        "/status — 显示运行状态",
        "/help — 显示帮助信息",
    )
    return OutboundMessage(
        channel = ctx.msg.channel,
        chatId = ctx.msg.chatId,
        content = lines.joinToString("\n"),
        metadata = mapOf("render_as" to "text"),
    )
}

fun registerBuiltinCommands(router: CommandRouter) {
    router.priority("/stop", ::cmdStop)
    router.exact("/help", ::cmdHelp)
}
