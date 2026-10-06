package com.faldo.hsk_quest.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import androidx.annotation.DrawableRes
import java.lang.ref.WeakReference

/**
 * Sprite animation utility for retro 2D pixel-art spritesheets.
 * Slices rows/columns and loops frames on an ImageView with configurable FPS.
 */
class SpriteAnimator(
    private val imageView: ImageView,
    private val frames: List<Bitmap>,
    private val fps: Int = 8,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var currentFrameIndex = 0
    private var isRunning = false
    private val viewRef = WeakReference(imageView)

    private val updateRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            val view = viewRef.get() ?: return
            if (frames.isNotEmpty()) {
                currentFrameIndex = (currentFrameIndex + 1) % frames.size
                view.setImageBitmap(frames[currentFrameIndex])
            }
            handler.postDelayed(this, 1000L / fps)
        }
    }

    fun start() {
        if (isRunning || frames.isEmpty()) return
        isRunning = true
        viewRef.get()?.setImageBitmap(frames[0])
        handler.postDelayed(updateRunnable, 1000L / fps)
    }

    fun stop() {
        isRunning = false
        handler.removeCallbacks(updateRunnable)
    }

    companion object {
        /**
         * Loads a spritesheet from drawable resources and splits it into frame bitmaps.
         *
         * @param rows Total rows in the sheet
         * @param cols Total columns in the sheet
         * @param targetRow 0-indexed row to extract
         * @param frameCount Number of frames in this row to read (left to right)
         */
        fun loadFrames(
            imageView: ImageView,
            @DrawableRes drawableRes: Int,
            rows: Int,
            cols: Int,
            targetRow: Int = 0,
            frameCount: Int = cols,
        ): List<Bitmap> {
            val context = imageView.context
            val options = BitmapFactory.Options().apply {
                inScaled = false // Keep pixel art sharp without bilinear blur
            }
            val sheet = BitmapFactory.decodeResource(context.resources, drawableRes, options)
                ?: return emptyList()

            val frameWidth = sheet.width / cols
            val frameHeight = sheet.height / rows
            val count = frameCount.coerceAtMost(cols)
            val result = ArrayList<Bitmap>(count)

            for (col in 0 until count) {
                val x = col * frameWidth
                val y = targetRow * frameHeight
                if (x + frameWidth <= sheet.width && y + frameHeight <= sheet.height) {
                    val frame = Bitmap.createBitmap(sheet, x, y, frameWidth, frameHeight)
                    result.add(frame)
                }
            }
            return result
        }

        /**
         * Convenience helper to setup and start an animation on an ImageView.
         */
        fun startAnimation(
            imageView: ImageView,
            @DrawableRes drawableRes: Int,
            rows: Int,
            cols: Int,
            targetRow: Int = 0,
            frameCount: Int = cols,
            fps: Int = 8,
        ): SpriteAnimator {
            val frames = loadFrames(imageView, drawableRes, rows, cols, targetRow, frameCount)
            return SpriteAnimator(imageView, frames, fps).apply { start() }
        }
    }
}
