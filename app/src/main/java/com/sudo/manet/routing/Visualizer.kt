package com.sudo.manet.routing

import com.sudo.manet.protocol.NodeId
import com.sudo.manet.transport.SimulationTransport

object Visualizer {
    
    /**
     * Prints a text-based representation of the network topology and shortest paths.
     */
    fun visualizeTopology(transport: SimulationTransport) {
        val topology = transport.getTopology()
        println("\n=== MANET NETWORK TOPOLOGY ===")
        
        if (topology.isEmpty()) {
            println("Network is empty.")
            return
        }

        topology.forEach { (node, neighbors) ->
            val neighborStr = if (neighbors.isEmpty()) "None" else neighbors.joinToString(", ")
            println("Node [$node] -> Neighbors: $neighborStr")
        }
        
        println("==============================\n")
    }

    /**
     * Visualizes the path taken by a packet using ASCII arrows.
     */
    fun visualizePath(path: List<NodeId>) {
        if (path.isEmpty()) return
        println("\n>>> ROUTING PATH: " + path.joinToString(" -> ") + " <<<\n")
    }
}
