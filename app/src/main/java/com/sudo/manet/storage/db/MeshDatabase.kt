package com.sudo.manet.storage.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [NodeEntity::class, PacketCacheEntity::class, RouteEntity::class],
    version = 3,
    exportSchema = false
)
abstract class MeshDatabase : RoomDatabase() {
    abstract fun nodeDao(): NodeDao
    abstract fun packetCacheDao(): PacketCacheDao
    abstract fun routeDao(): RouteDao

    companion object {
        @Volatile
        private var INSTANCE: MeshDatabase? = null

        fun getDatabase(context: Context): MeshDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MeshDatabase::class.java,
                    "mesh_database"
                )
                    .fallbackToDestructiveMigration(true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
