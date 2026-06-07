package com.sudo.manet.ui.viewmodels

import androidx.lifecycle.ViewModel
import com.sudo.manet.models.MessageStatus
import com.sudo.manet.models.MeshPacket
import com.sudo.manet.models.NetworkConstants
import com.sudo.manet.models.Peer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MainViewModel : ViewModel() {
    // Current user's SHA-256 ID (Fixed for session)
    val myNodeId = "5e884898da28047151d0e56f8dc6292773603d0d6aabbdd62a11ef721d1542d8"

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
    }

    fun sendDirectMessage(destId: String, text: String) {
        val packet = MeshPacket(
            senderId = myNodeId,
            destId = destId,
            payload = text,
            status = MessageStatus.PENDING
        )
        _messages.value += packet
    }
    
    // Helper for background layer to inject updates
    fun updatePeers(newPeers: List<Peer>) {
        _peers.value = newPeers
    }
    
    fun addReceivedPacket(packet: MeshPacket) {
        _messages.value += packet
    }
}
