package com.clawgui.android.core.nano.bus

import kotlinx.coroutines.channels.Channel

class MessageBus {
    private val _inbound = Channel<InboundMessage>(Channel.UNLIMITED)
    private val _outbound = Channel<OutboundMessage>(Channel.UNLIMITED)

    suspend fun publishInbound(msg: InboundMessage) = _inbound.send(msg)
    suspend fun consumeInbound(): InboundMessage = _inbound.receive()
    suspend fun publishOutbound(msg: OutboundMessage) = _outbound.send(msg)
    suspend fun consumeOutbound(): OutboundMessage = _outbound.receive()
}
