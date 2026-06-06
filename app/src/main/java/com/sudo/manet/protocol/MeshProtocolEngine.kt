package com.sudo.manet.protocol

import com.sudo.manet.storage.NodeIdentity
import com.sudo.manet.storage.PacketCache
import com.sudo.manet.storage.db.PacketCacheDao
import com.sudo.manet.storage.db.RouteDao
import com.sudo.manet.storage.db.RouteEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

// Simple routing table entry
data class RouteEntry(
    val nextHop: NodeId,
    val hopCount: Int,
    val createdAt: Long = System.currentTimeMillis()
) {
    // Route expires after 30 seconds — stale routes cause delivery failures
    fun isExpired(): Boolean = System.currentTimeMillis() - createdAt > 30_000
}

class MeshProtocolEngine(
    private val sendPacket: (toNeighbor: NodeId, packet: Packet) -> Unit,
    private val getNeighbors: () -> List<NodeId>,
    private val maxGossipFanout: Int = 7,
    private val defaultTtl: Int = 8,
    private val nodeId: NodeId? = null,   // injectable for tests
    private val packetCacheDao: PacketCacheDao? = null,
    private val routeDao: RouteDao? = null
) {
    private val localId: NodeId = nodeId ?: NodeIdentity.localNodeId
    private val packetCache = PacketCache(maxSize = 200, dao = packetCacheDao)
    private val routingTable = mutableMapOf<NodeId, RouteEntry>()
    private val pendingMessages = mutableMapOf<String, Packet>()
    private val scope = CoroutineScope(Dispatchers.IO)

    init {
        routeDao?.let { dao ->
            scope.launch {
                dao.getAll().forEach { entity ->
                    val entry = RouteEntry(entity.nextHop, entity.hopCount, entity.createdAt)
                    if (!entry.isExpired()) {
                        synchronized(routingTable) {
                            routingTable[entity.destId] = entry
                        }
                    } else {
                        dao.delete(entity)
                    }
                }
            }
        }
    }

    // Reverse path table for AODV — needed to route RREP back to requester
    private val reversePath = mutableMapOf<String, NodeId>()

    // ── Metrics (exposed to dashboard) ──────────────────────────────────────
    private val _events = MutableStateFlow<EngineEvent?>(null)
    val events: StateFlow<EngineEvent?> = _events.asStateFlow()

    var totalDelivered = 0; private set
    var totalExpired = 0;   private set
    var totalDuplicates = 0; private set
    var totalTransmissions = 0; private set
    var totalHops = 0;      private set

    // ── Public API ───────────────────────────────────────────────────────────

    // Called by UI to send a broadcast emergency message
    fun sendGossip(message: String) {
        val packet = Packet(
            type = PacketType.MSG_GOSSIP,
            senderId = localId,
            destId = BROADCAST_ADDRESS,
            ttl = defaultTtl,
            payload = message
        )
        packetCache.isNew(packet.packetId) // mark as seen so we don't relay our own
        forwardGossip(packet)
    }

    // Called by UI to send a direct message to a known NodeId
    fun sendDirect(destId: NodeId, message: String) {
        val packet = Packet(
            type = PacketType.MSG_DIRECT,
            senderId = localId,
            destId = destId,
            ttl = defaultTtl,
            payload = message
        )
        pendingMessages[packet.packetId] = packet

        val route = synchronized(routingTable) { routingTable[destId] }
        if (route != null && !route.isExpired()) {
            // Route exists — send directly
            transmit(route.nextHop, packet)
        } else {
            // No route — start AODV discovery
            _events.value = EngineEvent.RouteDiscoveryStarted(destId)
            startRouteDiscovery(destId, packet.packetId)
        }
    }

    // Called by TransportAdapter when a packet arrives from a neighbor
    fun receive(packet: Packet, fromNeighbor: NodeId) {

        // ── Step 1: duplicate check ──────────────────────────────────────────
        if (!packetCache.isNew(packet.packetId)) {
            totalDuplicates++
            _events.value = EngineEvent.DuplicateDropped(packet.packetId)
            return
        }

        // ── Step 2: TTL check ────────────────────────────────────────────────
        if (packet.ttl <= 0) {
            totalExpired++
            _events.value = EngineEvent.TtlExpired(packet.packetId)
            return
        }

        // ── Step 3: dispatch by type ─────────────────────────────────────────
        when (packet.type) {
            PacketType.MSG_GOSSIP  -> handleGossip(packet)
            PacketType.MSG_DIRECT  -> handleDirect(packet, fromNeighbor)
            PacketType.RREQ        -> handleRreq(packet, fromNeighbor)
            PacketType.RREP        -> handleRrep(packet, fromNeighbor)
            PacketType.ACK         -> handleAck(packet)
        }
    }

    // ── Private handlers ─────────────────────────────────────────────────────

    private fun handleGossip(packet: Packet) {
        // Deliver to local user
        _events.value = EngineEvent.MessageReceived(packet)
        // Forward to neighbors (with TTL decremented)
        forwardGossip(packet)
    }

    private fun handleDirect(packet: Packet, fromNeighbor: NodeId) {
        if (packet.destId == localId) {
            // We are the destination — deliver and send ACK
            totalDelivered++
            totalHops += packet.hopCount
            _events.value = EngineEvent.MessageReceived(packet)
            sendAck(packet, fromNeighbor)
        } else {
            // We are a relay — forward if we have a route
            val route = synchronized(routingTable) { routingTable[packet.destId] }
            if (route != null && !route.isExpired()) {
                transmit(route.nextHop, packet.withTtl(packet.ttl - 1).withHop())
            } else {
                // No route in table — check if destination is a direct neighbor
                val neighbors = getNeighbors()
                if (neighbors.contains(packet.destId)) {
                    transmit(packet.destId, packet.withTtl(packet.ttl - 1).withHop())
                }
                // Otherwise drop — sender will timeout
            }
        }
    }

    private fun handleRreq(packet: Packet, fromNeighbor: NodeId) {
        // Save reverse path so RREP can find its way back
        reversePath[packet.packetId] = fromNeighbor

        if (packet.destId == localId) {
            // We are the destination — send RREP back
            val rrep = Packet(
                type = PacketType.RREP,
                senderId = localId,
                destId = packet.senderId,
                ttl = defaultTtl,
                payload = packet.packetId, // carries original RREQ id
                hopCount = packet.hopCount
            )
            transmit(fromNeighbor, rrep)
        } else {
            // Relay RREQ outward (flood)
            val relayed = packet.withTtl(packet.ttl - 1).withHop()
            getNeighbors()
                .filter { it != fromNeighbor }
                .forEach { neighbor -> transmit(neighbor, relayed) }
        }
    }

    private fun handleRrep(packet: Packet, fromNeighbor: NodeId) {
        // Learn the route: destination is reachable via fromNeighbor
        val entry = RouteEntry(
            nextHop = fromNeighbor,
            hopCount = packet.hopCount
        )
        synchronized(routingTable) {
            routingTable[packet.senderId] = entry
        }
        routeDao?.let { dao ->
            scope.launch {
                dao.insert(RouteEntity(packet.senderId, entry.nextHop, entry.hopCount, entry.createdAt))
            }
        }
        _events.value = EngineEvent.RouteFound(packet.senderId, fromNeighbor)

        if (packet.destId == localId) {
            // We initiated the discovery — send any pending messages now
            pendingMessages.values
                .filter { it.destId == packet.senderId }
                .forEach { pending -> transmit(fromNeighbor, pending) }
        } else {
            // Relay RREP back toward the original requester
            // Also store forward route: the requester is reachable via the direction
            // we're relaying toward (back through reversePath)
            val prev = reversePath[packet.payload]
            if (prev != null) {
                // B learns: packet.destId (original sender A) is reachable via prev
                val forwardEntry = RouteEntry(
                    nextHop = prev,
                    hopCount = packet.hopCount + 1
                )
                synchronized(routingTable) {
                    routingTable[packet.destId] = forwardEntry
                }
                routeDao?.let { dao ->
                    scope.launch {
                        dao.insert(RouteEntity(packet.destId, forwardEntry.nextHop, forwardEntry.hopCount, forwardEntry.createdAt))
                    }
                }
                transmit(prev, packet.withTtl(packet.ttl - 1))
            }
        }
    }

    private fun handleAck(packet: Packet) {
        val original = pendingMessages.remove(packet.payload)
        if (original != null) {
            totalDelivered++
            _events.value = EngineEvent.AckReceived(packet.payload)
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun forwardGossip(packet: Packet) {
        val relayed = packet.withTtl(packet.ttl - 1).withHop()
        getNeighbors()
            .shuffled()
            .take(maxGossipFanout)
            .forEach { neighbor -> transmit(neighbor, relayed) }
    }

    private fun startRouteDiscovery(destId: NodeId, originatingPacketId: String) {
        val rreq = Packet(
            type = PacketType.RREQ,
            senderId = localId,
            destId = destId,
            ttl = defaultTtl,
            payload = originatingPacketId
        )
        packetCache.isNew(rreq.packetId)
        getNeighbors().forEach { neighbor -> transmit(neighbor, rreq) }
    }

    private fun sendAck(originalPacket: Packet, toNeighbor: NodeId) {
        val ack = Packet(
            type = PacketType.ACK,
            senderId = localId,
            destId = originalPacket.senderId,
            ttl = defaultTtl,
            payload = originalPacket.packetId
        )
        transmit(toNeighbor, ack)
    }

    private fun transmit(toNeighbor: NodeId, packet: Packet) {
        totalTransmissions++
        sendPacket(toNeighbor, packet)
    }

    // Called when a neighbor disappears — invalidate stale routes
    fun onNeighborLost(neighborId: NodeId) {
        synchronized(routingTable) {
            val staleDests = routingTable
                .filter { it.value.nextHop == neighborId }
                .keys
            staleDests.forEach { dest ->
                routingTable.remove(dest)
                routeDao?.let { dao ->
                    scope.launch {
                        dao.delete(RouteEntity(dest, neighborId, 0, 0)) // hopCount/createdAt don't matter for delete by PK
                    }
                }
                _events.value = EngineEvent.RouteFailed(dest)
            }
        }
    }

    fun getMetrics() = mapOf(
        "delivered"     to totalDelivered,
        "expired"       to totalExpired,
        "duplicates"    to totalDuplicates,
        "transmissions" to totalTransmissions,
        "avgHops"       to if (totalDelivered > 0) totalHops / totalDelivered else 0
    )
}