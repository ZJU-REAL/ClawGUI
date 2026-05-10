package com.clawgui.android.core.nano.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AgentDefaults(
    val workspace: String = "clawgui/workspace",
    val model: String = "glm-4v-plus",
    val provider: String = "zhipu",
    @SerialName("max_tokens") val maxTokens: Int = 8192,
    @SerialName("context_window_tokens") val contextWindowTokens: Int = 65_536,
    val temperature: Float = 0.1f,
    @SerialName("max_tool_iterations") val maxToolIterations: Int = 40,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    val timezone: String = "Asia/Shanghai",
)

@Serializable
data class ProviderConfig(
    @SerialName("api_key") val apiKey: String = "",
    @SerialName("api_base") val apiBase: String? = null,
)

@Serializable
data class ProvidersConfig(
    val zhipu: ProviderConfig = ProviderConfig(),
)

@Serializable
data class WebSearchConfig(
    val provider: String = "duckduckgo",
    @SerialName("api_key") val apiKey: String = "",
    @SerialName("base_url") val baseUrl: String = "",
    @SerialName("max_results") val maxResults: Int = 5,
)

@Serializable
data class WebToolsConfig(
    val proxy: String? = null,
    val search: WebSearchConfig = WebSearchConfig(),
)

@Serializable
data class GUIToolConfig(
    val enable: Boolean = true,
    @SerialName("max_steps") val maxSteps: Int = 50,
    @SerialName("gui_base_url") val guiBaseUrl: String = "https://open.bigmodel.cn/api/paas/v4/",
    @SerialName("gui_api_key") val guiApiKey: String = "",
    @SerialName("gui_model_name") val guiModelName: String = "autoglm-phone",
    @SerialName("prompt_template_lang") val promptTemplateLang: String = "cn",
    @SerialName("prompt_template_style") val promptTemplateStyle: String = "autoglm",
    @SerialName("trace_enabled") val traceEnabled: Boolean = false,
)

@Serializable
data class ToolsConfig(
    val web: WebToolsConfig = WebToolsConfig(),
    val gui: GUIToolConfig = GUIToolConfig(),
)

@Serializable
data class HeartbeatConfig(
    val enabled: Boolean = true,
    @SerialName("interval_s") val intervalS: Int = 30 * 60,
    @SerialName("keep_recent_messages") val keepRecentMessages: Int = 8,
)

@Serializable
data class AppConfig(
    val agents: AgentDefaults = AgentDefaults(),
    val providers: ProvidersConfig = ProvidersConfig(),
    val tools: ToolsConfig = ToolsConfig(),
    val heartbeat: HeartbeatConfig = HeartbeatConfig(),
)
