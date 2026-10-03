package com.faldo.hsk_quest.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.faldo.hsk_quest.data.local.entity.PlayerEntity

@Dao
interface PlayerDao {

    @Query("SELECT * FROM player_cache WHERE username = :username LIMIT 1")
    suspend fun getByUsername(username: String): PlayerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(player: PlayerEntity)

    @Query("DELETE FROM player_cache")
    suspend fun clear()
}
