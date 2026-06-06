package com.sudo.manet.transport

import com.sudo.manet.protocol.MeshProtocolEngine
import com.sudo.manet.protocol.NodeId
import com.sudo.manet.protocol.Packet

// Represents one node in the simulated network
class SimulatedNode(
    val nodeId: NodeId,
    val engine: MeshProtocolEngine
)

class SimulationTransport {

    private val nodes = mutableMapOf<NodeId, SimulatedNode>()

    // Adjacency list — which nodes can hear which
    private val topology = mutableMapOf<NodeId, MutableSet<NodeId>>()

    // Add a node to the simulation
    fun addNode(node: SimulatedNode) {
        nodes[node.nodeId] = node
        topology[node.nodeId] = mutableSetOf()
    }

    // Connect two nodes bidirectionally (they are in radio range)
    fun connect(idA: NodeId, idB: NodeId) {
        topology[idA]?.add(idB)
        topology[idB]?.add(idA)
    }

    // Disconnect two nodes (one moved out of range)
    fun disconnect(idA: NodeId, idB: NodeId) {
        topology[idA]?.remove(idB)
        topology[idB]?.remove(idA)
        nodes[idA]?.engine?.onNeighborLost(idB)
        nodes[idB]?.engine?.onNeighborLost(idA)
    }

    // Deliver a packet from one node to a neighbor
    fun deliver(fromId: NodeId, toId: NodeId, packet: Packet) {
        val neighbors = topology[fromId] ?: return
        if (!neighbors.contains(toId)) return // not in range, drop

        val targetNode = nodes[toId] ?: return
        targetNode.engine.receive(packet, fromId)
    }

    // Get neighbors of a node — used by the engine's getNeighbors lambda
    fun getNeighbors(nodeId: NodeId): List<NodeId> {
        return topology[nodeId]?.toList() ?: emptyList()
    }

    // Remove a node entirely (device turned off)
    fun removeNode(nodeId: NodeId) {
        val neighbors = topology[nodeId]?.toList() ?: emptyList()
        neighbors.forEach { neighbor ->
            topology[neighbor]?.remove(nodeId)
            nodes[neighbor]?.engine?.onNeighborLost(nodeId)
        }
        nodes.remove(nodeId)
        topology.remove(nodeId)
    }

    fun getNodeCount() = nodes.size
    fun getNode(nodeId: NodeId) = nodes[nodeId]
}