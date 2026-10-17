package com.faldo.hsk_quest.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.faldo.hsk_quest.data.local.entity.StageProgressEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StageProgressDao {

    @Query("SELECT * FROM stage_progress WHERE username = :username AND areaId = :areaId")
    fun getAreaProgressFlow(username: String, areaId: Int): Flow<List<StageProgressEntity>>

    @Query("SELECT * FROM stage_progress WHERE username = :username AND areaId = :areaId")
    suspend fun getAreaProgress(username: String, areaId: Int): List<StageProgressEntity>

    @Query("SELECT * FROM stage_progress WHERE username = :username")
    fun getAllProgressFlow(username: String): Flow<List<StageProgressEntity>>

    @Query("SELECT * FROM stage_progress WHERE username = :username")
    suspend fun getAllProgress(username: String): List<StageProgressEntity>

    @Query("SELECT * FROM stage_progress WHERE stageId = :stageId LIMIT 1")
    suspend fun getStage(stageId: String): StageProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveStage(entity: StageProgressEntity)

    @Query("SELECT COUNT(*) FROM stage_progress WHERE username = :username AND areaId = :areaId AND isCleared = 1")
    suspend fun getClearedCountForArea(username: String, areaId: Int): Int

    @Query("SELECT COUNT(*) FROM stage_progress WHERE username = :username AND areaId = :areaId AND monsterType = 'boss' AND isCleared = 1")
    suspend fun isBossClearedForArea(username: String, areaId: Int): Int
}
