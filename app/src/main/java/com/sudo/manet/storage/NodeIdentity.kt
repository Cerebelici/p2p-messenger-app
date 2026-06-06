package com.sudo.manet.storage

import java.util.UUID

object NodeIdentity {
    var localNodeId: String = UUID.randomUUID().toString().take(8).uppercase()
        private set

    fun setId(id: String) {
        localNodeId = id
    }
}