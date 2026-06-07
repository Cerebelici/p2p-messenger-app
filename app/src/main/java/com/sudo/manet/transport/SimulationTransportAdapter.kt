package com.sudo.manet.transport

import com.sudo.manet.protocol.MeshProtocolEngine
import com.sudo.manet.protocol.NodeId
import com.sudo.manet.protocol.Packet

class SimulationTransportAdapter(
    private val localNodeId: NodeId,
    private val transport: SimulationTransport
) : TransportAdapter {

    private var engine: MeshProtocolEngine? = null

    fun setEngine(engine: MeshProtocolEngine) {
        this.engine = engine
    }

    override fun sendPacket(toNeighbor: NodeId, packet: Packet) {
        transport.deliver(localNodeId, toNeighbor, packet)
    }

    override fun getNeighbors(): List<NodeId> {
        return transport.getNeighbors(localNodeId)
    }

    override fun onPeerDiscovered(peerId: NodeId) {
        // Not used in simulation currently
    }

    override fun onPeerLost(peerId: NodeId) {
        engine?.onNeighborLost(peerId)
    }

    override fun onPacketReceived(packet: Packet, fromPeer: NodeId) {
        engine?.receive(packet, fromPeer)
    }
}
