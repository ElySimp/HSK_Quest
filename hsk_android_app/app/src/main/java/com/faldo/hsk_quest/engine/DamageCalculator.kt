package com.faldo.hsk_quest.engine

import com.faldo.hsk_quest.util.Constants
import kotlin.math.max

/**
 * Damage formulas. Kept in sync with backend app/services/combat.py.
 */
object DamageCalculator {

    /** Base damage = 10 + STR x 5. */
    fun baseDamage(statStr: Int): Int = Constants.BASE_DAMAGE + statStr * Constants.DAMAGE_PER_STR

    /** Critical multiplier = 1.5 + DEX x 0.02. */
    fun critMultiplier(statDex: Int): Double =
        Constants.BASE_CRIT_MULTIPLIER + statDex * Constants.CRIT_PER_DEX

    /**
     * Damage dealt by the player to a monster:
     * max(1, base - monsterDef) x (crit multiplier) x (pet damage boost if active).
     */
    fun playerDamage(
        statStr: Int,
        statDex: Int,
        monsterDef: Int,
        isCritical: Boolean,
        hasDamageBoost: Boolean = false,
    ): Int {
        val raw = max(1, baseDamage(statStr) - monsterDef.coerceAtLeast(0))
        var mult = if (isCritical) critMultiplier(statDex) else 1.0
        if (hasDamageBoost) {
            mult *= Constants.PET_DAMAGE_BOOST_MULTIPLIER
        }
        return (raw * mult).toInt()
    }

    /**
     * Damage dealt by a monster to the player:
     * If challenging Gatekeeper Boss while under-leveled, deals playerMaxHp (1-Hit KO unless shielded).
     * Otherwise: max(1, monsterDmg - DEF x 2).
     */
    fun monsterDamage(
        monsterDmg: Int,
        playerStatDef: Int,
        isUnderleveledBoss: Boolean = false,
        playerMaxHp: Int = 100,
    ): Int {
        if (isUnderleveledBoss) {
            return playerMaxHp
        }
        return max(1, monsterDmg - playerStatDef * Constants.DEF_REDUCTION_PER_POINT)
    }

    /** Max HP = 100 + VIT x 25. */
    fun maxHp(statVit: Int): Int = Constants.BASE_MAX_HP + statVit * Constants.HP_PER_VIT

    /** Critical-hit answer window, extended by the Time Extender pet buff. */
    fun critWindowMs(hasTimeExtender: Boolean): Long =
        if (hasTimeExtender) Constants.CRIT_WINDOW_TIME_EXTENDER_MS else Constants.CRIT_WINDOW_MS
}
