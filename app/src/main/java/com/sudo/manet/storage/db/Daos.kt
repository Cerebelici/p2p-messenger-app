package com.sudo.manet.storage.db

import androidx.room.*

@Entity(tableName = "node_identity")
data class NodeEntity(
    @PrimaryKey val id: Int = 0, // Singleton node ID for this device
    val nodeId: String,
    val lsaSequence: Int = 1,
    val aodvSequence: Int = 1
)

@Entity(tableName = "packet_cache")
data class PacketCacheEntity(
    @PrimaryKey val packetId: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "routing_table")
data class RouteEntity(
    @PrimaryKey val destId: String,
    val nextHop: String,
    val hopCount: Int,
    val sequenceNumber: Int,
    val createdAt: Long
)

@Dao
interface NodeDao {
    @Query("SELECT * FROM node_identity WHERE id = 0")
    suspend fun getNodeIdentity(): NodeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNodeIdentity(node: NodeEntity): Long
}

@Dao
interface PacketCacheDao {
    @Query("SELECT * FROM packet_cache")
    suspend fun getAll(): List<PacketCacheEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(packet: PacketCacheEntity): Long

    @Query("DELETE FROM packet_cache WHERE packetId NOT IN (SELECT packetId FROM packet_cache ORDER BY timestamp DESC LIMIT :limit)")
    suspend fun trim(limit: Int): Int
}

@Dao
interface RouteDao {
    @Query("SELECT * FROM routing_table")
    suspend fun getAll(): List<RouteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(route: RouteEntity): Long

    @Delete
    suspend fun delete(route: RouteEntity): Int

    @Query("DELETE FROM routing_table")
    suspend fun clearAll(): Int
}
