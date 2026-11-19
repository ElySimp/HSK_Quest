package com.faldo.hsk_quest.engine

/**
 * Configurable question-selection engine for CAT-QUEST battle evaluation.
 * Engine A uses deterministic Shuffle-Bag + cooldown rules; Engine B will use
 * the trained on-device `cat_scheduler.tflite` model.
 */
enum class QuestionEngineMode(
    val id: String,
    val shortTitle: String,
    val displayTitle: String,
    val subtitle: String,
) {
    RULE_BASED(
        id = "rule_based",
        shortTitle = "Rule-Based",
        displayTitle = "Engine A · Rule-Based",
        subtitle = "Shuffle-Bag full-level deck + 3-turn missed-word cooldown + fixed mastery (+15/-20).",
    ),
    ML_CAT(
        id = "ml_cat",
        shortTitle = "ML CAT",
        displayTitle = "Engine B · ML Adaptive CAT",
        subtitle = "Probability-guided selection (cat_scheduler.tflite) targeting the ~70-75% Flow Zone.",
    );

    companion object {
        fun fromId(raw: String?): QuestionEngineMode {
            return entries.firstOrNull { it.id == raw } ?: RULE_BASED
        }
    }
}

/**
 * Pairs the user's selected mode with the actual runtime engine used in battle,
 * falling back safely to [QuestionEngineMode.RULE_BASED] when the ML asset is absent.
 */
data class ResolvedQuestionEngine(
    val configuredMode: QuestionEngineMode,
    val effectiveMode: QuestionEngineMode,
    val isMlModelReady: Boolean,
) {
    val isFallbackToRuleBased: Boolean
        get() = configuredMode == QuestionEngineMode.ML_CAT &&
            effectiveMode == QuestionEngineMode.RULE_BASED

    fun formatCompactBadge(): String = when {
        isFallbackToRuleBased -> "${configuredMode.shortTitle} (Fallback: ${effectiveMode.shortTitle})"
        else -> effectiveMode.displayTitle
    }

    companion object {
        fun resolve(
            configuredMode: QuestionEngineMode,
            isMlModelReady: Boolean,
        ): ResolvedQuestionEngine {
            val effective = if (configuredMode == QuestionEngineMode.ML_CAT && !isMlModelReady) {
                QuestionEngineMode.RULE_BASED
            } else {
                configuredMode
            }
            return ResolvedQuestionEngine(
                configuredMode = configuredMode,
                effectiveMode = effective,
                isMlModelReady = isMlModelReady,
            )
        }
    }
}
