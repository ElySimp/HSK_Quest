package com.faldo.hsk_quest.engine

import com.faldo.hsk_quest.util.Constants
import kotlin.math.max
import kotlin.math.round

/**
 * Damage formulas. Kept in sync with backend app/services/combat.py.
 */
object DamageCalculator {

    /** Snap floating-point combat values to 1 decimal place (0.1 precision). */
    fun roundTenth(value: Double): Double = round(value * 10.0) / 10.0

    /** Format combat numbers cleanly: shows 1 decimal when fractional (e.g. 11.8), else integer (12). */
    fun formatDecimal(value: Double): String {
        val snapped = roundTenth(value)
        val tenths = round(snapped * 10.0).toLong()
        return if (tenths % 10L == 0L) {
            (tenths / 10L).toString()
        } else {
            String.format(java.util.Locale.US, "%.1f", snapped)
        }
    }

    /** Base damage = 15 + STR x 2. */
    fun baseDamage(statStr: Int): Int = Constants.BASE_DAMAGE + statStr * Constants.DAMAGE_PER_STR

    /** Critical multiplier = 1.5 + DEX x 0.0025. */
    fun critMultiplier(statDex: Int): Double =
        Constants.BASE_CRIT_MULTIPLIER + statDex * Constants.CRIT_PER_DEX

    /**
     * Exact 0.1-precision player damage so small percentage buffs (e.g. +4% Crit) remain meaningful.
     */
    fun playerDamageExact(
        statStr: Int,
        statDex: Int,
        monsterDef: Int,
        isCritical: Boolean,
        hasDamageBoost: Boolean = false,
        damageBonusPct: Int = 0,
        critDamageBonusPct: Int = 0,
        firstStrikeBonusPct: Int = 0,
    ): Double {
        val raw = max(1, baseDamage(statStr) - monsterDef.coerceAtLeast(0))
        var critMult = if (isCritical) critMultiplier(statDex) else 1.0
        if (isCritical && critDamageBonusPct > 0) {
            critMult += critDamageBonusPct / 100.0
        }
        val effectiveDmgPct = when {
            damageBonusPct > 0 -> damageBonusPct
            hasDamageBoost -> 15
            else -> 0
        }
        var petMult = 1.0 + (effectiveDmgPct / 100.0)
        if (firstStrikeBonusPct > 0) {
            petMult *= 1.0 + (firstStrikeBonusPct / 100.0)
        }
        return roundTenth(max(0.1, raw * critMult * petMult))
    }

    /**
     * Damage dealt by the player to a monster (legacy Int signature retained for compatibility):
     */
    fun playerDamage(
        statStr: Int,
        statDex: Int,
        monsterDef: Int,
        isCritical: Boolean,
        hasDamageBoost: Boolean = false,
        damageBonusPct: Int = 0,
        critDamageBonusPct: Int = 0,
        firstStrikeBonusPct: Int = 0,
    ): Int = playerDamageExact(
        statStr = statStr,
        statDex = statDex,
        monsterDef = monsterDef,
        isCritical = isCritical,
        hasDamageBoost = hasDamageBoost,
        damageBonusPct = damageBonusPct,
        critDamageBonusPct = critDamageBonusPct,
        firstStrikeBonusPct = firstStrikeBonusPct,
    ).toInt()

    /**
     * Exact 0.1-precision monster damage so small pet DMG reduction buffs (e.g. -2%) apply noticeably.
     */
    fun monsterDamageExact(
        monsterDmg: Int,
        playerStatDef: Int,
        isUnderleveledBoss: Boolean = false,
        playerMaxHp: Int = 100,
        damageReductionPct: Int = 0,
    ): Double {
        if (isUnderleveledBoss) {
            return playerMaxHp.toDouble()
        }
        val base = max(1.0, monsterDmg - (playerStatDef * Constants.DEF_REDUCTION_PER_POINT))
        if (damageReductionPct <= 0) return roundTenth(base)
        val clampedPct = damageReductionPct.coerceIn(0, 75)
        return roundTenth(max(0.1, base * (1.0 - clampedPct / 100.0)))
    }

    /**
     * Damage dealt by a monster to the player (legacy Int signature retained for compatibility).
     */
    fun monsterDamage(
        monsterDmg: Int,
        playerStatDef: Int,
        isUnderleveledBoss: Boolean = false,
        playerMaxHp: Int = 100,
        damageReductionPct: Int = 0,
    ): Int {
        if (isUnderleveledBoss) {
            return playerMaxHp
        }
        val base = (monsterDmg - round(playerStatDef * Constants.DEF_REDUCTION_PER_POINT).toInt()).coerceAtLeast(1)
        if (damageReductionPct <= 0) return base
        val clampedPct = damageReductionPct.coerceIn(0, 75)
        return max(1, round(base * (1.0 - clampedPct / 100.0)).toInt())
    }

    /** Max HP = 100 + (Level - 1) x 5 + VIT x 5. */
    fun maxHp(statVit: Int, level: Int = 1): Int =
        Constants.BASE_MAX_HP +
            max(0, level - 1) * Constants.HP_PER_LEVEL +
            statVit * Constants.HP_PER_VIT

    /** Critical-hit answer window, extended by pet crit-window buffs. */
    fun critWindowMs(hasTimeExtender: Boolean, extraWindowMs: Long = 0L): Long =
        when {
            extraWindowMs > 0L -> Constants.CRIT_WINDOW_MS + extraWindowMs
            hasTimeExtender -> Constants.CRIT_WINDOW_TIME_EXTENDER_MS
            else -> Constants.CRIT_WINDOW_MS
        }
}
