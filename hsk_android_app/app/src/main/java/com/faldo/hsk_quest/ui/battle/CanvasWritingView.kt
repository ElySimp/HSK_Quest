package com.faldo.hsk_quest.ui.battle

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Custom canvas view that captures finger-drawn Hanzi stroke paths and vector points.
 * Part of Phase 2D input shells for writing challenges.
 */
class CanvasWritingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val paths = mutableListOf<Path>()
    private var currentPath = Path()

    // Stores (x, y) coordinates for each completed stroke for stroke order validation
    private val strokeVectors = mutableListOf<MutableList<PointF>>()
    private var currentStrokePoints = mutableListOf<PointF>()
    private var guideText: String? = null

    var onStrokeCountChanged: ((Int) -> Unit)? = null

    private val strokePaint = Paint().apply {
        color = Color.parseColor("#D4AF6A") // Champagne gold ink
        isAntiAlias = true
        isDither = true
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 14f
    }

    private val guideLinePaint = Paint().apply {
        color = Color.parseColor("#33D4AF6A") // Subtle dashed guidelines
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val guideTextPaint = Paint().apply {
        color = Color.parseColor("#26D4AF6A") // Faint translucent watermark ink
        isAntiAlias = true
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
    }

    /**
     * Sets the target Hanzi watermark to render behind the user's brush strokes
     * for stroke tracing practice.
     */
    fun setGuideText(hanzi: String?) {
        guideText = hanzi?.trim()?.takeIf { it.isNotEmpty() }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw cross grid guidelines (Mizige / Tianzige guide)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawLine(w / 2f, 0f, w / 2f, h, guideLinePaint)
        canvas.drawLine(0f, h / 2f, w, h / 2f, guideLinePaint)

        // Draw guide character watermark if non-blank
        val watermark = guideText
        if (!watermark.isNullOrBlank() && w > 0f && h > 0f) {
            val minDim = minOf(w, h)
            val charCount = watermark.length.coerceAtLeast(1)
            val targetSize = when (charCount) {
                1 -> minDim * 0.68f
                2 -> minDim * 0.48f
                else -> (minDim * 0.85f / charCount).coerceAtMost(minDim * 0.45f)
            }
            guideTextPaint.textSize = targetSize
            val textY = (h / 2f) - ((guideTextPaint.descent() + guideTextPaint.ascent()) / 2f)
            canvas.drawText(watermark, w / 2f, textY, guideTextPaint)
        }

        // Draw completed strokes
        for (p in paths) {
            canvas.drawPath(p, strokePaint)
        }
        // Draw current in-progress stroke
        canvas.drawPath(currentPath, strokePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                currentPath = Path()
                currentPath.moveTo(x, y)
                currentStrokePoints = mutableListOf(PointF(x, y))
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                currentPath.lineTo(x, y)
                currentStrokePoints.add(PointF(x, y))
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                currentPath.lineTo(x, y)
                currentStrokePoints.add(PointF(x, y))
                paths.add(currentPath)
                strokeVectors.add(currentStrokePoints)
                currentPath = Path()
                invalidate()

                onStrokeCountChanged?.invoke(paths.size)
                return true
            }

            else -> return false
        }
    }

    /**
     * Resets canvas paths and clear stroke logs.
     */
    fun clearCanvas() {
        paths.clear()
        currentPath.reset()
        strokeVectors.clear()
        currentStrokePoints.clear()
        invalidate()
        onStrokeCountChanged?.invoke(0)
    }

    /**
     * Alias for [clearCanvas] for convenient dictionary tracing reset.
     */
    fun clear() = clearCanvas()

    /**
     * Total number of distinct strokes lifted.
     */
    fun getStrokeCount(): Int = paths.size

    /**
     * Validates whether user drew at least the minimum required strokes (2+ strokes)
     * to avoid empty or single-dot cheat attacks.
     */
    fun isValidHanziAttempt(minStrokes: Int = 2): Boolean = paths.size >= minStrokes

    /**
     * Export raw touch vectors for AI writing engine verification.
     */
    fun getStrokeVectors(): List<List<PointF>> = strokeVectors.toList()
}
