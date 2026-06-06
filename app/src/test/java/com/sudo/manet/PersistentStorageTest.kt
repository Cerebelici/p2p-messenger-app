package com.sudo.manet

import com.sudo.manet.protocol.MeshProtocolEngine
import com.sudo.manet.protocol.Packet
import com.sudo.manet.protocol.PacketType
import com.sudo.manet.storage.db.PacketCacheDao
import com.sudo.manet.storage.db.PacketCacheEntity
import com.sudo.manet.storage.db.RouteDao
import com.sudo.manet.storage.db.RouteEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class PersistentStorageTest {

    // Fake DAOs for unit testing
    class FakePacketCacheDao : PacketCacheDao {
        val cache = mutableListOf<PacketCacheEntity>()
        override suspend fun getAll() = cache
        override suspend fun insert(packet: PacketCacheEntity): Long { 
            cache.add(packet) 
            return 1L
        }
        override suspend fun trim(limit: Int): Int { return 0 }
    }

    class FakeRouteDao : RouteDao {
        val routes = mutableMapOf<String, RouteEntity>()
        override suspend fun getAll() = routes.values.toList()
        override suspend fun insert(route: RouteEntity): Long { 
            routes[route.destId] = route 
            return 1L
        }
        override suspend fun delete(route: RouteEntity): Int { 
            routes.remove(route.destId)
            return 1
        }
        override suspend fun clearAll(): Int { 
            val size = routes.size
            routes.clear()
            return size
        }
    }

    @Test
    fun `engine restores routes from dao`() = runBlocking {
        val routeDao = FakeRouteDao()
        routeDao.insert(RouteEntity("DEST_Z", "HOP_B", 2, System.currentTimeMillis()))

        val engine = MeshProtocolEngine(
            sendPacket = { _, _ -> },
            getNeighbors = { emptyList() },
            routeDao = routeDao
        )

        // Give it a moment to load from "DB"
        Thread.sleep(100)

        // Metrics don't show routing table size, but we can try to send
        // and see if it triggers discovery or not.
        // Actually, let's check the behavior.
        
        // If route exists, it shouldn't send RREQ
        val sentPackets = mutableListOf<Packet>()
        val engine2 = MeshProtocolEngine(
            sendPacket = { _, p -> sentPackets.add(p) },
            getNeighbors = { listOf("HOP_B") },
            routeDao = routeDao,
            nodeId = "ME"
        )
        
        Thread.sleep(100)
        engine2.sendDirect("DEST_Z", "hello")
        
        // It should have sent MSG_DIRECT, not RREQ
        val firstPacket = sentPackets.firstOrNull()
        assertEquals(PacketType.MSG_DIRECT, firstPacket?.type)
    }

    @Test
    fun `engine records new packets in dao`() = runBlocking {
        val packetDao = FakePacketCacheDao()
        val engine = MeshProtocolEngine(
            sendPacket = { _, _ -> },
            getNeighbors = { emptyList() },
            packetCacheDao = packetDao
        )

        val packet = Packet(
            type = PacketType.MSG_GOSSIP,
            senderId = "SENDER",
            destId = "BROADCAST",
            ttl = 4,
            payload = "test"
        )

        engine.receive(packet, "NEIGHBOR")
        
        Thread.sleep(100)
        assertEquals(1, packetDao.cache.size)
        assertEquals(packet.packetId, packetDao.cache.first().packetId)
    }
}
