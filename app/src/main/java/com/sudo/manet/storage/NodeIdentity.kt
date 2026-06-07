package com.sudo.manet.storage

import android.content.Context
import com.sudo.manet.storage.db.MeshDatabase
import com.sudo.manet.storage.db.NodeEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.UUID

object NodeIdentity {
    var localNodeId: String = generateSha256(UUID.randomUUID().toString())
        private set

    private fun generateSha256(input: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private var db: MeshDatabase? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    fun init(context: Context) {
        db = MeshDatabase.getDatabase(context)
        scope.launch {
            val entity = db?.nodeDao()?.getNodeIdentity()
            if (entity != null) {
                localNodeId = entity.nodeId
            } else {
                // First run, save the generated ID
                db?.nodeDao()?.insertNodeIdentity(NodeEntity(nodeId = localNodeId))
            }
        }
    }

    fun setId(id: String) {
        localNodeId = id
        scope.launch {
            db?.nodeDao()?.insertNodeIdentity(NodeEntity(nodeId = id))
        }
    }
}
