package com.sudo.manet

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import com.sudo.manet.ui.navigation.Screen
import com.sudo.manet.ui.screens.*
import com.sudo.manet.ui.theme.AndroidMANETTheme
import com.sudo.manet.ui.viewmodels.MainViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AndroidMANETTheme {
                MainAppShell()
            }
        }
    }
}

@Composable
fun MainAppShell() {
    val navController = rememberNavController()
    val viewModel: MainViewModel = viewModel()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    Scaffold(
        bottomBar = {
            // Hide bottom bar when in a specific chat window
            val isChatScreen = currentDestination?.route?.contains("chat") == true
            if (!isChatScreen) {
                NavigationBar {
                    Screen.items.forEach { screen ->
                        NavigationBarItem(
                            icon = { Icon(screen.icon, contentDescription = screen.title) },
                            label = { Text(screen.title) },
                            selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Broadcast.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Broadcast.route) {
                BroadcastScreen(viewModel)
            }
            composable(Screen.DirectMessages.route) {
                DirectMessagesScreen(viewModel) { peerId ->
                    navController.navigate("chat/$peerId")
                }
            }
            composable(Screen.Dashboard.route) {
                DashboardScreen(viewModel)
            }
            composable("chat/{peerId}") { backStackEntry ->
                val peerId = backStackEntry.arguments?.getString("peerId") ?: return@composable
                ChatScreen(peerId, viewModel, onBack = { navController.popBackStack() })
            }
        }
    }
}
