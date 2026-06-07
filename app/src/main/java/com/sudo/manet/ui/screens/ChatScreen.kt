package com.sudo.manet.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sudo.manet.ui.components.MessageBubble
import com.sudo.manet.ui.viewmodels.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(peerId: String, viewModel: MainViewModel, onBack: () -> Unit) {
    val messages by viewModel.messages.collectAsState()
    val peers by viewModel.peers.collectAsState()
    val peer = peers.find { it.nodeId == peerId }
    
    val chatMessages = messages.filter { 
        (it.senderId == viewModel.myNodeId && it.destId == peerId) || 
        (it.senderId == peerId && it.destId == viewModel.myNodeId) 
    }
    
    var textState by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text(peer?.alias ?: "Direct Message")
                        Text(peerId.take(16) + "...", style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier
            .padding(innerPadding)
            .fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(8.dp)
            ) {
                items(chatMessages) { message ->
                    MessageBubble(
                        packet = message,
                        isFromMe = message.senderId == viewModel.myNodeId
                    )
                }
            }

            Row(
                modifier = Modifier
                    .padding(16.dp)
                    .imePadding()
                    .fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                TextField(
                    value = textState,
                    onValueChange = { textState = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Private message...") }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (textState.isNotBlank()) {
                            viewModel.sendDirectMessage(peerId, textState)
                            textState = ""
                        }
                    }
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Send")
                }
            }
        }
    }
}
