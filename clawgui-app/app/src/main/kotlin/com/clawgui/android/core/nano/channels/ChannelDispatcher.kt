package com.clawgui.android.core.nano.channels

import com.clawgui.android.core.nano.bus.MessageBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.logging.Logger

/**
 * Bus outbound 的唯一消费者,按 `msg.channel` 路由到对应的 [BaseChannel]。
 * 对齐 ClawGUI Python `nanobot/channels/manager.py::_dispatch_outbound`。
 *
 * 与 Python 版相比,阶段 2 故意不做:
 *  - stream delta coalescing(要时再加)
 *  - 发送失败指数退避重试(要时再加)
 *  - _progress / _tool_hint 开关(目前无 channel 依赖)
 */
class ChannelDispatcher(
    private val bus: MessageBus,
    private val scope: CoroutineScope,
) {
    private val logger = Logger.getLogger("ChannelDispatcher")
    private val channels = mutableMapOf<String, BaseChannel>()
    private var dispatchJob: Job? = null

    fun register(channel: BaseChannel) {
        channels[channel.name] = channel
    }

    fun get(name: String): BaseChannel? = channels[name]

    /**
     * 启动 outbound 消费循环。进程生命周期内只调一次(App.onCreate 里),之后哪怕
     * 界面切到后台也不停 —— in-app turn 跑完由它送回 UI,停了就落不了幕。
     */
    fun startDispatch() {
        if (dispatchJob != null) {
            logger.info("Outbound dispatcher already running, skipping")
            return
        }
        dispatchJob = scope.launch {
            logger.info("Outbound dispatcher started")
            com.clawgui.android.core.util.Log.i("Dispatcher", null, "outbound loop started")
            while (isActive) {
                val msg = try {
                    bus.consumeOutbound()
                } catch (_: CancellationException) {
                    break
                } catch (e: Exception) {
                    logger.warning("Error consuming outbound: $e")
                    com.clawgui.android.core.util.Log.w("Dispatcher", null, "consumeOutbound failed", e)
                    continue
                }
                val key = msg.metadata["sessionKey"] as? String
                val ch = channels[msg.channel]
                if (ch == null) {
                    logger.warning("Unknown channel: ${msg.channel}")
                    com.clawgui.android.core.util.Log.w(
                        "Dispatcher", key,
                        "unknown channel=${msg.channel}, dropping msg (len=${msg.content.length})",
                    )
                    continue
                }
                com.clawgui.android.core.util.Log.i(
                    "Dispatcher", key,
                    "→ ${ch.name}.send (len=${msg.content.length})",
                )
                val t0 = System.currentTimeMillis()
                try {
                    ch.send(msg)
                    com.clawgui.android.core.util.Log.i(
                        "Dispatcher", key,
                        "✓ ${ch.name}.send done (${System.currentTimeMillis() - t0}ms)",
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warning("Channel ${ch.name} send failed: $e")
                    com.clawgui.android.core.util.Log.e(
                        "Dispatcher", key,
                        "✗ ${ch.name}.send failed (${System.currentTimeMillis() - t0}ms)", e,
                    )
                }
            }
            logger.info("Outbound dispatcher stopped")
            com.clawgui.android.core.util.Log.i("Dispatcher", null, "outbound loop stopped")
        }
    }

    /**
     * 启动所有注册 channel 自身的长连/订阅(例如飞书 WS)。可配对 [stopChannels]
     * 随前后台反复切。各 channel 自己管幂等(FeishuChannel.start 有 running 守卫,
     * InAppChannel.start/stop 是 no-op)。
     */
    fun startChannels() {
        for (ch in channels.values) {
            scope.launch {
                try {
                    ch.start()
                    logger.info("Channel ${ch.name} started")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warning("Channel ${ch.name} start failed: $e")
                }
            }
        }
    }

    suspend fun stopChannels() {
        for (ch in channels.values) {
            try {
                ch.stop()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warning("Channel ${ch.name} stop failed: $e")
            }
        }
    }
}
