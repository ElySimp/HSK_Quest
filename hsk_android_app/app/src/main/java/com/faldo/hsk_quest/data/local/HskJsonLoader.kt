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
 * Represents one segmented word token inside a multi-word / sentence dictionary query
 * (e.g., "wo" -> 我 in "wo ai ni").
 */
data class WordSegmentGroup(
    val segmentIndex: Int,
    val rawToken: String,
    val primaryEntry: DictionaryEntry,
    val matches: List<DictionaryEntry>,
)

/**
 * Result of a smart dictionary lookup supporting both single-word search and
 * multi-word sentence segmentation with word-by-word breakdown.
 */
data class MultiWordSearchResponse(
    val isMultiWordSentence: Boolean,
    val sentenceSummaryHanzi: String,
    val sentenceSummaryPinyin: String,
    val sentenceSummaryEnglish: String,
    val segments: List<WordSegmentGroup>,
    val flatEntries: List<DictionaryEntry>,
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

    /**
     * Performs an intelligent dictionary search that supports both single-word queries
     * and multi-word / sentence segmentation (e.g., "wo ai ni", "ni hao ma", "今天我吃苹果").
     */
    suspend fun searchSmartDictionary(
        query: String,
        levelFilter: Int? = null,
        limit: Int = 120,
    ): MultiWordSearchResponse = withContext(Dispatchers.IO) {
        val rawQuery = query.trim()
        val standardEntries = searchDictionary(rawQuery, levelFilter, limit)
        if (rawQuery.isBlank()) {
            return@withContext MultiWordSearchResponse(
                isMultiWordSentence = false,
                sentenceSummaryHanzi = "",
                sentenceSummaryPinyin = "",
                sentenceSummaryEnglish = "",
                segments = emptyList(),
                flatEntries = standardEntries,
            )
        }

        val allEntries = loadDictionaryEntries(null)
        val hasCjk = rawQuery.any { isCjkChar(it) }

        val segments: List<WordSegmentGroup> = if (hasCjk) {
            segmentHanziSentence(rawQuery, allEntries, levelFilter)
        } else {
            segmentPinyinOrLatinSentence(rawQuery, allEntries, levelFilter, standardEntries)
        }

        if (segments.size >= 2) {
            val summaryHanzi = segments.joinToString("") { it.primaryEntry.term.simplified }
            val summaryPinyin = segments.joinToString(" ") { it.primaryEntry.term.pinyin }
            val summaryEnglish = segments.joinToString("  •  ") { seg ->
                val shortMeaning = conciseEnglishGloss(seg.primaryEntry.term.english)
                "${seg.primaryEntry.term.simplified} (${seg.primaryEntry.term.pinyin}: $shortMeaning)"
            }
            val combinedFlat = segments.flatMap { it.matches }.distinctBy {
                "${it.levelKey}_${it.term.simplified}_${it.term.pinyin}"
            }
            MultiWordSearchResponse(
                isMultiWordSentence = true,
                sentenceSummaryHanzi = summaryHanzi,
                sentenceSummaryPinyin = summaryPinyin,
                sentenceSummaryEnglish = summaryEnglish,
                segments = segments,
                flatEntries = if (combinedFlat.isNotEmpty()) combinedFlat else standardEntries,
            )
        } else {
            MultiWordSearchResponse(
                isMultiWordSentence = false,
                sentenceSummaryHanzi = "",
                sentenceSummaryPinyin = "",
                sentenceSummaryEnglish = "",
                segments = emptyList(),
                flatEntries = standardEntries,
            )
        }
    }

    private fun segmentHanziSentence(
        rawQuery: String,
        allEntries: List<DictionaryEntry>,
        levelFilter: Int?,
    ): List<WordSegmentGroup> {
        val cjkChars = rawQuery.filter { isCjkChar(it) }
        if (cjkChars.length < 2) return emptyList()

        // If the entire string is an exact single dictionary word (e.g. "你好" or "苹果"),
        // keep single-word display unless it is 3+ chars and not found as an exact entry.
        val hasWholeWordExact = allEntries.any {
            it.term.simplified == cjkChars || it.term.traditional == cjkChars
        }
        if (hasWholeWordExact && cjkChars.length <= 2) {
            return emptyList()
        }

        val bySimplified = allEntries.groupBy { it.term.simplified }
        val byTraditional = allEntries
            .filter { !it.term.traditional.isNullOrBlank() }
            .groupBy { it.term.traditional!! }

        val groups = ArrayList<WordSegmentGroup>()
        var idx = 0
        var segIndex = 1

        while (idx < cjkChars.length) {
            var matchedToken: String? = null
            var matchedEntries: List<DictionaryEntry>? = null

            val maxSpan = minOf(4, cjkChars.length - idx)
            for (span in maxSpan downTo 1) {
                val sub = cjkChars.substring(idx, idx + span)
                // Avoid matching the whole query as a single segment if we want sentence breakdown
                if (span == cjkChars.length && cjkChars.length >= 3 && hasWholeWordExact) {
                    continue
                }
                val candidates = (bySimplified[sub].orEmpty() + byTraditional[sub].orEmpty())
                    .distinctBy { "${it.levelKey}_${it.term.simplified}_${it.term.pinyin}" }
                if (candidates.isNotEmpty()) {
                    matchedToken = sub
                    matchedEntries = rankSegmentCandidates(candidates, levelFilter, exactHanzi = sub)
                    idx += span
                    break
                }
            }

            if (matchedToken != null && !matchedEntries.isNullOrEmpty()) {
                val topMatches = matchedEntries.take(4)
                groups.add(
                    WordSegmentGroup(
                        segmentIndex = segIndex++,
                        rawToken = matchedToken,
                        primaryEntry = topMatches.first(),
                        matches = topMatches,
                    ),
                )
            } else {
                idx++
            }
        }

        return if (groups.size >= 2) groups else emptyList()
    }

    private fun segmentPinyinOrLatinSentence(
        rawQuery: String,
        allEntries: List<DictionaryEntry>,
        levelFilter: Int?,
        standardEntries: List<DictionaryEntry>,
    ): List<WordSegmentGroup> {
        val normalized = normalizePinyin(rawQuery)
            .replace("[^a-zv\\s']".toRegex(), " ")
            .trim()
        if (normalized.isBlank()) return emptyList()

        val spaceTokens = normalized.split(WHITESPACE_REGEX).filter { it.isNotBlank() }

        // If user typed a single token without spaces (e.g. "woaini" or "nihaoma")
        // and standard search found no matches, try splitting concatenated Pinyin syllables.
        val tokens: List<String> = if (spaceTokens.size >= 2) {
            spaceTokens
        } else if (spaceTokens.size == 1 && standardEntries.isEmpty() && spaceTokens[0].length >= 4) {
            splitConcatenatedPinyin(spaceTokens[0], allEntries)
        } else {
            return emptyList()
        }

        if (tokens.size < 2) return emptyList()

        // If the entire query is just a 2-syllable exact compound word (like "ni hao" or "ping guo")
        // and has only 2 tokens, check whether both tokens form a common 2-syllable word.
        // However, if it's 2 separate words like "wo ai" or "chi fan", or 3+ tokens like "wo ai ni",
        // segment it into words!
        val fullNormalized = tokens.joinToString(" ")
        val fullCompact = tokens.joinToString("")
        if (tokens.size == 2) {
            val isCommonSingleWord = allEntries.any {
                (it.normalizedPinyin == fullNormalized || it.compactPinyin == fullCompact) &&
                    it.levelKey <= 4 &&
                    it.term.simplified.length == 2
            }
            if (isCommonSingleWord) {
                return emptyList()
            }
        }

        val byCompactPinyin = allEntries.groupBy { it.compactPinyin }
        val byNormalizedPinyin = allEntries.groupBy { it.normalizedPinyin }

        val groups = ArrayList<WordSegmentGroup>()
        var i = 0
        var segIndex = 1

        while (i < tokens.size) {
            var matchedSpan = 0
            var matchedRaw = ""
            var matchedCandidates: List<DictionaryEntry> = emptyList()

            val maxSpan = minOf(3, tokens.size - i)
            for (span in maxSpan downTo 1) {
                // Do not collapse the entire multi-token query into 1 segment
                if (span == tokens.size) continue

                val subTokens = tokens.subList(i, i + span)
                val subNorm = subTokens.joinToString(" ")
                val subCompact = subTokens.joinToString("")

                val rawList = (byNormalizedPinyin[subNorm].orEmpty() + byCompactPinyin[subCompact].orEmpty())
                    .distinctBy { "${it.levelKey}_${it.term.simplified}_${it.term.pinyin}" }

                if (rawList.isEmpty()) continue

                // For multi-syllable merges (span >= 2) inside a sentence, only merge if it is a
                // well-known compound word (HSK 1-4) and not accidentally merging a core pronoun ("wo", "ni", "ta")
                // with an unrelated verb unless it's "wo men" / "ni men" / "ta men".
                if (span >= 2) {
                    val firstTok = subTokens[0]
                    val secondTok = subTokens[1]
                    val isPronounPlural = (firstTok == "wo" || firstTok == "ni" || firstTok == "ta") && secondTok == "men"
                    val isBlockedPronounMerge = (firstTok == "wo" || firstTok == "ni" || firstTok == "ta") && !isPronounPlural
                    val hasCommonCompound = rawList.any {
                        it.levelKey <= 3 && it.term.simplified in COMMON_COMPOUND_WORDS
                    }
                    if (isBlockedPronounMerge || !hasCommonCompound) {
                        continue
                    }
                }

                matchedSpan = span
                matchedRaw = subNorm
                matchedCandidates = rankSegmentCandidates(rawList, levelFilter, exactHanzi = null)
                break
            }

            if (matchedSpan > 0 && matchedCandidates.isNotEmpty()) {
                val topMatches = matchedCandidates.take(4)
                groups.add(
                    WordSegmentGroup(
                        segmentIndex = segIndex++,
                        rawToken = matchedRaw,
                        primaryEntry = topMatches.first(),
                        matches = topMatches,
                    ),
                )
                i += matchedSpan
            } else {
                // Fallback: try English word match or prefix match for single token
                val singleTok = tokens[i]
                val fallbackMatches = allEntries.filter { entry ->
                    val engWords = entry.term.english.lowercase().split("[^a-z]+".toRegex())
                    entry.compactPinyin == singleTok || engWords.contains(singleTok)
                }
                if (fallbackMatches.isNotEmpty()) {
                    val ranked = rankSegmentCandidates(fallbackMatches, levelFilter, exactHanzi = null).take(4)
                    groups.add(
                        WordSegmentGroup(
                            segmentIndex = segIndex++,
                            rawToken = singleTok,
                            primaryEntry = ranked.first(),
                            matches = ranked,
                        ),
                    )
                }
                i++
            }
        }

        return if (groups.size >= 2) groups else emptyList()
    }

    private fun splitConcatenatedPinyin(
        compactInput: String,
        allEntries: List<DictionaryEntry>,
    ): List<String> {
        val validSyllables = HashSet<String>()
        for (entry in allEntries) {
            if (entry.term.simplified.length == 1 && entry.compactPinyin.isNotBlank()) {
                validSyllables.add(entry.compactPinyin)
            }
        }
        val result = ArrayList<String>()
        var idx = 0
        val clean = compactInput.replace("'", "")
        while (idx < clean.length) {
            var matched: String? = null
            val maxLen = minOf(6, clean.length - idx)
            for (len in maxLen downTo 1) {
                val sub = clean.substring(idx, idx + len)
                if (validSyllables.contains(sub)) {
                    matched = sub
                    idx += len
                    break
                }
            }
            if (matched != null) {
                result.add(matched)
            } else {
                return emptyList()
            }
        }
        return result
    }

    private fun rankSegmentCandidates(
        candidates: List<DictionaryEntry>,
        levelFilter: Int?,
        exactHanzi: String?,
    ): List<DictionaryEntry> {
        return candidates.sortedWith(
            compareByDescending<DictionaryEntry> { entry ->
                exactHanzi != null && entry.term.simplified == exactHanzi
            }.thenByDescending { entry ->
                entry.term.simplified in CORE_SENTENCE_HANZI_PRIORITY
            }.thenBy { entry ->
                CORE_SENTENCE_HANZI_ORDER[entry.term.simplified] ?: 999
            }.thenByDescending { entry ->
                levelFilter != null && levelFilter > 0 && entry.levelKey == levelFilter
            }.thenBy { entry ->
                entry.levelKey
            }.thenBy { entry ->
                entry.term.simplified.length
            },
        )
    }

    private fun conciseEnglishGloss(english: String): String {
        val firstPart = english.split(";", ",", "/").firstOrNull()?.trim().orEmpty()
        return if (firstPart.length > 22) "${firstPart.take(20)}…" else firstPart.ifBlank { english }
    }

    private fun isCjkChar(c: Char): Boolean {
        return c.code in 0x4E00..0x9FFF
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

        private val CORE_SENTENCE_HANZI_LIST = listOf(
            "我", "你", "他", "她", "们", "我们", "你们", "他们",
            "好", "爱", "是", "不", "在", "有", "没", "没有",
            "吃", "喝", "看", "听", "说", "读", "写", "学", "学习",
            "去", "来", "回", "想", "要", "喜欢", "会", "能", "可以",
            "很", "太", "都", "也", "和", "的", "了", "吗", "呢", "吧",
            "这", "那", "哪", "谁", "什么", "怎么", "多少", "几",
            "今天", "明天", "昨天", "现在", "时候", "中国", "北京",
            "苹果", "米饭", "水", "茶", "菜", "书", "钱", "车",
            "老师", "学生", "朋友", "妈妈", "爸爸", "家", "学校", "医院",
            "大", "小", "多", "少", "冷", "热", "高兴", "漂亮", "谢谢", "再见",
        )

        private val CORE_SENTENCE_HANZI_PRIORITY: Set<String> = CORE_SENTENCE_HANZI_LIST.toSet()

        private val CORE_SENTENCE_HANZI_ORDER: Map<String, Int> =
            CORE_SENTENCE_HANZI_LIST.mapIndexed { index, hanzi -> hanzi to index }.toMap()

        private val COMMON_COMPOUND_WORDS: Set<String> = setOf(
            "我们", "你们", "他们", "她们", "今天", "明天", "昨天", "现在",
            "喜欢", "学习", "苹果", "米饭", "中国", "北京", "汉语", "中文",
            "老师", "学生", "朋友", "同学", "妈妈", "爸爸", "哥哥", "姐姐", "弟弟", "妹妹",
            "什么", "怎么", "多少", "哪里", "没有", "可以", "因为", "所以", "虽然", "但是",
            "高兴", "漂亮", "谢谢", "再见", "医院", "学校", "商店", "饭店", "飞机", "出租车",
            "电脑", "电视", "电影", "电话", "手机", "衣服", "天气", "工作", "休息", "准备",
        )

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

