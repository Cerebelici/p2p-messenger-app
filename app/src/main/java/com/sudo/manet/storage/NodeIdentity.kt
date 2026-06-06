package com.sudo.manet.storage

import java.util.UUID

object NodeIdentity {
    // In a real app this would be persisted to SharedPreferences
    // For the MVP and simulator, a stable ID per app session is enough
    val localNodeId: String = UUID.randomUUID().toString().take(8).uppercase()
}