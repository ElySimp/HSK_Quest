package com.faldo.hsk_quest.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.faldo.hsk_quest.data.model.Player
import com.faldo.hsk_quest.data.model.PlayerStats

/**
 * Offline cache of the last fetched player profile (single row per user).
 */
@Entity(tableName = "player_cache")
data class PlayerEntity(
    @PrimaryKey val userId: Int,
    val username: String,
    val hskTarget: Int,
    val level: Int,
    val xp: Int,
    val xpToNextLevel: Int,
    val unspentStatPoints: Int,
    val statStr: Int,
    val statDex: Int,
    val statDef: Int,
    val statVit: Int,
    val hp: Int,
    val maxHp: Int,
    val survivalHp: Int,
    val coins: Int,
    val diamonds: Int,
    val league: String,
    val highestAreaCleared: Int,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun toModel(): Player = Player(
        userId = userId,
        username = username,
        hskTarget = hskTarget,
        stats = PlayerStats(
            level = level,
            xp = xp,
            xpToNextLevel = xpToNextLevel,
            unspentStatPoints = unspentStatPoints,
            statStr = statStr,
            statDex = statDex,
            statDef = statDef,
            statVit = statVit,
            hp = hp,
            maxHp = maxHp,
            survivalHp = survivalHp,
            coins = coins,
            diamonds = diamonds,
            league = league,
            highestAreaCleared = highestAreaCleared,
        ),
    )

    companion object {
        fun fromModel(p: Player): PlayerEntity = PlayerEntity(
            userId = p.userId,
            username = p.username,
            hskTarget = p.hskTarget,
            level = p.stats.level,
            xp = p.stats.xp,
            xpToNextLevel = p.stats.xpToNextLevel,
            unspentStatPoints = p.stats.unspentStatPoints,
            statStr = p.stats.statStr,
            statDex = p.stats.statDex,
            statDef = p.stats.statDef,
            statVit = p.stats.statVit,
            hp = p.stats.hp,
            maxHp = p.stats.maxHp,
            survivalHp = p.stats.survivalHp,
            coins = p.stats.coins,
            diamonds = p.stats.diamonds,
            league = p.stats.league,
            highestAreaCleared = p.stats.highestAreaCleared,
        )
    }
}
