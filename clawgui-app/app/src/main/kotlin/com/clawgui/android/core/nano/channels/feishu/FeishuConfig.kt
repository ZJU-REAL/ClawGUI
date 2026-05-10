package com.clawgui.android.core.nano.channels.feishu

/**
 * 飞书 channel 运行配置。[allowedOpenIds] 白名单为空且 [allowAll] = false 时,
 * 任何入站消息都会在 BaseChannel.isAllowed 被 drop(默认关的最小权限)。
 */
data class FeishuConfig(
    val appId: String,
    val appSecret: String,
    val allowedOpenIds: List<String>,
    val allowAll: Boolean,
) {
    val isUsable: Boolean get() = appId.isNotBlank() && appSecret.isNotBlank()
}
