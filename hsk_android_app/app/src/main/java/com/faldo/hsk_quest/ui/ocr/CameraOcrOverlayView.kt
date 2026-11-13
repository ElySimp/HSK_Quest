package com.faldo.hsk_quest.ui.ocr

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import kotlin.math.min

/**
 * Pleco-style target reticle overlay positioned above CameraX PreviewView.
 * Renders a semi-transparent outer mask, crisp gold corner brackets around the active
 * Hanzi detection ROI box, and exposes normalized [0..1] ROI bounds to match crop geometry.
 */
class CameraOcrOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val scrimPaint = Paint().apply {
        color = Color.parseColor("#8C080D16")
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#55D4AF6A")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D4AF6A")
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
    }

    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#44D4AF6A")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F2E8D5")
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            12f,
            resources.displayMetrics,
        )
    }

    private val roiRect = RectF()
    private var multiCharMode: Boolean = false
    private var boxScale: Float = 1.0f

    fun setMultiCharMode(enabled: Boolean) {
        if (multiCharMode != enabled) {
            multiCharMode = enabled
            recomputeRoi()
            invalidate()
        }
    }

    fun setBoxScale(scale: Float) {
        val clamped = scale.coerceIn(0.65f, 1.35f)
        if (boxScale != clamped) {
            boxScale = clamped
            recomputeRoi()
            invalidate()
        }
    }

    /**
     * Returns the ROI box normalized to [0f..1f] relative to this overlay's dimensions.
     */
    fun getNormalizedRoi(): RectF {
        if (width <= 0 || height <= 0) {
            return if (multiCharMode) {
                RectF(0.10f, 0.36f, 0.90f, 0.64f)
            } else {
                RectF(0.24f, 0.24f, 0.76f, 0.76f)
            }
        }
        if (roiRect.isEmpty) recomputeRoi()
        return RectF(
            (roiRect.left / width).coerceIn(0f, 1f),
            (roiRect.top / height).coerceIn(0f, 1f),
            (roiRect.right / width).coerceIn(0f, 1f),
            (roiRect.bottom / height).coerceIn(0f, 1f),
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputeRoi()
    }

    private fun recomputeRoi() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val minDim = min(w, h)
        val boxW: Float
        val boxH: Float
        if (multiCharMode) {
            boxW = (w * 0.78f * boxScale).coerceIn(w * 0.45f, w * 0.92f)
            boxH = (minDim * 0.30f * boxScale).coerceIn(h * 0.18f, h * 0.48f)
        } else {
            val side = (minDim * 0.54f * boxScale).coerceIn(minDim * 0.30f, minDim * 0.84f)
            boxW = side
            boxH = side
        }

        val cx = w / 2f
        val cy = h * 0.52f
        roiRect.set(
            cx - boxW / 2f,
            cy - boxH / 2f,
            cx + boxW / 2f,
            cy + boxH / 2f,
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        if (roiRect.isEmpty) recomputeRoi()

        // Four outer scrim rects keep the ROI clear with zero offscreen buffer overhead
        canvas.drawRect(0f, 0f, w, roiRect.top, scrimPaint)
        canvas.drawRect(0f, roiRect.bottom, w, h, scrimPaint)
        canvas.drawRect(0f, roiRect.top, roiRect.left, roiRect.bottom, scrimPaint)
        canvas.drawRect(roiRect.right, roiRect.top, w, roiRect.bottom, scrimPaint)

        // Subtle inner box border and crosshairs
        canvas.drawRect(roiRect, borderPaint)
        val cx = roiRect.centerX()
        val cy = roiRect.centerY()
        if (!multiCharMode) {
            canvas.drawLine(cx, roiRect.top + 12f, cx, roiRect.bottom - 12f, crosshairPaint)
            canvas.drawLine(roiRect.left + 12f, cy, roiRect.right - 12f, cy, crosshairPaint)
        } else {
            canvas.drawLine(roiRect.left + 12f, cy, roiRect.right - 12f, cy, crosshairPaint)
        }

        // Gold L-shaped corner brackets
        val cornerLen = min(roiRect.width(), roiRect.height()) * 0.22f
        val l = roiRect.left
        val t = roiRect.top
        val r = roiRect.right
        val b = roiRect.bottom

        canvas.drawLine(l, t, l + cornerLen, t, cornerPaint)
        canvas.drawLine(l, t, l, t + cornerLen, cornerPaint)

        canvas.drawLine(r - cornerLen, t, r, t, cornerPaint)
        canvas.drawLine(r, t, r, t + cornerLen, cornerPaint)

        canvas.drawLine(l, b, l + cornerLen, b, cornerPaint)
        canvas.drawLine(l, b - cornerLen, l, b, cornerPaint)

        canvas.drawLine(r - cornerLen, b, r, b, cornerPaint)
        canvas.drawLine(r, b - cornerLen, r, b, cornerPaint)

        // Prompt caption above ROI
        val label = if (multiCharMode) {
            "Align Hanzi word/phrase inside bar"
        } else {
            "Center 1 Hanzi inside the square"
        }
        val textY = (roiRect.top - 16f).coerceAtLeast(36f)
        canvas.drawText(label, w / 2f, textY, hintPaint)
    }
}
