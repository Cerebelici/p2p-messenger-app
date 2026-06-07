package com.sudo.manet.routing

import com.sudo.manet.protocol.NodeId
import com.sudo.manet.protocol.Packet
import com.sudo.manet.protocol.PacketType
import com.sudo.manet.storage.db.RouteDao
import com.sudo.manet.storage.db.RouteEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

data class RouteEntry(
    val destId: NodeId,
    val nextHop: NodeId,
    val hopCount: Int,
    val sequenceNumber: Int,
    val createdAt: Long = System.currentTimeMillis(),
    val isInvalid: Boolean = false
) {
    fun isExpired(): Boolean = System.currentTimeMillis() - createdAt > 30_000
}

class AodvRouter(
    private val localId: NodeId,
    private val transmit: (toNeighbor: NodeId, packet: Packet) -> Unit,
    private val getNeighbors: () -> List<NodeId>,
    private val onMessageReceived: (Packet) -> Unit,
    private val onAckReceived: (String) -> Unit,
    private val onRouteDiscoveryStarted: (NodeId) -> Unit,
    private val onRouteFound: (NodeId, NodeId) -> Unit,
    private val onRouteFailed: (NodeId) -> Unit,
    private val routeDao: RouteDao? = null,
    private val defaultTtl: Int = 8
) : Router {

    private val routingTable = ConcurrentHashMap<NodeId, RouteEntry>()
    private val reversePath = ConcurrentHashMap<String, NodeId>() // rreqId -> previousHop
    private val pendingMessages = ConcurrentHashMap<String, MutableList<Packet>>() // destId -> packets
    private val scope = CoroutineScope(Dispatchers.IO)
    private var localSequenceNumber = 1

    init {
        loadRoutesFromDb()
    }

    private fun loadRoutesFromDb() {
        routeDao?.let { dao ->
            scope.launch {
                dao.getAll().forEach { entity ->
                    val entry = RouteEntry(
                        entity.destId,
                        entity.nextHop,
                        entity.hopCount,
                        entity.sequenceNumber,
                        entity.createdAt
                    )
                    if (!entry.isExpired()) {
                        routingTable[entity.destId] = entry
                    } else {
                        dao.delete(entity)
                    }
                }
            }
        }
    }

    override fun handlePacket(packet: Packet, fromNeighbor: NodeId): Boolean {
        return when (packet.type) {
            PacketType.MSG_DIRECT -> handleDirect(packet, fromNeighbor)
            PacketType.RREQ -> handleRreq(packet, fromNeighbor)
            PacketType.RREP -> handleRrep(packet, fromNeighbor)
            PacketType.RERR -> handleRerr(packet, fromNeighbor)
            PacketType.ACK -> handleAck(packet)
            else -> false
        }
    }

    override fun sendMessage(destId: NodeId, message: String) {
        val packet = Packet(
            type = PacketType.MSG_DIRECT,
            senderId = localId,
            destId = destId,
            ttl = defaultTtl,
            payload = message
        )
        sendOrDiscover(packet)
    }

    private fun sendOrDiscover(packet: Packet) {
        val route = routingTable[packet.destId]
        if (route != null && !route.isExpired() && !route.isInvalid) {
            transmit(route.nextHop, packet)
        } else {
            // Buffer packet and start discovery
            pendingMessages.getOrPut(packet.destId) { mutableListOf() }.add(packet)
            startRouteDiscovery(packet.destId)
        }
    }

    private fun handleDirect(packet: Packet, fromNeighbor: NodeId): Boolean {
        if (packet.destId == localId) {
            onMessageReceived(packet)
            sendAck(packet, fromNeighbor)
        } else {
            val route = routingTable[packet.destId]
            if (route != null && !route.isExpired() && !route.isInvalid) {
                transmit(route.nextHop, packet.withTtl(packet.ttl - 1))
            } else {
                // If no route, we could start discovery, but usually the source does it.
                // For now, just drop and maybe send RERR back.
                sendRerr(packet.destId)
            }
        }
        return true
    }

    private fun handleRreq(packet: Packet, fromNeighbor: NodeId): Boolean {
        // Update reverse route to sender (originator)
        updateRoute(
            destId = packet.senderId,
            nextHop = fromNeighbor,
            hopCount = packet.hopCount,
            sequenceNumber = packet.sequenceNumber
        )
        
        reversePath[packet.packetId] = fromNeighbor

        if (packet.destId == localId) {
            // We are the destination, reply with RREP
            localSequenceNumber++
            val rrep = Packet(
                type = PacketType.RREP,
                senderId = localId,
                destId = packet.senderId,
                ttl = defaultTtl,
                payload = packet.packetId, // carries RREQ ID
                sequenceNumber = localSequenceNumber,
                hopCount = 0
            )
            transmit(fromNeighbor, rrep)
        } else {
            // Relay RREQ
            if (packet.ttl > 0) {
                val relayed = packet.withTtl(packet.ttl - 1)
                getNeighbors().filter { it != fromNeighbor }.forEach { neighbor ->
                    transmit(neighbor, relayed)
                }
            }
        }
        return true
    }

    private fun handleRrep(packet: Packet, fromNeighbor: NodeId): Boolean {
        // Update forward route to packet.senderId (the node that generated RREP)
        updateRoute(
            destId = packet.senderId,
            nextHop = fromNeighbor,
            hopCount = packet.hopCount,
            sequenceNumber = packet.sequenceNumber
        )

        if (packet.destId == localId) {
            // We initiated this RREQ
            onRouteFound(packet.senderId, fromNeighbor)
            // Send pending messages
            pendingMessages.remove(packet.senderId)?.forEach { pending ->
                transmit(fromNeighbor, pending)
            }
        } else {
            // Relay RREP back along reverse path
            val prevHop = reversePath[packet.payload]
            if (prevHop != null && packet.ttl > 0) {
                val relayed = packet.withTtl(packet.ttl - 1)
                transmit(prevHop, relayed)
            }
        }
        return true
    }

    private fun handleRerr(packet: Packet, fromNeighbor: NodeId): Boolean {
        val unreachableDest = packet.payload
        val route = routingTable[unreachableDest]
        if (route != null && route.nextHop == fromNeighbor) {
            // Invalidate route
            routingTable[unreachableDest] = route.copy(isInvalid = true)
            onRouteFailed(unreachableDest)
            
            // Propagate RERR to neighbors
            val rerr = Packet(
                type = PacketType.RERR,
                senderId = localId,
                destId = "BROADCAST",
                ttl = 1,
                payload = unreachableDest
            )
            getNeighbors().filter { it != fromNeighbor }.forEach { neighbor ->
                transmit(neighbor, rerr)
            }
        }
        return true
    }

    private fun handleAck(packet: Packet): Boolean {
        onAckReceived(packet.payload)
        return true
    }

    override fun onNeighborLost(neighborId: NodeId) {
        val brokenRoutes = routingTable.filter { it.value.nextHop == neighborId && !it.value.isInvalid }
        brokenRoutes.forEach { (destId, entry) ->
            routingTable[destId] = entry.copy(isInvalid = true)
            onRouteFailed(destId)
            sendRerr(destId)
        }
    }

    private fun startRouteDiscovery(destId: NodeId) {
        onRouteDiscoveryStarted(destId)
        val rreq = Packet(
            type = PacketType.RREQ,
            senderId = localId,
            destId = destId,
            ttl = defaultTtl,
            sequenceNumber = localSequenceNumber
        )
        getNeighbors().forEach { neighbor ->
            transmit(neighbor, rreq)
        }
    }

    private fun sendRerr(destId: NodeId) {
        val rerr = Packet(
            type = PacketType.RERR,
            senderId = localId,
            destId = "BROADCAST",
            ttl = 1,
            payload = destId
        )
        getNeighbors().forEach { neighbor ->
            transmit(neighbor, rerr)
        }
    }

    private fun sendAck(originalPacket: Packet, toNeighbor: NodeId) {
        val ack = Packet(
            type = PacketType.ACK,
            senderId = localId,
            destId = originalPacket.senderId,
            ttl = 1,
            payload = originalPacket.packetId
        )
        transmit(toNeighbor, ack)
    }

    private fun updateRoute(destId: NodeId, nextHop: NodeId, hopCount: Int, sequenceNumber: Int) {
        val existing = routingTable[destId]
        if (existing == null || sequenceNumber > existing.sequenceNumber || 
            (sequenceNumber == existing.sequenceNumber && hopCount + 1 < existing.hopCount)) {
            
            val entry = RouteEntry(destId, nextHop, hopCount + 1, sequenceNumber)
            routingTable[destId] = entry
            
            // Persist to DB
            routeDao?.let { dao ->
                scope.launch {
                    dao.insert(RouteEntity(destId, nextHop, entry.hopCount, sequenceNumber, entry.createdAt))
                }
            }
        }
    }

}
