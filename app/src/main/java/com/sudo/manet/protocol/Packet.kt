package com.sudo.manet.protocol

import java.io.Serializable
import java.util.UUID

typealias NodeId = String

data class Packet(
    val packetId: String = UUID.randomUUID().toString(),
    val type: PacketType,
    val senderId: NodeId,
    val destId: NodeId,          // use "BROADCAST" for gossip packets
    val ttl: Int,
    val payload: String = "",
    val status: DeliveryState = DeliveryState.PENDING,
    val hopCount: Int = 0,
    val sequenceNumber: Int = 0
) : Serializable {
    fun withTtl(newTtl: Int) = copy(ttl = newTtl)
    fun withStatus(newStatus: DeliveryState) = copy(status = newStatus)
    fun withHop() = copy(hopCount = hopCount + 1)
}

const val BROADCAST_ADDRESS = "BROADCAST"