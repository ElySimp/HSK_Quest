package com.faldo.hsk_quest.data.model

import kotlin.math.ceil

/**
 * Immutable snapshot of an ongoing battle.
 * Kept inside BattleViewModel (never in Fragment field variables).
 */
data class BattleState(
    val monster: Monster,
    val monsterHp: Double,
    val playerHp: Double,
    val playerMaxHp: Int,
    val combo: Int = 0,
    val totalDamageDealt: Double = 0.0,
    val totalDamageTaken: Double = 0.0,
    val correctAnswers: Int = 0,
    val wrongAnswers: Int = 0,
    val shieldAvailable: Boolean = false,
    val shieldsRemaining: Int = if (shieldAvailable) 1 else 0,
    val rebirthAvailable: Boolean = false,
    val rebirthUsed: Boolean = false,
    val totalPetHealing: Double = 0.0,
    val lastPetHeal: Double = 0.0,
    val lastRebirthTriggered: Boolean = false,
    val questionIndex: Int = 0,
    val activeBuffLabel: String? = null,
    val isGatekeeperPenaltyActive: Boolean = false,
    val lastDamageDealt: Double = 0.0,
    val lastDamageTaken: Double = 0.0,
    val wasCritical: Boolean = false,
    val wasShieldBlocked: Boolean = false,
    val isLowHpWarningActive: Boolean = false,
    val lowHpPromptShown: Boolean = false,
    val phase: Phase = Phase.IN_PROGRESS,
) {
    enum class Phase { IN_PROGRESS, VICTORY, DEFEAT, FLED }

    val isFinished: Boolean get() = phase != Phase.IN_PROGRESS
    val totalQuestionsAnswered: Int get() = correctAnswers + wrongAnswers
    val playerHpPercent: Int
        get() {
            if (playerMaxHp <= 0 || playerHp <= 0.0) return 0
            return ((playerHp / playerMaxHp) * 100.0).toInt().coerceAtLeast(1).coerceAtMost(100)
        }
    val monsterHpPercent: Int
        get() {
            if (monster.maxHp <= 0 || monsterHp <= 0.0) return 0
            return ((monsterHp / monster.maxHp) * 100.0).toInt().coerceAtLeast(1).coerceAtMost(100)
        }
    val roundedUpPlayerHp: Int
        get() = if (playerHp <= 0.0) 0 else ceil(playerHp).toInt().coerceIn(1, playerMaxHp.coerceAtLeast(1))
    val isBossEnraged: Boolean
        get() = monster.type == MonsterType.BOSS && monsterHp > 0.0 && monsterHpPercent <= 30
}
