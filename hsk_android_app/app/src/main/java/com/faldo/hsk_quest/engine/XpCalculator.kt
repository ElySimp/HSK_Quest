package com.faldo.hsk_quest.engine

import com.faldo.hsk_quest.util.Constants
import kotlin.math.pow

/**
 * XP curve and level-up math. Must stay in sync with backend combat.xp_to_next_level().
 */
object XpCalculator {

    /** XP required to go from [level] to level + 1: floor(100 x level^1.2). */
    fun xpToNextLevel(level: Int): Int =
        (Constants.XP_BASE * level.coerceAtLeast(1).toDouble().pow(Constants.XP_EXPONENT)).toInt()

    data class LevelResult(val level: Int, val xp: Int, val levelsGained: Int)

    /**
     * Applies [gainedXp] to the current level/xp, rolling over as many levels as needed.
     * Each level gained grants one stat point.
     */
    fun applyXp(level: Int, xp: Int, gainedXp: Int): LevelResult {
        var newLevel = level
        var newXp = xp + gainedXp.coerceAtLeast(0)
        var gained = 0
        while (newXp >= xpToNextLevel(newLevel)) {
            newXp -= xpToNextLevel(newLevel)
            newLevel++
            gained++
        }
        return LevelResult(newLevel, newXp, gained)
    }

    /** Progress toward the next level in [0, 100]. */
    fun progressPercent(level: Int, xp: Int): Int {
        val needed = xpToNextLevel(level)
        if (needed <= 0) return 0
        return ((xp.toDouble() / needed) * 100).toInt().coerceIn(0, 100)
    }
}
