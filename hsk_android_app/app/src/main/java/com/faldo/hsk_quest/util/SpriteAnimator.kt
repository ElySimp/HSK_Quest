package com.faldo.hsk_quest.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import androidx.annotation.DrawableRes
import com.faldo.hsk_quest.R
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
        private val frameCache = mutableMapOf<String, Bitmap>()
        private val sheetCache = mutableMapOf<Int, Bitmap>()

        /**
         * Resolves the best drawable resource for a companion pet.
         * Prioritizes custom `pet_<petType>` drawables (e.g., `pet_azure_dragon`)
         * before falling back to `spriteKey` (`pet_dummy_1` / `pet_dummy_2`).
         */
        @DrawableRes
        fun resolvePetDrawable(
            context: Context,
            petType: String?,
            spriteKey: String?,
        ): Int {
            val cleanType = petType
                ?.trim()
                ?.lowercase()
                ?.replace('-', '_')
                .orEmpty()
            if (cleanType.isNotEmpty()) {
                val typeCandidate = if (cleanType.startsWith("pet_")) cleanType else "pet_$cleanType"
                val id = context.resources.getIdentifier(typeCandidate, "drawable", context.packageName)
                if (id != 0) return id
            }

            val cleanKey = spriteKey
                ?.trim()
                ?.lowercase()
                ?.replace('-', '_')
                .orEmpty()
            if (cleanKey.isNotEmpty()) {
                val id = context.resources.getIdentifier(cleanKey, "drawable", context.packageName)
                if (id != 0) return id
            }

            return if (spriteKey == "pet_dummy_2") {
                R.drawable.pet_dummy_2
            } else {
                R.drawable.pet_dummy_1
            }
        }

        /**
         * Decodes a spritesheet with `inScaled = false` and automatically strips
         * solid magenta (#FF00FF) chroma-key backgrounds (including JPEG compression tolerance).
         */
        private fun decodeChromaKeyedSheet(context: Context, @DrawableRes drawableRes: Int): Bitmap? {
            sheetCache[drawableRes]?.let { return it }

            val options = BitmapFactory.Options().apply {
                inScaled = false
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val raw = BitmapFactory.decodeResource(context.resources, drawableRes, options)
                ?: return null

            val processed = applyChromaKeyTransparency(raw)
            sheetCache[drawableRes] = processed
            return processed
        }

        private fun applyChromaKeyTransparency(source: Bitmap): Bitmap {
            if (source.width <= 0 || source.height <= 0) return source
            val corner = source.getPixel(0, 0)
            val cAlpha = Color.alpha(corner)
            val cRed = Color.red(corner)
            val cGreen = Color.green(corner)
            val cBlue = Color.blue(corner)

            // Only run chroma-key stripping if top-left corner is opaque magenta (#FF00FF)
            val isMagentaBg = cAlpha > 200 && cRed > 190 && cBlue > 190 && cGreen < 90
            if (!isMagentaBg) return source

            val width = source.width
            val height = source.height
            val pixels = IntArray(width * height)
            source.getPixels(pixels, 0, width, 0, 0, width, height)

            for (i in pixels.indices) {
                val px = pixels[i]
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                if (r > 165 && b > 165 && g < 115 && (r - g) > 75 && (b - g) > 75) {
                    pixels[i] = Color.TRANSPARENT
                }
            }

            return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        }

        /**
         * Extracts a single frame from a spritesheet as a sharp pixel-art Bitmap.
         * Caches extracted bitmaps in memory to prevent repeated decoding.
         */
        fun extractFrame(
            context: Context,
            @DrawableRes drawableRes: Int,
            rows: Int,
            cols: Int,
            targetRow: Int,
            targetCol: Int,
        ): Bitmap? {
            val cacheKey = "$drawableRes-$rows-$cols-$targetRow-$targetCol"
            frameCache[cacheKey]?.let { return it }

            val sheet = decodeChromaKeyedSheet(context, drawableRes)
                ?: return null

            val frameWidth = sheet.width / cols
            val frameHeight = sheet.height / rows
            val x = targetCol * frameWidth
            val y = targetRow * frameHeight

            if (x + frameWidth <= sheet.width && y + frameHeight <= sheet.height) {
                val frame = Bitmap.createBitmap(sheet, x, y, frameWidth, frameHeight)
                frameCache[cacheKey] = frame
                return frame
            }
            return null
        }

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
            val sheet = decodeChromaKeyedSheet(context, drawableRes)
                ?: return emptyList()

            val frameWidth = sheet.width / cols
            val frameHeight = sheet.height / rows
            val count = frameCount.coerceAtMost(cols)
            val result = ArrayList<Bitmap>(count)

            for (col in 0 until count) {
                val cacheKey = "$drawableRes-$rows-$cols-$targetRow-$col"
                val cached = frameCache[cacheKey]
                if (cached != null) {
                    result.add(cached)
                    continue
                }
                val x = col * frameWidth
                val y = targetRow * frameHeight
                if (x + frameWidth <= sheet.width && y + frameHeight <= sheet.height) {
                    val frame = Bitmap.createBitmap(sheet, x, y, frameWidth, frameHeight)
                    frameCache[cacheKey] = frame
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

        /**
         * Loads a horizontal strip of frames (1 row, N columns) and scales each frame with
         * nearest-neighbor filtering (filter = false) so retro pixel-art NPCs, potions, and
         * monsters render razor-sharp on high-DPI screens.
         */
        fun loadHorizontalStripFrames(
            imageView: ImageView,
            @DrawableRes drawableRes: Int,
            cols: Int,
            frameCount: Int = cols,
            scaleFactor: Int = 4,
        ): List<Bitmap> {
            val context = imageView.context
            val sheet = decodeChromaKeyedSheet(context, drawableRes)
                ?: return emptyList()

            val safeCols = cols.coerceAtLeast(1)
            val frameWidth = (sheet.width / safeCols).coerceAtLeast(1)
            val frameHeight = sheet.height.coerceAtLeast(1)
            val count = frameCount.coerceIn(1, safeCols)
            val safeScale = scaleFactor.coerceAtLeast(1)
            val result = ArrayList<Bitmap>(count)

            for (col in 0 until count) {
                val cacheKey = "strip-$drawableRes-$safeCols-$col-$safeScale"
                val cached = frameCache[cacheKey]
                if (cached != null) {
                    result.add(cached)
                    continue
                }
                val x = col * frameWidth
                if (x + frameWidth <= sheet.width) {
                    val rawFrame = Bitmap.createBitmap(sheet, x, 0, frameWidth, frameHeight)
                    val scaledFrame = if (safeScale > 1) {
                        Bitmap.createScaledBitmap(
                            rawFrame,
                            frameWidth * safeScale,
                            frameHeight * safeScale,
                            false,
                        )
                    } else {
                        rawFrame
                    }
                    frameCache[cacheKey] = scaledFrame
                    result.add(scaledFrame)
                }
            }
            return result
        }

        /**
         * Convenience helper to animate a 1-row horizontal pixel-art spritesheet with crisp scaling.
         */
        fun startHorizontalStripAnimation(
            imageView: ImageView,
            @DrawableRes drawableRes: Int,
            cols: Int,
            frameCount: Int = cols,
            fps: Int = 8,
            scaleFactor: Int = 4,
        ): SpriteAnimator {
            val frames = loadHorizontalStripFrames(
                imageView = imageView,
                drawableRes = drawableRes,
                cols = cols,
                frameCount = frameCount,
                scaleFactor = scaleFactor,
            )
            return SpriteAnimator(imageView, frames, fps).apply { start() }
        }

        /**
         * Loads a vertical column of frames from a spritesheet and scales each frame with
         * nearest-neighbor filtering (filter = false) so pixel art remains razor-sharp.
         */
        fun loadColumnFrames(
            imageView: ImageView,
            @DrawableRes drawableRes: Int,
            rows: Int,
            cols: Int,
            targetCol: Int,
            startRow: Int = 0,
            frameCount: Int = rows,
            scaleFactor: Int = 4,
        ): List<Bitmap> {
            val context = imageView.context
            val options = BitmapFactory.Options().apply {
                inScaled = false
            }
            val sheet = BitmapFactory.decodeResource(context.resources, drawableRes, options)
                ?: return emptyList()

            val frameWidth = sheet.width / cols
            val frameHeight = sheet.height / rows
            val endRow = (startRow + frameCount).coerceAtMost(rows)
            val result = ArrayList<Bitmap>((endRow - startRow).coerceAtLeast(0))
            val safeScale = scaleFactor.coerceAtLeast(1)

            for (row in startRow until endRow) {
                val x = targetCol * frameWidth
                val y = row * frameHeight
                if (x + frameWidth <= sheet.width && y + frameHeight <= sheet.height) {
                    val rawFrame = Bitmap.createBitmap(sheet, x, y, frameWidth, frameHeight)
                    val scaledFrame = if (safeScale > 1) {
                        Bitmap.createScaledBitmap(
                            rawFrame,
                            frameWidth * safeScale,
                            frameHeight * safeScale,
                            false,
                        )
                    } else {
                        rawFrame
                    }
                    result.add(scaledFrame)
                }
            }
            return result
        }

        /**
         * Convenience helper to setup and start a vertical column sprite animation on an ImageView.
         */
        fun startColumnAnimation(
            imageView: ImageView,
            @DrawableRes drawableRes: Int,
            rows: Int,
            cols: Int,
            targetCol: Int,
            startRow: Int = 0,
            frameCount: Int = rows,
            fps: Int = 8,
            scaleFactor: Int = 4,
        ): SpriteAnimator {
            val frames = loadColumnFrames(
                imageView = imageView,
                drawableRes = drawableRes,
                rows = rows,
                cols = cols,
                targetCol = targetCol,
                startRow = startRow,
                frameCount = frameCount,
                scaleFactor = scaleFactor,
            )
            return SpriteAnimator(imageView, frames, fps).apply { start() }
        }
    }
}
