package com.clawgui.ng.data.repo

import com.clawgui.ng.data.ProviderKind
import com.clawgui.ng.data.ProviderProfile
import com.clawgui.ng.data.ProviderRole
import com.clawgui.ng.data.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class Appearance { LIGHT, DARK, SYSTEM }

/**
 * Settings repository, backed by [SettingsStore]. Holds the canonical list of
 * provider profiles (defaults are merged with persisted user-supplied keys at
 * construction time).
 */
class SettingsRepository(private val store: SettingsStore) {

    private val _providers = MutableStateFlow(loadProviders())
    val providers: StateFlow<List<ProviderProfile>> = _providers

    private val _activeBrain = MutableStateFlow(
        // Migrate older defaults that pointed at the now-removed GLM-4 profile.
        store.activeBrain.ifBlank { "zhipu_glm51" }
            .let { if (it == "zhipu_glm4") "zhipu_glm51" else it }
    )
    val activeBrain: StateFlow<String> = _activeBrain

    private val _activeVision = MutableStateFlow(
        store.activeVision.ifBlank { "zhipu_autoglm_phone" }
            .let { if (it == "zhipu_glm4v") "zhipu_autoglm_phone" else it }
    )
    val activeVision: StateFlow<String> = _activeVision

    private val _tracesEnabled = MutableStateFlow(store.tracesEnabled)
    val tracesEnabled: StateFlow<Boolean> = _tracesEnabled

    private val _feishuEnabled = MutableStateFlow(store.feishuEnabled)
    val feishuEnabled: StateFlow<Boolean> = _feishuEnabled

    private val _feishuAppId = MutableStateFlow(store.feishuAppId)
    val feishuAppId: StateFlow<String> = _feishuAppId
    fun setFeishuAppId(v: String) { _feishuAppId.value = v; store.feishuAppId = v }

    private val _feishuAppSecretSet = MutableStateFlow(store.feishuAppSecret.isNotBlank())
    val feishuAppSecretSet: StateFlow<Boolean> = _feishuAppSecretSet
    fun setFeishuAppSecret(v: String) {
        store.feishuAppSecret = v
        _feishuAppSecretSet.value = v.isNotBlank()
    }
    fun feishuAppSecret(): String = store.feishuAppSecret

    private val _feishuBotName = MutableStateFlow(store.feishuBotName)
    val feishuBotName: StateFlow<String> = _feishuBotName
    fun setFeishuBotName(v: String) { _feishuBotName.value = v.ifBlank { "ClawGUI" }; store.feishuBotName = v }

    private val _feishuAllowedOpenIds = MutableStateFlow(store.feishuAllowedOpenIds)
    val feishuAllowedOpenIds: StateFlow<String> = _feishuAllowedOpenIds
    fun setFeishuAllowedOpenIds(v: String) { _feishuAllowedOpenIds.value = v; store.feishuAllowedOpenIds = v }

    private val _feishuAllowAll = MutableStateFlow(store.feishuAllowAll)
    val feishuAllowAll: StateFlow<Boolean> = _feishuAllowAll
    fun setFeishuAllowAll(v: Boolean) { _feishuAllowAll.value = v; store.feishuAllowAll = v }

    private val _feishuAutoReply = MutableStateFlow(store.feishuAutoReply)
    val feishuAutoReply: StateFlow<Boolean> = _feishuAutoReply
    fun setFeishuAutoReply(v: Boolean) { _feishuAutoReply.value = v; store.feishuAutoReply = v }

    private val _appearance = MutableStateFlow(
        runCatching { Appearance.valueOf(store.appearance) }.getOrDefault(Appearance.SYSTEM)
    )
    val appearance: StateFlow<Appearance> = _appearance

    private val _seenOnboarding = MutableStateFlow(store.seenOnboarding)
    val seenOnboarding: StateFlow<Boolean> = _seenOnboarding

    fun markOnboardingSeen() {
        _seenOnboarding.value = true
        store.seenOnboarding = true
    }

    private val _guiModeEnabled = MutableStateFlow(store.guiModeEnabled)
    val guiModeEnabled: StateFlow<Boolean> = _guiModeEnabled

    fun setGuiModeEnabled(v: Boolean) {
        _guiModeEnabled.value = v
        store.guiModeEnabled = v
    }

    private val _screenshotQuality = MutableStateFlow(
        runCatching {
            com.clawgui.ng.runtime.phone.util.ScreenshotCompressor.Quality.valueOf(store.screenshotQuality)
        }.getOrDefault(com.clawgui.ng.runtime.phone.util.ScreenshotCompressor.Quality.MEDIUM)
    )
    val screenshotQuality: StateFlow<com.clawgui.ng.runtime.phone.util.ScreenshotCompressor.Quality> =
        _screenshotQuality

    fun setScreenshotQuality(q: com.clawgui.ng.runtime.phone.util.ScreenshotCompressor.Quality) {
        _screenshotQuality.value = q
        store.screenshotQuality = q.name
    }

    private val _notifyEachStep = MutableStateFlow(store.notifyEachStep)
    val notifyEachStep: StateFlow<Boolean> = _notifyEachStep
    fun setNotifyEachStep(v: Boolean) {
        _notifyEachStep.value = v
        store.notifyEachStep = v
    }

    private val _notifyEnabled = MutableStateFlow(store.notifyEnabled)
    val notifyEnabled: StateFlow<Boolean> = _notifyEnabled
    fun setNotifyEnabled(v: Boolean) {
        _notifyEnabled.value = v
        store.notifyEnabled = v
    }

    private val _notifyHeadsUp = MutableStateFlow(store.notifyHeadsUp)
    val notifyHeadsUp: StateFlow<Boolean> = _notifyHeadsUp
    fun setNotifyHeadsUp(v: Boolean) {
        _notifyHeadsUp.value = v
        store.notifyHeadsUp = v
    }

    private val _notifyVerbose = MutableStateFlow(store.notifyVerbose)
    val notifyVerbose: StateFlow<Boolean> = _notifyVerbose
    fun setNotifyVerbose(v: Boolean) {
        _notifyVerbose.value = v
        store.notifyVerbose = v
    }

    private val _overlayEnabled = MutableStateFlow(store.overlayEnabled)
    val overlayEnabled: StateFlow<Boolean> = _overlayEnabled
    fun setOverlayEnabled(v: Boolean) {
        _overlayEnabled.value = v
        store.overlayEnabled = v
    }

    private val _overlayAlphaPct = MutableStateFlow(store.overlayAlphaPct)
    val overlayAlphaPct: StateFlow<Int> = _overlayAlphaPct
    fun setOverlayAlphaPct(v: Int) {
        val clamped = v.coerceIn(40, 100)
        _overlayAlphaPct.value = clamped
        store.overlayAlphaPct = clamped
    }

    private val _feishuReplyImageMode = MutableStateFlow(
        runCatching { FeishuReplyImageMode.valueOf(store.feishuReplyImageMode) }
            .getOrDefault(FeishuReplyImageMode.FINAL_ONLY)
    )
    val feishuReplyImageMode: StateFlow<FeishuReplyImageMode> = _feishuReplyImageMode
    fun setFeishuReplyImageMode(v: FeishuReplyImageMode) {
        _feishuReplyImageMode.value = v
        store.feishuReplyImageMode = v.name
    }

    fun setActiveBrain(id: String) {
        _activeBrain.value = id
        store.activeBrain = id
    }

    fun setActiveVision(id: String) {
        _activeVision.value = id
        store.activeVision = id
    }

    fun setTracesEnabled(v: Boolean) {
        _tracesEnabled.value = v
        store.tracesEnabled = v
    }

    fun setFeishuEnabled(v: Boolean) {
        _feishuEnabled.value = v
        store.feishuEnabled = v
    }

    fun setAppearance(a: Appearance) {
        _appearance.value = a
        store.appearance = a.name
    }

    fun setProviderApiKey(id: String, key: String) {
        store.setProviderApiKey(id, key)
        _providers.update { list ->
            list.map { if (it.id == id) it.copy(hasApiKey = key.isNotBlank()) else it }
        }
    }

    fun setProviderBaseUrl(id: String, base: String) {
        store.setProviderBaseUrl(id, base)
        _providers.update { list ->
            list.map { if (it.id == id) it.copy(baseUrl = base) else it }
        }
    }

    fun setProviderModel(id: String, model: String) {
        store.setProviderModel(id, model)
        _providers.update { list ->
            list.map { if (it.id == id) it.copy(model = model) else it }
        }
    }

    /** Resolve current credentials for a provider — used by HTTP layer. */
    fun resolveCredentials(id: String): ProviderCredentials? {
        val profile = _providers.value.firstOrNull { it.id == id } ?: return null
        return ProviderCredentials(
            kind = profile.kind,
            baseUrl = store.providerBaseUrl(id, profile.baseUrl),
            model = store.providerModel(id, profile.model),
            apiKey = store.providerApiKey(id),
        )
    }

    private fun loadProviders(): List<ProviderProfile> = defaults().map { spec ->
        spec.copy(
            baseUrl = store.providerBaseUrl(spec.id, spec.baseUrl),
            model = store.providerModel(spec.id, spec.model),
            hasApiKey = store.providerApiKey(spec.id).isNotBlank(),
        )
    }

    private fun defaults() = listOf(
        // Default Brain — 智谱 GLM-5.1
        ProviderProfile(
            id = "zhipu_glm51",
            displayName = "智谱 GLM-5.1",
            kind = ProviderKind.ZHIPU,
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            model = "glm-5.1",
            hasApiKey = false,
            role = ProviderRole.BRAIN,
        ),
        ProviderProfile(
            id = "anthropic_default",
            displayName = "Claude (Anthropic)",
            kind = ProviderKind.ANTHROPIC,
            baseUrl = "https://api.anthropic.com",
            model = "claude-opus-4-7",
            hasApiKey = false,
            role = ProviderRole.BRAIN,
        ),
        ProviderProfile(
            id = "openai_default",
            displayName = "OpenAI 兼容",
            kind = ProviderKind.OPENAI_COMPAT,
            baseUrl = "https://api.openai.com/v1",
            model = "gpt-4o",
            hasApiKey = false,
            role = ProviderRole.BRAIN,
        ),
        ProviderProfile(
            id = "zhipu_autoglm_phone",
            displayName = "AutoGLM-Phone(智谱)",
            kind = ProviderKind.ZHIPU,
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            model = "autoglm-phone",
            hasApiKey = false,
            role = ProviderRole.VISION,
        ),
    )
}

data class ProviderCredentials(
    val kind: ProviderKind,
    val baseUrl: String,
    val model: String,
    val apiKey: String,
) {
    /**
     * Heuristic: does this Brain model support image inputs? Used to decide
     * whether to ship the user's attached images alongside their text or to
     * route the turn to the VLM instead.
     *
     * Inferred from the model name rather than stored as a settings field so
     * users who change the model string (e.g. `glm-4.5` → `glm-4.5v`) flip
     * the capability automatically without re-touching settings. Override
     * with a stored toggle later if this proves too coarse.
     */
    val supportsVision: Boolean
        get() {
            val m = model.lowercase()
            // Conservative deny-list first — text-only models we explicitly
            // know cannot see images (so a model name like `glm-5.1-pro`
            // doesn't accidentally match the `vl` substring inside another word).
            val textOnly = listOf(
                "glm-4-",
                "glm-4.5-",
                "glm-5.0",
                "glm-5.1",
                "gpt-3.5",
                "gpt-4-turbo", // text-only variants
            )
            if (textOnly.any { m.startsWith(it) }) return false

            // Known vision families.
            val vision = listOf(
                "vision", "vl", "-v", "4o", "4.1", "5o",
                "gemini", "gpt-4o", "gpt-5",
                "claude-3", "claude-opus", "claude-sonnet", "claude-haiku",
                "autoglm",
            )
            return vision.any { m.contains(it) }
        }
}

/**
 * What gets shipped back to the Feishu chat when a PhoneAgent task ends.
 * Text reply is always sent regardless of this setting.
 *
 *  - OFF        — text only, no images.
 *  - FINAL_ONLY — one screenshot of the final state.
 *  - COMPOSITE  — vertically stitched long image of every step's
 *                 screenshot (requires trace recording to be enabled;
 *                 falls back to FINAL_ONLY when trace data is missing).
 */
enum class FeishuReplyImageMode { OFF, FINAL_ONLY, COMPOSITE }
