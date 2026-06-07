package com.sudo.manet.protocol

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
}

class MeshProtocolEngine(
    private val sendPacket: (toNeighbor: NodeId, packet: Packet) -> Unit,
    private val getNeighbors: () -> List<NodeId>,
    maxGossipFanout: Int = 7,
    private val defaultTtl: Int = 8,
    nodeId: NodeId? = null,   // injectable for tests
    packetCacheDao: PacketCacheDao? = null,
    routeDao: RouteDao? = null
) {
    private val localId: NodeId = nodeId ?: NodeIdentity.localNodeId
    private val packetCache = PacketCache(maxSize = 200, dao = packetCacheDao)
    
    // ── Metrics (exposed to dashboard) ──────────────────────────────────────
    private val _events = MutableStateFlow<EngineEvent?>(null)
    val events: StateFlow<EngineEvent?> = _events.asStateFlow()

    var totalDelivered = 0; private set
    var totalExpired = 0;   private set
    var totalDuplicates = 0; private set
    var totalTransmissions = 0; private set
    var totalHops = 0;      private set

    // ── Routers ─────────────────────────────────────────────────────────────
    
    private val gossipRouter = GossipRouter(
        localId = localId,
        transmit = ::transmit,
        getNeighbors = getNeighbors,
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
        getNeighbors = getNeighbors,
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
        getNeighbors = getNeighbors,
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
        packetCache.isNew(UUID_LIKE_ID()) // mark next one as seen if we were to receive it
        gossipRouter.sendMessage(BROADCAST_ADDRESS, message)
    }

    fun sendDirect(destId: NodeId, message: String, useLinkState: Boolean = false) {
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

    fun receive(packet: Packet, fromNeighbor: NodeId) {
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
        sendPacket(toNeighbor, packet)
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
