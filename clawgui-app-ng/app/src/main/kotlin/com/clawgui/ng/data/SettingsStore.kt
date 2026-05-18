package com.clawgui.ng.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Persistent key/value store for ng. Secrets (API keys, Feishu app secret)
 * live in an EncryptedSharedPreferences instance; on devices where the
 * keystore fails (some MIUI / Honor builds), we fall back to plain prefs so
 * the app stays usable — at the cost of weaker at-rest security. Non-secret
 * settings always go to plain prefs.
 *
 * Layout:
 *   provider.<id>.apiKey      (encrypted)
 *   provider.<id>.baseUrl     (plain — user-visible, no value in encrypting)
 *   provider.<id>.model       (plain)
 *   active.brain              (plain)
 *   active.vision             (plain)
 *   traces.enabled            (plain)
 *   feishu.enabled            (plain)
 *   feishu.appId              (plain)
 *   feishu.appSecret          (encrypted)
 *   appearance                (plain, "LIGHT"/"DARK"/"SYSTEM")
 */
class SettingsStore(context: Context) {

    private val plain: SharedPreferences =
        context.getSharedPreferences("clawng_settings", Context.MODE_PRIVATE)

    /**
     * EncryptedSharedPreferences creation hits the keystore — on Honor / MIUI
     * cold launches this is 200-500ms. Defer it via `lazy` so it only runs
     * the first time the user actually reads/writes an API key, not at app
     * start.
     */
    private val secrets: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "clawng_secrets",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (t: Throwable) {
            // Keystore unavailable — fall back to plain so the app still works.
            context.getSharedPreferences("clawng_secrets_plain", Context.MODE_PRIVATE)
        }
    }

    // ── Provider credentials ───────────────────────────────────────────────

    fun providerApiKey(id: String): String =
        secrets.getString("provider.$id.apiKey", "").orEmpty()

    fun setProviderApiKey(id: String, key: String) {
        secrets.edit().putString("provider.$id.apiKey", key.trim()).apply()
    }

    fun providerBaseUrl(id: String, default: String): String =
        plain.getString("provider.$id.baseUrl", default).orEmpty().ifBlank { default }

    fun setProviderBaseUrl(id: String, base: String) {
        plain.edit().putString("provider.$id.baseUrl", base.trim()).apply()
    }

    fun providerModel(id: String, default: String): String =
        plain.getString("provider.$id.model", default).orEmpty().ifBlank { default }

    fun setProviderModel(id: String, model: String) {
        plain.edit().putString("provider.$id.model", model.trim()).apply()
    }

    // ── Active selection ───────────────────────────────────────────────────

    var activeBrain: String
        get() = plain.getString("active.brain", "zhipu_glm4").orEmpty()
        set(v) { plain.edit().putString("active.brain", v).apply() }

    var activeVision: String
        get() = plain.getString("active.vision", "zhipu_autoglm_phone").orEmpty()
        set(v) { plain.edit().putString("active.vision", v).apply() }

    // ── Toggles ────────────────────────────────────────────────────────────

    var tracesEnabled: Boolean
        get() = plain.getBoolean("traces.enabled", true)
        set(v) { plain.edit().putBoolean("traces.enabled", v).apply() }

    var feishuEnabled: Boolean
        get() = plain.getBoolean("feishu.enabled", false)
        set(v) { plain.edit().putBoolean("feishu.enabled", v).apply() }

    var feishuAppId: String
        get() = plain.getString("feishu.appId", "").orEmpty()
        set(v) { plain.edit().putString("feishu.appId", v.trim()).apply() }

    var feishuAppSecret: String
        get() = secrets.getString("feishu.appSecret", "").orEmpty()
        set(v) { secrets.edit().putString("feishu.appSecret", v.trim()).apply() }

    /** Bot display name (informational — used to filter @-mentions if needed). */
    var feishuBotName: String
        get() = plain.getString("feishu.botName", "ClawGUI").orEmpty().ifBlank { "ClawGUI" }
        set(v) { plain.edit().putString("feishu.botName", v.trim()).apply() }

    /**
     * Comma-separated open_id whitelist. Empty + [feishuAllowAll]=false means
     * everyone is rejected — safe default that forces explicit opt-in.
     */
    var feishuAllowedOpenIds: String
        get() = plain.getString("feishu.allowedOpenIds", "").orEmpty()
        set(v) { plain.edit().putString("feishu.allowedOpenIds", v.trim()).apply() }

    var feishuAllowAll: Boolean
        get() = plain.getBoolean("feishu.allowAll", false)
        set(v) { plain.edit().putBoolean("feishu.allowAll", v).apply() }

    /** Auto-reply Brain-generated text to every inbound DM. Off = user-driven. */
    var feishuAutoReply: Boolean
        get() = plain.getBoolean("feishu.autoReply", false)
        set(v) { plain.edit().putBoolean("feishu.autoReply", v).apply() }

    var appearance: String
        get() = plain.getString("appearance", "SYSTEM").orEmpty()
        set(v) { plain.edit().putString("appearance", v).apply() }

    var seenOnboarding: Boolean
        get() = plain.getBoolean("seen_onboarding", false)
        set(v) { plain.edit().putBoolean("seen_onboarding", v).apply() }

    var guiModeEnabled: Boolean
        get() = plain.getBoolean("gui_mode", false)
        set(v) { plain.edit().putBoolean("gui_mode", v).apply() }

    /** Screenshot quality preset (`ORIGINAL` / `HIGH` / `MEDIUM` / `LOW` / `TINY`). */
    var screenshotQuality: String
        get() = plain.getString("screenshot_quality", "MEDIUM").orEmpty().ifBlank { "MEDIUM" }
        set(v) { plain.edit().putString("screenshot_quality", v).apply() }

    /** Post one notification per agent step (default off — running notif still updates). */
    var notifyEachStep: Boolean
        get() = plain.getBoolean("notify_each_step", false)
        set(v) { plain.edit().putBoolean("notify_each_step", v).apply() }

    /** Master switch — when off the AgentService stays silent entirely. */
    var notifyEnabled: Boolean
        get() = plain.getBoolean("notify_enabled", true)
        set(v) { plain.edit().putBoolean("notify_enabled", v).apply() }

    /** Whether each notification pops as a heads-up banner. */
    var notifyHeadsUp: Boolean
        get() = plain.getBoolean("notify_heads_up", true)
        set(v) { plain.edit().putBoolean("notify_heads_up", v).apply() }

    /** Show full think + action in the BigText body, or only the action line. */
    var notifyVerbose: Boolean
        get() = plain.getBoolean("notify_verbose", true)
        set(v) { plain.edit().putBoolean("notify_verbose", v).apply() }
}
