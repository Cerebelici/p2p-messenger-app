package com.sudo.manet.storage

import com.sudo.manet.storage.db.PacketCacheDao
import com.sudo.manet.storage.db.PacketCacheEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PacketCache(
    private val maxSize: Int = 200,
    private val dao: PacketCacheDao? = null
) {
    private val scope = CoroutineScope(Dispatchers.IO)

    // LinkedHashMap in access-order mode = built-in LRU behavior
    private val cache = object : LinkedHashMap<String, Unit>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Unit>): Boolean {
            return size > maxSize
        }
    }

    init {
        dao?.let { d ->
            scope.launch {
                d.getAll().forEach { entity ->
                    synchronized(cache) {
                        cache[entity.packetId] = Unit
                    }
                }
            }
        }
    }

    // Returns true if the packet is new (not seen before)
    // Returns false if it's a duplicate — engine should drop it
    fun isNew(packetId: String): Boolean {
        synchronized(cache) {
            if (cache.containsKey(packetId)) return false
            cache[packetId] = Unit
        }
        
        dao?.let { d ->
            scope.launch {
                d.insert(PacketCacheEntity(packetId))
                d.trim(maxSize)
            }
        }
        
        return true
    }

    fun size(): Int = synchronized(cache) { cache.size }

    fun clear() {
        synchronized(cache) {
            cache.clear()
        }
        // NOTE: Not clearing the DB as we want to remember packets even after re-init if not cleared
    }
}
