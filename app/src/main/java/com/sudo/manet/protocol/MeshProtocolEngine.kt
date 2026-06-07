package com.sudo.manet.protocol

import android.util.Log
import com.sudo.manet.routing.AodvRouter
import com.sudo.manet.routing.GossipRouter
import com.sudo.manet.routing.LinkStateRouter
import com.sudo.manet.routing.Router
import com.sudo.manet.storage.NodeIdentity
import com.sudo.manet.storage.PacketCache
import com.sudo.manet.storage.db.PacketCacheDao
import com.sudo.manet.storage.db.RouteDao
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// Events the engine emits upward to the UI
sealed class EngineEvent {
    data class MessageReceived(val packet: Packet) : EngineEvent()
    data class RouteFound(val destId: NodeId, val nextHop: NodeId) : EngineEvent()
    data class RouteDiscoveryStarted(val destId: NodeId) : EngineEvent()
    data class AckReceived(val packetId: String) : EngineEvent()
    data class DuplicateDropped(val packetId: String) : EngineEvent()
    data class TtlExpired(val packetId: String) : EngineEvent()
    data class RouteFailed(val destId: NodeId) : EngineEvent()
    data class PacketRelayed(val type: String, val sender: NodeId, val dest: NodeId) : EngineEvent()
    data class PacketDropped(val reason: String, val from: NodeId) : EngineEvent()
}

class MeshProtocolEngine(
    private val sendPacket: (toNeighbor: NodeId, packet: Packet) -> Unit,
    val getNeighbors: () -> List<NodeId>,
    maxGossipFanout: Int = 7,
    private val defaultTtl: Int = 8,
    nodeId: NodeId? = null,   // injectable for tests
    packetCacheDao: PacketCacheDao? = null,
    routeDao: RouteDao? = null
) {
    val localId: NodeId = nodeId ?: NodeIdentity.localNodeId
    private val packetCache = PacketCache(maxSize = 200, dao = packetCacheDao)
    
    // ── Demo Simulation ──────────────────────────────────────────────────
    private val _blockedNodes = MutableStateFlow<Set<NodeId>>(emptySet())
    val blockedNodes: StateFlow<Set<NodeId>> = _blockedNodes.asStateFlow()

    fun toggleBlockNode(nodeId: NodeId) {
        val current = _blockedNodes.value
        if (current.contains(nodeId)) {
            _blockedNodes.value = current - nodeId
        } else {
            _blockedNodes.value = current + nodeId
        }
        // Force a topology sync to tell others our links changed
        syncTopology()
    }

    private fun getFilteredNeighbors(): List<NodeId> {
        val all = getNeighbors()
        val blocked = _blockedNodes.value
        return all.filter { !blocked.contains(it) }
    }

    // ── Metrics (exposed to dashboard) ──────────────────────────────────────
    private val _events = MutableStateFlow<EngineEvent?>(null)
    val events: StateFlow<EngineEvent?> = _events.asStateFlow()

    val topology: StateFlow<Map<NodeId, Set<NodeId>>> by lazy { linkStateRouter.topologyFlow }

    var totalDelivered = 0; private set
    var totalExpired = 0;   private set
    var totalDuplicates = 0; private set
    var totalTransmissions = 0; private set
    var totalHops = 0;      private set

    // ── Routers ─────────────────────────────────────────────────────────────
    
    private val gossipRouter = GossipRouter(
        localId = localId,
        transmit = ::transmit,
        getNeighbors = ::getFilteredNeighbors,
        onMessageReceived = { packet ->
            _events.value = EngineEvent.MessageReceived(packet)
            totalDelivered++
            totalHops += packet.hopCount
        },
        maxGossipFanout = maxGossipFanout,
        defaultTtl = defaultTtl
    )

    private val aodvRouter = AodvRouter(
        localId = localId,
        transmit = ::transmit,
        getNeighbors = ::getFilteredNeighbors,
        onMessageReceived = { packet ->
            _events.value = EngineEvent.MessageReceived(packet)
            totalDelivered++
            totalHops += packet.hopCount
        },
        onAckReceived = { packetId ->
            _events.value = EngineEvent.AckReceived(packetId)
            totalDelivered++
        },
        onRouteDiscoveryStarted = { destId ->
            _events.value = EngineEvent.RouteDiscoveryStarted(destId)
        },
        onRouteFound = { destId, nextHop ->
            _events.value = EngineEvent.RouteFound(destId, nextHop)
        },
        onRouteFailed = { destId ->
            _events.value = EngineEvent.RouteFailed(destId)
        },
        routeDao = routeDao,
        defaultTtl = defaultTtl
    )

    private val linkStateRouter = LinkStateRouter(
        localId = localId,
        transmit = ::transmit,
        getNeighbors = ::getFilteredNeighbors,
        onMessageReceived = { packet ->
            _events.value = EngineEvent.MessageReceived(packet)
            totalDelivered++
            totalHops += packet.hopCount
        },
        defaultTtl = defaultTtl
    )

    private val routers = listOf<Router>(gossipRouter, aodvRouter, linkStateRouter)

    // ── Public API ───────────────────────────────────────────────────────────

    fun sendGossip(message: String) {
        val packet = Packet(
            type = PacketType.MSG_GOSSIP,
            senderId = localId,
            destId = BROADCAST_ADDRESS,
            ttl = defaultTtl,
            payload = message
        )
        packetCache.isNew(packet.packetId) // Mark as seen so we don't process echoes
        gossipRouter.sendMessage(BROADCAST_ADDRESS, message)
    }

    fun sendDirect(destId: NodeId, message: String, useLinkState: Boolean = false) {
        // We'd ideally pre-generate the packet ID here too to mark it as seen,
        // but AodvRouter generates its own. For now, Gossip is the main concern for loops.
        if (useLinkState) {
            linkStateRouter.sendMessage(destId, message)
        } else {
            aodvRouter.sendMessage(destId, message)
        }
    }

    /**
     * Call this to synchronize link states across the network
     */
    fun syncTopology() {
        linkStateRouter.broadcastLocalLinkState()
    }

    fun forceFullSync() {
        packetCache.clear() // Allow re-processing of LSAs
        syncTopology()
    }

    fun resetMeshState() {
        packetCache.clear()
        linkStateRouter.clearTopology()
        totalDelivered = 0
        totalTransmissions = 0
        totalDuplicates = 0
        totalExpired = 0
        totalHops = 0
    }

    fun receive(packet: Packet, fromNeighbor: NodeId) {
        // ── Step 0: simulation block check ──────────────────────────────────
        if (_blockedNodes.value.contains(fromNeighbor)) {
            Log.d("MeshEngine", "Dropped packet from blocked neighbor: $fromNeighbor")
            _events.value = EngineEvent.PacketDropped("Blocked Node", fromNeighbor)
            return
        }

        // ── Step 1: duplicate check ──────────────────────────────────────────
        if (!packetCache.isNew(packet.packetId)) {
            totalDuplicates++
            _events.value = EngineEvent.DuplicateDropped(packet.packetId)
            return
        }

        // ── Step 2: TTL check ────────────────────────────────────────────────
        if (packet.ttl <= 0 && packet.type != PacketType.RERR) { // RERR usually has low TTL but shouldn't be dropped if 1
            totalExpired++
            _events.value = EngineEvent.TtlExpired(packet.packetId)
            return
        }

        // ── Step 3: delegate to routers ─────────────────────────────────────
        for (router in routers) {
            if (router.handlePacket(packet, fromNeighbor)) {
                break
            }
        }
        
    }

    fun onNeighborLost(neighborId: NodeId) {
        routers.forEach { it.onNeighborLost(neighborId) }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun transmit(toNeighbor: NodeId, packet: Packet) {
        totalTransmissions++
        // If we are not the original sender, this is a relay
        if (packet.senderId != localId) {
            _events.value = EngineEvent.PacketRelayed(
                type = packet.type.name,
                sender = packet.senderId,
                dest = packet.destId
            )
        }
        // We append our ID to the path whenever we send/forward a packet
        sendPacket(toNeighbor, packet.withHop(localId))
    }

    private fun UUID_LIKE_ID() = java.util.UUID.randomUUID().toString()

    fun getMetrics() = mapOf(
        "delivered"     to totalDelivered,
        "expired"       to totalExpired,
        "duplicates"    to totalDuplicates,
        "transmissions" to totalTransmissions,
        "avgHops"       to if (totalDelivered > 0) totalHops / totalDelivered else 0
    )
}
