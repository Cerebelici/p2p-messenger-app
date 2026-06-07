package com.sudo.manet.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sudo.manet.models.NetworkConstants
import com.sudo.manet.ui.components.MessageBubble
import com.sudo.manet.ui.viewmodels.MainViewModel

@Composable
fun BroadcastScreen(viewModel: MainViewModel) {
    val messages by viewModel.messages.collectAsState()
    val broadcastMessages = messages.filter { it.destId == NetworkConstants.BROADCAST_DEST }
    var textState by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(8.dp),
            reverseLayout = false // Normal order for now
        ) {
            items(broadcastMessages) { message ->
                MessageBubble(
                    packet = message,
                    isFromMe = message.senderId == viewModel.myNodeId
                )
            }
        }

        Row(
            modifier = Modifier
                .padding(16.dp)
                .navigationBarsPadding()
                .imePadding()
                .fillMaxWidth(),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            TextField(
                value = textState,
                onValueChange = { textState = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Global Emergency Alert...") }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    if (textState.isNotBlank()) {
                        viewModel.sendBroadcast(textState)
                        textState = ""
                    }
                }
            ) {
                Icon(Icons.Default.Send, contentDescription = "Send")
            }
        }
    }
}
