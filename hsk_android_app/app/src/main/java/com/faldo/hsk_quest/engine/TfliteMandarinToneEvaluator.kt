package com.faldo.hsk_quest.engine

import android.content.Context
import android.os.SystemClock
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class SyllableToneReport(
    val display: String,
    val expectedTone: Int,
    val predictedTone: Int,
    val targetProb: Float,
    val toneOk: Boolean,
    val windowStartFrame: Int,
    val windowEndFrame: Int,
)

data class ToneEvaluationResult(
    val grade: SpeechGrade,
    val damageMultiplierPct: Int,
    val phoneSimPct: Float,
    val toneScorePct: Float,
    val expectedPinyin: String,
    val predictedPhones: List<String>,
    val syllableReports: List<SyllableToneReport>,
    val feedback: String,
    val inferenceTimeMs: Long = 0L,
)

/**
 * Dual-head Mandarin speech tone & phoneme evaluator powered by LiteRT
 * (`assets/ml/hsk_flashcard_tonenet_fp16.tflite`).
 *
 * Combines greedy CTC phoneme edit-distance validation with Viterbi CTC forced alignment
 * and voicing-weighted per-syllable tone posterior scoring (including T3+T3 sandhi).
 */
class TfliteMandarinToneEvaluator private constructor(
    private val interpreter: Interpreter,
    val pinyinConverter: PinyinPhoneConverter,
    private val ctcOutputIdx: Int,
    private val toneOutputIdx: Int,
) {

    private val interpreterLock = Any()
    private val inputBuffer: ByteBuffer = ByteBuffer
        .allocateDirect(MandarinAudioFeatureExtractor.MAX_FRAMES * MandarinAudioFeatureExtractor.N_FEATURES * 4)
        .order(ByteOrder.nativeOrder())

    val meta: ToneModelMeta get() = pinyinConverter.meta

    fun isTermSupported(rawPinyin: String, maxSyllables: Int = PinyinPhoneConverter.DEFAULT_MAX_SYLLABLES): Boolean {
        return pinyinConverter.isSupportedFlashcardTerm(rawPinyin, maxSyllables)
    }

    /**
     * Evaluates recorded 16 kHz mono PCM float audio against a target HSK vocabulary term.
     */
    fun evaluateAudio(wav16k: FloatArray, targetPinyin: String, hanzi: String): ToneEvaluationResult? {
        val variants = pinyinConverter.segmentVariants(targetPinyin)
        if (variants.isEmpty()) return null

        val startMs = SystemClock.elapsedRealtime()
        val trimmed = MandarinAudioFeatureExtractor.trimToMaxWindow(wav16k)
        val features = MandarinAudioFeatureExtractor.extractFeatures(trimmed)

        val ctcLogits = Array(1) {
            Array(MandarinAudioFeatureExtractor.SUBSAMPLED_FRAMES) { FloatArray(meta.numPhoneClasses) }
        }
        val toneRaw = Array(1) {
            Array(MandarinAudioFeatureExtractor.SUBSAMPLED_FRAMES) { FloatArray(meta.numToneClasses) }
        }

        synchronized(interpreterLock) {
            inputBuffer.rewind()
            for (v in features.tensorData) {
                inputBuffer.putFloat(v)
            }
            inputBuffer.rewind()

            val outputs = HashMap<Int, Any>(2)
            outputs[ctcOutputIdx] = ctcLogits
            outputs[toneOutputIdx] = toneRaw
            interpreter.runForMultipleInputsOutputs(arrayOf<Any>(inputBuffer), outputs)
        }

        val elapsedMs = SystemClock.elapsedRealtime() - startMs

        // Grade across any valid pronunciation listed in the term entry and keep the strongest match.
        var best: ToneEvaluationResult? = null
        for (variant in variants) {
            val candidate = gradeUtterance(
                ctcLogits = ctcLogits[0],
                toneRaw = toneRaw[0],
                nSubFrames = features.nSubFrames,
                parsed = variant,
                meta = meta,
                hanzi = hanzi,
                inferenceTimeMs = elapsedMs,
            )
            if (best == null || isBetterResult(candidate, best)) {
                best = candidate
            }
        }
        return best
    }

    private fun isBetterResult(candidate: ToneEvaluationResult, current: ToneEvaluationResult): Boolean {
        if (candidate.damageMultiplierPct != current.damageMultiplierPct) {
            return candidate.damageMultiplierPct > current.damageMultiplierPct
        }
        val candScore = candidate.toneScorePct + candidate.phoneSimPct * 0.5f
        val currScore = current.toneScorePct + current.phoneSimPct * 0.5f
        return candScore > currScore
    }

    fun close() {
        synchronized(interpreterLock) {
            interpreter.close()
        }
    }

    companion object {
        const val MODEL_ASSET_PATH = "ml/hsk_flashcard_tonenet_fp16.tflite"
        private const val TAIL_PAD_FRAMES = 6

        fun createOrNull(context: Context): TfliteMandarinToneEvaluator? {
            return try {
                val converter = PinyinPhoneConverter.fromAssets(context)
                val modelBuffer = loadAssetModelBuffer(context, MODEL_ASSET_PATH)
                val options = Interpreter.Options().apply {
                    setNumThreads(2)
                }
                val interpreter = Interpreter(modelBuffer, options)

                val out0Shape = interpreter.getOutputTensor(0).shape()
                val out1Shape = interpreter.getOutputTensor(1).shape()
                val ctcIdx: Int
                val toneIdx: Int
                when {
                    out0Shape.lastOrNull() == converter.meta.numPhoneClasses &&
                        out1Shape.lastOrNull() == converter.meta.numToneClasses -> {
                        ctcIdx = 0
                        toneIdx = 1
                    }
                    out1Shape.lastOrNull() == converter.meta.numPhoneClasses &&
                        out0Shape.lastOrNull() == converter.meta.numToneClasses -> {
                        ctcIdx = 1
                        toneIdx = 0
                    }
                    else -> {
                        interpreter.close()
                        return null
                    }
                }

                TfliteMandarinToneEvaluator(interpreter, converter, ctcIdx, toneIdx)
            } catch (_: Throwable) {
                null
            }
        }

        private fun loadAssetModelBuffer(context: Context, assetPath: String): ByteBuffer {
            return try {
                context.assets.openFd(assetPath).use { afd ->
                    FileInputStream(afd.fileDescriptor).use { fis ->
                        fis.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
                    }
                }
            } catch (_: Throwable) {
                context.assets.open(assetPath).use { stream ->
                    val bytes = stream.readBytes()
                    ByteBuffer.allocateDirect(bytes.size)
                        .order(ByteOrder.nativeOrder())
                        .put(bytes)
                        .apply { flip() }
                }
            }
        }

        /**
         * Pure-Kotlin decoder & 3-tier flashcard grader matching Cell 7 of
         * kaggle_mandarin_tone_classifier.ipynb. Exposed internally for JVM unit tests.
         */
        internal fun gradeUtterance(
            ctcLogits: Array<FloatArray>,
            toneRaw: Array<FloatArray>,
            nSubFrames: Int,
            parsed: ParsedPinyin,
            meta: ToneModelMeta,
            hanzi: String,
            inferenceTimeMs: Long = 0L,
        ): ToneEvaluationResult {
            val tSteps = min(min(max(1, nSubFrames), ctcLogits.size), toneRaw.size)
            val targetIds = parsed.phoneIds
            val targetTones = parsed.tones
            val numSyllables = parsed.syllableCount

            val predIds = greedyDecodeIds(ctcLogits, tSteps, meta.blankIndex)
            val predPhones = predIds.mapNotNull { id -> meta.phonemeVocab.getOrNull(id) }
            val editDist = levenshtein(targetIds, predIds)
            val phoneSim = max(0f, 1f - editDist.toFloat() / max(1, targetIds.size))
            val phoneSimPct = phoneSim * 100f

            val toneProbs = normalizeToneProbabilities(toneRaw, tSteps)
            val windows = ctcForceAlignSyllables(
                ctcLogits = ctcLogits,
                tSteps = tSteps,
                syllables = parsed.syllables,
                blankIndex = meta.blankIndex,
            )

            val reports = ArrayList<SyllableToneReport>(numSyllables)
            var pTargetSum = 0f
            var allTonesOk = true

            for (j in 0 until numSyllables) {
                val win = windows[j]
                val wS = max(0, min(tSteps - 1, win.first))
                val wE = max(wS + 1, min(tSteps, win.second))

                val weightedToneSum = FloatArray(5)
                var weightTotal = 0f
                for (t in wS until wE) {
                    val voicedW = max(0.03f, 1.0f - toneProbs[t][0])
                    weightTotal += voicedW
                    for (toneIdx in 0 until 5) {
                        weightedToneSum[toneIdx] += toneProbs[t][toneIdx + 1] * voicedW
                    }
                }

                val dist = FloatArray(5)
                val distSum = weightedToneSum.sum()
                if (distSum > 0f) {
                    for (k in 0 until 5) dist[k] = weightedToneSum[k] / distSum
                } else {
                    for (k in 0 until 5) dist[k] = 0.2f
                }

                var bestToneIdx = 0
                for (k in 1 until 5) {
                    if (dist[k] > dist[bestToneIdx]) bestToneIdx = k
                }
                val predTone = bestToneIdx + 1
                val expTone = targetTones[j]
                val isSandhi = meta.sandhiT3T3Allowed &&
                    (j + 1 < numSyllables) &&
                    expTone == 3 &&
                    targetTones[j + 1] == 3

                val pTarget: Float
                val toneOk: Boolean
                if (isSandhi) {
                    pTarget = max(dist[2], dist[1])
                    toneOk = (predTone == 2 || predTone == 3)
                } else {
                    pTarget = dist[(expTone - 1).coerceIn(0, 4)]
                    toneOk = (predTone == expTone)
                }

                pTargetSum += pTarget
                if (!toneOk) allTonesOk = false

                reports += SyllableToneReport(
                    display = parsed.syllables[j].display,
                    expectedTone = expTone,
                    predictedTone = predTone,
                    targetProb = pTarget,
                    toneOk = toneOk,
                    windowStartFrame = wS,
                    windowEndFrame = wE,
                )
            }

            val toneScorePct = if (numSyllables > 0) (pTargetSum / numSyllables) * 100f else 0f

            val grade: SpeechGrade
            val dmgPct: Int
            when {
                phoneSimPct < meta.minPhoneSimPct -> {
                    grade = SpeechGrade.NO_MATCH
                    dmgPct = meta.noMatchDamagePct
                }
                toneScorePct >= meta.exactMatchToneScorePct ||
                    (allTonesOk && toneScorePct >= meta.allTonesOkFloorPct) -> {
                    grade = SpeechGrade.EXACT_MATCH
                    dmgPct = meta.exactDamagePct
                }
                else -> {
                    grade = SpeechGrade.TONE_LENIENT_MATCH
                    dmgPct = meta.lenientDamagePct
                }
            }

            val feedback = buildFeedback(
                grade = grade,
                dmgPct = dmgPct,
                phoneSimPct = phoneSimPct,
                toneScorePct = toneScorePct,
                reports = reports,
                hanzi = hanzi,
            )

            return ToneEvaluationResult(
                grade = grade,
                damageMultiplierPct = dmgPct,
                phoneSimPct = phoneSimPct,
                toneScorePct = toneScorePct,
                expectedPinyin = parsed.raw,
                predictedPhones = predPhones,
                syllableReports = reports,
                feedback = feedback,
                inferenceTimeMs = inferenceTimeMs,
            )
        }

        internal fun greedyDecodeIds(
            ctcLogits: Array<FloatArray>,
            tSteps: Int,
            blankIndex: Int,
        ): List<Int> {
            val out = mutableListOf<Int>()
            var prev = -1
            for (t in 0 until tSteps) {
                val row = ctcLogits[t]
                var bestIdx = 0
                var bestVal = row[0]
                for (c in 1 until row.size) {
                    if (row[c] > bestVal) {
                        bestVal = row[c]
                        bestIdx = c
                    }
                }
                if (bestIdx != blankIndex && bestIdx != prev) {
                    out += bestIdx
                }
                prev = bestIdx
            }
            return out
        }

        internal fun levenshtein(a: List<Int>, b: List<Int>): Int {
            if (a.isEmpty()) return b.size
            if (b.isEmpty()) return a.size
            var prev = IntArray(b.size + 1) { it }
            var curr = IntArray(b.size + 1)
            for (i in a.indices) {
                curr[0] = i + 1
                val ai = a[i]
                for (j in b.indices) {
                    val cost = if (ai == b[j]) 0 else 1
                    curr[j + 1] = min(
                        min(curr[j] + 1, prev[j + 1] + 1),
                        prev[j] + cost,
                    )
                }
                val tmp = prev
                prev = curr
                curr = tmp
            }
            return prev[b.size]
        }

        internal fun ctcForceAlignSyllables(
            ctcLogits: Array<FloatArray>,
            tSteps: Int,
            syllables: List<PinyinSyllablePhones>,
            blankIndex: Int,
        ): List<Pair<Int, Int>> {
            val k = syllables.size
            if (k == 0) return emptyList()

            val flatIds = ArrayList<Int>(k * 3)
            val sylOfPhone = ArrayList<Int>(k * 3)
            for (sIdx in 0 until k) {
                for (pid in syllables[sIdx].phoneIds) {
                    flatIds += pid
                    sylOfPhone += sIdx
                }
            }
            val lLen = flatIds.size
            if (lLen == 0) {
                return List(k) { j -> (j * tSteps / k) to ((j + 1) * tSteps / k) }
            }

            val mLen = 2 * lLen + 1
            val states = IntArray(mLen) { s ->
                if (s % 2 == 0) blankIndex else flatIds[(s - 1) / 2]
            }

            val logProbs = Array(tSteps) { t -> logSoftmaxClamped(ctcLogits[t]) }
            val negInf = -1e9f
            val dp = Array(tSteps) { FloatArray(mLen) { negInf } }
            val bp = Array(tSteps) { IntArray(mLen) { -1 } }

            dp[0][0] = logProbs[0][states[0]]
            if (mLen > 1) {
                dp[0][1] = logProbs[0][states[1]]
            }

            for (t in 1 until tSteps) {
                val lpRow = logProbs[t]
                val prevDp = dp[t - 1]
                val currDp = dp[t]
                val currBp = bp[t]
                for (s in 0 until mLen) {
                    var bestVal = prevDp[s]
                    var bestPrev = s
                    if (s >= 1 && prevDp[s - 1] > bestVal) {
                        bestVal = prevDp[s - 1]
                        bestPrev = s - 1
                    }
                    if (s >= 2 && s % 2 == 1 && states[s] != states[s - 2] && prevDp[s - 2] > bestVal) {
                        bestVal = prevDp[s - 2]
                        bestPrev = s - 2
                    }
                    if (bestVal > negInf * 0.5f) {
                        currDp[s] = bestVal + lpRow[states[s]]
                        currBp[s] = bestPrev
                    }
                }
            }

            val lastM1 = dp[tSteps - 1][mLen - 1]
            val lastM2 = if (mLen >= 2) dp[tSteps - 1][mLen - 2] else negInf
            var cur = if (lastM1 >= lastM2) mLen - 1 else mLen - 2

            if (dp[tSteps - 1][cur] <= -1e8f) {
                return List(k) { j -> (j * tSteps / k) to max(j * tSteps / k + 1, (j + 1) * tSteps / k) }
            }

            val path = IntArray(tSteps)
            for (t in tSteps - 1 downTo 0) {
                path[t] = cur
                if (t > 0) {
                    val prev = bp[t][cur]
                    if (prev < 0) {
                        return List(k) { j -> (j * tSteps / k) to max(j * tSteps / k + 1, (j + 1) * tSteps / k) }
                    }
                    cur = prev
                }
            }

            val firstT = IntArray(k) { Int.MAX_VALUE }
            val lastT = IntArray(k) { -1 }
            for (t in 0 until tSteps) {
                val s = path[t]
                if (s % 2 == 1) {
                    val sylIdx = sylOfPhone[(s - 1) / 2]
                    if (t < firstT[sylIdx]) firstT[sylIdx] = t
                    if (t > lastT[sylIdx]) lastT[sylIdx] = t
                }
            }

            for (j in 0 until k) {
                if (lastT[j] < 0) {
                    val fallback = if (j == 0) 0 else min(tSteps - 1, lastT[j - 1] + 1)
                    firstT[j] = fallback
                    lastT[j] = fallback
                }
            }

            val windows = ArrayList<Pair<Int, Int>>(k)
            for (j in 0 until k) {
                val wS = firstT[j]
                val wE = if (j + 1 < k) {
                    min(tSteps, max(wS + 1, firstT[j + 1]))
                } else {
                    min(tSteps, max(wS + 1, lastT[j] + TAIL_PAD_FRAMES))
                }
                windows += (wS to wE)
            }
            return windows
        }

        private fun logSoftmaxClamped(logits: FloatArray): FloatArray {
            var maxVal = logits[0]
            for (i in 1 until logits.size) {
                if (logits[i] > maxVal) maxVal = logits[i]
            }
            var sumExp = 0.0
            for (v in logits) {
                sumExp += exp((v - maxVal).toDouble())
            }
            val logZ = maxVal + ln(max(sumExp, 1e-12))
            val minLogProb = ln(1e-9).toFloat()
            return FloatArray(logits.size) { i ->
                max((logits[i] - logZ).toFloat(), minLogProb)
            }
        }

        private fun normalizeToneProbabilities(toneRaw: Array<FloatArray>, tSteps: Int): Array<FloatArray> {
            return Array(tSteps) { t ->
                val row = toneRaw[t]
                var hasNeg = false
                var sum = 0f
                for (v in row) {
                    if (v < -1e-4f) hasNeg = true
                    sum += v
                }
                if (!hasNeg && abs(sum - 1f) <= 1e-2f) {
                    row.copyOf()
                } else {
                    var maxVal = row[0]
                    for (i in 1 until row.size) {
                        if (row[i] > maxVal) maxVal = row[i]
                    }
                    val probs = FloatArray(row.size)
                    var expSum = 0.0
                    for (i in row.indices) {
                        val e = exp((row[i] - maxVal).toDouble())
                        probs[i] = e.toFloat()
                        expSum += e
                    }
                    val inv = (1.0 / max(expSum, 1e-12)).toFloat()
                    for (i in probs.indices) probs[i] *= inv
                    probs
                }
            }
        }

        private fun buildFeedback(
            grade: SpeechGrade,
            dmgPct: Int,
            phoneSimPct: Float,
            toneScorePct: Float,
            reports: List<SyllableToneReport>,
            hanzi: String,
        ): String {
            val phoneInt = phoneSimPct.roundToInt()
            val toneInt = toneScorePct.roundToInt()
            val sylSummary = reports.joinToString(" · ") { r ->
                if (r.toneOk) "${r.display}✓" else "${r.display}->T${r.predictedTone}"
            }
            return when (grade) {
                SpeechGrade.EXACT_MATCH ->
                    "🎙️ PERFECT TONES ($dmgPct% DMG)! $hanzi · $sylSummary · Tone $toneInt%, Phone $phoneInt%"
                SpeechGrade.TONE_LENIENT_MATCH ->
                    "🎙️ Word understood, check tone ($dmgPct% DMG)! $sylSummary · Tone $toneInt%, Phone $phoneInt%"
                SpeechGrade.NO_MATCH ->
                    "🎙️ Pronunciation unclear ($dmgPct% DMG) · Expected $hanzi · $sylSummary · Phone $phoneInt%, Tone $toneInt%"
            }
        }
    }
}
