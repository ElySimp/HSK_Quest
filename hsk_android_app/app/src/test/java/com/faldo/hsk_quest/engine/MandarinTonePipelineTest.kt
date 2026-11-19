package com.faldo.hsk_quest.engine

import com.faldo.hsk_quest.data.model.BattleState
import com.faldo.hsk_quest.data.model.HskLevelFile
import com.faldo.hsk_quest.data.model.Monster
import com.faldo.hsk_quest.data.model.MonsterType
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import kotlin.math.PI
import kotlin.math.sin

class MandarinTonePipelineTest {

    private fun resolveAssetFile(relativeAssetPath: String): File {
        val candidates = listOf(
            File("src/main/assets/$relativeAssetPath"),
            File("app/src/main/assets/$relativeAssetPath"),
            File("hsk_android_app/app/src/main/assets/$relativeAssetPath"),
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("Could not locate asset file: $relativeAssetPath from ${File(".").absolutePath}")
    }

    private fun loadRealConverter(): PinyinPhoneConverter {
        val metaFile = resolveAssetFile(PinyinPhoneConverter.META_ASSET_PATH)
        val mapFile = resolveAssetFile(PinyinPhoneConverter.PINYIN_MAP_ASSET_PATH)
        return FileInputStream(metaFile).use { metaStream ->
            FileInputStream(mapFile).use { mapStream ->
                PinyinPhoneConverter.fromStreams(metaStream, mapStream)
            }
        }
    }

    @Test
    fun pinyinConverter_segmentsSpacelessApostropheAndErhuaTerms() {
        val converter = loadRealConverter()

        val yixia = converter.segmentPrimary("yíxià")
        assertNotNull(yixia)
        assertEquals(listOf("yi", "xia"), yixia!!.syllables.map { it.base })
        assertEquals(listOf(2, 4), yixia.tones)

        val xuexi = converter.segmentPrimary("xuéxí")
        assertNotNull(xuexi)
        assertEquals(listOf("xue", "xi"), xuexi!!.syllables.map { it.base })
        assertEquals(listOf(2, 2), xuexi.tones)
        assertEquals(listOf("x", "ve", "x", "i"), xuexi.syllables.flatMap { it.phones })

        val nver = converter.segmentPrimary("nǚ'ér")
        assertNotNull(nver)
        assertEquals(listOf("nv", "er"), nver!!.syllables.map { it.base })
        assertEquals(listOf(3, 2), nver.tones)
        assertEquals(listOf("n", "v", "er"), nver.syllables.flatMap { it.phones })

        val dongxi = converter.segmentPrimary("dōngxi")
        assertNotNull(dongxi)
        assertEquals(listOf("dong", "xi"), dongxi!!.syllables.map { it.base })
        assertEquals(listOf(1, 5), dongxi.tones)

        val zher = converter.segmentPrimary("zhèr")
        assertNotNull(zher)
        assertEquals(listOf("zher"), zher!!.syllables.map { it.base })
        assertEquals(listOf(4), zher.tones)
        assertEquals(listOf("zh", "er"), zher.syllables.flatMap { it.phones })

        val multiReading = converter.segmentVariants("hái / huán")
        assertEquals(2, multiReading.size)
        assertEquals(listOf("hai"), multiReading[0].syllables.map { it.base })
        assertEquals(listOf("huan"), multiReading[1].syllables.map { it.base })
    }

    @Test
    fun pinyinConverter_handlesMajorityOfRealHskVocabulary() {
        val converter = loadRealConverter()
        val gson = Gson()
        var totalTerms = 0
        var parsedTerms = 0

        for (level in 1..6) {
            val file = resolveAssetFile("hsk_data/hsk-$level.json")
            val parsedLevel = file.reader(Charsets.UTF_8).use { reader ->
                gson.fromJson(reader, HskLevelFile::class.java)
            }
            for (term in parsedLevel.terms) {
                if (term.simplified.any { it in '\u4E00'..'\u9FFF' }) {
                    totalTerms++
                    if (converter.segmentPrimary(term.pinyin) != null) {
                        parsedTerms++
                    }
                }
            }
        }

        assertTrue("Expected > 4000 HSK terms, got $totalTerms", totalTerms > 4000)
        val coverage = parsedTerms.toDouble() / totalTerms
        assertTrue(
            "Expected >= 92% HSK pinyin parsing coverage, got ${(coverage * 100).toInt()}% ($parsedTerms/$totalTerms)",
            coverage >= 0.92,
        )
    }

    @Test
    fun featureExtractor_producesValidFiniteTensorAndDetectsHarmonicF0() {
        val sampleRate = MandarinAudioFeatureExtractor.SAMPLE_RATE
        val durationSamples = sampleRate // 1.0s = 16,000 samples
        val voicedHarmonic = FloatArray(durationSamples) { n ->
            val t = n.toDouble() / sampleRate
            // 180 Hz fundamental + harmonics to ensure clear YIN periodicity and mel bins
            (0.35 * sin(2.0 * PI * 180.0 * t) +
                0.20 * sin(2.0 * PI * 360.0 * t) +
                0.10 * sin(2.0 * PI * 540.0 * t)).toFloat()
        }

        assertTrue(MandarinAudioFeatureExtractor.hasAudibleSpeech(voicedHarmonic))
        assertFalse(MandarinAudioFeatureExtractor.hasAudibleSpeech(FloatArray(sampleRate) { 0.0005f }))

        val features = MandarinAudioFeatureExtractor.extractFeatures(voicedHarmonic)
        assertEquals(MandarinAudioFeatureExtractor.MAX_FRAMES * MandarinAudioFeatureExtractor.N_FEATURES, features.tensorData.size)
        assertEquals(98, features.nFrames)
        assertEquals(49, features.nSubFrames)

        for (v in features.tensorData) {
            assertFalse("Feature tensor must not contain NaN", v.isNaN())
            assertFalse("Feature tensor must not contain Infinite", v.isInfinite())
        }

        // Verify middle active frame exhibits strong voicing probability (channel 84)
        val midRowOffset = 45 * MandarinAudioFeatureExtractor.N_FEATURES
        val midVoicing = features.tensorData[midRowOffset + 84]
        assertTrue("Expected voicing prob > 0.75 on 180 Hz harmonic tone, got $midVoicing", midVoicing > 0.75f)

        // Trailing frames beyond nFrames must remain zero-padded
        val tailOffset = features.nFrames * MandarinAudioFeatureExtractor.N_FEATURES
        for (i in tailOffset until features.tensorData.size) {
            assertEquals(0f, features.tensorData[i], 1e-6f)
        }
    }

    @Test
    fun featureExtractor_trimsLongBufferedAudioInside2400MsWindow() {
        val sampleRate = MandarinAudioFeatureExtractor.SAMPLE_RATE
        val totalSamples = (3.2 * sampleRate).toInt() // 51,200 samples (> 38,400 limit)
        val longClip = FloatArray(totalSamples)
        // 1.1s silence, 1.0s active utterance, 1.1s trailing silence
        val activeStart = (1.1 * sampleRate).toInt()
        val activeEnd = (2.1 * sampleRate).toInt()
        for (n in activeStart until activeEnd) {
            val t = n.toDouble() / sampleRate
            longClip[n] = (0.3 * sin(2.0 * PI * 200.0 * t)).toFloat()
        }

        val trimmed = MandarinAudioFeatureExtractor.trimToMaxWindow(longClip)
        assertTrue("Trimmed length must fit in 38,400 samples, was ${trimmed.size}", trimmed.size <= MandarinAudioFeatureExtractor.CLIP_MAX_SAMPLES)
        assertTrue("Trimmed clip must retain active speech", MandarinAudioFeatureExtractor.hasAudibleSpeech(trimmed))
    }

    @Test
    fun toneEvaluator_gradesExactMatchLenientToneAndSandhiAndNoMatchCorrectly() {
        val converter = loadRealConverter()
        val meta = converter.meta
        val xuexi = converter.segmentPrimary("xuéxí")!!
        val nihao = converter.segmentPrimary("nǐhǎo")!!

        val tSteps = 40
        // 1) EXACT_MATCH on xuéxí (T2, T2)
        val exactCtc = buildSyntheticCtcLogits(tSteps, meta.numPhoneClasses, meta.blankIndex, xuexi.phoneIds)
        val exactTones = buildSyntheticToneLogits(
            tSteps = tSteps,
            numClasses = meta.numToneClasses,
            splitFrame = 20,
            firstTone = 2,
            secondTone = 2,
        )
        val exactResult = TfliteMandarinToneEvaluator.gradeUtterance(
            ctcLogits = exactCtc,
            toneRaw = exactTones,
            nSubFrames = tSteps,
            parsed = xuexi,
            meta = meta,
            hanzi = "学习",
        )
        assertEquals(SpeechGrade.EXACT_MATCH, exactResult.grade)
        assertEquals(120, exactResult.damageMultiplierPct)
        assertTrue(exactResult.phoneSimPct >= 95f)
        assertTrue(exactResult.toneScorePct >= 85f)

        // 2) TONE_LENIENT_MATCH on xuéxí when pronounced with T4 + T4 tones
        val wrongTones = buildSyntheticToneLogits(
            tSteps = tSteps,
            numClasses = meta.numToneClasses,
            splitFrame = 20,
            firstTone = 4,
            secondTone = 4,
        )
        val lenientResult = TfliteMandarinToneEvaluator.gradeUtterance(
            ctcLogits = exactCtc,
            toneRaw = wrongTones,
            nSubFrames = tSteps,
            parsed = xuexi,
            meta = meta,
            hanzi = "学习",
        )
        assertEquals(SpeechGrade.TONE_LENIENT_MATCH, lenientResult.grade)
        assertEquals(50, lenientResult.damageMultiplierPct)
        assertTrue(lenientResult.phoneSimPct >= 95f)

        // 3) T3 + T3 sandhi: nǐhǎo spoken with T2 + T3 earns EXACT_MATCH (120%)
        val nihaoCtc = buildSyntheticCtcLogits(tSteps, meta.numPhoneClasses, meta.blankIndex, nihao.phoneIds)
        val sandhiTones = buildSyntheticToneLogits(
            tSteps = tSteps,
            numClasses = meta.numToneClasses,
            splitFrame = 20,
            firstTone = 2,
            secondTone = 3,
        )
        val sandhiResult = TfliteMandarinToneEvaluator.gradeUtterance(
            ctcLogits = nihaoCtc,
            toneRaw = sandhiTones,
            nSubFrames = tSteps,
            parsed = nihao,
            meta = meta,
            hanzi = "你好",
        )
        assertEquals(SpeechGrade.EXACT_MATCH, sandhiResult.grade)
        assertEquals(120, sandhiResult.damageMultiplierPct)

        // 4) NO_MATCH when phoneme sequence does not match target
        val wrongWordIds = converter.segmentPrimary("guójiā")!!.phoneIds
        val wrongCtc = buildSyntheticCtcLogits(tSteps, meta.numPhoneClasses, meta.blankIndex, wrongWordIds)
        val noMatchResult = TfliteMandarinToneEvaluator.gradeUtterance(
            ctcLogits = wrongCtc,
            toneRaw = exactTones,
            nSubFrames = tSteps,
            parsed = xuexi,
            meta = meta,
            hanzi = "学习",
        )
        assertEquals(SpeechGrade.NO_MATCH, noMatchResult.grade)
        assertEquals(0, noMatchResult.damageMultiplierPct)
    }

    @Test
    fun battleEngine_appliesExactAndLenientSpeakingMultipliers() {
        val monster = Monster(
            id = "tone_guardian",
            name = "Tone Guardian",
            areaId = 1,
            type = MonsterType.ELITE,
            maxHp = 200,
            def = 0,
            dmg = 10,
            xpReward = 20,
            coinReward = 20,
        )
        val initial = BattleState(
            monster = monster,
            monsterHp = 200.0,
            playerHp = 100.0,
            playerMaxHp = 100,
        )

        val baseNormal = DamageCalculator.playerDamageExact(statStr = 0, statDex = 0, monsterDef = 0, isCritical = false)

        val exactState = BattleEngine.applyAnswer(
            currentState = initial,
            isCorrect = true,
            elapsedTimeMs = 1_000L,
            statStr = 0,
            statDex = 0,
            statDef = 0,
            forceCritical = false,
            damageMultiplierOverride = 1.2,
        )
        assertEquals(DamageCalculator.roundTenth(baseNormal * 1.2), exactState.lastDamageDealt, 0.05)
        assertFalse(exactState.wasCritical)

        val lenientState = BattleEngine.applyAnswer(
            currentState = initial,
            isCorrect = true,
            elapsedTimeMs = 1_000L,
            statStr = 0,
            statDex = 0,
            statDef = 0,
            forceCritical = false,
            damageMultiplierOverride = 0.5,
        )
        assertEquals(DamageCalculator.roundTenth(baseNormal * 0.5), lenientState.lastDamageDealt, 0.05)

        val noMatchState = BattleEngine.applyAnswer(
            currentState = initial,
            isCorrect = false,
            elapsedTimeMs = 1_000L,
            statStr = 0,
            statDex = 0,
            statDef = 0,
        )
        assertEquals(0.0, noMatchState.lastDamageDealt, 0.001)
    }

    private fun buildSyntheticCtcLogits(
        tSteps: Int,
        numClasses: Int,
        blankIndex: Int,
        phoneIds: List<Int>,
    ): Array<FloatArray> {
        val out = Array(tSteps) { FloatArray(numClasses) { -8f } }
        for (t in 0 until tSteps) {
            out[t][blankIndex] = 4f
        }
        val stride = maxOf(1, tSteps / (phoneIds.size + 1))
        for ((idx, pid) in phoneIds.withIndex()) {
            val frame = ((idx + 1) * stride).coerceIn(0, tSteps - 1)
            out[frame][blankIndex] = -4f
            out[frame][pid] = 6f
        }
        return out
    }

    private fun buildSyntheticToneLogits(
        tSteps: Int,
        numClasses: Int,
        splitFrame: Int,
        firstTone: Int,
        secondTone: Int,
    ): Array<FloatArray> {
        return Array(tSteps) { t ->
            FloatArray(numClasses) { c ->
                val targetTone = if (t < splitFrame) firstTone else secondTone
                when {
                    c == targetTone -> 5.0f
                    c == 0 -> -4.0f
                    else -> -3.0f
                }
            }
        }
    }
}
