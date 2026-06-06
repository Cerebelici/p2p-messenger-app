package com.sudo.manet.transport

import com.sudo.manet.protocol.NodeId
import com.sudo.manet.protocol.Packet

interface TransportAdapter {
    // Send a packet to a direct neighbor (one hop only)
    fun sendPacket(toNeighbor: NodeId, packet: Packet)

    // Returns current known neighbors
    fun getNeighbors(): List<NodeId>

    // Call this when a new peer is discovered
    fun onPeerDiscovered(peerId: NodeId)

    // Call this when a peer goes away
    fun onPeerLost(peerId: NodeId)

    // Deliver an incoming packet to the engine
    fun onPacketReceived(packet: Packet, fromPeer: NodeId)
}