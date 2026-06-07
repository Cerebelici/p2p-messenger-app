package com.sudo.manet.models

import java.util.UUID

enum class MessageStatus {
    PENDING,  // Route Discovery (BFS/AODV)
    BUFFERED, // Active Relay (In LRU Cache)
    DELIVERED // ACK Received
}

data class MeshPacket(
    val packetId: String = UUID.randomUUID().toString(),
    val senderId: String,
    val destId: String, // "BROADCAST" or Peer SHA-256
    val ttl: Int = 10,
    val payload: String,
    val timestamp: Long = System.currentTimeMillis(),
    val status: MessageStatus = MessageStatus.PENDING
)

data class Peer(
    val nodeId: String, // 64-character SHA-256 hash
    val alias: String? = null,
    val lastSeen: Long = System.currentTimeMillis(),
    val isDirectNeighbor: Boolean = false,
    val connections: List<String> = emptyList() // List of NodeIDs this peer can see
)

object NetworkConstants {
    const val BROADCAST_DEST = "BROADCAST"
}
