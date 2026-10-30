package com.faldo.hsk_quest.engine

import com.faldo.hsk_quest.data.model.Monster
import com.faldo.hsk_quest.data.model.MonsterType
import com.faldo.hsk_quest.util.Constants

/**
 * Monster stat templates per HSK area plus progressive sub-stage scaling and elite/boss multipliers.
 * Mirrors MONSTER_BASE_STATS and get_monster_stats() in backend combat.py.
 */
object MonsterStats {

    private data class Base(val hp: Int, val def: Int, val dmg: Int, val xp: Int, val coins: Int)

    private val BASE_STATS = mapOf(
        1 to Base(80, 1, 12, 20, 15),
        2 to Base(140, 3, 18, 35, 25),
        3 to Base(230, 5, 26, 55, 40),
        4 to Base(360, 8, 36, 80, 60),
        5 to Base(540, 11, 48, 115, 85),
        6 to Base(780, 15, 64, 160, 120),
        7 to Base(1080, 20, 85, 220, 165),
        8 to Base(1450, 26, 115, 300, 225),
        9 to Base(1950, 34, 155, 420, 320),
    )

    private const val ELITE_HP_MULT = 2.2
    private const val ELITE_DMG_MULT = 2.5
    private const val ELITE_REWARD_MULT = 2.5

    private const val BOSS_HP_MULT = 7.0
    private const val BOSS_DMG_MULT = 4.2
    private const val BOSS_REWARD_MULT = 6.0

    fun create(areaId: Int, type: MonsterType, index: Int = 1): Monster {
        val base = BASE_STATS[areaId] ?: BASE_STATS.getValue(1)
        val (hpMult, dmgMult, rewardMult) = when (type) {
            MonsterType.NORMAL -> Triple(1.0, 1.0, 1.0)
            MonsterType.ELITE -> Triple(ELITE_HP_MULT, ELITE_DMG_MULT, ELITE_REWARD_MULT)
            MonsterType.BOSS -> Triple(BOSS_HP_MULT, BOSS_DMG_MULT, BOSS_REWARD_MULT)
        }

        // Sub-stage progressive scaling (1.00x, 1.18x, 1.36x, 1.54x for stages 1..4)
        val stepMult = if (type == MonsterType.BOSS) 1.0 else (1.0 + Constants.STAGE_STEP_SCALING * (index.coerceIn(1, 4) - 1))

        val label = type.name.lowercase().replaceFirstChar { it.uppercase() }
        return Monster(
            id = "area${areaId}_${type.apiName}_$index",
            name = if (type == MonsterType.BOSS) "HSK $areaId Gatekeeper Boss" else "HSK $areaId $label #$index",
            areaId = areaId,
            type = type,
            maxHp = (base.hp * hpMult * stepMult).toInt(),
            def = base.def,
            dmg = (base.dmg * dmgMult * stepMult).toInt(),
            xpReward = (base.xp * rewardMult * stepMult).toInt(),
            coinReward = (base.coins * rewardMult * stepMult).toInt(),
        )
    }

    /** Diminishing returns for farming the same monster: 100%, 90%, 75%, 55%, then 30%. */
    fun diminishingMultiplier(consecutiveKills: Int): Double {
        val tiers = doubleArrayOf(1.0, 0.9, 0.75, 0.55, 0.30)
        return tiers[consecutiveKills.coerceIn(0, tiers.lastIndex)]
    }
}
