package com.sudo.manet.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sudo.manet.ui.viewmodels.MainViewModel

@Composable
fun DirectMessagesScreen(viewModel: MainViewModel, onPeerClick: (String) -> Unit) {
    val peers by viewModel.peers.collectAsState()

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(peers) { peer ->
            ListItem(
                headlineContent = { Text(peer.alias ?: "Unknown Node") },
                supportingContent = { 
                    Text(
                        text = "Hash: ${peer.nodeId.take(12)}...", 
                        style = MaterialTheme.typography.bodySmall 
                    ) 
                },
                leadingContent = {
                    Icon(Icons.Default.Person, contentDescription = null)
                },
                trailingContent = {
                    if (peer.isDirectNeighbor) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = "Neighbor",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                },
                modifier = Modifier.clickable { onPeerClick(peer.nodeId) }
            )
            HorizontalDivider()
        }
    }
}
