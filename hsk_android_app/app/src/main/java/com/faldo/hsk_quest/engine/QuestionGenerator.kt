package com.faldo.hsk_quest.engine

import com.faldo.hsk_quest.data.model.HskTerm

enum class QuestionType {
    ENGLISH_TO_HANZI,
    HANZI_TO_PINYIN,
    HANZI_TO_ENGLISH;

    val displayLabel: String
        get() = when (this) {
            ENGLISH_TO_HANZI -> "Translate to Hanzi"
            HANZI_TO_PINYIN -> "Select Correct Pinyin"
            HANZI_TO_ENGLISH -> "Translate to English"
        }
}

data class BattleQuestion(
    val type: QuestionType,
    val prompt: String,
    val subPrompt: String? = null,
    val options: List<String>,
    val correctIndex: Int,
    val targetTerm: HskTerm,
) {
    fun isCorrect(selectedIndex: Int): Boolean = selectedIndex == correctIndex
}

/**
 * Generates flashcard questions with 3 distractors from the current HSK vocabulary pool.
 */
object QuestionGenerator {

    fun generateQuestion(terms: List<HskTerm>): BattleQuestion? {
        if (terms.size < 4) return null

        val target = terms.random()
        val type = QuestionType.entries.random()

        val (prompt, subPrompt, getOptionValue) = when (type) {
            QuestionType.ENGLISH_TO_HANZI -> Triple(
                target.english,
                "HSK Level vocabulary",
                { t: HskTerm -> t.simplified },
            )
            QuestionType.HANZI_TO_PINYIN -> Triple(
                target.simplified,
                target.english.take(28),
                { t: HskTerm -> t.pinyin },
            )
            QuestionType.HANZI_TO_ENGLISH -> Triple(
                target.simplified,
                target.pinyin,
                { t: HskTerm -> t.english },
            )
        }

        val targetOption = getOptionValue(target)

        // Select 3 unique distractors with distinct values from target
        val distractors = terms
            .filter { getOptionValue(it) != targetOption }
            .shuffled()
            .distinctBy { getOptionValue(it) }
            .take(3)
            .map { getOptionValue(it) }

        if (distractors.size < 3) return null

        val allOptions = (distractors + targetOption).shuffled()
        val correctIndex = allOptions.indexOf(targetOption)

        return BattleQuestion(
            type = type,
            prompt = prompt,
            subPrompt = subPrompt,
            options = allOptions,
            correctIndex = correctIndex,
            targetTerm = target,
        )
    }
}
