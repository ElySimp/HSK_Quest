package com.faldo.hsk_quest.data.model

/**
 * Immutable snapshot of an ongoing battle.
 * Kept inside BattleViewModel (never in Fragment field variables).
 */
data class BattleState(
    val monster: Monster,
    val monsterHp: Int,
    val playerHp: Int,
    val playerMaxHp: Int,
    val combo: Int = 0,
    val totalDamageDealt: Int = 0,
    val totalDamageTaken: Int = 0,
    val correctAnswers: Int = 0,
    val wrongAnswers: Int = 0,
    val shieldAvailable: Boolean = false,
    val lastDamageDealt: Int = 0,
    val lastDamageTaken: Int = 0,
    val wasCritical: Boolean = false,
    val isLowHpWarningActive: Boolean = false,
    val lowHpPromptShown: Boolean = false,
    val phase: Phase = Phase.IN_PROGRESS,
) {
    enum class Phase { IN_PROGRESS, VICTORY, DEFEAT, FLED }

    val isFinished: Boolean get() = phase != Phase.IN_PROGRESS
    val totalQuestionsAnswered: Int get() = correctAnswers + wrongAnswers
    val playerHpPercent: Int get() = if (playerMaxHp > 0) ((playerHp.toDouble() / playerMaxHp) * 100).toInt() else 0
    val monsterHpPercent: Int get() = if (monster.maxHp > 0) ((monsterHp.toDouble() / monster.maxHp) * 100).toInt() else 0
}
