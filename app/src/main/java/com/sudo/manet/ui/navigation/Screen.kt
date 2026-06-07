package com.sudo.manet.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Public
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Broadcast : Screen("broadcast", "Broadcast", Icons.Default.Public)
    object DirectMessages : Screen("direct_messages", "Messages", Icons.Default.Chat)
    object Dashboard : Screen("dashboard", "Dashboard", Icons.Default.Dns)

    companion object {
        val items = listOf(Broadcast, DirectMessages, Dashboard)
    }
}
