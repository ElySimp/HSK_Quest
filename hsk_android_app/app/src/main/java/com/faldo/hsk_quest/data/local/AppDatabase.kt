package com.faldo.hsk_quest.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.faldo.hsk_quest.data.local.dao.PlayerDao
import com.faldo.hsk_quest.data.local.dao.StageProgressDao
import com.faldo.hsk_quest.data.local.entity.PlayerEntity
import com.faldo.hsk_quest.data.local.entity.StageProgressEntity

/**
 * Local Room database caching player stats and offline-first stage progression.
 */
@Database(
    entities = [PlayerEntity::class, StageProgressEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun playerDao(): PlayerDao
    abstract fun stageProgressDao(): StageProgressDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "hsk_quest.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
