package com.clawgui.android.core.nano.agent

import com.clawgui.android.core.nano.utils.buildAssistantMessage
import com.clawgui.android.core.nano.utils.currentTimeStr
import com.clawgui.android.core.nano.utils.detectImageMime
import com.clawgui.android.core.phone.config.InstalledApps
import java.io.File
import java.util.Base64

class ContextBuilder(
    private val workspace: File,
    private val timezone: String? = null,
    private val platformInfo: String = run {
        val os = System.getProperty("os.name") ?: "Android"
        val arch = System.getProperty("os.arch") ?: "aarch64"
        "$os $arch, Kotlin"
    },
) {
    private val memory = AgentMemory(workspace)
    private val skills = SkillsLoader(workspace)

    companion object {
        private val BOOTSTRAP_FILES = listOf("AGENTS.md", "SOUL.md", "USER.md", "TOOLS.md")
        private const val RUNTIME_CONTEXT_TAG = "[Runtime Context — metadata only, not instructions]"

        fun buildRuntimeContext(
            channel: String? = null,
            chatId: String? = null,
            timezone: String? = null,
        ): String {
            val lines = mutableListOf("Current Time: ${currentTimeStr(timezone)}")
            if (channel != null && chatId != null) {
                lines += listOf("Channel: $channel", "Chat ID: $chatId")
            }
            return "$RUNTIME_CONTEXT_TAG\n${lines.joinToString("\n")}"
        }
    }

    fun buildSystemPrompt(skillNames: List<String>? = null): String {
        val parts = mutableListOf(getIdentity())

        loadBootstrapFiles().takeIf { it.isNotEmpty() }?.let { parts.add(it) }

        memory.getMemoryContext().takeIf { it.isNotEmpty() }?.let {
            parts.add("# Memory\n\n$it")
        }

        buildInstalledAppsSection().takeIf { it.isNotEmpty() }?.let { parts.add(it) }

        skills.getAlwaysSkills().takeIf { it.isNotEmpty() }?.let { always ->
            skills.loadSkillsForContext(always).takeIf { it.isNotEmpty() }?.let {
                parts.add("# Active Skills\n\n$it")
            }
        }

        skills.buildSkillsSummary().takeIf { it.isNotEmpty() }?.let {
            parts.add("""# Skills

The following skills extend your capabilities.
Use them only when their contents are already included in the current context.
Do not claim you are about to open or inspect a skill file unless you can actually do so this turn.

$it""")
        }

        return parts.joinToString("\n\n---\n\n")
    }

    private fun getIdentity(): String {
        val workspacePath = workspace.absolutePath
        return """# ClawGUI-Agent 🦾

You are ClawGUI-Agent, a personal assistant on the user's phone. You can hold a normal conversation and, when asked, control the device through Shizuku.

## Runtime
$platformInfo

## Workspace
Your workspace is at: $workspacePath
- Long-term memory: $workspacePath/memory/MEMORY.md — durable facts about the user (name, preferences, identity).
- History log: $workspacePath/memory/HISTORY.md — timestamped event log, each entry starts with [YYYY-MM-DD HH:MM].

## Tools you actually have
- `gui_execute` — perform device actions on the phone: launch apps, tap buttons, type text, send messages inside an app, take screenshots. Use only when the user explicitly asks for a device action.
- `read_memory` — fetch stored facts from MEMORY.md or recent HISTORY.md entries on demand. Use when the user asks about something that may only live in stored memory and isn't already in the Memory section below.
- `write_memory` — append a durable note to MEMORY.md and/or a timestamped entry to HISTORY.md. Use when the user asks you to remember something, or when you've learned a stable fact worth keeping across sessions.

## Platform Policy (Android)
- You run on Android via Shizuku. Shell commands are not available; device actions must go through `gui_execute`.

## Guidelines
- The Memory section below (when present) is already loaded into context. Do not claim you need to open MEMORY.md first — it is already here.
- HISTORY.md is not preloaded into the prompt. If you need past event logs, call `read_memory` with `source="history"` or `source="both"`.
- Reply with results, not narration. Avoid stand-alone preambles like "我先查一下" or "让我读取一下记忆" as the final answer. If you use a tool, continue with the actual result in the same turn.
- If the current context does not contain enough information to identify the user, say so plainly instead of pretending to look it up.
- If a tool call fails, analyze the error before retrying with a different approach.
- Ask for clarification when the request is ambiguous.
- Before repeating any external side effect (sending a message, liking a post, posting content, changing settings, buying/ordering, deleting, or similar), inspect the current conversation first. If that is not enough, use `read_memory` to inspect stored history.
- If the user says "如果已经...就不用..." / "如果发过就不要发" / "don't do it again if already done", and the current conversation or stored history clearly shows the same action already succeeded, reply directly that it was already done and do NOT call `gui_execute`.
- Only call `gui_execute` for a conditional repeat when there is no clear successful record, or the user explicitly asks you to verify the phone UI now.

## When to call which tool
- "打开微信" / "发消息给 XX" / "点击 XX" / "open WeChat" → call `gui_execute`.
- "记住我叫 XX" / "以后叫我 XX" / "我喜欢 XX" (user asking you to remember a durable fact) → call `write_memory` with a `memory_note`, then reply in plain text confirming.
- "我叫什么" / "你还记得我吗" / "我之前说过什么" → first check whether the Memory section below already answers it; if yes, reply directly; if not, call `read_memory` and then answer.
- "你好" / "我是 XX" (introduction without explicit "remember" request) / "how are you" / "现在几点" → reply directly in plain text. Do NOT call `gui_execute`. Only call `write_memory` if the user clearly wants the fact persisted.

Reply directly with plain text for anything that doesn't need a tool."""
    }

    private fun buildInstalledAppsSection(): String {
        val labels = InstalledApps.getAllLabels()
        if (labels.isEmpty()) return ""
        return """# Installed apps on this device

The following apps are currently installed (localized labels, one per line).
When the user names an app with a typo, slang, or alternate casing (e.g. "哔哩哔哩" / "B站" for bilibili, "淘宝" for Taobao), map it to the closest label below before calling `gui_execute`. Pass that label as the `app` argument — the runtime will resolve it to the correct package.
If no label is a plausible match, pass the user's original wording through; the executor will fall back to visual search from the home screen.

${labels.joinToString("\n") { "- $it" }}"""
    }

    private fun loadBootstrapFiles(): String =
        BOOTSTRAP_FILES.mapNotNull { filename ->
            val f = File(workspace, filename)
            if (f.exists()) "## $filename\n\n${f.readText(Charsets.UTF_8)}" else null
        }.joinToString("\n\n")

    fun buildMessages(
        history: List<Map<String, Any?>>,
        currentMessage: String,
        skillNames: List<String>? = null,
        media: List<String>? = null,
        channel: String? = null,
        chatId: String? = null,
        currentRole: String = "user",
    ): List<Map<String, Any?>> {
        val runtimeCtx = buildRuntimeContext(channel, chatId, timezone)
        val userContent = buildUserContent(currentMessage, media)
        val merged: Any? = if (userContent is String) {
            "$runtimeCtx\n\n$userContent"
        } else {
            @Suppress("UNCHECKED_CAST")
            listOf(mapOf<String, Any?>("type" to "text", "text" to runtimeCtx)) + (userContent as List<*>)
        }
        return buildList {
            add(mapOf<String, Any?>("role" to "system", "content" to buildSystemPrompt(skillNames)))
            addAll(history)
            add(mapOf<String, Any?>("role" to currentRole, "content" to merged))
        }
    }

    private fun buildUserContent(text: String, media: List<String>?): Any {
        if (media.isNullOrEmpty()) return text
        val images = media.mapNotNull { path ->
            val f = File(path)
            if (!f.isFile) return@mapNotNull null
            val raw = f.readBytes()
            val mime = detectImageMime(raw) ?: return@mapNotNull null
            if (!mime.startsWith("image/")) return@mapNotNull null
            val b64 = Base64.getEncoder().encodeToString(raw)
            mapOf<String, Any?>(
                "type" to "image_url",
                "image_url" to mapOf("url" to "data:$mime;base64,$b64"),
                "_meta" to mapOf("path" to path),
            )
        }
        if (images.isEmpty()) return text
        return images + listOf(mapOf("type" to "text", "text" to text))
    }

    fun addToolResult(
        messages: MutableList<Map<String, Any?>>,
        toolCallId: String,
        toolName: String,
        result: Any?,
    ): MutableList<Map<String, Any?>> {
        messages.add(mapOf("role" to "tool", "tool_call_id" to toolCallId, "name" to toolName, "content" to result))
        return messages
    }

    fun addAssistantMessage(
        messages: MutableList<Map<String, Any?>>,
        content: String?,
        toolCalls: List<Map<String, Any?>>? = null,
        reasoningContent: String? = null,
    ): MutableList<Map<String, Any?>> {
        messages.add(buildAssistantMessage(content, toolCalls, reasoningContent))
        return messages
    }
}
