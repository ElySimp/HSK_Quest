package com.faldo.hsk_quest.engine

import android.content.Context
import com.google.gson.JsonParser
import java.io.InputStream
import java.io.InputStreamReader
import java.text.Normalizer
import java.util.Locale

data class PinyinSyllablePhones(
    val display: String,
    val base: String,
    val tone: Int,
    val phones: List<String>,
    val phoneIds: List<Int>,
)

data class ParsedPinyin(
    val raw: String,
    val syllables: List<PinyinSyllablePhones>,
) {
    val syllableCount: Int get() = syllables.size
    val tones: List<Int> get() = syllables.map { it.tone }
    val phoneIds: List<Int> get() = syllables.flatMap { it.phoneIds }
}

data class ToneModelMeta(
    val phonemeVocab: List<String>,
    val phoneToId: Map<String, Int>,
    val blankIndex: Int,
    val numPhoneClasses: Int,
    val numToneClasses: Int,
    val minPhoneSimPct: Float,
    val exactMatchToneScorePct: Float,
    val allTonesOkFloorPct: Float,
    val sandhiT3T3Allowed: Boolean,
    val exactDamagePct: Int,
    val lenientDamagePct: Int,
    val noMatchDamagePct: Int,
)

/**
 * Loads HSK pinyin-to-phoneme mappings and segments space-less or apostrophe-delimited
 * tone-marked HSK pinyin strings (e.g., "yíxià", "nǚ'ér", "dōngxi") into syllables,
 * expected tones (1..5), and ToneNet 70-phoneme CTC ID sequences.
 */
class PinyinPhoneConverter(
    val meta: ToneModelMeta,
    private val pinyinToPhones: Map<String, List<String>>,
) {

    fun isSupportedFlashcardTerm(rawPinyin: String, maxSyllables: Int = DEFAULT_MAX_SYLLABLES): Boolean {
        val primary = segmentPrimary(rawPinyin) ?: return false
        return primary.syllableCount in 1..maxSyllables
    }

    fun segmentPrimary(rawPinyin: String): ParsedPinyin? {
        return segmentVariants(rawPinyin).firstOrNull()
    }

    /**
     * Parses all slash/comma/semicolon-separated pinyin readings present in HSK entries.
     */
    fun segmentVariants(rawPinyin: String): List<ParsedPinyin> {
        val cleaned = rawPinyin.trim()
        if (cleaned.isEmpty()) return emptyList()

        val variants = cleaned.split('/', ',', ';', '|', '、')
            .map { stripParentheticals(it).trim() }
            .filter { it.isNotEmpty() }

        val results = mutableListOf<ParsedPinyin>()
        val seenKeys = mutableSetOf<String>()
        for (variant in variants) {
            val parsed = parseSingleReading(variant) ?: continue
            val key = parsed.syllables.joinToString("-") { "${it.base}${it.tone}" }
            if (seenKeys.add(key)) {
                results += parsed
            }
        }
        return results
    }

    private fun parseSingleReading(reading: String): ParsedPinyin? {
        val normalized = reading
            .replace('’', '\'')
            .replace('‘', '\'')
            .replace('ʼ', '\'')
            .replace('ɑ', 'a')
            .replace('ɡ', 'g')
            .trim()
        if (normalized.isEmpty()) return null

        val rawTokens = normalized.split(TOKEN_SPLIT_REGEX)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (rawTokens.isEmpty()) return null

        val allSyllables = mutableListOf<PinyinSyllablePhones>()
        for (token in rawTokens) {
            val segmented = segmentToken(token) ?: return null
            allSyllables += segmented
        }
        return if (allSyllables.isEmpty()) null else ParsedPinyin(reading, allSyllables)
    }

    private fun segmentToken(token: String): List<PinyinSyllablePhones>? {
        val extracted = extractBaseAndTones(token) ?: return null
        val (baseStr, tonePerChar, trailingDigitTone) = extracted
        if (baseStr.isEmpty()) return null

        // Strict Mandarin orthography pass first: disallow zero-initial a/o/e mid-token.
        val spans = dpSegment(baseStr, tonePerChar, allowMidZeroInitial = false)
            ?: dpSegment(baseStr, tonePerChar, allowMidZeroInitial = true)
            ?: return null

        val result = mutableListOf<PinyinSyllablePhones>()
        for (idx in spans.indices) {
            val span = spans[idx]
            val spanSub = baseStr.substring(span.start, span.end)
            val lookupKey = canonicalLookupKey(spanSub)
            val phones = pinyinToPhones[lookupKey] ?: return null
            val phoneIds = phones.map { meta.phoneToId[it] ?: return null }

            var tone = 0
            for (cIdx in span.start until span.end) {
                if (tonePerChar[cIdx] != 0) {
                    tone = tonePerChar[cIdx]
                    break
                }
            }
            if (tone == 0 && idx == spans.lastIndex && trailingDigitTone != 0) {
                tone = trailingDigitTone
            }
            if (tone == 0) {
                tone = 5
            }

            result += PinyinSyllablePhones(
                display = formatSyllableDisplay(spanSub, tone),
                base = lookupKey,
                tone = tone,
                phones = phones,
                phoneIds = phoneIds,
            )
        }
        return result
    }

    private data class Span(val start: Int, val end: Int)

    private fun dpSegment(
        baseStr: String,
        tonePerChar: IntArray,
        allowMidZeroInitial: Boolean,
    ): List<Span>? {
        val n = baseStr.length
        val bestCount = IntArray(n + 1) { Int.MAX_VALUE }
        val nextBreak = IntArray(n + 1) { -1 }
        bestCount[n] = 0

        for (i in n - 1 downTo 0) {
            val maxEnd = minOf(n, i + MAX_SYLLABLE_LEN)
            for (j in maxEnd downTo i + 1) {
                if (bestCount[j] == Int.MAX_VALUE) continue
                val sub = baseStr.substring(i, j)
                val key = canonicalLookupKey(sub)
                if (!pinyinToPhones.containsKey(key)) continue
                if (!allowMidZeroInitial && i > 0 && isZeroInitialVowelSyllable(key)) continue
                if (countTonedChars(tonePerChar, i, j) > 1) continue

                val candidateCount = 1 + bestCount[j]
                if (candidateCount < bestCount[i]) {
                    bestCount[i] = candidateCount
                    nextBreak[i] = j
                }
            }
        }

        if (bestCount[0] == Int.MAX_VALUE) {
            return handleErhuaTrailingR(baseStr, tonePerChar, allowMidZeroInitial)
        }

        val spans = mutableListOf<Span>()
        var pos = 0
        while (pos < n) {
            val end = nextBreak[pos]
            if (end <= pos) return null
            spans += Span(pos, end)
            pos = end
        }
        return spans
    }

    /**
     * Handles erhua words whose r-less base syllable is present in hsk_pinyin_to_phones.json
     * even when the specific "<syllable>r" key is omitted (e.g., "wánr" -> "wan" + "er" coda).
     */
    private fun handleErhuaTrailingR(
        baseStr: String,
        tonePerChar: IntArray,
        allowMidZeroInitial: Boolean,
    ): List<Span>? {
        if (baseStr.length >= 3 && baseStr.endsWith("r") && !baseStr.endsWith("er")) {
            val withoutR = baseStr.substring(0, baseStr.length - 1)
            val withoutRTones = tonePerChar.copyOf(withoutR.length)
            val prefixSpans = dpSegment(withoutR, withoutRTones, allowMidZeroInitial) ?: return null
            val last = prefixSpans.last()
            val candidateR = withoutR.substring(last.start, last.end) + "r"
            if (pinyinToPhones.containsKey(canonicalLookupKey(candidateR))) {
                return prefixSpans.dropLast(1) + Span(last.start, baseStr.length)
            }
        }
        return null
    }

    private fun countTonedChars(tonePerChar: IntArray, start: Int, end: Int): Int {
        var count = 0
        for (i in start until end) {
            if (tonePerChar[i] != 0) count++
        }
        return count
    }

    private fun isZeroInitialVowelSyllable(key: String): Boolean {
        val first = key.firstOrNull() ?: return false
        return first == 'a' || first == 'o' || first == 'e'
    }

    private fun canonicalLookupKey(sub: String): String {
        return if (sub.startsWith('j') || sub.startsWith('q') || sub.startsWith('x') || sub.startsWith('y')) {
            sub.replace('v', 'u')
        } else {
            sub
        }
    }

    private data class BaseExtraction(
        val base: String,
        val tonePerChar: IntArray,
        val trailingDigitTone: Int,
    )

    private fun extractBaseAndTones(rawToken: String): BaseExtraction? {
        var token = rawToken.lowercase(Locale.US)
            .replace("u:", "v")
            .replace("ü", "v")
        if (token.isEmpty()) return null

        var trailingDigitTone = 0
        val lastChar = token.last()
        if (lastChar in '1'..'5') {
            trailingDigitTone = lastChar - '0'
            token = token.dropLast(1)
        }
        if (token.isEmpty()) return null

        val decomposed = Normalizer.normalize(token, Normalizer.Form.NFD)
        val baseBuilder = StringBuilder(decomposed.length)
        val tones = mutableListOf<Int>()

        for (ch in decomposed) {
            when (ch) {
                '\u0304' -> if (tones.isNotEmpty()) tones[tones.lastIndex] = 1
                '\u0301' -> if (tones.isNotEmpty()) tones[tones.lastIndex] = 2
                '\u030C' -> if (tones.isNotEmpty()) tones[tones.lastIndex] = 3
                '\u0300' -> if (tones.isNotEmpty()) tones[tones.lastIndex] = 4
                '\u0308' -> if (baseBuilder.isNotEmpty() && baseBuilder[baseBuilder.lastIndex] == 'u') {
                    baseBuilder[baseBuilder.lastIndex] = 'v'
                }
                '\u0302' -> Unit // Combining circumflex (rare ê)
                in 'a'..'z' -> {
                    baseBuilder.append(ch)
                    tones.add(0)
                }
                else -> return null
            }
        }
        if (baseBuilder.isEmpty()) return null
        return BaseExtraction(baseBuilder.toString(), tones.toIntArray(), trailingDigitTone)
    }

    private fun formatSyllableDisplay(base: String, tone: Int): String {
        val readable = if (base.startsWith("j") || base.startsWith("q") || base.startsWith("x") || base.startsWith("y")) {
            base
        } else {
            base.replace('v', 'ü')
        }
        return if (tone in 1..5) "$readable($tone)" else readable
    }

    private fun stripParentheticals(text: String): String {
        return text.replace(PAREN_REGEX, " ").trim()
    }

    companion object {
        const val DEFAULT_MAX_SYLLABLES = 3
        private const val MAX_SYLLABLE_LEN = 7
        private val TOKEN_SPLIT_REGEX = Regex("[\\s'\\-·.,!?()]+")
        private val PAREN_REGEX = Regex("\\([^)]*\\)")

        const val META_ASSET_PATH = "ml/tone_model_meta.json"
        const val PINYIN_MAP_ASSET_PATH = "ml/hsk_pinyin_to_phones.json"

        fun fromAssets(context: Context): PinyinPhoneConverter {
            return context.assets.open(META_ASSET_PATH).use { metaStream ->
                context.assets.open(PINYIN_MAP_ASSET_PATH).use { pinyinStream ->
                    fromStreams(metaStream, pinyinStream)
                }
            }
        }

        fun fromStreams(metaStream: InputStream, pinyinStream: InputStream): PinyinPhoneConverter {
            val metaRoot = InputStreamReader(metaStream, Charsets.UTF_8).use {
                JsonParser.parseReader(it).asJsonObject
            }

            val vocabArray = metaRoot.getAsJsonArray("phoneme_vocab")
            val vocab = ArrayList<String>(vocabArray.size())
            val phoneToId = HashMap<String, Int>(vocabArray.size() * 2)
            for (i in 0 until vocabArray.size()) {
                val phone = vocabArray[i].asString
                vocab.add(phone)
                phoneToId[phone] = i
            }

            val gradingObj = metaRoot.getAsJsonObject("grading_rules")
            val dmgObj = gradingObj.getAsJsonObject("damage_multipliers")

            val meta = ToneModelMeta(
                phonemeVocab = vocab,
                phoneToId = phoneToId,
                blankIndex = metaRoot.get("blank_index").asInt,
                numPhoneClasses = metaRoot.get("num_phone_classes").asInt,
                numToneClasses = metaRoot.get("num_tone_classes").asInt,
                minPhoneSimPct = gradingObj.get("min_phone_sim_pct").asFloat,
                exactMatchToneScorePct = gradingObj.get("exact_match_tone_score_pct").asFloat,
                allTonesOkFloorPct = gradingObj.get("all_tones_ok_floor_pct").asFloat,
                sandhiT3T3Allowed = gradingObj.get("sandhi_t3_t3_allowed").asBoolean,
                exactDamagePct = dmgObj.get("EXACT_MATCH").asInt,
                lenientDamagePct = dmgObj.get("TONE_LENIENT_MATCH").asInt,
                noMatchDamagePct = dmgObj.get("NO_MATCH").asInt,
            )

            val pinyinObj = InputStreamReader(pinyinStream, Charsets.UTF_8).use {
                JsonParser.parseReader(it).asJsonObject
            }
            val map = HashMap<String, List<String>>(pinyinObj.size() * 2)
            for ((key, value) in pinyinObj.entrySet()) {
                val arr = value.asJsonArray
                val phones = ArrayList<String>(arr.size())
                for (el in arr) {
                    phones.add(el.asString)
                }
                map[key] = phones
            }

            return PinyinPhoneConverter(meta, map)
        }
    }
}
