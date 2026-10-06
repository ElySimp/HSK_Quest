package com.faldo.hsk_quest.engine

import com.faldo.hsk_quest.data.model.BattleState
import kotlin.math.max

/**
 * Pure combat engine executing turn calculations and state transitions.
 * Guaranteed to be side-effect free and 100% unit-testable.
 */
object BattleEngine {

    const val DEFAULT_ANSWER_LIMIT_MS = 5_000L
    const val LOW_HP_WARNING_THRESHOLD_PERCENT = 20

    fun applyAnswer(
        currentState: BattleState,
        isCorrect: Boolean,
        elapsedTimeMs: Long,
        statStr: Int,
        statDex: Int,
        statDef: Int,
        hasTimeExtender: Boolean = false,
    ): BattleState {
        if (currentState.isFinished) return currentState

        val critWindowMs = DamageCalculator.critWindowMs(hasTimeExtender)
        val isCritical = isCorrect && (elapsedTimeMs <= critWindowMs)

        return if (isCorrect) {
            val damageDealt = DamageCalculator.playerDamage(
                statStr = statStr,
                statDex = statDex,
                monsterDef = currentState.monster.def,
                isCritical = isCritical,
            )
            val newMonsterHp = max(0, currentState.monsterHp - damageDealt)
            val newPhase = if (newMonsterHp == 0) BattleState.Phase.VICTORY else BattleState.Phase.IN_PROGRESS

            currentState.copy(
                monsterHp = newMonsterHp,
                combo = currentState.combo + 1,
                totalDamageDealt = currentState.totalDamageDealt + damageDealt,
                correctAnswers = currentState.correctAnswers + 1,
                lastDamageDealt = damageDealt,
                lastDamageTaken = 0,
                wasCritical = isCritical,
                phase = newPhase,
            )
        } else {
            // Wrong answer or timeout
            val rawMonsterDamage = DamageCalculator.monsterDamage(
                monsterDmg = currentState.monster.dmg,
                playerStatDef = statDef,
            )

            val (actualDamage, shieldRemaining) = if (currentState.shieldAvailable) {
                0 to false // Shield absorbs the attack
            } else {
                rawMonsterDamage to currentState.shieldAvailable
            }

            val newPlayerHp = max(0, currentState.playerHp - actualDamage)
            val newPhase = if (newPlayerHp == 0) BattleState.Phase.DEFEAT else BattleState.Phase.IN_PROGRESS

            // Check <= 20% HP warning prompt (triggers once per battle)
            val hpPercent = if (currentState.playerMaxHp > 0) {
                ((newPlayerHp.toDouble() / currentState.playerMaxHp) * 100).toInt()
            } else {
                0
            }

            val shouldTriggerWarning = (newPhase == BattleState.Phase.IN_PROGRESS) &&
                (hpPercent in 1..LOW_HP_WARNING_THRESHOLD_PERCENT) &&
                !currentState.lowHpPromptShown

            currentState.copy(
                playerHp = newPlayerHp,
                combo = 0,
                totalDamageTaken = currentState.totalDamageTaken + actualDamage,
                wrongAnswers = currentState.wrongAnswers + 1,
                shieldAvailable = shieldRemaining,
                lastDamageDealt = 0,
                lastDamageTaken = actualDamage,
                wasCritical = false,
                isLowHpWarningActive = shouldTriggerWarning,
                lowHpPromptShown = currentState.lowHpPromptShown || shouldTriggerWarning,
                phase = newPhase,
            )
        }
    }

    fun flee(currentState: BattleState): BattleState {
        return currentState.copy(
            phase = BattleState.Phase.FLED,
            isLowHpWarningActive = false,
        )
    }

    fun dismissLowHpWarning(currentState: BattleState): BattleState {
        return currentState.copy(isLowHpWarningActive = false)
    }
}
