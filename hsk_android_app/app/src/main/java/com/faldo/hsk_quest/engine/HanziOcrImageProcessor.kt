package com.faldo.hsk_quest.engine

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import androidx.camera.core.ImageProxy
import com.faldo.hsk_quest.data.local.DictionaryEntry
import com.faldo.hsk_quest.data.local.HskJsonLoader
import com.faldo.hsk_quest.ui.practice.HanziCandidate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class OcrScanMode {
    SINGLE,
    MULTI,
}

enum class OcrPolarityMode {
    DARK_INK_ON_LIGHT,
    LIGHT_INK_ON_DARK,
}

data class CameraOcrResult(
    val hasSignal: Boolean,
    val detectedText: String,
    val pinyin: String,
    val english: String,
    val hskLevelLabel: String?,
    val confidence: Float,
    val inferenceLatencyMs: Long,
    val slotCount: Int,
    val alternatives: List<HanziCandidate>,
    val matchedEntry: DictionaryEntry?,
    val previewBitmap: Bitmap?,
)

/**
 * Pure on-device Computer Vision + Custom TFLite OCR pipeline.
 * Extracts only the camera luminance (Y) channel inside the on-screen ROI box,
 * performs adaptive local-mean binarization, segments 1 or multiple Hanzi characters,
 * pads each character to the exact 96x96 white-bg/black-ink training contract,
 * and matches predictions against the HSK 1-9 dictionary.
 */
class HanziOcrImageProcessor(
    private val recognizer: CustomTfliteHandwritingRecognizer,
    private val hskJsonLoader: HskJsonLoader,
) {

    private data class ComponentBox(
        var minX: Int,
        var minY: Int,
        var maxX: Int,
        var maxY: Int,
        var pixelCount: Int,
    ) {
        val width: Int get() = maxX - minX + 1
        val height: Int get() = maxY - minY + 1
    }

    private data class SampledLuma(
        val width: Int,
        val height: Int,
        val pixels: FloatArray,
    )

    suspend fun processFrame(
        image: ImageProxy,
        previewWidth: Int,
        previewHeight: Int,
        roiNorm: RectF,
        scanMode: OcrScanMode,
        polarityMode: OcrPolarityMode,
        allEntries: List<DictionaryEntry>,
    ): CameraOcrResult {
        if (!recognizer.isReady || previewWidth <= 0 || previewHeight <= 0) {
            return emptyResult()
        }

        val startNs = System.nanoTime()
        val crop = sampleRoiLuminance(image, previewWidth, previewHeight, roiNorm, scanMode)
            ?: return emptyResult()

        val binary = binarizeAdaptive(crop, polarityMode) ?: return emptyResult()
        val charBoxes = segmentCharacters(binary, crop.width, crop.height, scanMode)
        if (charBoxes.isEmpty()) {
            return emptyResult(previewBitmap = buildDebugMaskBitmap(binary, crop.width, crop.height, emptyList()))
        }

        val inputSize = recognizer.getInputSize()
        val padRatio = recognizer.getPadMarginRatio()

        return if (scanMode == OcrScanMode.SINGLE || charBoxes.size == 1) {
            val box = charBoxes.first()
            val patch = renderSquarePatch(
                binaryInk = binary,
                luma = crop,
                polarityMode = polarityMode,
                box = box,
                targetSize = inputSize,
                padMarginRatio = padRatio,
            )
            val preds = recognizer.classifyNormalizedPatch(patch, topK = 5)
            val candidates = recognizer.mapRawPredictionsToCandidates(preds, allEntries)
            val best = candidates.firstOrNull()
            val bestText = best?.hanzi.orEmpty()
            val matched = if (bestText.isNotEmpty()) recognizer.lookupEntry(bestText, allEntries) else null
            val latencyMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)

            CameraOcrResult(
                hasSignal = best != null && best.score >= MIN_SINGLE_CONFIDENCE,
                detectedText = bestText,
                pinyin = matched?.term?.pinyin ?: best?.pinyin.orEmpty(),
                english = matched?.term?.english ?: best?.english.orEmpty(),
                hskLevelLabel = matched?.levelLabel,
                confidence = best?.score ?: 0f,
                inferenceLatencyMs = latencyMs,
                slotCount = 1,
                alternatives = candidates,
                matchedEntry = matched,
                previewBitmap = buildPatchPreviewBitmap(patch, inputSize),
            )
        } else {
            val slotPredictions = charBoxes.take(MAX_MULTI_SLOTS).map { box ->
                val patch = renderSquarePatch(
                    binaryInk = binary,
                    luma = crop,
                    polarityMode = polarityMode,
                    box = box,
                    targetSize = inputSize,
                    padMarginRatio = padRatio,
                )
                recognizer.classifyNormalizedPatch(patch, topK = 4)
            }

            val wordCandidates = recognizer.buildMultiCharCandidates(
                slotPredictions = slotPredictions,
                allEntries = allEntries,
                maxResults = 6,
            )
            val primaryWord = wordCandidates.firstOrNull()
            val primaryText = primaryWord?.hanzi.orEmpty()
            val matched = if (primaryText.isNotEmpty()) {
                recognizer.lookupEntry(primaryText, allEntries)
            } else {
                null
            }

            val resolvedPinyin: String
            val resolvedEnglish: String
            if (matched != null) {
                resolvedPinyin = matched.term.pinyin
                resolvedEnglish = matched.term.english
            } else if (primaryText.length >= 2) {
                val smart = hskJsonLoader.searchSmartDictionary(primaryText, levelFilter = null, limit = 12)
                resolvedPinyin = smart.sentenceSummaryPinyin.ifBlank { primaryWord?.pinyin.orEmpty() }
                resolvedEnglish = smart.sentenceSummaryEnglish.ifBlank { primaryWord?.english.orEmpty() }
            } else {
                resolvedPinyin = primaryWord?.pinyin.orEmpty()
                resolvedEnglish = primaryWord?.english.orEmpty()
            }

            // Build combined alternative chips: top compound phrases + individual slot Top-1s
            val slotBreakdowns = slotPredictions.mapNotNull { slot ->
                val top = slot.firstOrNull() ?: return@mapNotNull null
                val entry = recognizer.lookupEntry(top.hanzi, allEntries)
                HanziCandidate(
                    hanzi = top.hanzi,
                    pinyin = entry?.term?.pinyin.orEmpty(),
                    english = entry?.term?.english ?: "Character",
                    score = top.confidence,
                )
            }
            val alternatives = (wordCandidates + slotBreakdowns)
                .distinctBy { it.hanzi }
                .take(8)

            val latencyMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
            CameraOcrResult(
                hasSignal = primaryText.isNotEmpty() && (primaryWord?.score ?: 0f) >= MIN_MULTI_CONFIDENCE,
                detectedText = primaryText,
                pinyin = resolvedPinyin,
                english = resolvedEnglish,
                hskLevelLabel = matched?.levelLabel ?: if (primaryText.length > 1) "${charBoxes.size} Chars" else null,
                confidence = primaryWord?.score ?: 0f,
                inferenceLatencyMs = latencyMs,
                slotCount = charBoxes.size.coerceAtMost(MAX_MULTI_SLOTS),
                alternatives = alternatives,
                matchedEntry = matched,
                previewBitmap = buildDebugMaskBitmap(binary, crop.width, crop.height, charBoxes),
            )
        }
    }

    /**
     * Extracts luminance samples strictly inside [roiNorm] matching PreviewView's FILL_CENTER geometry.
     */
    private fun sampleRoiLuminance(
        image: ImageProxy,
        previewWidth: Int,
        previewHeight: Int,
        roiNorm: RectF,
        scanMode: OcrScanMode,
    ): SampledLuma? {
        val yPlane = image.planes.firstOrNull() ?: return null
        val yBuffer = yPlane.buffer
        val rowStride = yPlane.rowStride
        val pixelStride = yPlane.pixelStride
        val imgW = image.width
        val imgH = image.height
        val rot = ((image.imageInfo.rotationDegrees % 360) + 360) % 360

        val uprightW = if (rot == 90 || rot == 270) imgH.toFloat() else imgW.toFloat()
        val uprightH = if (rot == 90 || rot == 270) imgW.toFloat() else imgH.toFloat()

        val scale = max(previewWidth / uprightW, previewHeight / uprightH)
        val visW = previewWidth / scale
        val visH = previewHeight / scale
        val visLeft = (uprightW - visW) / 2f
        val visTop = (uprightH - visH) / 2f

        val uLeft = (visLeft + roiNorm.left * visW).coerceIn(0f, uprightW - 2f)
        val uRight = (visLeft + roiNorm.right * visW).coerceIn(uLeft + 2f, uprightW - 1f)
        val uTop = (visTop + roiNorm.top * visH).coerceIn(0f, uprightH - 2f)
        val uBottom = (visTop + roiNorm.bottom * visH).coerceIn(uTop + 2f, uprightH - 1f)

        val roiSpanW = uRight - uLeft
        val roiSpanH = uBottom - uTop
        if (roiSpanW < 16f || roiSpanH < 16f) return null

        val targetHeight = if (scanMode == OcrScanMode.SINGLE) 136 else 128
        val workH = targetHeight
        val workW = ((roiSpanW / roiSpanH) * workH).roundToInt().coerceIn(80, 400)
        val out = FloatArray(workW * workH)

        val bufCap = yBuffer.limit()
        for (wy in 0 until workH) {
            val uy = uTop + ((wy + 0.5f) / workH) * roiSpanH
            for (wx in 0 until workW) {
                val ux = uLeft + ((wx + 0.5f) / workW) * roiSpanW

                val rawX: Int
                val rawY: Int
                when (rot) {
                    90 -> {
                        rawX = uy.toInt()
                        rawY = imgH - 1 - ux.toInt()
                    }
                    180 -> {
                        rawX = imgW - 1 - ux.toInt()
                        rawY = imgH - 1 - uy.toInt()
                    }
                    270 -> {
                        rawX = imgW - 1 - uy.toInt()
                        rawY = ux.toInt()
                    }
                    else -> {
                        rawX = ux.toInt()
                        rawY = uy.toInt()
                    }
                }

                val cx = rawX.coerceIn(0, imgW - 1)
                val cy = rawY.coerceIn(0, imgH - 1)
                val idx = cy * rowStride + cx * pixelStride
                out[wy * workW + wx] = if (idx in 0 until bufCap) {
                    (yBuffer.get(idx).toInt() and 0xFF).toFloat()
                } else {
                    255f
                }
            }
        }
        return SampledLuma(workW, workH, out)
    }

    /**
     * Integral-image local-mean thresholding with percentile contrast guard so empty surfaces
     * never produce phantom character noise.
     */
    private fun binarizeAdaptive(
        crop: SampledLuma,
        polarityMode: OcrPolarityMode,
    ): BooleanArray? {
        val w = crop.width
        val h = crop.height
        val n = w * h
        val luma = crop.pixels

        val hist = IntArray(256)
        for (i in 0 until n) {
            hist[luma[i].toInt().coerceIn(0, 255)]++
        }
        var p5 = 0
        var p95 = 255
        var acc = 0
        val lowTarget = (n * 0.05f).toInt()
        val highTarget = (n * 0.95f).toInt()
        var foundLow = false
        for (v in 0..255) {
            acc += hist[v]
            if (!foundLow && acc >= lowTarget) {
                p5 = v
                foundLow = true
            }
            if (acc >= highTarget) {
                p95 = v
                break
            }
        }
        val contrast = p95 - p5
        if (contrast < MIN_CONTRAST_RANGE) {
            return null
        }

        // Build integral image (width + 1 by height + 1)
        val stride = w + 1
        val integral = DoubleArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var rowSum = 0.0
            val yOff = y * w
            val iRow = (y + 1) * stride
            val prevRow = y * stride
            for (x in 0 until w) {
                rowSum += luma[yOff + x]
                integral[iRow + x + 1] = integral[prevRow + x + 1] + rowSum
            }
        }

        val radius = max(10, h / 6)
        val delta = max(11f, contrast * 0.16f).coerceAtMost(28f)
        val mask = BooleanArray(n)
        var inkCount = 0

        for (y in 0 until h) {
            val y0 = max(0, y - radius)
            val y1 = min(h - 1, y + radius)
            val row0 = y0 * stride
            val row1 = (y1 + 1) * stride
            for (x in 0 until w) {
                val x0 = max(0, x - radius)
                val x1 = min(w - 1, x + radius)
                val area = (x1 - x0 + 1) * (y1 - y0 + 1)
                val sum = integral[row1 + x1 + 1] -
                    integral[row0 + x1 + 1] -
                    integral[row1 + x0] +
                    integral[row0 + x0]
                val localMean = (sum / area).toFloat()
                val v = luma[y * w + x]
                val isInk = when (polarityMode) {
                    OcrPolarityMode.DARK_INK_ON_LIGHT -> v < (localMean - delta)
                    OcrPolarityMode.LIGHT_INK_ON_DARK -> v > (localMean + delta)
                }
                if (isInk) {
                    mask[y * w + x] = true
                    inkCount++
                }
            }
        }

        val density = inkCount.toFloat() / n
        if (density < 0.015f || density > 0.72f) {
            return null
        }
        return mask
    }

    private fun segmentCharacters(
        mask: BooleanArray,
        w: Int,
        h: Int,
        scanMode: OcrScanMode,
    ): List<Rect> {
        val rawComponents = extractConnectedComponents(mask, w, h)
        if (rawComponents.isEmpty()) return emptyList()

        val minPixels = max(8, (w * h) / 700)
        val filtered = rawComponents.filter { comp ->
            if (comp.pixelCount < minPixels) return@filter false
            // Ignore thin horizontal frame lines/borders at very top or bottom edge
            val isLongThinRule = comp.width > h * 1.9f && comp.height < h * 0.14f
            !isLongThinRule
        }
        if (filtered.isEmpty()) return emptyList()

        if (scanMode == OcrScanMode.SINGLE) {
            // Focus on components inside the middle 84% square and drop detached edge borders
            val valid = filtered.filter { comp ->
                val cx = (comp.minX + comp.maxX) * 0.5f / w
                val cy = (comp.minY + comp.maxY) * 0.5f / h
                cx in 0.08f..0.92f && cy in 0.08f..0.92f
            }.ifEmpty { filtered }

            var minX = w
            var minY = h
            var maxX = 0
            var maxY = 0
            var totalPx = 0
            for (c in valid) {
                minX = min(minX, c.minX)
                minY = min(minY, c.minY)
                maxX = max(maxX, c.maxX)
                maxY = max(maxY, c.maxY)
                totalPx += c.pixelCount
            }
            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            val fill = totalPx.toFloat() / max(1, bw * bh)
            if (bw < 6 || bh < 6 || fill < 0.03f || fill > 0.78f) return emptyList()
            return listOf(Rect(minX, minY, maxX + 1, maxY + 1))
        }

        // MULTI-HANZI MODE: merge left-right / top-bottom radicals into square Hanzi slots
        val lineAligned = filtered.filter { comp ->
            val cy = (comp.minY + comp.maxY) * 0.5f / h
            cy in 0.06f..0.94f
        }.ifEmpty { filtered }

        val sortedHeights = lineAligned.map { it.height }.sorted()
        val p75Height = sortedHeights[((sortedHeights.size - 1) * 0.75f).roundToInt()]
        val estCharSize = p75Height.toFloat().coerceIn(h * 0.30f, h * 0.90f)

        val sorted = lineAligned.sortedBy { it.minX }
        val clusters = mutableListOf<ComponentBox>()
        for (c in sorted) {
            val prev = clusters.lastOrNull()
            if (prev == null) {
                clusters.add(c.copy())
                continue
            }
            val gapX = c.minX - prev.maxX
            val combinedW = max(prev.maxX, c.maxX) - min(prev.minX, c.minX) + 1
            val overlapsOrNested = gapX <= 2
            val radicalPair = combinedW <= estCharSize * 1.16f && gapX <= estCharSize * 0.28f
            if (overlapsOrNested || radicalPair) {
                prev.minX = min(prev.minX, c.minX)
                prev.minY = min(prev.minY, c.minY)
                prev.maxX = max(prev.maxX, c.maxX)
                prev.maxY = max(prev.maxY, c.maxY)
                prev.pixelCount += c.pixelCount
            } else {
                clusters.add(c.copy())
            }
        }

        // Split merged wide clusters at vertical projection valleys when tightly kerned
        val finalRects = mutableListOf<Rect>()
        for (cluster in clusters) {
            if (cluster.width > estCharSize * 1.65f) {
                val subBoxes = splitWideClusterByProjection(mask, w, cluster, estCharSize)
                finalRects.addAll(subBoxes)
            } else if (cluster.width >= 6 && cluster.height >= 8) {
                finalRects.add(Rect(cluster.minX, cluster.minY, cluster.maxX + 1, cluster.maxY + 1))
            }
        }

        return finalRects
            .sortedBy { it.left }
            .take(MAX_MULTI_SLOTS)
    }

    private fun splitWideClusterByProjection(
        mask: BooleanArray,
        w: Int,
        box: ComponentBox,
        estCharSize: Float,
    ): List<Rect> {
        val approxCount = (box.width / estCharSize).roundToInt().coerceIn(2, MAX_MULTI_SLOTS)
        val cuts = ArrayList<Int>(approxCount + 1).apply { add(box.minX) }
        val step = box.width.toFloat() / approxCount

        for (i in 1 until approxCount) {
            val expectedX = (box.minX + step * i).roundToInt()
            val searchRadius = max(4, (estCharSize * 0.22f).roundToInt())
            val startX = max(box.minX + 3, expectedX - searchRadius)
            val endX = min(box.maxX - 3, expectedX + searchRadius)
            var bestX = expectedX
            var minInk = Int.MAX_VALUE
            for (x in startX..endX) {
                var colInk = 0
                for (y in box.minY..box.maxY) {
                    if (mask[y * w + x]) colInk++
                }
                val distPenalty = abs(x - expectedX) / 4
                val score = colInk + distPenalty
                if (score < minInk) {
                    minInk = score
                    bestX = x
                }
            }
            cuts.add(bestX)
        }
        cuts.add(box.maxX + 1)

        val out = mutableListOf<Rect>()
        for (i in 0 until cuts.size - 1) {
            val l = cuts[i]
            val r = cuts[i + 1]
            if (r - l < 5) continue
            // Tighten vertical bounds per slice
            var t = box.maxY
            var b = box.minY
            var any = false
            for (y in box.minY..box.maxY) {
                for (x in l until r) {
                    if (mask[y * w + x]) {
                        t = min(t, y)
                        b = max(b, y)
                        any = true
                    }
                }
            }
            if (any && b >= t) {
                out.add(Rect(l, t, r, b + 1))
            }
        }
        return out
    }

    private fun extractConnectedComponents(
        mask: BooleanArray,
        w: Int,
        h: Int,
    ): List<ComponentBox> {
        val n = w * h
        val visited = BooleanArray(n)
        val queue = IntArray(n)
        val components = mutableListOf<ComponentBox>()

        for (start in 0 until n) {
            if (!mask[start] || visited[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true

            var minX = w
            var minY = h
            var maxX = 0
            var maxY = 0
            var count = 0

            while (head < tail) {
                val curr = queue[head++]
                val x = curr % w
                val y = curr / w
                count++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y

                if (x > 0) {
                    val left = curr - 1
                    if (mask[left] && !visited[left]) {
                        visited[left] = true
                        queue[tail++] = left
                    }
                }
                if (x + 1 < w) {
                    val right = curr + 1
                    if (mask[right] && !visited[right]) {
                        visited[right] = true
                        queue[tail++] = right
                    }
                }
                if (y > 0) {
                    val up = curr - w
                    if (mask[up] && !visited[up]) {
                        visited[up] = true
                        queue[tail++] = up
                    }
                }
                if (y + 1 < h) {
                    val down = curr + w
                    if (mask[down] && !visited[down]) {
                        visited[down] = true
                        queue[tail++] = down
                    }
                }
            }

            components.add(ComponentBox(minX, minY, maxX, maxY, count))
        }
        return components
    }

    /**
     * Converts one segmented character box into a square [targetSize x targetSize] float array
     * matching Kaggle training: white background 1.0f, dark ink near 0.0f, padded with [padMarginRatio].
     */
    private fun renderSquarePatch(
        binaryInk: BooleanArray,
        luma: SampledLuma,
        polarityMode: OcrPolarityMode,
        box: Rect,
        targetSize: Int,
        padMarginRatio: Float,
    ): FloatArray {
        val bw = box.width().coerceAtLeast(1)
        val bh = box.height().coerceAtLeast(1)
        val maxSide = max(bw, bh).toFloat()

        // Find ink intensity range inside the box for smooth anti-aliased stroke rendering
        var bgAccum = 0f
        var bgCount = 0
        var inkMin = 255f
        var inkMax = 0f
        for (y in box.top until box.bottom) {
            val row = y * luma.width
            for (x in box.left until box.right) {
                val idx = row + x
                val v = luma.pixels[idx]
                if (!binaryInk[idx]) {
                    bgAccum += v
                    bgCount++
                } else {
                    if (v < inkMin) inkMin = v
                    if (v > inkMax) inkMax = v
                }
            }
        }
        val bgLevel = if (bgCount > 0) bgAccum / bgCount else 200f
        val inkPeak = if (polarityMode == OcrPolarityMode.DARK_INK_ON_LIGHT) inkMin else inkMax
        val span = abs(bgLevel - inkPeak).coerceAtLeast(24f)

        val usableRatio = (1f - 2f * padMarginRatio).coerceIn(0.4f, 0.94f)
        val scale = (targetSize * usableRatio) / maxSide
        val drawW = bw * scale
        val drawH = bh * scale
        val offsetX = (targetSize - drawW) / 2f
        val offsetY = (targetSize - drawH) / 2f

        val out = FloatArray(targetSize * targetSize) { 1f }
        for (ty in 0 until targetSize) {
            val syF = box.top + (ty - offsetY) / scale
            val sy = syF.toInt()
            if (sy < box.top || sy >= box.bottom) continue
            for (tx in 0 until targetSize) {
                val sxF = box.left + (tx - offsetX) / scale
                val sx = sxF.toInt()
                if (sx < box.left || sx >= box.right) continue

                val idx = sy * luma.width + sx
                if (!binaryInk[idx]) {
                    out[ty * targetSize + tx] = 1f
                } else {
                    val v = luma.pixels[idx]
                    val normalizedDist = when (polarityMode) {
                        OcrPolarityMode.DARK_INK_ON_LIGHT -> ((v - inkPeak) / span).coerceIn(0f, 0.35f)
                        OcrPolarityMode.LIGHT_INK_ON_DARK -> ((inkPeak - v) / span).coerceIn(0f, 0.35f)
                    }
                    out[ty * targetSize + tx] = normalizedDist
                }
            }
        }
        return out
    }

    private fun buildPatchPreviewBitmap(patch: FloatArray, size: Int): Bitmap {
        val pixels = IntArray(size * size)
        for (i in patch.indices) {
            val g = (patch[i].coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
            pixels[i] = Color.rgb(g, g, g)
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    private fun buildDebugMaskBitmap(
        mask: BooleanArray,
        w: Int,
        h: Int,
        boxes: List<Rect>,
    ): Bitmap {
        val pixels = IntArray(w * h)
        for (i in 0 until w * h) {
            pixels[i] = if (mask[i]) Color.BLACK else Color.WHITE
        }
        for (box in boxes) {
            val l = box.left.coerceIn(0, w - 1)
            val r = (box.right - 1).coerceIn(0, w - 1)
            val t = box.top.coerceIn(0, h - 1)
            val b = (box.bottom - 1).coerceIn(0, h - 1)
            for (x in l..r) {
                pixels[t * w + x] = Color.rgb(212, 175, 106)
                pixels[b * w + x] = Color.rgb(212, 175, 106)
            }
            for (y in t..b) {
                pixels[y * w + l] = Color.rgb(212, 175, 106)
                pixels[y * w + r] = Color.rgb(212, 175, 106)
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun emptyResult(previewBitmap: Bitmap? = null): CameraOcrResult {
        return CameraOcrResult(
            hasSignal = false,
            detectedText = "",
            pinyin = "",
            english = "",
            hskLevelLabel = null,
            confidence = 0f,
            inferenceLatencyMs = 0L,
            slotCount = 0,
            alternatives = emptyList(),
            matchedEntry = null,
            previewBitmap = previewBitmap,
        )
    }

    companion object {
        private const val MIN_CONTRAST_RANGE = 22
        private const val MIN_SINGLE_CONFIDENCE = 0.08f
        private const val MIN_MULTI_CONFIDENCE = 0.06f
        private const val MAX_MULTI_SLOTS = 5
    }
}
