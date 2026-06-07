package com.sudo.manet.routing

import com.sudo.manet.protocol.NodeId
import com.sudo.manet.protocol.Packet

interface Router {
    /**
     * Handle an incoming packet. Returns true if the packet was handled/consumed,
     * false if it should be handled by other routers or the engine.
     */
    fun handlePacket(packet: Packet, fromNeighbor: NodeId): Boolean

    /**
     * Send a message using this routing strategy.
     */
    fun sendMessage(destId: NodeId, message: String)

    /**
     * Notify the router that a neighbor is no longer reachable.
     */
    fun onNeighborLost(neighborId: NodeId)
}
