package com.sudo.manet.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.sudo.manet.protocol.MeshProtocolEngine
import com.sudo.manet.storage.NodeIdentity
import com.sudo.manet.storage.db.MeshDatabase
import com.sudo.manet.transport.NsdTransportAdapter

class MeshService : LifecycleService() {

    private lateinit var db: MeshDatabase
    
    // Using a nullable engine to handle async init if needed, 
    // but here we initialize it in onCreate.
    private var _engine: MeshProtocolEngine? = null
    val engine: MeshProtocolEngine
        get() = _engine ?: throw IllegalStateException("MeshService not initialized")

    private val binder = MeshBinder()

    inner class MeshBinder : Binder() {
        fun getService(): MeshService = this@MeshService
    }

    private var adapter: NsdTransportAdapter? = null

    override fun onCreate() {
        super.onCreate()
        NodeIdentity.init(this)
        db = MeshDatabase.getDatabase(this)
        
        val nsdAdapter = NsdTransportAdapter(this, NodeIdentity.localNodeId)
        this.adapter = nsdAdapter
        
        _engine = MeshProtocolEngine(
            sendPacket = { to, packet -> nsdAdapter.sendPacket(to, packet) },
            getNeighbors = { nsdAdapter.getNeighbors() },
            packetCacheDao = db.packetCacheDao(),
            routeDao = db.routeDao()
        )
        nsdAdapter.setEngine(engine)
        nsdAdapter.start()
        
        startForeground(NOTIFICATION_ID, createNotification())
    }

    fun connectToManualPeer(ip: String, port: Int) {
        adapter?.connectToManualPeer(ip, port)
    }

    fun getLocalPort(): Int = adapter?.getLocalPort() ?: -1

    fun getLocalIp(): String = adapter?.getLocalIp() ?: "Unknown"

    fun resetMesh() {
        adapter?.clearPeers()
        engine.resetMeshState()
    }

    val connectionStatus: kotlinx.coroutines.flow.StateFlow<String?>
        get() = adapter?.connectionStatus ?: kotlinx.coroutines.flow.MutableStateFlow(null)

    override fun onDestroy() {
        adapter?.stop()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return binder
    }

    private fun createNotification(): Notification {
        val channelId = "mesh_service_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Mesh Network Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Mesh Protocol Active")
            .setContentText("Keeping the peer-to-peer network alive")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
    }
}
