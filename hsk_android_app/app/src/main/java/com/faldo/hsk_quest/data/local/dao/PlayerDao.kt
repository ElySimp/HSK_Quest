package com.faldo.hsk_quest.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.faldo.hsk_quest.data.local.entity.PlayerEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlayerDao {

    @Query("SELECT * FROM player_cache WHERE username = :username LIMIT 1")
    suspend fun getByUsername(username: String): PlayerEntity?

    @Query("SELECT * FROM player_cache ORDER BY updatedAt DESC LIMIT 1")
    fun observeLatest(): Flow<PlayerEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(player: PlayerEntity)

    @Query("DELETE FROM player_cache")
    suspend fun clear()
}
