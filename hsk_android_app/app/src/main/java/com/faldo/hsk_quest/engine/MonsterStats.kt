package com.faldo.hsk_quest.engine

import com.faldo.hsk_quest.data.model.Monster
import com.faldo.hsk_quest.data.model.MonsterType

/**
 * Monster stat templates per HSK area plus elite/boss multipliers.
 * Mirrors MONSTER_BASE_STATS and get_monster_stats() in backend combat.py.
 */
object MonsterStats {

    private data class Base(val hp: Int, val def: Int, val dmg: Int, val xp: Int, val coins: Int)

    private val BASE_STATS = mapOf(
        1 to Base(50, 0, 5, 15, 10),
        2 to Base(80, 2, 8, 25, 18),
        3 to Base(120, 5, 12, 40, 28),
        4 to Base(180, 8, 18, 60, 42),
        5 to Base(260, 12, 25, 85, 60),
        6 to Base(360, 16, 35, 120, 85),
        7 to Base(500, 22, 48, 170, 120),
        8 to Base(680, 28, 65, 240, 170),
        9 to Base(900, 35, 85, 350, 250),
    )

    private const val ELITE_HP_MULT = 1.5
    private const val ELITE_DMG_MULT = 3.5
    private const val ELITE_REWARD_MULT = 2.0

    private const val BOSS_HP_MULT = 3.0
    private const val BOSS_DMG_MULT = 2.0
    private const val BOSS_REWARD_MULT = 5.0

    fun create(areaId: Int, type: MonsterType, index: Int = 1): Monster {
        val base = BASE_STATS[areaId] ?: BASE_STATS.getValue(1)
        val (hpMult, dmgMult, rewardMult) = when (type) {
            MonsterType.NORMAL -> Triple(1.0, 1.0, 1.0)
            MonsterType.ELITE -> Triple(ELITE_HP_MULT, ELITE_DMG_MULT, ELITE_REWARD_MULT)
            MonsterType.BOSS -> Triple(BOSS_HP_MULT, BOSS_DMG_MULT, BOSS_REWARD_MULT)
        }
        val label = type.name.lowercase().replaceFirstChar { it.uppercase() }
        return Monster(
            id = "area${areaId}_${type.apiName}_$index",
            name = if (type == MonsterType.BOSS) "HSK $areaId Boss" else "HSK $areaId $label #$index",
            areaId = areaId,
            type = type,
            maxHp = (base.hp * hpMult).toInt(),
            def = base.def,
            dmg = (base.dmg * dmgMult).toInt(),
            xpReward = (base.xp * rewardMult).toInt(),
            coinReward = (base.coins * rewardMult).toInt(),
        )
    }

    /** Diminishing returns for farming the same monster: 100%, 90%, 75%, 55%, then 30%. */
    fun diminishingMultiplier(consecutiveKills: Int): Double {
        val tiers = doubleArrayOf(1.0, 0.9, 0.75, 0.55, 0.30)
        return tiers[consecutiveKills.coerceIn(0, tiers.lastIndex)]
    }
}
