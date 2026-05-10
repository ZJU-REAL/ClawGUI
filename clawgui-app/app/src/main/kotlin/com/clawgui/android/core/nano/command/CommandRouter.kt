package com.clawgui.android.core.nano.command

import com.clawgui.android.core.nano.bus.InboundMessage
import com.clawgui.android.core.nano.bus.OutboundMessage

typealias CommandHandler = suspend (CommandContext) -> OutboundMessage?

data class CommandContext(
    val msg: InboundMessage,
    val key: String,
    val raw: String,
    var args: String = "",
    val loop: Any? = null,
)

class CommandRouter {

    private val priority: MutableMap<String, CommandHandler> = mutableMapOf()
    private val exact: MutableMap<String, CommandHandler> = mutableMapOf()
    private val prefix: MutableList<Pair<String, CommandHandler>> = mutableListOf()
    private val interceptors: MutableList<CommandHandler> = mutableListOf()

    fun priority(cmd: String, handler: CommandHandler) {
        priority[cmd] = handler
    }

    fun exact(cmd: String, handler: CommandHandler) {
        exact[cmd] = handler
    }

    fun prefix(pfx: String, handler: CommandHandler) {
        prefix += Pair(pfx, handler)
        prefix.sortByDescending { it.first.length }
    }

    fun intercept(handler: CommandHandler) {
        interceptors += handler
    }

    fun isPriority(text: String): Boolean = text.trim().lowercase() in priority

    suspend fun dispatchPriority(ctx: CommandContext): OutboundMessage? {
        val handler = priority[ctx.raw.lowercase()] ?: return null
        return handler(ctx)
    }

    suspend fun dispatch(ctx: CommandContext): OutboundMessage? {
        val cmd = ctx.raw.lowercase()

        exact[cmd]?.let { return it(ctx) }

        for ((pfx, handler) in prefix) {
            if (cmd.startsWith(pfx)) {
                ctx.args = ctx.raw.substring(pfx.length)
                return handler(ctx)
            }
        }

        for (interceptor in interceptors) {
            val result = interceptor(ctx)
            if (result != null) return result
        }

        return null
    }
}
