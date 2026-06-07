package com.sudo.manet.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sudo.manet.ui.components.MeshGraph
import com.sudo.manet.ui.viewmodels.MainViewModel

@Composable
fun DashboardScreen(viewModel: MainViewModel) {
    val peers by viewModel.peers.collectAsState()
    // Filter out ourselves and only show real neighbors
    val neighbors = peers.filter { it.isDirectNeighbor && it.nodeId != viewModel.myNodeId }
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
                val context = LocalContext.current
                Text("Local Node Identity", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = viewModel.myNodeId,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "IP Address: ${viewModel.getLocalIp()}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "Listening on port: ${viewModel.getLocalPort()}",
                    style = MaterialTheme.typography.bodySmall
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.forceMeshSync() },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text("Force Sync", style = MaterialTheme.typography.labelSmall)
                    }
                    
                    OutlinedButton(
                        onClick = { viewModel.regenerateIdentity(context) },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text("New ID", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        // Manual Connection Section
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                var ipAddress by remember { mutableStateOf("") }
                var port by remember { mutableStateOf("8888") }
                val status by viewModel.connectionStatus.collectAsState()

                Text("Manual Peer Connection", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Bridge connection to an emulator or remote device", 
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                status?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (it.contains("Failed")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                
                OutlinedTextField(
                    value = ipAddress,
                    onValueChange = { ipAddress = it },
                    label = { Text("IP Address") },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("e.g. 192.168.1.50 or 10.0.2.2") },
                    singleLine = true
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text("Port") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Button(
                    onClick = { 
                        val portInt = port.toIntOrNull() ?: 8888
                        if (ipAddress.isNotBlank()) {
                            viewModel.connectToPeer(ipAddress, portInt)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Connect to Peer")
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = "Tip: If using an emulator, use your COMPUTER'S IP on the phone.",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(8.dp),
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
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
