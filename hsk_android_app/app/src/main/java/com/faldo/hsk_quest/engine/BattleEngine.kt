package com.faldo.hsk_quest.engine

import com.faldo.hsk_quest.data.model.BattleState
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * Pure combat engine executing turn calculations and state transitions.
 * Guaranteed to be side-effect free and 100% unit-testable.
 */
object BattleEngine {

    const val DEFAULT_ANSWER_LIMIT_MS = 25_000L
    const val LISTENING_SPEAKING_LIMIT_MS = 15_000L
    const val ELITE_READING_LIMIT_MS = 45_000L
    const val ELITE_WRITING_LIMIT_MS = 45_000L
    const val LOW_HP_WARNING_THRESHOLD_PERCENT = 20

    fun applyAnswer(
        currentState: BattleState,
        isCorrect: Boolean,
        elapsedTimeMs: Long,
        statStr: Int,
        statDex: Int,
        statDef: Int,
        hasTimeExtender: Boolean = false,
        hasDamageBoost: Boolean = false,
        isUnderleveledBoss: Boolean = false,
        usedPinyinHint: Boolean = false,
        petBuff: PetBuffSpec? = null,
    ): BattleState {
        if (currentState.isFinished) return currentState

        val critWindowMs = DamageCalculator.critWindowMs(
            hasTimeExtender = hasTimeExtender,
            extraWindowMs = petBuff?.critWindowBonusMs ?: 0L,
        )
        val isCritical = isCorrect && (elapsedTimeMs <= critWindowMs)
        val nextQuestionIndex = currentState.questionIndex + 1

        return if (isCorrect) {
            val openerBonusPct = if (currentState.questionIndex == 0) {
                petBuff?.firstStrikeBonusPct ?: 0
            } else {
                0
            }
            val baseDealt = DamageCalculator.playerDamageExact(
                statStr = statStr,
                statDex = statDex,
                monsterDef = currentState.monster.def,
                isCritical = isCritical,
                hasDamageBoost = hasDamageBoost,
                damageBonusPct = petBuff?.damageBonusPct ?: 0,
                critDamageBonusPct = petBuff?.critDamageBonusPct ?: 0,
                firstStrikeBonusPct = openerBonusPct,
            )
            val damageDealt = if (usedPinyinHint) {
                DamageCalculator.roundTenth(max(0.5, baseDealt * 0.5))
            } else {
                baseDealt
            }
            val newMonsterHp = DamageCalculator.roundTenth(max(0.0, currentState.monsterHp - damageDealt))
            val newPhase = if (newMonsterHp <= 0.0) BattleState.Phase.VICTORY else BattleState.Phase.IN_PROGRESS

            val rawPetHeal = ((petBuff?.healOnCorrect ?: 0) +
                if (isCritical) (petBuff?.healOnCrit ?: 0) else 0).toDouble()
            val actualPetHeal = if (rawPetHeal > 0.0) {
                DamageCalculator.roundTenth(
                    min(currentState.playerMaxHp.toDouble() - currentState.playerHp, rawPetHeal).coerceAtLeast(0.0)
                )
            } else {
                0.0
            }
            val newPlayerHp = DamageCalculator.roundTenth(currentState.playerHp + actualPetHeal)

            currentState.copy(
                monsterHp = newMonsterHp,
                playerHp = newPlayerHp,
                combo = currentState.combo + 1,
                totalDamageDealt = DamageCalculator.roundTenth(currentState.totalDamageDealt + damageDealt),
                correctAnswers = currentState.correctAnswers + 1,
                totalPetHealing = DamageCalculator.roundTenth(currentState.totalPetHealing + actualPetHeal),
                lastPetHeal = actualPetHeal,
                lastRebirthTriggered = false,
                questionIndex = nextQuestionIndex,
                lastDamageDealt = damageDealt,
                lastDamageTaken = 0.0,
                wasCritical = isCritical,
                wasShieldBlocked = false,
                phase = newPhase,
            )
        } else {
            // Wrong answer or timeout
            val rawMonsterDamage = DamageCalculator.monsterDamageExact(
                monsterDmg = currentState.monster.dmg,
                playerStatDef = statDef,
                isUnderleveledBoss = isUnderleveledBoss,
                playerMaxHp = currentState.playerMaxHp,
                damageReductionPct = petBuff?.monsterDamageReductionPct ?: 0,
            )

            val shieldUsed = currentState.shieldsRemaining > 0
            val nextShields = if (shieldUsed) currentState.shieldsRemaining - 1 else currentState.shieldsRemaining
            val candidateDamage = if (shieldUsed) 0.0 else rawMonsterDamage

            val canRebirth = !shieldUsed &&
                candidateDamage >= currentState.playerHp &&
                currentState.rebirthAvailable &&
                !currentState.rebirthUsed

            val actualDamage: Double
            val rebirthHeal: Double
            val newPlayerHp: Double
            val newPhase: BattleState.Phase
            if (canRebirth) {
                actualDamage = currentState.playerHp
                val revivePct = (petBuff?.rebirthHpPct ?: 30).coerceIn(10, 100)
                val targetHp = max(1, round(currentState.playerMaxHp * revivePct / 100.0).toInt()).toDouble()
                newPlayerHp = min(currentState.playerMaxHp.toDouble(), targetHp)
                rebirthHeal = newPlayerHp
                newPhase = BattleState.Phase.IN_PROGRESS
            } else {
                actualDamage = candidateDamage
                rebirthHeal = 0.0
                newPlayerHp = DamageCalculator.roundTenth(max(0.0, currentState.playerHp - actualDamage))
                newPhase = if (newPlayerHp <= 0.0) BattleState.Phase.DEFEAT else BattleState.Phase.IN_PROGRESS
            }

            // Check <= 20% HP warning prompt (triggers once per battle)
            val hpPercent = if (currentState.playerMaxHp > 0) {
                ((newPlayerHp / currentState.playerMaxHp) * 100.0).toInt()
            } else {
                0
            }

            val shouldTriggerWarning = (newPhase == BattleState.Phase.IN_PROGRESS) &&
                (newPlayerHp > 0.0 && hpPercent in 0..LOW_HP_WARNING_THRESHOLD_PERCENT) &&
                !currentState.lowHpPromptShown

            currentState.copy(
                playerHp = newPlayerHp,
                combo = 0,
                totalDamageTaken = DamageCalculator.roundTenth(currentState.totalDamageTaken + actualDamage),
                wrongAnswers = currentState.wrongAnswers + 1,
                shieldAvailable = nextShields > 0,
                shieldsRemaining = nextShields,
                rebirthAvailable = currentState.rebirthAvailable && !canRebirth,
                rebirthUsed = currentState.rebirthUsed || canRebirth,
                totalPetHealing = DamageCalculator.roundTenth(currentState.totalPetHealing + rebirthHeal),
                lastPetHeal = rebirthHeal,
                lastRebirthTriggered = canRebirth,
                questionIndex = nextQuestionIndex,
                lastDamageDealt = 0.0,
                lastDamageTaken = actualDamage,
                wasCritical = false,
                wasShieldBlocked = shieldUsed,
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
            wasShieldBlocked = false,
        )
    }

    fun dismissLowHpWarning(currentState: BattleState): BattleState {
        return currentState.copy(isLowHpWarningActive = false)
    }

    fun applyElixirHeal(currentState: BattleState, healAmount: Int): BattleState {
        if (currentState.isFinished || healAmount <= 0) return currentState
        val newHp = DamageCalculator.roundTenth(
            min(currentState.playerMaxHp.toDouble(), currentState.playerHp + healAmount.toDouble())
        )
        val actualHealed = DamageCalculator.roundTenth(newHp - currentState.playerHp)
        return currentState.copy(
            playerHp = newHp,
            totalDamageTaken = DamageCalculator.roundTenth(max(0.0, currentState.totalDamageTaken - actualHealed)),
            lastDamageDealt = 0.0,
            lastDamageTaken = 0.0,
            wasShieldBlocked = false,
            isLowHpWarningActive = false,
        )
    }
}
