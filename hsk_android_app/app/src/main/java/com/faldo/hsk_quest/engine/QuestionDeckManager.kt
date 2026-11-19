package com.faldo.hsk_quest.engine

import android.content.Context
import android.content.SharedPreferences
import com.faldo.hsk_quest.data.model.HskTerm
import com.faldo.hsk_quest.data.model.MonsterType
import com.google.gson.Gson
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * Manages Engine A's persistent Shuffle-Bag word decks, missed-word cooldown re-queues,
 * and balanced QuestionType modality bags per (username, areaId).
 */
class QuestionDeckManager private constructor(
    private val prefs: SharedPreferences?,
) {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    constructor() : this(null)

    private data class RequeuedWord(
        val hanzi: String,
        var dueInTurns: Int,
    )

    private data class DeckState(
        val remainingHanzi: MutableList<String> = mutableListOf(),
        val requeue: MutableList<RequeuedWord> = mutableListOf(),
        var turnsSinceLastReview: Int = REVIEW_COOLDOWN_TURNS,
        var lastAskedHanzi: String? = null,
    )

    private data class ModalityBagState(
        val remaining: MutableList<QuestionType> = mutableListOf(),
        var lastType: QuestionType? = null,
    )

    private val lock = Any()
    private val gson = Gson()
    private val deckCache = ConcurrentHashMap<String, DeckState>()
    private val modalityBags = HashMap<String, ModalityBagState>()

    fun nextQuestionType(
        monsterType: MonsterType,
        allowSpeaking: Boolean,
    ): QuestionType = synchronized(lock) {
        val normalizedRank = if (monsterType == MonsterType.NORMAL) "NORMAL" else "ELITE_BOSS"
        val key = "${normalizedRank}_$allowSpeaking"
        val state = modalityBags.getOrPut(key) { ModalityBagState() }

        if (state.remaining.isEmpty()) {
            val fresh = buildModalityTemplate(monsterType, allowSpeaking).toMutableList()
            fresh.shuffle()
            if (fresh.size > 1 && fresh[0] == state.lastType) {
                val swapIdx = fresh.indexOfFirst { it != state.lastType }.takeIf { it > 0 } ?: 1
                val tmp = fresh[0]
                fresh[0] = fresh[swapIdx]
                fresh[swapIdx] = tmp
            }
            state.remaining.addAll(fresh)
        }

        val next = state.remaining.removeAt(0)
        state.lastType = next
        next
    }

    private fun buildModalityTemplate(
        monsterType: MonsterType,
        allowSpeaking: Boolean,
    ): List<QuestionType> {
        if (monsterType == MonsterType.NORMAL) {
            return listOf(
                QuestionType.ENGLISH_TO_HANZI,
                QuestionType.HANZI_TO_PINYIN,
                QuestionType.HANZI_TO_ENGLISH,
            )
        }
        return if (allowSpeaking) {
            listOf(
                QuestionType.CLOZE_SENTENCE,
                QuestionType.CLOZE_SENTENCE,
                QuestionType.CLOZE_SENTENCE,
                QuestionType.WRITING_CHALLENGE,
                QuestionType.WRITING_CHALLENGE,
                QuestionType.LISTENING_TO_HANZI,
                QuestionType.LISTENING_TO_HANZI,
                QuestionType.LISTENING_TO_ENGLISH,
                QuestionType.SPEAKING_CHALLENGE,
                QuestionType.SPEAKING_CHALLENGE,
            )
        } else {
            listOf(
                QuestionType.CLOZE_SENTENCE,
                QuestionType.CLOZE_SENTENCE,
                QuestionType.CLOZE_SENTENCE,
                QuestionType.CLOZE_SENTENCE,
                QuestionType.WRITING_CHALLENGE,
                QuestionType.WRITING_CHALLENGE,
                QuestionType.LISTENING_TO_HANZI,
                QuestionType.LISTENING_TO_HANZI,
                QuestionType.LISTENING_TO_ENGLISH,
                QuestionType.LISTENING_TO_ENGLISH,
            )
        }
    }

    fun drawNextTarget(
        username: String,
        areaId: Int,
        terms: List<HskTerm>,
        mistakeWeights: Map<String, Int> = emptyMap(),
        eligiblePredicate: (HskTerm) -> Boolean = { true },
    ): HskTerm = synchronized(lock) {
        val termByHanzi = LinkedHashMap<String, HskTerm>(terms.size)
        for (term in terms) {
            termByHanzi.putIfAbsent(term.simplified, term)
        }
        if (termByHanzi.isEmpty()) {
            return terms.first()
        }

        val stateKey = deckKey(username, areaId)
        val state = loadDeckState(stateKey)

        // Prune stale entries not belonging to the active level vocabulary.
        state.remainingHanzi.retainAll(termByHanzi.keys)
        state.requeue.removeAll { it.hanzi !in termByHanzi }

        for (item in state.requeue) {
            item.dueInTurns = item.dueInTurns - 1
        }
        state.turnsSinceLastReview += 1

        // Step A: Serve due missed word only after both per-word cooldown and review spacing pass.
        if (state.turnsSinceLastReview >= REVIEW_COOLDOWN_TURNS) {
            val dueIdx = state.requeue.indexOfFirst { req ->
                req.dueInTurns <= 0 &&
                    req.hanzi != state.lastAskedHanzi &&
                    (termByHanzi[req.hanzi]?.let(eligiblePredicate) == true)
            }
            if (dueIdx >= 0) {
                val chosen = state.requeue.removeAt(dueIdx).hanzi
                state.turnsSinceLastReview = 0
                state.lastAskedHanzi = chosen
                saveDeckState(stateKey, state)
                return termByHanzi.getValue(chosen)
            }
        }

        // Step B: Draw from full-level no-repeat Shuffle-Bag.
        if (state.remainingHanzi.isEmpty()) {
            refillDeck(state, termByHanzi.keys, mistakeWeights)
        }

        var pickIdx = state.remainingHanzi.indexOfFirst { h ->
            h != state.lastAskedHanzi && (termByHanzi[h]?.let(eligiblePredicate) == true)
        }
        if (pickIdx < 0) {
            pickIdx = state.remainingHanzi.indexOfFirst { h ->
                termByHanzi[h]?.let(eligiblePredicate) == true
            }
        }

        if (pickIdx >= 0) {
            val chosen = state.remainingHanzi.removeAt(pickIdx)
            state.lastAskedHanzi = chosen
            saveDeckState(stateKey, state)
            return termByHanzi.getValue(chosen)
        }

        // Tail of bag has no terms matching a narrow modality filter; preserve bag for future draws.
        val eligibleTerms = termByHanzi.values.filter(eligiblePredicate)
        val fallbackPool = eligibleTerms.filter { it.simplified != state.lastAskedHanzi }
            .ifEmpty { eligibleTerms }
            .ifEmpty { termByHanzi.values.toList() }
        val fallback = fallbackPool.random()
        state.lastAskedHanzi = fallback.simplified
        saveDeckState(stateKey, state)
        return fallback
    }

    fun recordOutcome(
        username: String,
        areaId: Int,
        hanzi: String,
        isCorrect: Boolean,
    ) = synchronized(lock) {
        if (hanzi.isBlank()) return
        val stateKey = deckKey(username, areaId)
        val state = loadDeckState(stateKey)

        if (!isCorrect) {
            val existing = state.requeue.firstOrNull { it.hanzi == hanzi }
            if (existing != null) {
                existing.dueInTurns = max(existing.dueInTurns, MISSED_WORD_COOLDOWN_TURNS)
            } else {
                state.requeue.add(RequeuedWord(hanzi = hanzi, dueInTurns = MISSED_WORD_COOLDOWN_TURNS))
            }
        } else {
            state.requeue.removeAll { it.hanzi == hanzi }
        }
        saveDeckState(stateKey, state)
    }

    private fun refillDeck(
        state: DeckState,
        allKeys: Set<String>,
        mistakeWeights: Map<String, Int>,
    ) {
        val weakKeys = mutableListOf<String>()
        val regularKeys = mutableListOf<String>()
        for (h in allKeys) {
            if ((mistakeWeights[h] ?: 0) > 0) {
                weakKeys += h
            } else {
                regularKeys += h
            }
        }

        val combined = mutableListOf<String>()
        if (weakKeys.isNotEmpty() && regularKeys.isNotEmpty()) {
            weakKeys.shuffle()
            regularKeys.shuffle()
            // Bias weak words into the first half of the reshuffled cycle without duplicating entries.
            val firstHalfRegularCount = regularKeys.size / 2
            val firstHalf = mutableListOf<String>()
            firstHalf.addAll(weakKeys)
            firstHalf.addAll(regularKeys.subList(0, firstHalfRegularCount))
            firstHalf.shuffle()
            combined.addAll(firstHalf)
            combined.addAll(regularKeys.subList(firstHalfRegularCount, regularKeys.size))
        } else {
            combined.addAll(allKeys)
            combined.shuffle()
        }

        if (combined.size > 1 && combined[0] == state.lastAskedHanzi) {
            val tmp = combined[0]
            combined[0] = combined[1]
            combined[1] = tmp
        }

        state.remainingHanzi.clear()
        state.remainingHanzi.addAll(combined)
    }

    private fun deckKey(username: String, areaId: Int): String {
        val cleanUser = username.trim().lowercase().ifEmpty { "guest" }
        val normalizedArea = if (areaId in 7..9) 7 else areaId.coerceIn(1, 9)
        return "deck_${cleanUser}_area_$normalizedArea"
    }

    private fun loadDeckState(key: String): DeckState {
        deckCache[key]?.let { return it }
        val raw = prefs?.getString(key, null)
        val parsed = if (!raw.isNullOrBlank()) {
            runCatching { gson.fromJson(raw, DeckState::class.java) }.getOrNull()
        } else {
            null
        }
        val state = parsed ?: DeckState()
        deckCache[key] = state
        return state
    }

    private fun saveDeckState(key: String, state: DeckState) {
        deckCache[key] = state
        prefs?.edit()?.putString(key, gson.toJson(state))?.apply()
    }

    companion object {
        private const val PREFS_NAME = "hsk_shuffle_bag_prefs"
        const val MISSED_WORD_COOLDOWN_TURNS = 3
        const val REVIEW_COOLDOWN_TURNS = 3
    }
}
