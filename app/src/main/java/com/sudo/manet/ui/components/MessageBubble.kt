package com.sudo.manet.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sudo.manet.models.MessageStatus
import com.sudo.manet.models.MeshPacket

@Composable
fun MessageBubble(packet: MeshPacket, isFromMe: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        horizontalAlignment = if (isFromMe) Alignment.End else Alignment.Start
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isFromMe) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
            ),
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (!isFromMe) {
                    Text(
                        text = "ID: ${packet.senderId.take(8)}...",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                Text(text = packet.payload)
                
                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Hops: ${packet.hopCount}",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    if (isFromMe) {
                        StatusIcon(status = packet.status)
                    }
                }
                
                if (packet.path.isNotEmpty()) {
                    Text(
                        text = "Path: " + packet.path.joinToString(" ➔ ") { it.take(4) },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun StatusIcon(status: MessageStatus) {
    val icon = when (status) {
        MessageStatus.PENDING -> Icons.Default.AccessTime
        MessageStatus.SENT -> Icons.Default.CheckCircle // Use same as delivered but maybe different color
        MessageStatus.BUFFERED -> Icons.Default.HourglassBottom
        MessageStatus.DELIVERED -> Icons.Default.CheckCircle
    }
    
    val tint = when (status) {
        MessageStatus.PENDING -> MaterialTheme.colorScheme.outline
        MessageStatus.SENT -> MaterialTheme.colorScheme.secondary
        MessageStatus.BUFFERED -> MaterialTheme.colorScheme.tertiary
        MessageStatus.DELIVERED -> MaterialTheme.colorScheme.primary
    }

    Icon(
        imageVector = icon,
        contentDescription = status.name,
        modifier = Modifier.size(14.dp),
        tint = tint
    )
}
