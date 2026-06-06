package com.sudo.manet.storage

class PacketCache(private val maxSize: Int = 200) {

    // LinkedHashMap in access-order mode = built-in LRU behavior
    private val cache = object : LinkedHashMap<String, Unit>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Unit>): Boolean {
            return size > maxSize
        }
    }

    // Returns true if the packet is new (not seen before)
    // Returns false if it's a duplicate — engine should drop it
    fun isNew(packetId: String): Boolean {
        if (cache.containsKey(packetId)) return false
        cache[packetId] = Unit
        return true
    }

    fun size(): Int = cache.size

    fun clear() = cache.clear()
}