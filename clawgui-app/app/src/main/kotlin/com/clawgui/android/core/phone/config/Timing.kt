package com.clawgui.android.core.phone.config

data class ActionTimingConfig(
    val keyboardSwitchDelay: Float = 1.0f,
    val textClearDelay: Float = 1.0f,
    val textInputDelay: Float = 1.0f,
    val keyboardRestoreDelay: Float = 1.0f,
)

data class DeviceTimingConfig(
    val defaultTapDelay: Float = 1.0f,
    val defaultDoubleTapDelay: Float = 1.0f,
    val doubleTapInterval: Float = 0.1f,
    val defaultLongPressDelay: Float = 1.0f,
    val defaultSwipeDelay: Float = 1.0f,
    val defaultBackDelay: Float = 1.0f,
    val defaultHomeDelay: Float = 1.0f,
    val defaultLaunchDelay: Float = 1.0f,
)

data class ConnectionTimingConfig(
    val adbRestartDelay: Float = 2.0f,
    val serverRestartDelay: Float = 1.0f,
)

data class TimingConfig(
    val action: ActionTimingConfig = ActionTimingConfig(),
    val device: DeviceTimingConfig = DeviceTimingConfig(),
    val connection: ConnectionTimingConfig = ConnectionTimingConfig(),
)

object TimingSettings {
    @Volatile
    var config: TimingConfig = TimingConfig()
        private set

    fun update(
        action: ActionTimingConfig? = null,
        device: DeviceTimingConfig? = null,
        connection: ConnectionTimingConfig? = null,
    ) {
        config = config.copy(
            action = action ?: config.action,
            device = device ?: config.device,
            connection = connection ?: config.connection,
        )
    }
}
