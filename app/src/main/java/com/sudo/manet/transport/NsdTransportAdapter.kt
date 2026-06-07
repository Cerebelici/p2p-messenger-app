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
    // Set of NodeIds that were manually connected (should not be overwritten by NSD)
    private val manualPeers = java.util.Collections.newSetFromMap(ConcurrentHashMap<NodeId, Boolean>())
    
    // Track failed send attempts per neighbor
    private val failureCounts = ConcurrentHashMap<NodeId, Int>()
    private val MAX_FAILURES = 3

    // Per-neighbor transmission queues to avoid socket collisions
    private val transmissionQueues = ConcurrentHashMap<NodeId, kotlinx.coroutines.channels.Channel<Packet>>()
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false

    private val _connectionStatus = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val connectionStatus = _connectionStatus.asStateFlow()

    fun getLocalPort() = localPort

    fun getPeerTransportInfo(nodeId: NodeId): Pair<String, Int>? {
        val info = discoveredPeers[nodeId] ?: return null
        return info.host.hostAddress to info.port
    }

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

    fun clearPeers() {
        discoveredPeers.clear()
        _connectionStatus.value = "Mesh data cleared"
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
                    // Send a HELLO packet with our listening port
                    val packet = Packet(
                        type = PacketType.HELLO,
                        senderId = localNodeId,
                        destId = BROADCAST_ADDRESS,
                        ttl = 1,
                        payload = localPort.toString()
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

                    // PROTECTION: Never learn that a peer is at 127.0.0.1. 
                    // This happens in emulators via ADB tunnels and breaks the return path.
                    if (remoteHost.isLoopbackAddress) {
                        Log.d(TAG, "Received connection via tunnel from $fromPeer, skipping IP update to preserve physical path")
                    } else if (!discoveredPeers.containsKey(fromPeer) || !manualPeers.contains(fromPeer)) {
                        Log.d(TAG, "Learning about reliable peer $fromPeer from incoming connection at ${remoteHost.hostAddress}")
                        
                        // Parse port from HELLO packet if available, else fallback to 8888
                        val remotePort = if (packet.type == PacketType.HELLO) {
                            packet.payload.toIntOrNull() ?: 8888
                        } else {
                            8888
                        }

                        val info = NsdServiceInfo().apply {
                            serviceName = fromPeer
                            host = remoteHost
                            port = remotePort
                        }
                        discoveredPeers[fromPeer] = info
                        manualPeers.add(fromPeer)
                        onPeerDiscovered(fromPeer)
                    }

                    if (packet.type != PacketType.HELLO) {
                        Log.d(TAG, "Received packet ${packet.packetId} from $fromPeer")
                        onPacketReceived(packet, fromPeer)
                    }
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
        val queue = transmissionQueues.getOrPut(toNeighbor) {
            val channel = kotlinx.coroutines.channels.Channel<Packet>(100)
            startQueueWorker(toNeighbor, channel)
            channel
        }
        
        scope.launch {
            queue.send(packet)
        }
    }

    private fun startQueueWorker(neighborId: NodeId, channel: kotlinx.coroutines.channels.Channel<Packet>) {
        scope.launch {
            for (packet in channel) {
                val info = discoveredPeers[neighborId]
                if (info == null) {
                    Log.w(TAG, "Worker: Neighbor $neighborId not found in discoveredPeers, dropping packet")
                    continue
                }

                try {
                    val socket = Socket()
                    socket.connect(java.net.InetSocketAddress(info.host, info.port), 4000)
                    
                    socket.use { s ->
                        val oos = ObjectOutputStream(s.getOutputStream())
                        oos.writeObject(packet)
                        oos.flush()
                        Log.d(TAG, "Worker: Sent packet ${packet.packetId} to $neighborId")
                        failureCounts.remove(neighborId)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Worker: Error sending packet to $neighborId", e)
                    val currentFailures = (failureCounts[neighborId] ?: 0) + 1
                    failureCounts[neighborId] = currentFailures
                    
                    if (currentFailures >= MAX_FAILURES) {
                        Log.w(TAG, "Worker: Neighbor $neighborId reached max failures ($MAX_FAILURES). Dropping peer.")
                        onPeerLost(neighborId)
                        failureCounts.remove(neighborId)
                        transmissionQueues.remove(neighborId)
                        channel.close()
                        break // Stop worker
                    }
                }
                // Small gap between packets to give the receiver's OS a breather
                delay(50) 
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
                    val peerId = resolvedInfo.serviceName
                    
                    // PROTECTION: If we already have a reliable manual IP for this peer,
                    // do NOT let NSD overwrite it with an internal/broken IP (common with emulators).
                    if (manualPeers.contains(peerId)) {
                        Log.d(TAG, "Ignoring NSD update for manual peer $peerId to avoid overwriting stable tunnel IP")
                        return
                    }

                    Log.d(TAG, "Service resolved: ${resolvedInfo.serviceName} at ${resolvedInfo.host}:${resolvedInfo.port}")
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
