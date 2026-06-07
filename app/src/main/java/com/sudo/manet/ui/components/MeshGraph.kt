package com.sudo.manet.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sudo.manet.models.Peer
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun MeshGraph(peers: List<Peer>) {
    val textMeasurer = rememberTextMeasurer()
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val outlineColor = MaterialTheme.colorScheme.outlineVariant
    val textColor = MaterialTheme.colorScheme.onSurface

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp),
    ) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = size.minDimension / 3

        // Draw local node (Center)
        drawCircle(
            color = primaryColor,
            radius = 20.dp.toPx(),
            center = center
        )
        
        drawText(
            textMeasurer = textMeasurer,
            text = "YOU",
            topLeft = center.copy(x = center.x - 12.dp.toPx(), y = center.y - 8.dp.toPx()),
            style = TextStyle(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 10.sp)
        )

        // Calculate positions for peers
        val peerPositions = mutableMapOf<String, Offset>()
        peers.forEachIndexed { index, peer ->
            val angle = (2.0 * Math.PI * index / peers.size).toFloat()
            val x = center.x + radius * cos(angle)
            val y = center.y + radius * sin(angle)
            peerPositions[peer.nodeId] = Offset(x, y)
        }

        // Draw connections
        peers.forEach { peer ->
            val startPos = peerPositions[peer.nodeId] ?: return@forEach
            
            // Link to local node if direct neighbor
            if (peer.isDirectNeighbor) {
                drawLine(
                    color = primaryColor.copy(alpha = 0.5f),
                    start = center,
                    end = startPos,
                    strokeWidth = 2.dp.toPx()
                )
            }

            // Links between peers
            peer.connections.forEach { connectedId ->
                peerPositions[connectedId]?.let { endPos ->
                    drawLine(
                        color = outlineColor,
                        start = startPos,
                        end = endPos,
                        strokeWidth = 1.dp.toPx()
                    )
                }
            }
        }

        // Draw peer nodes
        peers.forEach { peer ->
            val pos = peerPositions[peer.nodeId] ?: return@forEach
            drawCircle(
                color = if (peer.isDirectNeighbor) primaryColor else secondaryColor,
                radius = 12.dp.toPx(),
                center = pos
            )
            
            val label = peer.alias ?: peer.nodeId.take(4)
            drawText(
                textMeasurer = textMeasurer,
                text = label,
                topLeft = pos.copy(y = pos.y + 15.dp.toPx(), x = pos.x - 15.dp.toPx()),
                style = TextStyle(color = textColor, fontSize = 10.sp)
            )
        }
    }
}
