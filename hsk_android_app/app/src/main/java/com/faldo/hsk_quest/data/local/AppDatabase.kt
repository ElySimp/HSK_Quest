package com.faldo.hsk_quest.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.faldo.hsk_quest.data.local.dao.PlayerDao
import com.faldo.hsk_quest.data.local.entity.PlayerEntity

/**
 * Local Room database. More entities (battle log, inventory, pets) are added in later phases.
 */
@Database(
    entities = [PlayerEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun playerDao(): PlayerDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "hsk_quest.db")
                // Pre-release: wipe the local cache on schema changes instead of writing migrations.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
