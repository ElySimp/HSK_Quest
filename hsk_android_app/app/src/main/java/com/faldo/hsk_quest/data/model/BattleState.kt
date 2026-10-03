package com.faldo.hsk_quest.data.model

/**
 * Immutable snapshot of an ongoing battle.
 * Held inside a ViewModel (never in Fragment fields) so it survives configuration changes.
 * Fully used starting in Phase 2.
 */
data class BattleState(
    val monster: Monster,
    val monsterHp: Int,
    val playerHp: Int,
    val playerMaxHp: Int,
    val combo: Int = 0,
    val totalDamageDealt: Int = 0,
    val correctAnswers: Int = 0,
    val wrongAnswers: Int = 0,
    val shieldAvailable: Boolean = false,
    val phase: Phase = Phase.IN_PROGRESS,
) {
    enum class Phase { IN_PROGRESS, VICTORY, DEFEAT }

    val isFinished: Boolean get() = phase != Phase.IN_PROGRESS
}
