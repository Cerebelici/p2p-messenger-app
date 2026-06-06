package com.sudo.manet

import com.sudo.manet.protocol.*
import com.sudo.manet.storage.NodeIdentity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MeshProtocolEngineTest {

    // Fake transport — records everything the engine tries to send
    private val sentPackets = mutableListOf<Pair<NodeId, Packet>>()
    private val neighbors = mutableListOf<NodeId>()

    private lateinit var engine: MeshProtocolEngine

    @Before
    fun setup() {
        sentPackets.clear()
        neighbors.clear()
        engine = MeshProtocolEngine(
            sendPacket = { to, packet -> sentPackets.add(Pair(to, packet)) },
            getNeighbors = { neighbors.toList() }
        )
    }

    // ── Test 1: duplicate suppression ────────────────────────────────────────
    @Test
    fun `duplicate packet is dropped`() {
        neighbors.add("NODE_B")

        val packet = Packet(
            type = PacketType.MSG_GOSSIP,
            senderId = "NODE_B",
            destId = BROADCAST_ADDRESS,
            ttl = 4,
            payload = "help"
        )

        engine.receive(packet, "NODE_B")
        val transmissionsAfterFirst = engine.totalTransmissions

        // Send exact same packet again
        engine.receive(packet, "NODE_B")

        // Transmissions should not increase — duplicate was dropped
        assertEquals(transmissionsAfterFirst, engine.totalTransmissions)
        assertEquals(1, engine.totalDuplicates)
    }

    // ── Test 2: TTL expiry ───────────────────────────────────────────────────
    @Test
    fun `packet with ttl zero is expired and not forwarded`() {
        neighbors.add("NODE_B")

        val packet = Packet(
            type = PacketType.MSG_GOSSIP,
            senderId = "NODE_B",
            destId = BROADCAST_ADDRESS,
            ttl = 0,   // already dead
            payload = "help"
        )

        engine.receive(packet, "NODE_B")

        assertEquals(0, engine.totalTransmissions)
        assertEquals(1, engine.totalExpired)
    }

    // ── Test 3: gossip propagation ───────────────────────────────────────────
    @Test
    fun `gossip packet is forwarded to all neighbors`() {
        neighbors.addAll(listOf("NODE_B", "NODE_C", "NODE_D"))

        val packet = Packet(
            type = PacketType.MSG_GOSSIP,
            senderId = "NODE_X",
            destId = BROADCAST_ADDRESS,
            ttl = 4,
            payload = "safe zone at north exit"
        )

        engine.receive(packet, "NODE_X")

        // Should have forwarded to all 3 neighbors
        assertEquals(3, sentPackets.size)

        // TTL should be decremented on each forwarded packet
        sentPackets.forEach { (_, forwarded) ->
            assertEquals(3, forwarded.ttl)
        }
    }

    // ── Test 4: direct message to self is delivered ──────────────────────────
    @Test
    fun `direct message addressed to local node is delivered`() {
        val localId = NodeIdentity.localNodeId
        neighbors.add("NODE_B")

        val packet = Packet(
            type = PacketType.MSG_DIRECT,
            senderId = "NODE_B",
            destId = localId,
            ttl = 4,
            payload = "meet at south gate"
        )

        engine.receive(packet, "NODE_B")

        // Engine should have sent an ACK back
        val ack = sentPackets.firstOrNull { it.second.type == PacketType.ACK }
        assertNotNull("Expected an ACK to be sent", ack)
        assertEquals(1, engine.totalDelivered)
    }

    // ── Test 5: RREQ is flooded to neighbors ─────────────────────────────────
    @Test
    fun `rreq is flooded to all neighbors except sender`() {
        neighbors.addAll(listOf("NODE_B", "NODE_C", "NODE_D"))

        val rreq = Packet(
            type = PacketType.RREQ,
            senderId = "NODE_A",
            destId = "NODE_Z",
            ttl = 4,
            payload = ""
        )

        engine.receive(rreq, "NODE_B") // arrives from NODE_B

        // Should flood to NODE_C and NODE_D but NOT back to NODE_B
        val destinations = sentPackets.map { it.first }
        assertFalse("Should not relay back to sender", destinations.contains("NODE_B"))
        assertTrue(destinations.contains("NODE_C"))
        assertTrue(destinations.contains("NODE_D"))
    }

    // ── Test 6: neighbor lost invalidates route ───────────────────────────────
    @Test
    fun `losing a neighbor invalidates routes through it`() {
        neighbors.addAll(listOf("NODE_B", "NODE_C"))

        // Simulate receiving a RREP that creates a route through NODE_B
        val rrep = Packet(
            type = PacketType.RREP,
            senderId = "NODE_Z",
            destId = NodeIdentity.localNodeId,
            ttl = 4,
            payload = "some-rreq-id",
            hopCount = 2
        )
        engine.receive(rrep, "NODE_B")

        // Now NODE_B disappears
        engine.onNeighborLost("NODE_B")

        // Try to send direct to NODE_Z — should trigger new discovery
        sentPackets.clear()
        engine.sendDirect("NODE_Z", "are you there?")

        val hasRreq = sentPackets.any { it.second.type == PacketType.RREQ }
        assertTrue("Should start new route discovery after neighbor lost", hasRreq)
    }

    // ── Test 7: metrics are tracked correctly ────────────────────────────────
    @Test
    fun `metrics reflect engine activity`() {
        neighbors.add("NODE_B")

        // Send one gossip
        engine.sendGossip("evacuation route open")

        // Send a duplicate to trigger duplicate counter
        val packet = Packet(
            type = PacketType.MSG_GOSSIP,
            senderId = "NODE_X",
            destId = BROADCAST_ADDRESS,
            ttl = 4,
            payload = "test"
        )
        engine.receive(packet, "NODE_X")
        engine.receive(packet, "NODE_X") // duplicate

        val metrics = engine.getMetrics()
        assertTrue((metrics["transmissions"] as Int) > 0)
        assertEquals(1, metrics["duplicates"])
    }
}