package com.faldo.hsk_quest.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Local Room entity storing cleared state and performance stars for world map stages,
 * scoped per user account to prevent cross-account progress bleed.
 * Format of stageId: "${username}_area${areaId}_${monsterType}_${stageIndex}"
 */
@Entity(tableName = "stage_progress")
data class StageProgressEntity(
    @PrimaryKey
    val stageId: String,
    val username: String,
    val areaId: Int,
    val monsterType: String,
    val stageIndex: Int,
    val isCleared: Boolean = false,
    val clearedAt: Long = 0L,
    val stars: Int = 0,
    val highestDamage: Int = 0,
)
