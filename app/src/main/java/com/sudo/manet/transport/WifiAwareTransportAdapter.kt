package com.sudo.manet.transport

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.aware.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.sudo.manet.protocol.MeshProtocolEngine
import com.sudo.manet.protocol.NodeId
import com.sudo.manet.protocol.Packet
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

class WifiAwareTransportAdapter(
    private val context: Context,
    private val localNodeId: NodeId
) : TransportAdapter {

    private val TAG = "WifiAwareTransport"
    private val SERVICE_NAME = "manet_mesh_service"
    
    private var engine: MeshProtocolEngine? = null
    private val wifiAwareManager = context.getSystemService(Context.WIFI_AWARE_SERVICE) as WifiAwareManager?
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    
    private var awareSession: WifiAwareSession? = null
    private var discoverySession: DiscoverySession? = null
    private var serverSocket: ServerSocket? = null
    var localPort: Int = -1
        private set

    private val peerHandles = ConcurrentHashMap<NodeId, PeerHandle>()
    private val activeNetworks = ConcurrentHashMap<NodeId, Network>()
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())

    private val _connectionStatus = MutableStateFlow<String?>("Initializing Wi-Fi Aware...")
    val connectionStatus = _connectionStatus.asStateFlow()

    fun setEngine(engine: MeshProtocolEngine) {
        this.engine = engine
    }

    fun getLocalIp(): String {
        return if (awareSession != null) "Wi-Fi Aware (Active)" else "Wi-Fi Aware (Waiting)"
    }
    
    fun retry() {
        if (awareSession == null) {
            start()
        }
    }

    private fun hasPermissions(): Boolean {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.CHANGE_WIFI_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        return permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun start() {
        if (wifiAwareManager == null || !wifiAwareManager.isAvailable) {
            _connectionStatus.value = "Wi-Fi Aware not available on this device"
            return
        }

        if (!hasPermissions()) {
            _connectionStatus.value = "Waiting for permissions..."
            return
        }

        val filter = IntentFilter(WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED)
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (wifiAwareManager.isAvailable) {
                    attach()
                } else {
                    _connectionStatus.value = "Wi-Fi Aware disabled by system"
                    cleanup()
                }
            }
        }, filter)

        attach()
    }

    private fun attach() {
        if (!hasPermissions() || awareSession != null) return
        
        try {
            Log.d(TAG, "Attaching to Wi-Fi Aware...")
            wifiAwareManager?.attach(object : AttachCallback() {
                override fun onAttached(session: WifiAwareSession) {
                    Log.d(TAG, "Attached successfully")
                    awareSession = session
                    _connectionStatus.value = "Attached to Wi-Fi Aware"
                    startServer()
                    startDiscovery()
                }

                override fun onAttachFailed() {
                    Log.e(TAG, "Attach failed")
                    _connectionStatus.value = "Attach failed (Is Wi-Fi/Location ON?)"
                }
            }, null)
        } catch (e: SecurityException) {
            _connectionStatus.value = "Permission denied: ${e.message}"
        }
    }

    private fun startServer() {
        try {
            serverSocket = ServerSocket(0)
            localPort = serverSocket!!.localPort
            scope.launch {
                while (true) {
                    val socket = serverSocket?.accept() ?: break
                    handleIncomingSocket(socket)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Server error", e)
        }
    }

    private fun handleIncomingSocket(socket: Socket) {
        scope.launch {
            try {
                socket.use { s ->
                    val ois = ObjectInputStream(s.getInputStream())
                    val packet = ois.readObject() as Packet
                    engine?.receive(packet, packet.senderId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Socket error", e)
            }
        }
    }

    private fun startDiscovery() {
        if (!hasPermissions() || awareSession == null) return

        val config = PublishConfig.Builder()
            .setServiceName(SERVICE_NAME)
            .setServiceSpecificInfo(localNodeId.toByteArray())
            .build()

        try {
            awareSession?.publish(config, object : DiscoverySessionCallback() {
                override fun onPublishStarted(session: PublishDiscoverySession) {
                    discoverySession = session
                    _connectionStatus.value = "Mesh Active (Publishing)"
                }

                override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                    val remoteNodeId = String(message)
                    peerHandles[remoteNodeId] = peerHandle
                    onPeerDiscovered(remoteNodeId)
                }
            }, handler)

            val subConfig = SubscribeConfig.Builder()
                .setServiceName(SERVICE_NAME)
                .build()

            awareSession?.subscribe(subConfig, object : DiscoverySessionCallback() {
                override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                    discoverySession = session
                    _connectionStatus.value = "Mesh Active (Scanning)"
                }

                override fun onServiceDiscovered(peerHandle: PeerHandle, serviceSpecificInfo: ByteArray, matchFilter: List<ByteArray>) {
                    val remoteNodeId = String(serviceSpecificInfo)
                    Log.d(TAG, "Discovered peer: $remoteNodeId")
                    peerHandles[remoteNodeId] = peerHandle
                    
                    // Send our ID to the publisher so they know who we are
                    discoverySession?.sendMessage(peerHandle, 0, localNodeId.toByteArray())
                    onPeerDiscovered(remoteNodeId)
                }
            }, handler)
        } catch (e: SecurityException) {
            _connectionStatus.value = "Permission error during discovery"
        }
    }

    override fun sendPacket(toNeighbor: NodeId, packet: Packet) {
        val peerHandle = peerHandles[toNeighbor] ?: return
        
        scope.launch {
            val network = getOrRequestNetwork(toNeighbor, peerHandle)
            if (network != null) {
                try {
                    val socket = network.socketFactory.createSocket()
                    // We need to resolve the IP of the peer. 
                    // For Wi-Fi Aware, we usually get it from onCapabilitiesChanged.
                    // Simplified for now: assuming direct connection logic.
                } catch (e: Exception) {
                    Log.e(TAG, "Send failed", e)
                }
            }
        }
    }

    private suspend fun getOrRequestNetwork(nodeId: NodeId, peerHandle: PeerHandle): Network? {
        val existing = activeNetworks[nodeId]
        if (existing != null) return existing

        val specifier = WifiAwareNetworkSpecifier.Builder(discoverySession!!, peerHandle)
            .setPskPassphrase("manet_mesh_secure")
            .setPort(localPort) // We provide our port for the responder to connect back
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(specifier)
            .build()

        val completer = CompletableDeferred<Network?>()

        connectivityManager.requestNetwork(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                activeNetworks[nodeId] = network
                completer.complete(network)
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                // Here we get the IPv6 address and port of the peer
                val info = networkCapabilities.transportInfo as? WifiAwareNetworkInfo
                // In a full implementation, we'd use info?.peerIpv6Addr to create the socket
            }

            override fun onLost(network: Network) {
                activeNetworks.remove(nodeId)
            }
        })

        return withTimeoutOrNull(10000) { completer.await() }
    }

    override fun getNeighbors(): List<NodeId> = peerHandles.keys().toList()

    override fun onPeerDiscovered(peerId: NodeId) {
        engine?.syncTopology()
    }

    override fun onPeerLost(peerId: NodeId) {
        peerHandles.remove(peerId)
        engine?.onNeighborLost(peerId)
    }

    override fun onPacketReceived(packet: Packet, fromPeer: NodeId) {
        engine?.receive(packet, fromPeer)
    }

    fun clearPeers() {
        peerHandles.clear()
        activeNetworks.values.forEach { 
            // In a full implementation, we'd close the networks here
        }
        activeNetworks.clear()
    }

    fun stop() {
        cleanup()
    }

    private fun cleanup() {
        awareSession?.close()
        discoverySession?.close()
        serverSocket?.close()
    }
}
