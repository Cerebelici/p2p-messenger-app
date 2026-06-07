package com.sudo.manet.transport

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.sudo.manet.protocol.BROADCAST_ADDRESS
import com.sudo.manet.protocol.MeshProtocolEngine
import com.sudo.manet.protocol.NodeId
import com.sudo.manet.protocol.Packet
import com.sudo.manet.protocol.PacketType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.asStateFlow
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class NsdTransportAdapter(
    private val context: Context,
    private val localNodeId: NodeId
) : TransportAdapter {

    private val TAG = "NsdTransport"
    private val SERVICE_TYPE = "_manet._tcp."
    private var engine: MeshProtocolEngine? = null

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var serverSocket: ServerSocket? = null
    private var localPort: Int = -1

    // Map of NodeId to resolved service info
    private val discoveredPeers = ConcurrentHashMap<NodeId, NsdServiceInfo>()
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false

    private val _connectionStatus = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val connectionStatus = _connectionStatus.asStateFlow()

    fun getLocalPort() = localPort

    fun getLocalIp(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress) {
                        val sAddr = addr.hostAddress
                        val isIPv4 = sAddr.indexOf(':') < 0
                        if (isIPv4) return sAddr
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting local IP", e)
        }
        return "Unknown"
    }

    fun setEngine(engine: MeshProtocolEngine) {
        this.engine = engine
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        startServer()
        registerService()
        discoverServices()
    }

    fun stop() {
        isRunning = false
        try {
            nsdManager.unregisterService(registrationListener)
            nsdManager.stopServiceDiscovery(discoveryListener)
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping NSD transport", e)
        }
        scope.cancel()
    }

    private fun startServer() {
        try {
            // Try to bind to port 8888 first for consistent adb reverse behavior
            serverSocket = try {
                ServerSocket(8888)
            } catch (e: Exception) {
                Log.d(TAG, "Port 8888 busy, falling back to random port")
                ServerSocket(0)
            }

            localPort = serverSocket!!.localPort
            Log.d(TAG, "Server started on port $localPort")
            
            scope.launch {
                try {
                    while (isRunning) {
                        val socket = serverSocket?.accept() ?: break
                        handleIncomingConnection(socket)
                    }
                } catch (e: Exception) {
                    if (isRunning) Log.e(TAG, "Accept loop error", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Server startup error", e)
        }
    }

    fun connectToManualPeer(ip: String, port: Int) {
        scope.launch {
            try {
                _connectionStatus.value = "Connecting to $ip..."
                Log.d(TAG, "Manually probing peer at $ip:$port")
                // Use a short timeout for the manual probe
                val socket = Socket()
                socket.connect(java.net.InetSocketAddress(ip, port), 5000)
                
                socket.use { s ->
                    val oos = ObjectOutputStream(s.getOutputStream())
                    val packet = Packet(
                        type = PacketType.LSA,
                        senderId = localNodeId,
                        destId = BROADCAST_ADDRESS,
                        ttl = 1,
                        payload = "" 
                    )
                    oos.writeObject(packet)
                    oos.flush()
                }
                _connectionStatus.value = "Successfully poked $ip"
                Log.d(TAG, "Manual probe sent to $ip:$port")
            } catch (e: Exception) {
                val errorMsg = e.message ?: "Unknown error"
                _connectionStatus.value = "Failed: $errorMsg"
                Log.e(TAG, "Manual probe failed: $errorMsg")
            }
        }
    }

    private fun handleIncomingConnection(socket: Socket) {
        scope.launch {
            try {
                val remoteHost = socket.inetAddress
                socket.use { s ->
                    val ois = ObjectInputStream(s.getInputStream())
                    val packet = ois.readObject() as Packet
                    val fromPeer = packet.senderId
                    
                    // NEVER learn about ourselves from a manual connection or loopback
                    if (fromPeer == localNodeId) {
                        Log.d(TAG, "Ignoring packet from self")
                        return@launch
                    }

                    // For manual/emulator connections, we might not have discovered this peer via NSD.
                    // If we don't know them, add them using the socket's address and assume their port 
                    // is 8888 if they are connecting to us (standard for this app).
                    if (!discoveredPeers.containsKey(fromPeer)) {
                        Log.d(TAG, "Learning about unknown peer $fromPeer from incoming connection at ${remoteHost.hostAddress}")
                        val info = NsdServiceInfo().apply {
                            serviceName = fromPeer
                            host = remoteHost
                            port = 8888 // Default fallback port
                        }
                        discoveredPeers[fromPeer] = info
                        onPeerDiscovered(fromPeer)
                    }

                    Log.d(TAG, "Received packet ${packet.packetId} from $fromPeer")
                    onPacketReceived(packet, fromPeer)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling incoming connection", e)
            }
        }
    }

    private fun registerService() {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = localNodeId
            serviceType = SERVICE_TYPE
            port = localPort
        }

        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    private fun discoverServices() {
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    override fun sendPacket(toNeighbor: NodeId, packet: Packet) {
        val info = discoveredPeers[toNeighbor]
        if (info == null) {
            Log.w(TAG, "Attempted to send to unknown neighbor: $toNeighbor")
            return
        }

        scope.launch {
            try {
                Socket(info.host, info.port).use { socket ->
                    val oos = ObjectOutputStream(socket.getOutputStream())
                    oos.writeObject(packet)
                    oos.flush()
                    Log.d(TAG, "Sent packet ${packet.packetId} to $toNeighbor")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending packet to $toNeighbor", e)
                // If we can't connect, assume peer is lost
                onPeerLost(toNeighbor)
            }
        }
    }

    override fun getNeighbors(): List<NodeId> {
        return discoveredPeers.keys().toList()
    }

    override fun onPeerDiscovered(peerId: NodeId) {
        Log.d(TAG, "Peer discovered: $peerId")
        // Notify engine to sync topology now that we have a new neighbor
        engine?.syncTopology()
    }

    override fun onPeerLost(peerId: NodeId) {
        Log.d(TAG, "Peer lost: $peerId")
        discoveredPeers.remove(peerId)
        engine?.onNeighborLost(peerId)
    }

    override fun onPacketReceived(packet: Packet, fromPeer: NodeId) {
        engine?.receive(packet, fromPeer)
    }

    private val registrationListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
            Log.d(TAG, "Service registered: ${serviceInfo.serviceName}")
        }
        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.e(TAG, "Registration failed: $errorCode")
        }
        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
    }

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(regType: String) {
            Log.d(TAG, "Service discovery started")
        }
        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            Log.d(TAG, "Service found: ${serviceInfo.serviceName}")
            if (serviceInfo.serviceName == localNodeId) return // Skip self

            nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.e(TAG, "Resolve failed: $errorCode")
                }
                override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                    Log.d(TAG, "Service resolved: ${resolvedInfo.serviceName} at ${resolvedInfo.host}:${resolvedInfo.port}")
                    val peerId = resolvedInfo.serviceName
                    discoveredPeers[peerId] = resolvedInfo
                    onPeerDiscovered(peerId)
                }
            })
        }
        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            Log.d(TAG, "Service lost: ${serviceInfo.serviceName}")
            onPeerLost(serviceInfo.serviceName)
        }
        override fun onDiscoveryStopped(regType: String) {}
        override fun onStartDiscoveryFailed(regType: String, errorCode: Int) {}
        override fun onStopDiscoveryFailed(regType: String, errorCode: Int) {}
    }
}
