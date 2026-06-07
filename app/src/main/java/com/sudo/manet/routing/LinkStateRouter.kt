package com.sudo.manet.routing

import com.sudo.manet.protocol.BROADCAST_ADDRESS
import com.sudo.manet.protocol.NodeId
import com.sudo.manet.protocol.Packet
import com.sudo.manet.protocol.PacketType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap

class LinkStateRouter(
    private val localId: NodeId,
    private val transmit: (toNeighbor: NodeId, packet: Packet) -> Unit,
    private val getNeighbors: () -> List<NodeId>,
    private val onMessageReceived: (Packet) -> Unit,
    private val defaultTtl: Int = 8
) : Router {

    // Topology map: NodeId -> Set of its neighbors
    private val topology = ConcurrentHashMap<NodeId, Set<NodeId>>()
    
    private val _topologyFlow = MutableStateFlow<Map<NodeId, Set<NodeId>>>(emptyMap())
    val topologyFlow: StateFlow<Map<NodeId, Set<NodeId>>> = _topologyFlow.asStateFlow()

    // Sequence numbers for LSAs to prevent processing old info
    private val lsaSequences = ConcurrentHashMap<NodeId, Int>()
    private var localLsaSequence = 1

    override fun handlePacket(packet: Packet, fromNeighbor: NodeId): Boolean {
        return when (packet.type) {
            PacketType.LSA -> handleLsa(packet, fromNeighbor)
            PacketType.MSG_DIRECT -> {
                // If AODV didn't handle it, we can try using our Dijkstra-based table
                handleDirect(packet, fromNeighbor)
            }
            else -> false
        }
    }

    override fun sendMessage(destId: NodeId, message: String) {
        val nextHop = calculateNextHop(destId)
        if (nextHop != null) {
            val packet = Packet(
                type = PacketType.MSG_DIRECT,
                senderId = localId,
                destId = destId,
                ttl = defaultTtl,
                payload = message
            )
            transmit(nextHop, packet)
        }
    }

    override fun onNeighborLost(neighborId: NodeId) {
        // When a neighbor is lost, our local link state changes
        broadcastLocalLinkState()
    }

    fun clearTopology() {
        topology.clear()
        lsaSequences.clear()
        _topologyFlow.value = emptyMap()
    }

    /**
     * Periodically or on change, broadcast who our current neighbors are.
     * The payload is a comma-separated list of neighbor IDs.
     */
    fun broadcastLocalLinkState() {
        val neighbors = getNeighbors()
        localLsaSequence++
        val lsa = Packet(
            type = PacketType.LSA,
            senderId = localId,
            destId = BROADCAST_ADDRESS,
            ttl = defaultTtl,
            sequenceNumber = localLsaSequence,
            payload = neighbors.joinToString(",")
        )
        // Update local topology view
        topology[localId] = neighbors.toSet()
        _topologyFlow.value = topology.toMap()
        // Flood LSA
        neighbors.forEach { transmit(it, lsa) }
    }

    private fun handleLsa(packet: Packet, fromNeighbor: NodeId): Boolean {
        val sender = packet.senderId
        val seq = packet.sequenceNumber
        
        val lastSeq = lsaSequences[sender]
        if (lastSeq == null || seq > lastSeq) {
            lsaSequences[sender] = seq
            val neighbors = packet.payload.split(",").filter { it.isNotEmpty() }.toSet()
            topology[sender] = neighbors
            _topologyFlow.value = topology.toMap()
            
            // Relay LSA (flooding)
            val relayed = packet.withTtl(packet.ttl - 1)
            if (relayed.ttl > 0) {
                getNeighbors().filter { it != fromNeighbor }.forEach { transmit(it, relayed) }
            }
            return true
        }
        return false
    }

    private fun handleDirect(packet: Packet, fromNeighbor: NodeId): Boolean {
        if (packet.destId == localId) {
            onMessageReceived(packet)
            return true
        }
        
        val nextHop = calculateNextHop(packet.destId)
        if (nextHop != null && packet.ttl > 1) {
            transmit(nextHop, packet.withTtl(packet.ttl - 1))
            return true
        }
        return false
    }

    /**
     * Dijkstra's Algorithm to find the shortest path to destId
     */
    private fun calculateNextHop(target: NodeId): NodeId? {
        if (target == localId) return null
        
        val distances = mutableMapOf<NodeId, Int>().withDefault { Int.MAX_VALUE }
        val previous = mutableMapOf<NodeId, NodeId?>()
        val nodes = PriorityQueue<Pair<NodeId, Int>>(compareBy { it.second })

        distances[localId] = 0
        nodes.add(localId to 0)

        val visited = mutableSetOf<NodeId>()

        while (nodes.isNotEmpty()) {
            val poll = nodes.poll() ?: break
            val (u, d) = poll
            if (u == target) break
            if (u in visited) continue
            visited.add(u)

            topology[u]?.forEach { v ->
                val alt = d + 1
                if (alt < distances.getValue(v)) {
                    distances[v] = alt
                    previous[v] = u
                    nodes.add(v to alt)
                }
            }
        }

        // Reconstruct path to find the first hop from localId
        var curr: NodeId? = target
        var prev: NodeId? = null
        
        if (previous[target] == null) return null

        while (curr != localId && curr != null) {
            prev = curr
            curr = previous[curr]
        }
        
        return prev // This is the neighbor of localId on the shortest path
    }
}
