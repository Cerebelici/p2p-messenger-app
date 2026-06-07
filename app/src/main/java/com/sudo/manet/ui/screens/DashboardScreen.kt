package com.sudo.manet.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sudo.manet.ui.components.MeshGraph
import com.sudo.manet.ui.viewmodels.MainViewModel

@Composable
fun DashboardScreen(viewModel: MainViewModel) {
    val peers by viewModel.peers.collectAsState()
    val neighbors = peers.filter { it.isDirectNeighbor }
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Mesh Status & Diagnostics", style = MaterialTheme.typography.headlineSmall)

        // Topology Visualization
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    "Network Topology Graph", 
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 8.dp, top = 4.dp)
                )
                MeshGraph(peers = peers)
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Local Node Identity", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = viewModel.myNodeId,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Network Topology Statistics", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Physical Neighbors in Range: ${neighbors.size}")
                Text("Total Mesh Nodes Known: ${peers.size}")
            }
        }
        
        Text("Active Neighbor PeerHandles", style = MaterialTheme.typography.titleSmall)
        neighbors.forEach { peer ->
            ListItem(
                headlineContent = { Text(peer.alias ?: "Neighbor") },
                supportingContent = { Text(peer.nodeId.take(24) + "...") }
            )
        }
    }
}
