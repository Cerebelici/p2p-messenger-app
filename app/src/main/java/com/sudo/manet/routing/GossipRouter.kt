package com.sudo.manet.routing

import com.sudo.manet.protocol.BROADCAST_ADDRESS
import com.sudo.manet.protocol.NodeId
import com.sudo.manet.protocol.Packet
import com.sudo.manet.protocol.PacketType

class GossipRouter(
    private val localId: NodeId,
    private val transmit: (toNeighbor: NodeId, packet: Packet) -> Unit,
    private val getNeighbors: () -> List<NodeId>,
    private val onMessageReceived: (Packet) -> Unit,
    private val maxGossipFanout: Int = 7,
    private val defaultTtl: Int = 8
) : Router {

    override fun handlePacket(packet: Packet, fromNeighbor: NodeId): Boolean {
        if (packet.type != PacketType.MSG_GOSSIP) return false

        // Deliver to local user
        onMessageReceived(packet)

        // Forward to neighbors
        if (packet.ttl > 0) {
            forward(packet, excludeNeighbor = fromNeighbor)
        }
        
        return true
    }

    override fun sendMessage(destId: NodeId, message: String) {
        val packet = Packet(
            type = PacketType.MSG_GOSSIP,
            senderId = localId,
            destId = BROADCAST_ADDRESS,
            ttl = defaultTtl,
            payload = message
        )
        forward(packet)
    }

    override fun onNeighborLost(neighborId: NodeId) {
        // Gossip doesn't maintain state about neighbors
    }

    private fun forward(packet: Packet, excludeNeighbor: NodeId? = null) {
        val relayed = packet.withTtl(packet.ttl - 1)
        getNeighbors()
            .filter { it != excludeNeighbor }
            .shuffled()
            .take(maxGossipFanout)
            .forEach { neighbor ->
                transmit(neighbor, relayed)
            }
    }
}
