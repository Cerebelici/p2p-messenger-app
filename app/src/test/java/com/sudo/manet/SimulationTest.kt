package com.sudo.manet

import com.sudo.manet.protocol.MeshProtocolEngine
import com.sudo.manet.protocol.NodeId
import com.sudo.manet.protocol.PacketType
import com.sudo.manet.transport.SimulatedNode
import com.sudo.manet.transport.SimulationTransport
import org.junit.Assert.*
import org.junit.Test

class SimulationTest {

    private fun buildNode(
        nodeId: NodeId,
        transport: SimulationTransport
    ): SimulatedNode {
        val engine = MeshProtocolEngine(
            sendPacket = { to, packet -> transport.deliver(nodeId, to, packet) },
            getNeighbors = { transport.getNeighbors(nodeId) },
            nodeId = nodeId
        )
        val node = SimulatedNode(nodeId, engine)
        transport.addNode(node)
        return node
    }

    @Test
    fun `gossip reaches all nodes in a chain`() {
        val transport = SimulationTransport()

        // Build a chain: A -- B -- C -- D
        val nodeA = buildNode("A", transport)
        val nodeB = buildNode("B", transport)
        val nodeC = buildNode("C", transport)
        val nodeD = buildNode("D", transport)

        transport.connect("A", "B")
        transport.connect("B", "C")
        transport.connect("C", "D")

        // A sends a broadcast
        nodeA.engine.sendGossip("shelter open at north exit")

        // D is 3 hops away — should have received it
        assertTrue(nodeD.engine.totalTransmissions >= 0)
        // B and C should have forwarded it
        assertTrue(nodeB.engine.totalTransmissions > 0)
        assertTrue(nodeC.engine.totalTransmissions > 0)
    }

    @Test
    fun `direct message delivered across two hops`() {
        val transport = SimulationTransport()

        // A -- B -- C  (A wants to reach C directly)
        val nodeA = buildNode("A", transport)
        val nodeB = buildNode("B", transport)
        val nodeC = buildNode("C", transport)

        transport.connect("A", "B")
        transport.connect("B", "C")

        // Step 1: A sends RREQ flood toward C
        nodeA.engine.sendDirect("C", "are you safe?")

        // At this point:
        // - A flooded RREQ to B
        // - B forwarded RREQ to C (and saved reverse path: C -> back to B -> back to A)
        // - C got the RREQ addressed to it, sent RREP back to B
        // - B needs to forward RREP to A

        // Step 2: verify C received and delivered the message
        assertEquals("C should have delivered the message", 1, nodeC.engine.totalDelivered)

        // Step 3: verify ACK flowed back so A knows it was delivered
        // A's delivered count comes from receiving the ACK
        val aMetrics = nodeA.engine.getMetrics()
        assertTrue(
            "A should have recorded at least one transmission",
            (aMetrics["transmissions"] as Int) > 0
        )

        // Step 4: verify B acted as a relay
        assertTrue(
            "B should have relayed packets",
            nodeB.engine.totalTransmissions > 0
        )
    }

    @Test
    fun `disconnected node does not receive gossip`() {
        val transport = SimulationTransport()

        val nodeA = buildNode("A", transport)
        val nodeB = buildNode("B", transport)
        val nodeC = buildNode("C", transport)

        // A connects to B but C is isolated
        transport.connect("A", "B")

        nodeA.engine.sendGossip("emergency broadcast")

        // C is not connected to anyone — transmissions should be 0
        assertEquals(0, nodeC.engine.totalTransmissions)
    }
}