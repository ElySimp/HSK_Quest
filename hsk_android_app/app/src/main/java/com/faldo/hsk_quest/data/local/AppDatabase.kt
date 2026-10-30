package com.faldo.hsk_quest.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.faldo.hsk_quest.data.local.dao.PlayerDao
import com.faldo.hsk_quest.data.local.dao.StageProgressDao
import com.faldo.hsk_quest.data.local.dao.VocabMasteryDao
import com.faldo.hsk_quest.data.local.entity.PlayerEntity
import com.faldo.hsk_quest.data.local.entity.StageProgressEntity
import com.faldo.hsk_quest.data.local.entity.VocabMasteryEntity

/**
 * Local Room database caching player stats, offline-first stage progression, and vocabulary mastery.
 */
@Database(
    entities = [
        PlayerEntity::class,
        StageProgressEntity::class,
        VocabMasteryEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun playerDao(): PlayerDao
    abstract fun stageProgressDao(): StageProgressDao
    abstract fun vocabMasteryDao(): VocabMasteryDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "hsk_quest.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
