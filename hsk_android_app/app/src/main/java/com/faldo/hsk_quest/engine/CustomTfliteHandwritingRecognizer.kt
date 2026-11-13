package com.faldo.hsk_quest.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import com.faldo.hsk_quest.data.local.DictionaryEntry
import com.faldo.hsk_quest.ui.practice.HanziCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Raw single-character class prediction from the Kaggle-trained MobileNetV2 TFLite model.
 */
data class RawCharPrediction(
    val hanzi: String,
    val confidence: Float,
)

/**
 * Custom MobileNetV2 (`hanzi_mobilenet_fp16.tflite`) TFLite recognizer trained on HSK 1-9 (3,087 chars).
 * Expects float32 tensor [1, 96, 96, 1] with white background (1.0f), black ink (0.0f), and 10% padding.
 */
class CustomTfliteHandwritingRecognizer private constructor(
    context: Context,
    private val modelAssetPath: String,
    private val metaAssetPath: String,
) : HandwritingRecognizer {

    private val appContext = context.applicationContext
    private val interpreterLock = Any()

    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()
    private var inputSize: Int = DEFAULT_INPUT_SIZE
    private var padMarginRatio: Float = DEFAULT_PAD_MARGIN
    private var modelName: String = "HanziMobileNetV2"

    private var inputBuffer: ByteBuffer? = null
    private var outputScores: Array<FloatArray>? = null

    @Volatile
    private var cachedEntriesSize: Int = -1

    @Volatile
    private var cachedCharMap: Map<String, DictionaryEntry> = emptyMap()

    @Volatile
    private var cachedWordMap: Map<String, DictionaryEntry> = emptyMap()

    override val engineId: String
        get() = "tflite_custom_${modelName}_${inputSize}"

    val isReady: Boolean
        get() = interpreter != null && labels.isNotEmpty()

    init {
        initializeSafely()
    }

    private fun initializeSafely() {
        runCatching {
            val metaJson = appContext.assets.open(metaAssetPath).bufferedReader(Charsets.UTF_8).use {
                it.readText()
            }
            val meta = JSONObject(metaJson)
            modelName = meta.optString("model_name", "HanziMobileNetV2")
            inputSize = meta.optInt("input_size", DEFAULT_INPUT_SIZE).coerceAtLeast(32)
            padMarginRatio = meta.optDouble("pad_margin_ratio", DEFAULT_PAD_MARGIN.toDouble())
                .toFloat()
                .coerceIn(0f, 0.35f)

            val labelArray = meta.getJSONArray("labels")
            val parsedLabels = ArrayList<String>(labelArray.length())
            for (i in 0 until labelArray.length()) {
                parsedLabels.add(labelArray.getString(i))
            }

            val modelBuffer = loadModelByteBuffer(modelAssetPath)
            val options = Interpreter.Options().apply {
                setNumThreads(4)
            }
            val tflite = Interpreter(modelBuffer, options)
            tflite.allocateTensors()

            val inTensorBytes = 4 * inputSize * inputSize
            inputBuffer = ByteBuffer.allocateDirect(inTensorBytes).order(ByteOrder.nativeOrder())
            outputScores = Array(1) { FloatArray(parsedLabels.size) }
            labels = parsedLabels
            interpreter = tflite
        }
    }

    private fun loadModelByteBuffer(assetPath: String): ByteBuffer {
        return try {
            appContext.assets.openFd(assetPath).use { afd ->
                FileInputStream(afd.fileDescriptor).use { fis ->
                    fis.channel.map(
                        FileChannel.MapMode.READ_ONLY,
                        afd.startOffset,
                        afd.declaredLength,
                    )
                }
            }
        } catch (_: Throwable) {
            val bytes = appContext.assets.open(assetPath).use { it.readBytes() }
            ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
                put(bytes)
                rewind()
            }
        }
    }

    override suspend fun recognize(
        strokes: List<List<PointF>>,
        allEntries: List<DictionaryEntry>,
        limit: Int,
    ): WritingRecognitionResult = withContext(Dispatchers.Default) {
        if (!isReady || strokes.isEmpty()) {
            return@withContext WritingRecognitionResult(
                candidates = emptyList(),
                engineId = engineId,
                inferenceLatencyMs = 0L,
            )
        }

        val startNs = System.nanoTime()
        val bitmap = CanvasBitmapRenderer.renderCenteredBitmap(
            strokes = strokes,
            outputSize = inputSize,
            paddingRatio = padMarginRatio,
            whiteBackground = true,
        )
        val rawPredictions = classifyBitmap(bitmap, topK = limit.coerceAtLeast(1))
        val candidates = mapRawPredictionsToCandidates(rawPredictions, allEntries)
        val latencyMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)

        WritingRecognitionResult(
            candidates = candidates,
            engineId = engineId,
            inferenceLatencyMs = latencyMs,
        )
    }

    /**
     * Supports both single-Hanzi input AND multiple horizontally spaced Hanzi written side by side
     * on the Dictionary handwriting pad. Combines full-pad single-character predictions with
     * clustered multi-character word matches backed by HSK vocabulary.
     */
    override suspend fun recognizeWithMultiCharSupport(
        strokes: List<List<PointF>>,
        allEntries: List<DictionaryEntry>,
        limit: Int,
    ): WritingRecognitionResult = withContext(Dispatchers.Default) {
        if (!isReady || strokes.isEmpty()) {
            return@withContext WritingRecognitionResult(
                candidates = emptyList(),
                engineId = engineId,
                inferenceLatencyMs = 0L,
            )
        }

        val startNs = System.nanoTime()
        ensureDictionaryIndexes(allEntries)
        val singleBitmap = CanvasBitmapRenderer.renderCenteredBitmap(
            strokes = strokes,
            outputSize = inputSize,
            paddingRatio = padMarginRatio,
            whiteBackground = true,
        )
        val singlePredictions = classifyBitmap(singleBitmap, topK = limit.coerceAtLeast(8))
        val singleCandidates = mapRawPredictionsToCandidates(singlePredictions, allEntries)

        val clusters = clusterStrokesHorizontally(strokes)
        if (clusters.size < 2 || clusters.size > 5) {
            val latencyMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
            return@withContext WritingRecognitionResult(
                candidates = singleCandidates.take(limit),
                engineId = engineId,
                inferenceLatencyMs = latencyMs,
            )
        }

        val slotPredictions = clusters.map { clusterStrokes ->
            val patch = CanvasBitmapRenderer.renderCenteredBitmap(
                strokes = clusterStrokes,
                outputSize = inputSize,
                paddingRatio = padMarginRatio,
                whiteBackground = true,
            )
            classifyBitmap(patch, topK = 3)
        }

        val multiWordCandidates = buildMultiCharCandidates(slotPredictions, allEntries, maxResults = 6)
        val merged = mergeSingleAndMultiCandidates(
            multiCandidates = multiWordCandidates,
            singleCandidates = singleCandidates,
            limit = limit,
        )
        val latencyMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)

        WritingRecognitionResult(
            candidates = merged,
            engineId = engineId,
            inferenceLatencyMs = latencyMs,
        )
    }

    /**
     * Classifies an already square-centered bitmap (white background, black ink) via TFLite.
     */
    fun classifyBitmap(bitmap: Bitmap, topK: Int = 5): List<RawCharPrediction> {
        val scaled = if (bitmap.width == inputSize && bitmap.height == inputSize) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        }

        val pixelCount = inputSize * inputSize
        val pixels = IntArray(pixelCount)
        scaled.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        val floatPatch = FloatArray(pixelCount)
        for (i in 0 until pixelCount) {
            val c = pixels[i]
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            floatPatch[i] = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
        }
        return classifyNormalizedPatch(floatPatch, topK)
    }

    /**
     * Classifies a [inputSize x inputSize] row-major grayscale array in [0f..1f]
     * (1.0 = white background, 0.0 = black ink).
     */
    fun classifyNormalizedPatch(grayscale01: FloatArray, topK: Int = 5): List<RawCharPrediction> {
        val tflite = interpreter ?: return emptyList()
        val inBuf = inputBuffer ?: return emptyList()
        val outBuf = outputScores ?: return emptyList()
        val expectedPixels = inputSize * inputSize
        if (grayscale01.size != expectedPixels || labels.isEmpty()) return emptyList()

        synchronized(interpreterLock) {
            inBuf.rewind()
            for (i in 0 until expectedPixels) {
                inBuf.putFloat(grayscale01[i].coerceIn(0f, 1f))
            }
            inBuf.rewind()
            tflite.run(inBuf, outBuf)

            val probs = outBuf[0]
            return selectTopK(probs, topK.coerceIn(1, probs.size))
        }
    }

    /**
     * Maps single-character raw predictions to dictionary-enriched [HanziCandidate]s.
     */
    fun mapRawPredictionsToCandidates(
        predictions: List<RawCharPrediction>,
        allEntries: List<DictionaryEntry>,
    ): List<HanziCandidate> {
        ensureDictionaryIndexes(allEntries)
        return predictions.map { pred ->
            val exact = cachedCharMap[pred.hanzi]
            if (exact != null) {
                HanziCandidate(
                    hanzi = pred.hanzi,
                    pinyin = exact.term.pinyin,
                    english = exact.term.english,
                    score = pred.confidence,
                )
            } else {
                HanziCandidate(
                    hanzi = pred.hanzi,
                    pinyin = "",
                    english = "HSK Hanzi (tap to search)",
                    score = pred.confidence,
                )
            }
        }
    }

    /**
     * Combines Top-K predictions per character slot into ranked multi-character sequences,
     * boosting sequences that match real HSK words in [allEntries].
     */
    fun buildMultiCharCandidates(
        slotPredictions: List<List<RawCharPrediction>>,
        allEntries: List<DictionaryEntry>,
        maxResults: Int = 6,
    ): List<HanziCandidate> {
        if (slotPredictions.isEmpty()) return emptyList()
        ensureDictionaryIndexes(allEntries)

        data class BeamItem(val text: String, val logProb: Float, val avgConf: Float)

        var beam = listOf(BeamItem(text = "", logProb = 0f, avgConf = 1f))
        val beamWidth = 10

        for ((slotIdx, preds) in slotPredictions.withIndex()) {
            val topSlot = preds.take(3)
            if (topSlot.isEmpty()) return emptyList()
            val nextBeam = ArrayList<BeamItem>(beam.size * topSlot.size)
            for (prev in beam) {
                for (p in topSlot) {
                    val safeConf = p.confidence.coerceIn(1e-4f, 1f)
                    val nextText = prev.text + p.hanzi
                    val nextLogProb = prev.logProb + ln(safeConf.toDouble()).toFloat()
                    val nextAvgConf = if (slotIdx == 0) {
                        p.confidence
                    } else {
                        ((prev.avgConf * slotIdx) + p.confidence) / (slotIdx + 1)
                    }
                    nextBeam.add(BeamItem(nextText, nextLogProb, nextAvgConf))
                }
            }
            beam = nextBeam.sortedByDescending { it.logProb }.take(beamWidth)
        }

        val ranked = beam.map { item ->
            val wordMatch = cachedWordMap[item.text]
            val dictBonus = if (wordMatch != null) 0.35f else 0f
            val pinyin = wordMatch?.term?.pinyin ?: item.text.mapNotNull { ch ->
                cachedCharMap[ch.toString()]?.term?.pinyin?.takeIf { it.isNotBlank() }
            }.joinToString(" ")
            val english = wordMatch?.term?.english ?: "Multi-Hanzi sequence"
            HanziCandidate(
                hanzi = item.text,
                pinyin = pinyin,
                english = english,
                score = (item.avgConf + dictBonus).coerceAtMost(1.35f),
            )
        }.sortedByDescending { it.score }

        return ranked.distinctBy { it.hanzi }.take(maxResults)
    }

    fun getInputSize(): Int = inputSize

    fun getPadMarginRatio(): Float = padMarginRatio

    fun lookupEntry(text: String, allEntries: List<DictionaryEntry>): DictionaryEntry? {
        ensureDictionaryIndexes(allEntries)
        return cachedWordMap[text] ?: cachedCharMap[text]
    }

    fun close() {
        synchronized(interpreterLock) {
            interpreter?.close()
            interpreter = null
        }
    }

    private fun selectTopK(probs: FloatArray, k: Int): List<RawCharPrediction> {
        val bestIdx = IntArray(k) { -1 }
        val bestVal = FloatArray(k) { Float.NEGATIVE_INFINITY }

        for (i in probs.indices) {
            val v = probs[i]
            if (v <= bestVal[k - 1]) continue
            var pos = k - 1
            while (pos > 0 && v > bestVal[pos - 1]) {
                bestVal[pos] = bestVal[pos - 1]
                bestIdx[pos] = bestIdx[pos - 1]
                pos--
            }
            bestVal[pos] = v
            bestIdx[pos] = i
        }

        return buildList(k) {
            for (j in 0 until k) {
                val idx = bestIdx[j]
                if (idx >= 0 && idx < labels.size) {
                    add(RawCharPrediction(hanzi = labels[idx], confidence = bestVal[j]))
                }
            }
        }
    }

    private fun ensureDictionaryIndexes(allEntries: List<DictionaryEntry>) {
        if (cachedEntriesSize == allEntries.size && cachedWordMap.isNotEmpty()) return

        val charMap = HashMap<String, DictionaryEntry>(3200)
        val wordMap = HashMap<String, DictionaryEntry>(allEntries.size)

        for (entry in allEntries) {
            val simp = entry.term.simplified
            val existingWord = wordMap[simp]
            if (existingWord == null || entry.levelKey < existingWord.levelKey) {
                wordMap[simp] = entry
            }
            if (simp.length == 1) {
                val existingChar = charMap[simp]
                if (existingChar == null || entry.levelKey < existingChar.levelKey) {
                    charMap[simp] = entry
                }
            }
        }

        // Fill missing single-char lookups from compound words when no standalone single-char entry exists
        for (entry in allEntries) {
            val simp = entry.term.simplified
            if (simp.length > 1) {
                for (ch in simp) {
                    val key = ch.toString()
                    if (!charMap.containsKey(key)) {
                        charMap[key] = entry
                    }
                }
            }
        }

        cachedWordMap = wordMap
        cachedCharMap = charMap
        cachedEntriesSize = allEntries.size
    }

    private fun mergeSingleAndMultiCandidates(
        multiCandidates: List<HanziCandidate>,
        singleCandidates: List<HanziCandidate>,
        limit: Int,
    ): List<HanziCandidate> {
        val result = ArrayList<HanziCandidate>(limit)
        val seen = HashSet<String>()

        // Put dictionary-verified multi-char words first, then top single-char candidates, then remaining
        val verifiedMulti = multiCandidates.filter { it.score >= 0.55f }
        val otherMulti = multiCandidates.filter { it.score < 0.55f }

        for (c in verifiedMulti) {
            if (result.size >= limit) break
            if (seen.add(c.hanzi)) result.add(c)
        }
        for (c in singleCandidates.take(4)) {
            if (result.size >= limit) break
            if (seen.add(c.hanzi)) result.add(c)
        }
        for (c in otherMulti) {
            if (result.size >= limit) break
            if (seen.add(c.hanzi)) result.add(c)
        }
        for (c in singleCandidates.drop(4)) {
            if (result.size >= limit) break
            if (seen.add(c.hanzi)) result.add(c)
        }
        return result
    }

    private fun clusterStrokesHorizontally(strokes: List<List<PointF>>): List<List<List<PointF>>> {
        val valid = strokes.filter { it.isNotEmpty() }
        if (valid.size < 2) return emptyList()

        data class StrokeGroup(
            val bounds: RectF,
            val items: MutableList<List<PointF>>,
        )

        var totalMinY = Float.POSITIVE_INFINITY
        var totalMaxY = Float.NEGATIVE_INFINITY
        val initialGroups = valid.map { stroke ->
            var minX = Float.POSITIVE_INFINITY
            var maxX = Float.NEGATIVE_INFINITY
            var minY = Float.POSITIVE_INFINITY
            var maxY = Float.NEGATIVE_INFINITY
            for (pt in stroke) {
                if (pt.x < minX) minX = pt.x
                if (pt.x > maxX) maxX = pt.x
                if (pt.y < minY) minY = pt.y
                if (pt.y > maxY) maxY = pt.y
            }
            totalMinY = min(totalMinY, minY)
            totalMaxY = max(totalMaxY, maxY)
            StrokeGroup(RectF(minX, minY, maxX, maxY), mutableListOf(stroke))
        }.sortedBy { it.bounds.left }

        val lineHeight = (totalMaxY - totalMinY).coerceAtLeast(36f)
        val merged = mutableListOf<StrokeGroup>()
        for (g in initialGroups) {
            val prev = merged.lastOrNull()
            if (prev == null) {
                merged.add(g)
                continue
            }
            val combinedLeft = min(prev.bounds.left, g.bounds.left)
            val combinedRight = max(prev.bounds.right, g.bounds.right)
            val combinedWidth = combinedRight - combinedLeft
            val gap = g.bounds.left - prev.bounds.right

            // Merge strokes that clearly overlap horizontally OR fit inside one square Hanzi footprint
            val overlapsOrTight = gap <= lineHeight * 0.14f
            val fitsInSingleSquare = combinedWidth <= lineHeight * 1.05f && gap <= lineHeight * 0.28f
            if (overlapsOrTight || fitsInSingleSquare) {
                prev.bounds.union(g.bounds)
                prev.items.addAll(g.items)
            } else {
                merged.add(g)
            }
        }

        return merged.map { it.items }
    }

    companion object {
        const val DEFAULT_TFLITE_ASSET = "ml/hanzi_mobilenet_fp16.tflite"
        const val DEFAULT_META_ASSET = "ml/hanzi_model_meta.json"
        const val DEFAULT_INPUT_SIZE = 96
        const val DEFAULT_PAD_MARGIN = 0.10f

        fun createOrNull(context: Context): CustomTfliteHandwritingRecognizer? {
            val engine = CustomTfliteHandwritingRecognizer(
                context = context,
                modelAssetPath = DEFAULT_TFLITE_ASSET,
                metaAssetPath = DEFAULT_META_ASSET,
            )
            return engine.takeIf { it.isReady }
        }
    }
}
