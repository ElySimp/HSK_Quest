package com.faldo.hsk_quest.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import com.faldo.hsk_quest.data.local.DictionaryEntry
import com.faldo.hsk_quest.ui.practice.HanziCandidate
import com.faldo.hsk_quest.ui.practice.HanziHandwritingMatcher
import kotlin.math.max

/**
 * Unified output produced by any Hanzi handwriting recognizer (rule-based or custom ML).
 */
data class WritingRecognitionResult(
    val candidates: List<HanziCandidate>,
    val engineId: String,
    val inferenceLatencyMs: Long,
)

/**
 * Pluggable interface for Hanzi handwriting classification engines.
 * Allows switching cleanly between the offline rule-based baseline and a custom
 * Kaggle-trained CNN/MobileNet TFLite model for A/B research experiments.
 */
interface HandwritingRecognizer {
    val engineId: String

    suspend fun recognize(
        strokes: List<List<PointF>>,
        allEntries: List<DictionaryEntry>,
        limit: Int = 10,
    ): WritingRecognitionResult

    /**
     * Recognizes either a single Hanzi or multiple horizontally arranged Hanzi strokes.
     * Defaults to single-character [recognize] for baselines without stroke clustering.
     */
    suspend fun recognizeWithMultiCharSupport(
        strokes: List<List<PointF>>,
        allEntries: List<DictionaryEntry>,
        limit: Int = 12,
    ): WritingRecognitionResult = recognize(strokes, allEntries, limit)
}

/**
 * Algorithmic baseline handwriting recognizer backed by [HanziHandwritingMatcher]
 * (20x20 bitmap cosine similarity + quadrant balance + stroke count priors).
 */
class RuleBasedHandwritingRecognizer : HandwritingRecognizer {
    override val engineId: String = "rule_based_cosine_20x20"

    override suspend fun recognize(
        strokes: List<List<PointF>>,
        allEntries: List<DictionaryEntry>,
        limit: Int,
    ): WritingRecognitionResult {
        val startNs = System.nanoTime()
        val candidates = HanziHandwritingMatcher.recognizeCandidates(
            strokes = strokes,
            allEntries = allEntries,
            limit = limit,
        )
        val latencyMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
        return WritingRecognitionResult(
            candidates = candidates,
            engineId = engineId,
            inferenceLatencyMs = latencyMs,
        )
    }
}

/**
 * Utility to rasterize finger-drawn stroke vectors into normalized square Bitmaps
 * compatible with CASIA-HWDB CNN / MobileNet TFLite models.
 */
object CanvasBitmapRenderer {

    fun renderCenteredBitmap(
        strokes: List<List<PointF>>,
        outputSize: Int = 64,
        paddingRatio: Float = 0.12f,
        whiteBackground: Boolean = true,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(outputSize, outputSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(if (whiteBackground) Color.WHITE else Color.BLACK)

        val validStrokes = strokes.filter { it.isNotEmpty() }
        if (validStrokes.isEmpty()) return bitmap

        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (stroke in validStrokes) {
            for (pt in stroke) {
                if (pt.x < minX) minX = pt.x
                if (pt.x > maxX) maxX = pt.x
                if (pt.y < minY) minY = pt.y
                if (pt.y > maxY) maxY = pt.y
            }
        }

        val span = max(maxX - minX, maxY - minY).coerceAtLeast(1f)
        val usableSize = outputSize * (1f - 2f * paddingRatio)
        val scale = usableSize / span
        val drawWidth = (maxX - minX) * scale
        val drawHeight = (maxY - minY) * scale
        val offsetX = (outputSize - drawWidth) / 2f
        val offsetY = (outputSize - drawHeight) / 2f

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (whiteBackground) Color.BLACK else Color.WHITE
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            strokeWidth = (outputSize / 16f).coerceAtLeast(2.5f)
        }

        val path = Path()
        for (stroke in validStrokes) {
            path.reset()
            val first = stroke.first()
            val startX = offsetX + (first.x - minX) * scale
            val startY = offsetY + (first.y - minY) * scale
            path.moveTo(startX, startY)
            if (stroke.size == 1) {
                canvas.drawCircle(startX, startY, paint.strokeWidth / 2f, paint)
            } else {
                for (i in 1 until stroke.size) {
                    val pt = stroke[i]
                    path.lineTo(
                        offsetX + (pt.x - minX) * scale,
                        offsetY + (pt.y - minY) * scale,
                    )
                }
                canvas.drawPath(path, paint)
            }
        }

        return bitmap
    }
}

/**
 * Central registry holding the active handwriting engine so Battle & Dictionary screens
 * seamlessly adopt custom TFLite models once plugged in.
 */
object HandwritingEngineRegistry {
    @Volatile
    var activeRecognizer: HandwritingRecognizer = RuleBasedHandwritingRecognizer()
}
