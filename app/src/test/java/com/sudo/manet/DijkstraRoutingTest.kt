package com.sudo.manet

import com.sudo.manet.protocol.*
import com.sudo.manet.routing.Visualizer
import com.sudo.manet.transport.SimulatedNode
import com.sudo.manet.transport.SimulationTransport
import org.junit.Assert.*
import org.junit.Test

class DijkstraRoutingTest {

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
    fun `dijkstra finds shortest path in a mesh`() {
        val transport = SimulationTransport()

        // Topology:
        // A -- B -- C
        //  \-------/
        // A is connected to B and C. 
        // B is connected to A and C.
        // C is connected to A and B.
        // Path A->C via direct link (1 hop) is shorter than A->B->C (2 hops).

        val nodeA = buildNode("A", transport)
        val nodeB = buildNode("B", transport)
        val nodeC = buildNode("C", transport)

        transport.connect("A", "B")
        transport.connect("B", "C")
        transport.connect("A", "C")

        // Step 1: Broadcast Link States so everyone knows the topology
        nodeA.engine.syncTopology()
        nodeB.engine.syncTopology()
        nodeC.engine.syncTopology()

        Visualizer.visualizeTopology(transport)

        // Step 2: A sends direct to C using Link State (Dijkstra)
        nodeA.engine.sendDirect("C", "dijkstra msg", useLinkState = true)
        
        Visualizer.visualizePath(listOf("A", "C"))

        // Verify C received it
        assertEquals(1, nodeC.engine.totalDelivered)
        
        // Check hops — should be 0 (direct from A to C) in the packet as received by C
        // Wait, MeshProtocolEngine.receive increments delivered and adds hopCount.
        // If it was direct, hopCount is 0.
        assertEquals(0, nodeC.engine.totalHops)
    }

    @Test
    fun `dijkstra routes around long paths`() {
        val transport = SimulationTransport()

        // A -- B -- C -- D
        //  \-----------/
        // Path A-D is direct (1 hop).
        // Path A-B-C-D is 3 hops.

        val nodeA = buildNode("A", transport)
        val nodeB = buildNode("B", transport)
        val nodeC = buildNode("C", transport)
        val nodeD = buildNode("D", transport)

        transport.connect("A", "B")
        transport.connect("B", "C")
        transport.connect("C", "D")
        transport.connect("A", "D")

        nodeA.engine.syncTopology()
        nodeB.engine.syncTopology()
        nodeC.engine.syncTopology()
        nodeD.engine.syncTopology()

        Visualizer.visualizeTopology(transport)

        nodeA.engine.sendDirect("D", "shortest path", useLinkState = true)

        Visualizer.visualizePath(listOf("A", "D"))

        assertEquals(1, nodeD.engine.totalDelivered)
        assertEquals(0, nodeD.engine.totalHops) // Direct
    }
}
