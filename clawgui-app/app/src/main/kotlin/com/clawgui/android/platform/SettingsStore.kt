package com.clawgui.android.platform

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.clawgui.android.core.nano.channels.feishu.FeishuConfig
import com.clawgui.android.core.nano.providers.LLMProvider
import com.clawgui.android.core.nano.providers.ProviderBackend
import com.clawgui.android.core.nano.providers.findProviderByName
import com.clawgui.android.core.phone.model.ModelConfig
import com.clawgui.android.platform.http.AnthropicClient
import com.clawgui.android.platform.http.OpenAICompatClient

/**
 * Provider-keyed credential storage. Every supported provider has its own
 * (apiKey, apiBase, model) triple stored under `provider.<name>.*`.
 *
 * Legacy fields `api_key`, `api_base`, `brain_model_name`, `vlm_model_name`
 * are migrated one-time into `provider.zhipu.*` on first read (existing
 * users keep their credentials). `apiKey` / `apiBase` getters remain on the
 * store as convenience fallbacks pointing at the Brain provider's values,
 * so the sendInstruction early-exit check in App.kt keeps working.
 */
class SettingsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("clawgui_settings", Context.MODE_PRIVATE)

    /**
     * Only the Feishu app secret lives here. Other provider API keys stay in plain
     * `clawgui_settings` to keep the existing provider-keyed storage compatible.
     * Encrypted prefs fall back to plain prefs if MasterKey init throws (rare; e.g.
     * emulator images without a hardware keystore).
     */
    private val securePrefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context.applicationContext,
                "clawgui_secure",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (_: Throwable) {
            context.getSharedPreferences("clawgui_secure_fallback", Context.MODE_PRIVATE)
        }
    }

    init {
        migrateLegacyIfNeeded()
        migrateRoleModelKeysIfNeeded()
    }

    // ------------------------------------------------------------------
    // Provider selections
    // ------------------------------------------------------------------

    var brainProvider: String
        get() = prefs.getString(KEY_BRAIN_PROVIDER, DEFAULT_BRAIN_PROVIDER) ?: DEFAULT_BRAIN_PROVIDER
        set(value) {
            prefs.edit().putString(KEY_BRAIN_PROVIDER, value.ifBlank { DEFAULT_BRAIN_PROVIDER }).apply()
        }

    var vlmProvider: String
        get() = prefs.getString(KEY_VLM_PROVIDER, DEFAULT_VLM_PROVIDER) ?: DEFAULT_VLM_PROVIDER
        set(value) {
            prefs.edit().putString(KEY_VLM_PROVIDER, value.ifBlank { DEFAULT_VLM_PROVIDER }).apply()
        }

    fun providerApiKey(provider: String): String =
        prefs.getString(keyApiKey(provider), "") ?: ""

    fun setProviderApiKey(provider: String, value: String) {
        prefs.edit().putString(keyApiKey(provider), value).apply()
    }

    fun providerApiBase(provider: String): String {
        val stored = prefs.getString(keyApiBase(provider), null)
        if (!stored.isNullOrBlank()) return stored
        return findProviderByName(provider)?.defaultApiBase ?: ""
    }

    fun setProviderApiBase(provider: String, value: String) {
        prefs.edit().putString(keyApiBase(provider), value).apply()
    }

    fun brainProviderModel(provider: String): String {
        val stored = prefs.getString(keyBrainModel(provider), null)
        if (!stored.isNullOrBlank()) return stored
        return findProviderByName(provider)?.defaultModelHint ?: ""
    }

    fun setBrainProviderModel(provider: String, value: String) {
        prefs.edit().putString(keyBrainModel(provider), value).apply()
    }

    fun vlmProviderModel(provider: String): String {
        val stored = prefs.getString(keyVlmModel(provider), null)
        if (!stored.isNullOrBlank()) return stored
        return if (provider.equals("zhipu", ignoreCase = true)) {
            "autoglm-phone"
        } else {
            findProviderByName(provider)?.defaultModelHint ?: ""
        }
    }

    fun setVlmProviderModel(provider: String, value: String) {
        prefs.edit().putString(keyVlmModel(provider), value).apply()
    }

    // ------------------------------------------------------------------
    // Convenience / legacy-compatible accessors
    // ------------------------------------------------------------------

    /** API key of the currently-selected Brain provider. Used by App.kt's
     *  early-exit check for "请先填写 API Key". */
    val apiKey: String
        get() = providerApiKey(brainProvider)

    /** API base of the currently-selected Brain provider. */
    val apiBase: String
        get() = providerApiBase(brainProvider)

    val brainModelName: String
        get() = brainProviderModel(brainProvider).ifBlank { "glm-5" }

    val vlmModelName: String
        get() = vlmProviderModel(vlmProvider).ifBlank { "autoglm-phone" }

    var maxSteps: Int
        get() = prefs.getInt("max_steps", 20)
        set(value) { prefs.edit().putInt("max_steps", value).apply() }

    var traceEnabled: Boolean
        get() = prefs.getBoolean("trace_enabled", true)
        set(value) { prefs.edit().putBoolean("trace_enabled", value).apply() }

    /**
     * 诊断模式。打开后调用点内的 `if (Log.diag) Log.i(...)` 会写更多细节日志
     * (VLM 每步、bus 出入队等)。默认关,常规用户无感。
     */
    var diagnosticMode: Boolean
        get() = prefs.getBoolean("diagnostic_mode", false)
        set(value) { prefs.edit().putBoolean("diagnostic_mode", value).apply() }

    // ------------------------------------------------------------------
    // 飞书 channel
    // ------------------------------------------------------------------

    var feishuEnabled: Boolean
        get() = prefs.getBoolean(KEY_FEISHU_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_FEISHU_ENABLED, value).apply() }

    var feishuAppId: String
        get() = prefs.getString(KEY_FEISHU_APP_ID, "") ?: ""
        set(value) { prefs.edit().putString(KEY_FEISHU_APP_ID, value).apply() }

    /** App Secret 走 EncryptedSharedPreferences,和其他 plain key 分表存储。 */
    var feishuAppSecret: String
        get() = securePrefs.getString(KEY_FEISHU_APP_SECRET, "") ?: ""
        set(value) { securePrefs.edit().putString(KEY_FEISHU_APP_SECRET, value).apply() }

    /** 默认 false:首次填完 App ID/Secret 还要手动加 open_id 才能接消息,避免任何人 at 机器人就能操作手机。 */
    var feishuAllowAll: Boolean
        get() = prefs.getBoolean(KEY_FEISHU_ALLOW_ALL, false)
        set(value) { prefs.edit().putBoolean(KEY_FEISHU_ALLOW_ALL, value).apply() }

    /** 逗号分隔的 open_id 白名单。 */
    var feishuAllowedOpenIds: List<String>
        get() = (prefs.getString(KEY_FEISHU_ALLOWED_OPEN_IDS, "") ?: "")
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        set(value) {
            val joined = value.joinToString(",") { it.trim() }
            prefs.edit().putString(KEY_FEISHU_ALLOWED_OPEN_IDS, joined).apply()
        }

    fun buildFeishuConfig(): FeishuConfig = FeishuConfig(
        appId = feishuAppId,
        appSecret = feishuAppSecret,
        allowedOpenIds = feishuAllowedOpenIds,
        allowAll = feishuAllowAll,
    )

    // ------------------------------------------------------------------
    // Factory: build a provider instance for Brain / VLM
    // ------------------------------------------------------------------

    fun buildBrainProvider(): LLMProvider {
        val spec = findProviderByName(brainProvider) ?: findProviderByName(DEFAULT_BRAIN_PROVIDER)!!
        val model = brainModelName
        val base = providerApiBase(spec.name)
        val key = providerApiKey(spec.name)
        return when (spec.backend) {
            ProviderBackend.ANTHROPIC -> AnthropicClient(
                apiKey = key,
                apiBase = base.ifBlank { spec.defaultApiBase },
                defaultModel = model,
            )
            ProviderBackend.OPENAI_COMPAT -> OpenAICompatClient(
                apiKey = key,
                apiBase = base.ifBlank { spec.defaultApiBase },
                defaultModel = model,
            )
        }
    }

    fun buildVlmConfig(): ModelConfig {
        val spec = findProviderByName(vlmProvider) ?: findProviderByName(DEFAULT_VLM_PROVIDER)!!
        return ModelConfig(
            baseUrl = providerApiBase(spec.name).ifBlank { spec.defaultApiBase ?: "" },
            apiKey = providerApiKey(spec.name),
            modelName = vlmModelName,
        )
    }

    // ------------------------------------------------------------------
    // Legacy migration
    // ------------------------------------------------------------------

    private fun migrateLegacyIfNeeded() {
        if (prefs.getBoolean(KEY_MIGRATED, false)) return
        val legacyKey = prefs.getString("api_key", null)
        val legacyBase = prefs.getString("api_base", null)
        val legacyBrainModel = prefs.getString("brain_model_name", null)
        val legacyVlmModel = prefs.getString("vlm_model_name", null)

        val edit = prefs.edit()
        val target = DEFAULT_BRAIN_PROVIDER // zhipu — the only provider the old single-key UI targeted
        if (!legacyKey.isNullOrBlank() && prefs.getString(keyApiKey(target), null).isNullOrBlank()) {
            edit.putString(keyApiKey(target), legacyKey)
        }
        if (!legacyBase.isNullOrBlank() && prefs.getString(keyApiBase(target), null).isNullOrBlank()) {
            edit.putString(keyApiBase(target), legacyBase)
        }
        if (!legacyBrainModel.isNullOrBlank() && prefs.getString(keyBrainModel(target), null).isNullOrBlank()) {
            edit.putString(keyBrainModel(target), legacyBrainModel)
        }
        if (!legacyVlmModel.isNullOrBlank() && prefs.getString(keyVlmModel(DEFAULT_VLM_PROVIDER), null).isNullOrBlank()) {
            // VLM also migrates into zhipu since GLM autoglm-phone is the only V1 VLM
            edit.putString(keyVlmModel(DEFAULT_VLM_PROVIDER), legacyVlmModel)
        }
        edit.putBoolean(KEY_MIGRATED, true).apply()
    }

    private fun migrateRoleModelKeysIfNeeded() {
        if (prefs.getBoolean(KEY_ROLE_MODEL_MIGRATED, false)) return

        val edit = prefs.edit()
        val currentBrainProvider = brainProvider
        val currentVlmProvider = vlmProvider
        val legacyBrainModel = prefs.getString("brain_model_name", null)
        val legacyVlmModel = prefs.getString("vlm_model_name", null)
        val oldBrainProviderModel = prefs.getString(keyModel(currentBrainProvider), null)
        val oldVlmProviderModel = prefs.getString(keyModel(currentVlmProvider), null)

        if (prefs.getString(keyBrainModel(currentBrainProvider), null).isNullOrBlank()) {
            val model = when {
                !legacyBrainModel.isNullOrBlank() -> legacyBrainModel
                !oldBrainProviderModel.isNullOrBlank() && !oldBrainProviderModel.equals("autoglm-phone", ignoreCase = true) -> oldBrainProviderModel
                else -> findProviderByName(currentBrainProvider)?.defaultModelHint
            }
            if (!model.isNullOrBlank()) edit.putString(keyBrainModel(currentBrainProvider), model)
        }

        if (prefs.getString(keyVlmModel(currentVlmProvider), null).isNullOrBlank()) {
            val model = when {
                !legacyVlmModel.isNullOrBlank() -> legacyVlmModel
                !oldVlmProviderModel.isNullOrBlank() -> oldVlmProviderModel
                currentVlmProvider.equals("zhipu", ignoreCase = true) -> "autoglm-phone"
                else -> findProviderByName(currentVlmProvider)?.defaultModelHint
            }
            if (!model.isNullOrBlank()) edit.putString(keyVlmModel(currentVlmProvider), model)
        }

        edit.putBoolean(KEY_ROLE_MODEL_MIGRATED, true).apply()
    }

    companion object {
        private const val DEFAULT_BRAIN_PROVIDER = "zhipu"
        private const val DEFAULT_VLM_PROVIDER = "zhipu"
        private const val KEY_BRAIN_PROVIDER = "brain_provider"
        private const val KEY_VLM_PROVIDER = "vlm_provider"
        private const val KEY_MIGRATED = "provider_keyed_migration_v1_done"
        private const val KEY_ROLE_MODEL_MIGRATED = "role_model_migration_v1_done"
        private const val KEY_FEISHU_ENABLED = "feishu.enabled"
        private const val KEY_FEISHU_APP_ID = "feishu.app_id"
        private const val KEY_FEISHU_APP_SECRET = "feishu.app_secret"
        private const val KEY_FEISHU_ALLOW_ALL = "feishu.allow_all"
        private const val KEY_FEISHU_ALLOWED_OPEN_IDS = "feishu.allowed_open_ids"

        private fun keyApiKey(provider: String) = "provider.$provider.apiKey"
        private fun keyApiBase(provider: String) = "provider.$provider.apiBase"
        private fun keyModel(provider: String) = "provider.$provider.model"
        private fun keyBrainModel(provider: String) = "provider.$provider.brainModel"
        private fun keyVlmModel(provider: String) = "provider.$provider.vlmModel"
    }
}
