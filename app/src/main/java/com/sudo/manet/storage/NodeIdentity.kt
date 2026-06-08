package com.sudo.manet.storage

import android.content.Context
import com.sudo.manet.storage.db.MeshDatabase
import com.sudo.manet.storage.db.NodeEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.UUID

object NodeIdentity {
    private val _localNodeId = MutableStateFlow(generateSha256(UUID.randomUUID().toString()))
    val nodeIdFlow = _localNodeId.asStateFlow()
    
    var localNodeId: String 
        get() = _localNodeId.value
        private set(value) { _localNodeId.value = value }

    var lsaSequence: Int = 1
        private set
    var aodvSequence: Int = 1
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
        // Use a blocking call during initialization to ensure the ID is ready
        // for the MeshService which starts immediately after.
        kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            val entity = db?.nodeDao()?.getNodeIdentity()
            if (entity != null) {
                localNodeId = entity.nodeId
                lsaSequence = entity.lsaSequence
                aodvSequence = entity.aodvSequence
            } else {
                // First run, save the generated ID
                db?.nodeDao()?.insertNodeIdentity(NodeEntity(nodeId = localNodeId))
            }
        }
    }

    fun nextLsaSequence(): Int {
        lsaSequence++
        saveSequences()
        return lsaSequence
    }

    fun nextAodvSequence(): Int {
        aodvSequence++
        saveSequences()
        return aodvSequence
    }

    private fun saveSequences() {
        scope.launch {
            db?.nodeDao()?.insertNodeIdentity(NodeEntity(
                nodeId = localNodeId,
                lsaSequence = lsaSequence,
                aodvSequence = aodvSequence
            ))
        }
    }

    fun setId(id: String) {
        localNodeId = id
        scope.launch {
            db?.nodeDao()?.insertNodeIdentity(NodeEntity(nodeId = id))
        }
    }

    fun regenerate(context: Context) {
        val newId = generateSha256(UUID.randomUUID().toString())
        localNodeId = newId
        scope.launch {
            db?.nodeDao()?.insertNodeIdentity(NodeEntity(nodeId = newId))
        }
    }
}
