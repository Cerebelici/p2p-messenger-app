package com.sudo.manet.ui.viewmodels

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sudo.manet.models.MessageStatus
import com.sudo.manet.models.MeshPacket
import com.sudo.manet.models.NetworkConstants
import com.sudo.manet.models.Peer
import com.sudo.manet.protocol.EngineEvent
import com.sudo.manet.protocol.Packet
import com.sudo.manet.service.MeshService
import com.sudo.manet.storage.NodeIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    // Current user's SHA-256 ID
    val myNodeId = NodeIdentity.localNodeId

    private var meshService: MeshService? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MeshService.MeshBinder
            meshService = binder.getService()
            isBound = true
            observeEngine()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            meshService = null
            isBound = false
        }
    }

    init {
        val intent = Intent(application, MeshService::class.java)
        application.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onCleared() {
        super.onCleared()
        if (isBound) {
            getApplication<Application>().unbindService(connection)
            isBound = false
        }
    }

    private fun observeEngine() {
        val engine = meshService?.engine ?: return
        
        viewModelScope.launch {
            engine.events.collect { event ->
                when (event) {
                    is EngineEvent.MessageReceived -> {
                        addReceivedPacket(event.packet.toUI())
                    }
                    is EngineEvent.AckReceived -> {
                        updateMessageStatus(event.packetId, MessageStatus.DELIVERED)
                    }
                    else -> {}
                }
            }
        }

        viewModelScope.launch {
            engine.topology.collect { topoMap ->
                val newPeers = topoMap.map { (nodeId, neighbors) ->
                    Peer(
                        nodeId = nodeId,
                        isDirectNeighbor = engine.getNeighbors().contains(nodeId),
                        connections = neighbors.toList()
                    )
                }
                updatePeers(newPeers)
            }
        }
    }

    private val _messages = MutableStateFlow<List<MeshPacket>>(emptyList())
    val messages: StateFlow<List<MeshPacket>> = _messages

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers

    fun sendBroadcast(text: String) {
        val packet = MeshPacket(
            senderId = myNodeId,
            destId = NetworkConstants.BROADCAST_DEST,
            payload = text,
            status = MessageStatus.PENDING
        )
        _messages.value += packet
        meshService?.engine?.sendGossip(text)
    }

    fun sendDirectMessage(destId: String, text: String) {
        val packet = MeshPacket(
            senderId = myNodeId,
            destId = destId,
            payload = text,
            status = MessageStatus.PENDING
        )
        _messages.value += packet
        meshService?.engine?.sendDirect(destId, text)
    }
    
    // Helper for background layer to inject updates
    fun updatePeers(newPeers: List<Peer>) {
        _peers.value = newPeers
    }
    
    fun addReceivedPacket(packet: MeshPacket) {
        _messages.value += packet
    }

    private fun updateMessageStatus(packetId: String, status: MessageStatus) {
        _messages.value = _messages.value.map { 
            if (it.packetId == packetId) it.copy(status = status) else it
        }
    }

    private fun Packet.toUI() = MeshPacket(
        packetId = this.packetId,
        senderId = this.senderId,
        destId = this.destId,
        ttl = this.ttl,
        payload = this.payload,
        status = when (this.status) {
            com.sudo.manet.protocol.DeliveryState.DELIVERED -> MessageStatus.DELIVERED
            else -> MessageStatus.PENDING
        }
    )
}
