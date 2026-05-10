package com.clawgui.android.core.nano.providers

/**
 * Subset of ClawGUI Python providers that make sense on Android
 * (OpenAI-compat HTTP + Anthropic native). Local/vLLM/Ollama/OAuth/Azure
 * are excluded — see plan notes.
 *
 * Order matters for keyword fallback matching.
 */

enum class ProviderBackend { OPENAI_COMPAT, ANTHROPIC }

data class ProviderSpec(
    val name: String,
    val displayName: String,
    val backend: ProviderBackend,
    val keywords: List<String>,
    val defaultApiBase: String? = null,
    val defaultModelHint: String? = null,
    val isGateway: Boolean = false,
)

val PROVIDERS: List<ProviderSpec> = listOf(
    ProviderSpec(
        name = "zhipu",
        displayName = "智谱 AI (GLM)",
        backend = ProviderBackend.OPENAI_COMPAT,
        keywords = listOf("zhipu", "glm", "zai"),
        defaultApiBase = "https://open.bigmodel.cn/api/paas/v4",
        defaultModelHint = "glm-5",
    ),
    ProviderSpec(
        name = "anthropic",
        displayName = "Anthropic (Claude)",
        backend = ProviderBackend.ANTHROPIC,
        keywords = listOf("anthropic", "claude"),
        defaultApiBase = "https://api.anthropic.com",
        defaultModelHint = "claude-sonnet-4-5",
    ),
    ProviderSpec(
        name = "openai",
        displayName = "OpenAI",
        backend = ProviderBackend.OPENAI_COMPAT,
        keywords = listOf("openai", "gpt"),
        defaultApiBase = "https://api.openai.com/v1",
        defaultModelHint = "gpt-4o",
    ),
    ProviderSpec(
        name = "deepseek",
        displayName = "DeepSeek",
        backend = ProviderBackend.OPENAI_COMPAT,
        keywords = listOf("deepseek"),
        defaultApiBase = "https://api.deepseek.com",
        defaultModelHint = "deepseek-chat",
    ),
    ProviderSpec(
        name = "moonshot",
        displayName = "Moonshot (Kimi)",
        backend = ProviderBackend.OPENAI_COMPAT,
        keywords = listOf("moonshot", "kimi"),
        defaultApiBase = "https://api.moonshot.ai/v1",
        defaultModelHint = "kimi-k2",
    ),
    ProviderSpec(
        name = "siliconflow",
        displayName = "SiliconFlow (硅基流动)",
        backend = ProviderBackend.OPENAI_COMPAT,
        keywords = listOf("siliconflow"),
        defaultApiBase = "https://api.siliconflow.cn/v1",
        isGateway = true,
    ),
    ProviderSpec(
        name = "openrouter",
        displayName = "OpenRouter",
        backend = ProviderBackend.OPENAI_COMPAT,
        keywords = listOf("openrouter"),
        defaultApiBase = "https://openrouter.ai/api/v1",
        isGateway = true,
    ),
    ProviderSpec(
        name = "dashscope",
        displayName = "DashScope (通义千问)",
        backend = ProviderBackend.OPENAI_COMPAT,
        keywords = listOf("qwen", "dashscope"),
        defaultApiBase = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        defaultModelHint = "qwen-plus",
    ),
    ProviderSpec(
        name = "gemini",
        displayName = "Gemini",
        backend = ProviderBackend.OPENAI_COMPAT,
        keywords = listOf("gemini"),
        defaultApiBase = "https://generativelanguage.googleapis.com/v1beta/openai/",
        defaultModelHint = "gemini-2.0-flash",
    ),
    ProviderSpec(
        name = "minimax",
        displayName = "MiniMax",
        backend = ProviderBackend.OPENAI_COMPAT,
        keywords = listOf("minimax"),
        defaultApiBase = "https://api.minimax.io/v1",
    ),
    ProviderSpec(
        name = "mistral",
        displayName = "Mistral",
        backend = ProviderBackend.OPENAI_COMPAT,
        keywords = listOf("mistral"),
        defaultApiBase = "https://api.mistral.ai/v1",
    ),
    ProviderSpec(
        name = "stepfun",
        displayName = "StepFun (阶跃星辰)",
        backend = ProviderBackend.OPENAI_COMPAT,
        keywords = listOf("stepfun", "step"),
        defaultApiBase = "https://api.stepfun.com/v1",
    ),
    ProviderSpec(
        name = "volcengine",
        displayName = "VolcEngine (火山引擎)",
        backend = ProviderBackend.OPENAI_COMPAT,
        keywords = listOf("volcengine", "volces", "ark"),
        defaultApiBase = "https://ark.cn-beijing.volces.com/api/v3",
        isGateway = true,
    ),
    ProviderSpec(
        name = "aihubmix",
        displayName = "AiHubMix",
        backend = ProviderBackend.OPENAI_COMPAT,
        keywords = listOf("aihubmix"),
        defaultApiBase = "https://aihubmix.com/v1",
        isGateway = true,
    ),
    ProviderSpec(
        name = "groq",
        displayName = "Groq",
        backend = ProviderBackend.OPENAI_COMPAT,
        keywords = listOf("groq"),
        defaultApiBase = "https://api.groq.com/openai/v1",
    ),
)

fun findProviderByName(name: String): ProviderSpec? =
    PROVIDERS.firstOrNull { it.name.equals(name, ignoreCase = true) }

fun findProviderForModel(model: String): ProviderSpec? {
    val lower = model.lowercase()
    return PROVIDERS.firstOrNull { spec ->
        spec.keywords.any { kw -> kw in lower || kw.replace("-", "_") in lower.replace("-", "_") }
    }
}
