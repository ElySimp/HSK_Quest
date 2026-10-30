package com.faldo.hsk_quest.data.local

import android.content.Context
import com.faldo.hsk_quest.data.model.HskLevelFile
import com.faldo.hsk_quest.data.model.HskTerm
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/**
 * Represents a searchable HSK dictionary item with pre-normalized Pinyin keys
 * for fast tone-insensitive and space-insensitive lookup.
 */
data class DictionaryEntry(
    val levelLabel: String,
    val levelKey: Int,
    val term: HskTerm,
    val normalizedPinyin: String,
    val compactPinyin: String,
)

/**
 * Loads HSK vocabulary from assets/hsk_data and caches each level in memory.
 * Area 7, 8 and 9 all map to the combined hsk-7-9.json file.
 */
class HskJsonLoader(context: Context) {

    private val assets = context.applicationContext.assets
    private val gson = Gson()
    private val cache = ConcurrentHashMap<String, List<HskTerm>>()
    private val dictionaryCache = ConcurrentHashMap<Int, List<DictionaryEntry>>()

    suspend fun loadLevel(level: Int): List<HskTerm> = withContext(Dispatchers.IO) {
        val fileName = fileNameFor(level)
        cache[fileName] ?: run {
            val terms = assets.open("$ASSET_DIR/$fileName").bufferedReader(Charsets.UTF_8).use {
                gson.fromJson(it, HskLevelFile::class.java).terms
            }.filter { term ->
                term.simplified.isNotBlank() && term.pinyin.isNotBlank() && term.english.isNotBlank()
            }
            cache[fileName] = terms
            terms
        }
    }

    /**
     * Loads and caches dictionary entries for a specific HSK level key (1..6, or 7 for HSK 7-9),
     * or across all levels (1..7) when [levelFilter] is null.
     */
    suspend fun loadDictionaryEntries(levelFilter: Int? = null): List<DictionaryEntry> =
        withContext(Dispatchers.IO) {
            if (levelFilter != null) {
                val key = if (levelFilter in 7..9) 7 else levelFilter.coerceIn(1, 7)
                loadDictionaryLevelKey(key)
            } else {
                (1..7).flatMap { key -> loadDictionaryLevelKey(key) }
            }
        }

    /**
     * Searches HSK vocabulary by Simplified/Traditional Hanzi, tone-insensitive Pinyin
     * (with or without spaces), or English meaning.
     */
    suspend fun searchDictionary(
        query: String,
        levelFilter: Int? = null,
        limit: Int = 120,
    ): List<DictionaryEntry> = withContext(Dispatchers.IO) {
        val entries = loadDictionaryEntries(levelFilter)
        val rawQuery = query.trim()
        if (rawQuery.isBlank()) {
            return@withContext entries.take(limit)
        }

        val q = normalizePinyin(rawQuery)
        val qCompact = q.replace(" ", "")

        val exactMatches = ArrayList<DictionaryEntry>()
        val prefixMatches = ArrayList<DictionaryEntry>()
        val containsMatches = ArrayList<DictionaryEntry>()

        for (entry in entries) {
            val term = entry.term
            val engLower = term.english.lowercase()

            val isExact = term.simplified == rawQuery ||
                term.traditional == rawQuery ||
                entry.normalizedPinyin == q ||
                (qCompact.isNotEmpty() && entry.compactPinyin == qCompact)

            if (isExact) {
                exactMatches.add(entry)
                continue
            }

            val isPrefix = term.simplified.startsWith(rawQuery) ||
                entry.normalizedPinyin.startsWith(q) ||
                (qCompact.isNotEmpty() && entry.compactPinyin.startsWith(qCompact)) ||
                engLower.startsWith(q)

            if (isPrefix) {
                prefixMatches.add(entry)
                continue
            }

            val isContains = term.simplified.contains(rawQuery) ||
                (term.traditional?.contains(rawQuery) == true) ||
                entry.normalizedPinyin.contains(q) ||
                (qCompact.isNotEmpty() && entry.compactPinyin.contains(qCompact)) ||
                engLower.contains(q)

            if (isContains) {
                containsMatches.add(entry)
            }
        }

        buildList(minOf(limit, exactMatches.size + prefixMatches.size + containsMatches.size)) {
            for (item in exactMatches) {
                if (size >= limit) break
                add(item)
            }
            for (item in prefixMatches) {
                if (size >= limit) break
                add(item)
            }
            for (item in containsMatches) {
                if (size >= limit) break
                add(item)
            }
        }
    }

    private suspend fun loadDictionaryLevelKey(levelKey: Int): List<DictionaryEntry> {
        return dictionaryCache[levelKey] ?: run {
            val label = levelLabelFor(levelKey)
            val entries = loadLevel(levelKey).map { term ->
                val normPinyin = normalizePinyin(term.pinyin.trim())
                val compact = normPinyin.replace(WHITESPACE_REGEX, "")
                DictionaryEntry(
                    levelLabel = label,
                    levelKey = levelKey,
                    term = term,
                    normalizedPinyin = normPinyin,
                    compactPinyin = compact,
                )
            }
            dictionaryCache[levelKey] = entries
            entries
        }
    }

    private fun levelLabelFor(levelKey: Int): String = when (levelKey) {
        in 7..9 -> "HSK 7–9"
        else -> "HSK $levelKey"
    }

    private fun fileNameFor(level: Int): String = when (level.coerceIn(1, 9)) {
        in 7..9 -> "hsk-7-9.json"
        else -> "hsk-${level.coerceIn(1, 9)}.json"
    }

    companion object {
        private const val ASSET_DIR = "hsk_data"
        private val DIACRITICS_REGEX = "\\p{InCombiningDiacriticalMarks}+".toRegex()
        private val WHITESPACE_REGEX = "\\s+".toRegex()

        /**
         * Strips tone diacritics and normalizes umlauts so queries like
         * "ni hao", "nihao", and "nǐ hǎo" all normalize consistently.
         */
        fun normalizePinyin(input: String): String {
            return Normalizer.normalize(input.lowercase(), Normalizer.Form.NFD)
                .replace(DIACRITICS_REGEX, "")
                .replace("ü", "v")
        }
    }
}

