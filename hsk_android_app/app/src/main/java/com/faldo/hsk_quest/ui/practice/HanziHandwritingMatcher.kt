package com.faldo.hsk_quest.ui.practice

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import com.faldo.hsk_quest.data.local.DictionaryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Represents a ranked single-Hanzi handwriting recognition candidate
 * displayed below the Pleco-style handwriting canvas.
 */
data class HanziCandidate(
    val hanzi: String,
    val pinyin: String,
    val english: String,
    val score: Float,
)

/**
 * 100% Offline Hanzi Stroke & Glyph Bitmap Handwriting Matcher for HSK 1–9.
 *
 * Combines three recognition signals:
 * 1. Normalized 20x20 Soft-Blurred Glyph Bitmap Cosine Similarity against Android's system CJK font.
 * 2. Stroke Count, Aspect Ratio, Quadrant Ink Balance, and Stroke Direction Topology priors.
 * 3. HSK Level frequency prior so common classroom characters surface in the top candidates.
 */
object HanziHandwritingMatcher {

    private const val GRID_SIZE = 20
    private const val RASTER_SIZE = 48

    private class GlyphProfile(
        val char: Char,
        val hanzi: String,
        val pinyin: String,
        val english: String,
        val minLevelKey: Int,
        val expectedStrokes: Int,
        val aspectRatio: Float,
        val densityGrid: FloatArray,
        val gridNorm: Float,
        val quadrantWeights: FloatArray,
    )

    private val profileCache = ConcurrentHashMap<Char, GlyphProfile>()

    /**
     * Ranks single-character Hanzi candidates from [allEntries] given the user's drawn [strokes].
     */
    suspend fun recognizeCandidates(
        strokes: List<List<PointF>>,
        allEntries: List<DictionaryEntry>,
        limit: Int = 14,
    ): List<HanziCandidate> = withContext(Dispatchers.Default) {
        val validStrokes = strokes.filter { it.isNotEmpty() }
        if (validStrokes.isEmpty() || allEntries.isEmpty()) {
            return@withContext emptyList()
        }

        ensureProfilesLoaded(allEntries)
        if (profileCache.isEmpty()) {
            return@withContext emptyList()
        }

        val userStrokeCount = validStrokes.size
        val userFeatures = extractUserDrawingFeatures(validStrokes)
        val userGrid = userFeatures.densityGrid
        val userNorm = userFeatures.gridNorm
        if (userNorm <= 1e-5f) {
            return@withContext emptyList()
        }

        val candidates = ArrayList<HanziCandidate>(profileCache.size)

        for (profile in profileCache.values) {
            // 1. 20x20 Soft-Blurred Bitmap Cosine Similarity
            var dot = 0f
            val refGrid = profile.densityGrid
            for (i in 0 until GRID_SIZE * GRID_SIZE) {
                dot += userGrid[i] * refGrid[i]
            }
            val cosineSim = (dot / (userNorm * profile.gridNorm)).coerceIn(0f, 1f)

            // 2. Quadrant Ink Distribution Similarity (Top-Left, Top-Right, Bottom-Left, Bottom-Right)
            var quadDiff = 0f
            for (q in 0 until 4) {
                quadDiff += abs(userFeatures.quadrantWeights[q] - profile.quadrantWeights[q])
            }
            val quadSim = (1f - (quadDiff * 0.5f)).coerceIn(0f, 1f)

            // 3. Aspect Ratio Compatibility
            val aspectDiff = abs(userFeatures.aspectRatio - profile.aspectRatio)
            val aspectSim = (1f - (aspectDiff / 2.2f)).coerceIn(0f, 1f)

            // 4. Stroke Count Prior
            val strokeDiff = abs(userStrokeCount - profile.expectedStrokes)
            val strokeScore = when {
                strokeDiff == 0 -> 0.30f
                strokeDiff == 1 -> 0.19f
                strokeDiff == 2 -> 0.08f
                strokeDiff == 3 -> -0.02f
                userStrokeCount < profile.expectedStrokes && strokeDiff <= 5 -> -0.06f
                else -> -0.18f
            }

            // 5. Structural / Directional Stroke Topology Bonus for low-stroke & distinct characters
            val topologyBonus = computeTopologyBonus(
                char = profile.char,
                userStrokeCount = userStrokeCount,
                features = userFeatures,
            )

            // 6. HSK Level Frequency Prior (slight preference for HSK 1-3 vocabulary)
            val frequencyBonus = when (profile.minLevelKey) {
                1 -> 0.065f
                2 -> 0.050f
                3 -> 0.035f
                4 -> 0.020f
                5 -> 0.010f
                else -> 0.0f
            }

            val totalScore = (cosineSim * 0.56f) +
                (quadSim * 0.16f) +
                (aspectSim * 0.08f) +
                strokeScore +
                topologyBonus +
                frequencyBonus

            candidates.add(
                HanziCandidate(
                    hanzi = profile.hanzi,
                    pinyin = profile.pinyin,
                    english = profile.english,
                    score = totalScore,
                ),
            )
        }

        candidates.sortByDescending { it.score }
        candidates.take(limit)
    }

    private fun ensureProfilesLoaded(allEntries: List<DictionaryEntry>) {
        // Collect the best (lowest HSK level) entry for each unique single Hanzi character
        // as well as individual characters extracted from compound HSK words.
        val singleCharBest = HashMap<Char, DictionaryEntry>()
        val fallbackCompoundChar = HashMap<Char, DictionaryEntry>()

        for (entry in allEntries) {
            val simp = entry.term.simplified.trim()
            if (simp.length == 1) {
                val c = simp[0]
                if (isCjkChar(c)) {
                    val existing = singleCharBest[c]
                    if (existing == null || entry.levelKey < existing.levelKey) {
                        singleCharBest[c] = entry
                    }
                }
            } else {
                for (c in simp) {
                    if (isCjkChar(c) && !singleCharBest.containsKey(c) && !fallbackCompoundChar.containsKey(c)) {
                        fallbackCompoundChar[c] = entry
                    }
                }
            }
        }

        val allTargetChars = HashSet<Char>(singleCharBest.size + fallbackCompoundChar.size)
        allTargetChars.addAll(singleCharBest.keys)
        allTargetChars.addAll(fallbackCompoundChar.keys)

        val missingChars = allTargetChars.filter { !profileCache.containsKey(it) }
        if (missingChars.isEmpty()) return

        val bitmap = Bitmap.createBitmap(RASTER_SIZE, RASTER_SIZE, Bitmap.Config.ALPHA_8)
        val canvas = Canvas(bitmap)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            textAlign = Paint.Align.CENTER
            textSize = 38f
        }

        for (c in missingChars) {
            val singleEntry = singleCharBest[c]
            val compoundEntry = fallbackCompoundChar[c]
            val chosenEntry = singleEntry ?: compoundEntry ?: continue

            val hanziStr = c.toString()
            val displayPinyin = if (singleEntry != null) {
                singleEntry.term.pinyin
            } else {
                extractCharacterPinyin(c, chosenEntry)
            }
            val displayEnglish = if (singleEntry != null) {
                singleEntry.term.english
            } else {
                "in ${chosenEntry.term.simplified} (${chosenEntry.term.english})"
            }

            bitmap.eraseColor(Color.TRANSPARENT)
            val textY = (RASTER_SIZE / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
            canvas.drawText(hanziStr, RASTER_SIZE / 2f, textY, textPaint)

            val rendered = buildGridFromAlphaBitmap(bitmap)
            val expectedStrokes = COMMON_STROKE_COUNTS[c] ?: estimateStrokesFromInk(rendered.inkCoverage)

            profileCache[c] = GlyphProfile(
                char = c,
                hanzi = hanziStr,
                pinyin = displayPinyin,
                english = displayEnglish,
                minLevelKey = chosenEntry.levelKey,
                expectedStrokes = expectedStrokes,
                aspectRatio = rendered.aspectRatio,
                densityGrid = rendered.grid,
                gridNorm = rendered.norm,
                quadrantWeights = rendered.quadrants,
            )
        }
        bitmap.recycle()
    }

    private fun extractCharacterPinyin(char: Char, compoundEntry: DictionaryEntry): String {
        val idx = compoundEntry.term.simplified.indexOf(char)
        val parts = compoundEntry.term.pinyin.trim().split("\\s+".toRegex())
        return if (idx in parts.indices) parts[idx] else compoundEntry.term.pinyin
    }

    private class RasterizedFeatures(
        val grid: FloatArray,
        val norm: Float,
        val aspectRatio: Float,
        val quadrants: FloatArray,
        val inkCoverage: Float,
    )

    private class UserDrawingFeatures(
        val densityGrid: FloatArray,
        val gridNorm: Float,
        val aspectRatio: Float,
        val quadrantWeights: FloatArray,
        val horizontalRatio: Float,
        val verticalRatio: Float,
        val slantedRatio: Float,
        val strokeDirections: List<StrokeDir>,
    )

    private enum class StrokeDir {
        HORIZONTAL,
        VERTICAL,
        FALLING_LEFT,
        FALLING_RIGHT,
        COMPLEX,
    }

    private fun extractUserDrawingFeatures(strokes: List<List<PointF>>): UserDrawingFeatures {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE

        for (stroke in strokes) {
            for (pt in stroke) {
                if (pt.x < minX) minX = pt.x
                if (pt.y < minY) minY = pt.y
                if (pt.x > maxX) maxX = pt.x
                if (pt.y > maxY) maxY = pt.y
            }
        }

        val rawW = max(1f, maxX - minX)
        val rawH = max(1f, maxY - minY)
        val aspectRatio = (rawW / rawH).coerceIn(0.15f, 6.0f)

        // Classify each stroke's primary direction
        val strokeDirs = ArrayList<StrokeDir>(strokes.size)
        var horizCount = 0
        var vertCount = 0
        var slantCount = 0

        for (stroke in strokes) {
            val first = stroke.first()
            val last = stroke.last()
            val dx = last.x - first.x
            val dy = last.y - first.y
            val chordLen = hypot(dx.toDouble(), dy.toDouble()).toFloat()

            var pathLen = 0f
            for (i in 1 until stroke.size) {
                pathLen += hypot(
                    (stroke[i].x - stroke[i - 1].x).toDouble(),
                    (stroke[i].y - stroke[i - 1].y).toDouble(),
                ).toFloat()
            }

            val straightness = if (pathLen > 1f) chordLen / pathLen else 1f
            val dir = if (straightness < 0.62f) {
                StrokeDir.COMPLEX
            } else {
                val angleDeg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                when {
                    abs(angleDeg) <= 26f || abs(angleDeg) >= 154f -> {
                        horizCount++
                        StrokeDir.HORIZONTAL
                    }
                    angleDeg in 64f..116f || angleDeg in -116f..-64f -> {
                        vertCount++
                        StrokeDir.VERTICAL
                    }
                    angleDeg in 116f..154f -> {
                        slantCount++
                        StrokeDir.FALLING_LEFT
                    }
                    else -> {
                        slantCount++
                        StrokeDir.FALLING_RIGHT
                    }
                }
            }
            strokeDirs.add(dir)
        }

        val totalStrokes = strokes.size.coerceAtLeast(1).toFloat()

        // Rasterize user strokes onto a 48x48 alpha bitmap using blended bounding normalization
        val bitmap = Bitmap.createBitmap(RASTER_SIZE, RASTER_SIZE, Bitmap.Config.ALPHA_8)
        val canvas = Canvas(bitmap)
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            strokeWidth = 4.4f
        }

        val maxSpan = max(rawW, rawH)
        // Preserve flat/narrow aspect for extreme shapes (like 一 or 丨) while gently normalizing square-ish characters
        val effectiveW = if (rawW < maxSpan * 0.38f) maxSpan else (rawW * 0.65f + maxSpan * 0.35f)
        val effectiveH = if (rawH < maxSpan * 0.38f) maxSpan else (rawH * 0.65f + maxSpan * 0.35f)
        val centerX = (minX + maxX) * 0.5f
        val centerY = (minY + maxY) * 0.5f
        val drawMargin = 6f
        val usable = RASTER_SIZE - 2f * drawMargin

        val path = Path()
        for (stroke in strokes) {
            if (stroke.isEmpty()) continue
            path.reset()
            val p0 = stroke[0]
            val x0 = ((p0.x - centerX) / effectiveW + 0.5f) * usable + drawMargin
            val y0 = ((p0.y - centerY) / effectiveH + 0.5f) * usable + drawMargin
            path.moveTo(x0, y0)
            if (stroke.size == 1) {
                path.lineTo(x0 + 0.5f, y0 + 0.5f)
            } else {
                for (i in 1 until stroke.size) {
                    val pt = stroke[i]
                    val px = ((pt.x - centerX) / effectiveW + 0.5f) * usable + drawMargin
                    val py = ((pt.y - centerY) / effectiveH + 0.5f) * usable + drawMargin
                    path.lineTo(px, py)
                }
            }
            canvas.drawPath(path, strokePaint)
        }

        val rasterized = buildGridFromAlphaBitmap(bitmap)
        bitmap.recycle()

        return UserDrawingFeatures(
            densityGrid = rasterized.grid,
            gridNorm = rasterized.norm,
            aspectRatio = aspectRatio,
            quadrantWeights = rasterized.quadrants,
            horizontalRatio = horizCount / totalStrokes,
            verticalRatio = vertCount / totalStrokes,
            slantedRatio = slantCount / totalStrokes,
            strokeDirections = strokeDirs,
        )
    }

    private fun buildGridFromAlphaBitmap(bitmap: Bitmap): RasterizedFeatures {
        val w = bitmap.width
        val h = bitmap.height
        var minX = w
        var minY = h
        var maxX = 0
        var maxY = 0
        var inkPixels = 0

        val alphaPixels = IntArray(w * h)
        bitmap.getPixels(alphaPixels, 0, w, 0, 0, w, h)

        for (y in 0 until h) {
            val rowOffset = y * w
            for (x in 0 until w) {
                val alpha = Color.alpha(alphaPixels[rowOffset + x])
                if (alpha > 32) {
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                    inkPixels++
                }
            }
        }

        if (inkPixels == 0 || minX > maxX || minY > maxY) {
            return RasterizedFeatures(
                grid = FloatArray(GRID_SIZE * GRID_SIZE),
                norm = 1f,
                aspectRatio = 1f,
                quadrants = floatArrayOf(0.25f, 0.25f, 0.25f, 0.25f),
                inkCoverage = 0f,
            )
        }

        val spanX = max(1, maxX - minX + 1).toFloat()
        val spanY = max(1, maxY - minY + 1).toFloat()
        val aspectRatio = (spanX / spanY).coerceIn(0.15f, 6.0f)
        val maxSpan = max(spanX, spanY)

        // Blend bounding box with aspect-preserving span so flat strokes (like 一) stay flat
        val normSpanX = if (spanX < maxSpan * 0.35f) maxSpan else (spanX * 0.72f + maxSpan * 0.28f)
        val normSpanY = if (spanY < maxSpan * 0.35f) maxSpan else (spanY * 0.72f + maxSpan * 0.28f)
        val cx = (minX + maxX) * 0.5f
        val cy = (minY + maxY) * 0.5f

        val rawGrid = FloatArray(GRID_SIZE * GRID_SIZE)
        val counts = IntArray(GRID_SIZE * GRID_SIZE)

        for (y in minY..maxY) {
            val rowOffset = y * w
            val ny = (((y - cy) / normSpanY) + 0.5f) * (GRID_SIZE - 2) + 1f
            val gy = ny.toInt().coerceIn(0, GRID_SIZE - 1)
            for (x in minX..maxX) {
                val alpha = Color.alpha(alphaPixels[rowOffset + x]) / 255f
                if (alpha > 0.05f) {
                    val nx = (((x - cx) / normSpanX) + 0.5f) * (GRID_SIZE - 2) + 1f
                    val gx = nx.toInt().coerceIn(0, GRID_SIZE - 1)
                    val idx = gy * GRID_SIZE + gx
                    rawGrid[idx] += alpha
                    counts[idx]++
                }
            }
        }

        for (i in rawGrid.indices) {
            if (counts[i] > 0) {
                rawGrid[i] = (rawGrid[i] / counts[i]).coerceAtMost(1f)
            }
        }

        // Apply a 3x3 Gaussian-like smoothing kernel so slight hand-drawing shifts still match
        val blurredGrid = FloatArray(GRID_SIZE * GRID_SIZE)
        var normSq = 0f
        val quadrants = FloatArray(4)
        val half = GRID_SIZE / 2

        for (gy in 0 until GRID_SIZE) {
            for (gx in 0 until GRID_SIZE) {
                var sum = 0f
                var weightSum = 0f
                for (ky in -1..1) {
                    val ny = gy + ky
                    if (ny !in 0 until GRID_SIZE) continue
                    for (kx in -1..1) {
                        val nx = gx + kx
                        if (nx !in 0 until GRID_SIZE) continue
                        val wKernel = when (abs(kx) + abs(ky)) {
                            0 -> 4f
                            1 -> 2f
                            else -> 1f
                        }
                        sum += rawGrid[ny * GRID_SIZE + nx] * wKernel
                        weightSum += wKernel
                    }
                }
                val v = if (weightSum > 0f) sum / weightSum else 0f
                val idx = gy * GRID_SIZE + gx
                blurredGrid[idx] = v
                normSq += v * v

                val qIdx = (if (gy >= half) 2 else 0) + (if (gx >= half) 1 else 0)
                quadrants[qIdx] += v
            }
        }

        val quadTotal = quadrants.sum().coerceAtLeast(1e-5f)
        for (q in 0 until 4) {
            quadrants[q] /= quadTotal
        }

        val coverage = inkPixels.toFloat() / (spanX * spanY).coerceAtLeast(1f)
        return RasterizedFeatures(
            grid = blurredGrid,
            norm = sqrt(normSq).coerceAtLeast(1e-5f),
            aspectRatio = aspectRatio,
            quadrants = quadrants,
            inkCoverage = coverage,
        )
    }

    private fun computeTopologyBonus(
        char: Char,
        userStrokeCount: Int,
        features: UserDrawingFeatures,
    ): Float {
        val dirs = features.strokeDirections
        return when (userStrokeCount) {
            1 -> {
                when (char) {
                    '一' -> if (dirs[0] == StrokeDir.HORIZONTAL || features.aspectRatio > 2.2f) 0.45f else -0.15f
                    '了', '乙' -> if (dirs[0] != StrokeDir.HORIZONTAL) 0.25f else -0.10f
                    else -> 0f
                }
            }
            2 -> {
                val allHoriz = dirs.all { it == StrokeDir.HORIZONTAL }
                val hasHorizAndVert = dirs.contains(StrokeDir.HORIZONTAL) &&
                    (dirs.contains(StrokeDir.VERTICAL) || dirs.contains(StrokeDir.COMPLEX))
                val hasSlants = dirs.contains(StrokeDir.FALLING_LEFT) || dirs.contains(StrokeDir.FALLING_RIGHT)
                when (char) {
                    '二' -> if (allHoriz) 0.42f else -0.10f
                    '十', '七', '丁', '厂', '卜' -> if (hasHorizAndVert) 0.34f else 0f
                    '人', '八', '入', '儿', '几', '九', '刀', '力', '又', '了' -> if (hasSlants || !allHoriz) 0.26f else 0f
                    else -> 0f
                }
            }
            3 -> {
                val allHoriz = dirs.all { it == StrokeDir.HORIZONTAL }
                when (char) {
                    '三' -> if (allHoriz) 0.45f else -0.12f
                    '口', '日', '山', '工', '土', '干', '上', '下', '大', '小', '女', '子', '马', '门', '习', '才', '万', '久', '千', '川', '己', '已', '也' ->
                        if (!allHoriz) 0.20f else 0f
                    else -> 0f
                }
            }
            else -> 0f
        }
    }

    private fun estimateStrokesFromInk(inkCoverage: Float): Int {
        return ((inkCoverage * 24f).toInt()).coerceIn(3, 14)
    }

    private fun isCjkChar(c: Char): Boolean {
        return c.code in 0x4E00..0x9FFF
    }

    /**
     * Curated stroke count lookup table for common HSK 1-4 Hanzi characters.
     */
    private val COMMON_STROKE_COUNTS: Map<Char, Int> = mapOf(
        // 1 stroke
        '一' to 1, '乙' to 1,
        // 2 strokes
        '二' to 2, '十' to 2, '人' to 2, '入' to 2, '八' to 2, '儿' to 2, '几' to 2, '九' to 2,
        '了' to 2, '刀' to 2, '力' to 2, '又' to 2, '七' to 2, '丁' to 2, '厂' to 2, '卜' to 2,
        // 3 strokes
        '三' to 3, '干' to 3, '工' to 3, '土' to 3, '士' to 3, '下' to 3, '上' to 3, '大' to 3,
        '小' to 3, '口' to 3, '山' to 3, '千' to 3, '川' to 3, '亿' to 3, '个' to 3, '夕' to 3,
        '久' to 3, '么' to 3, '门' to 3, '女' to 3, '子' to 3, '马' to 3, '习' to 3, '飞' to 3,
        '才' to 3, '万' to 3, '己' to 3, '已' to 3, '也' to 3,
        // 4 strokes
        '王' to 4, '开' to 4, '天' to 4, '夫' to 4, '太' to 4, '元' to 4, '无' to 4, '云' to 4,
        '专' to 4, '木' to 4, '五' to 4, '支' to 4, '不' to 4, '少' to 4, '日' to 4, '中' to 4,
        '水' to 4, '见' to 4, '午' to 4, '牛' to 4, '手' to 4, '毛' to 4, '气' to 4, '长' to 4,
        '片' to 4, '斤' to 4, '爪' to 4, '父' to 4, '月' to 4, '风' to 4, '文' to 4, '方' to 4,
        '火' to 4, '为' to 4, '心' to 4, '尺' to 4, '巴' to 4, '办' to 4, '队' to 4, '双' to 4,
        '书' to 4, '车' to 4, '从' to 4, '今' to 4, '分' to 4, '公' to 4, '六' to 4, '友' to 4,
        '比' to 4, '少' to 4, '认' to 4,
        // 5 strokes
        '玉' to 5, '正' to 5, '去' to 5, '节' to 5, '本' to 5, '术' to 5, '可' to 5, '左' to 5,
        '右' to 5, '石' to 5, '龙' to 5, '平' to 5, '东' to 5, '卡' to 5, '北' to 5, '业' to 5,
        '旧' to 5, '目' to 5, '电' to 5, '号' to 5, '只' to 5, '兄' to 5, '叫' to 5, '四' to 5,
        '生' to 5, '失' to 5, '禾' to 5, '白' to 5, '他' to 5, '们' to 5, '瓜' to 5, '用' to 5,
        '外' to 5, '冬' to 5, '鸟' to 5, '包' to 5, '主' to 5, '市' to 5, '立' to 5, '半' to 5,
        '头' to 5, '汉' to 5, '宁' to 5, '它' to 5, '写' to 5, '礼' to 5, '记' to 5, '出' to 5,
        '奶' to 5, '皮' to 5, '发' to 5, '对' to 5, '台' to 5, '母' to 5, '打' to 5, '田' to 5,
        // 6 strokes
        '动' to 6, '老' to 6, '考' to 6, '地' to 6, '场' to 6, '耳' to 6, '共' to 6, '机' to 6,
        '过' to 6, '再' to 6, '西' to 6, '在' to 6, '百' to 6, '有' to 6, '页' to 6, '师' to 6,
        '光' to 6, '早' to 6, '同' to 6, '因' to 6, '回' to 6, '吃' to 6, '岁' to 6, '肉' to 6,
        '年' to 6, '先' to 6, '竹' to 6, '自' to 6, '休' to 6, '件' to 6, '伤' to 6, '价' to 6,
        '任' to 6, '份' to 6, '华' to 6, '向' to 6, '后' to 6, '行' to 6, '会' to 6, '全' to 6,
        '杀' to 6, '爷' to 6, '伞' to 6, '色' to 6, '齐' to 6, '交' to 6, '衣' to 6, '问' to 6,
        '羊' to 6, '米' to 6, '灯' to 6, '汗' to 6, '忙' to 6, '兴' to 6, '宇' to 6, '守' to 6,
        '字' to 6, '安' to 6, '讲' to 6, '农' to 6, '那' to 6, '阳' to 6, '阴' to 6, '收' to 6,
        '好' to 6, '她' to 6, '妈' to 6, '戏' to 6, '欢' to 6, '买' to 6, '红' to 6, '级' to 6,
        // 7 strokes
        '麦' to 7, '走' to 7, '块' to 7, '坏' to 7, '坐' to 7, '丽' to 7, '两' to 7, '来' to 7,
        '医' to 7, '否' to 7, '还' to 7, '歼' to 7, '连' to 7, '步' to 7, '助' to 7, '县' to 7,
        '里' to 7, '呆' to 7, '园' to 7, '男' to 7, '吵' to 7, '听' to 7, '吹' to 7, '吧' to 7,
        '别' to 7, '员' to 7, '帐' to 7, '希' to 7, '利' to 7, '每' to 7, '兵' to 7, '体' to 7,
        '何' to 7, '但' to 7, '作' to 7, '你' to 7, '住' to 7, '位' to 7, '低' to 7, '身' to 7,
        '近' to 7, '坐' to 7, '邻' to 7, '肚' to 7, '角' to 7, '条' to 7, '饭' to 7, '饮' to 7,
        '系' to 7, '言' to 7, '冷' to 7, '忘' to 7, '快' to 7, '完' to 7, '究' to 7, '穷' to 7,
        '识' to 7, '词' to 7, '诉' to 7, '诊' to 7, '初' to 7, '远' to 7, '迟' to 7, '局' to 7,
        '层' to 7, '尾' to 7, '张' to 7, '改' to 7, '即' to 7, '阿' to 7, '陈' to 7, '附' to 7,
        '际' to 7, '陆' to 7, '努' to 7, '忍' to 7, '劲' to 7, '鸡' to 7, '纸' to 7, '我' to 7,
        '找' to 7, '花' to 7, '弟' to 7, '这' to 7, '汽' to 7, '沙' to 7, '没' to 7, '床' to 7,
        // 8 strokes
        '玩' to 8, '环' to 8, '现' to 8, '表' to 8, '规' to 8, '幸' to 8, '拉' to 8, '招' to 8,
        '担' to 8, '押' to 8, '抽' to 8, '拐' to 8, '拖' to 8, '拍' to 8, '顶' to 8, '拆' to 8,
        '拎' to 8, '势' to 8, '抱' to 8, '垃' to 8, '拉' to 8, '拦' to 8, '幸' to 8, '直' to 8,
        '林' to 8, '杯' to 8, '柜' to 8, '板' to 8, '松' to 8, '枪' to 8, '枫' to 8, '构' to 8,
        '丧' to 8, '画' to 8, '事' to 8, '雨' to 8, '卖' to 8, '奇' to 8, '奔' to 8, '态' to 8,
        '欧' to 8, '垄' to 8, '妻' to 8, '轰' to 8, '顷' to 8, '转' to 8, '斩' to 8, '轮' to 8,
        '软' to 8, '到' to 8, '非' to 8, '叔' to 8, '肯' to 8, '齿' to 8, '些' to 8, '虎' to 8,
        '具' to 8, '味' to 8, '果' to 8, '国' to 8, '明' to 8, '易' to 8, '昂' to 8, '帕' to 8,
        '账' to 8, '咖' to 8, '呼' to 8, '图' to 8, '知' to 8, '物' to 8, '和' to 8, '季' to 8,
        '委' to 8, '爸' to 8, '金' to 8, '命' to 8, '念' to 8, '使' to 8, '例' to 8, '供' to 8,
        '依' to 8, '侠' to 8, '侦' to 8, '侧' to 8, '侨' to 8, '佩' to 8, '往' to 8, '爬' to 8,
        '所' to 8, '欣' to 8, '朋' to 8, '服' to 8, '肥' to 8, '股' to 8, '胁' to 8, '周' to 8,
        '昏' to 8, '鱼' to 8, '兔' to 8, '狐' to 8, '狗' to 8, '饱' to 8, '饲' to 8, '变' to 8,
        '京' to 8, '夜' to 8, '底' to 8, '店' to 8, '府' to 8, '放' to 8, '刻' to 8, '育' to 8,
        '闹' to 8, '单' to 8, '炒' to 8, '炉' to 8, '炎' to 8, '浅' to 8, '法' to 8, '河' to 8,
        '沾' to 8, '泪' to 8, '油' to 8, '泳' to 8, '泥' to 8, '波' to 8, '注' to 8, '泪' to 8,
        '性' to 8, '怪' to 8, '怕' to 8, '学' to 8, '宝' to 8, '定' to 8, '宜' to 8, '实' to 8,
        '空' to 8, '帘' to 8, '房' to 8, '视' to 8, '话' to 8, '该' to 8, '详' to 8, '试' to 8,
        '诗' to 8, '诚' to 8, '衬' to 8, '居' to 8, '届' to 8, '刷' to 8, '屈' to 8, '弦' to 8,
        '孟' to 8, '孤' to 8, '录' to 8, '建' to 8, '参' to 8, '艰' to 8, '线' to 8, '练' to 8,
        '组' to 8, '细' to 8, '终' to 8, '织' to 8, '绍' to 8, '经' to 8, '贯' to 8, '妹' to 8,
        '姐' to 8, '姑' to 8, '姓' to 8, '始' to 8, '驾' to 8, '驼' to 8, '驿' to 8,
        // 9 strokes
        '春' to 9, '帮' to 9, '珍' to 9, '玻' to 9, '毒' to 9, '型' to 9, '城' to 9, '要' to 9,
        '南' to 9, '相' to 9, '查' to 9, '树' to 9, '柳' to 9, '故' to 9, '胡' to 9, '药' to 9,
        '茶' to 9, '草' to 9, '荣' to 9, '革' to 9, '面' to 9, '耍' to 9, '牵' to 9, '残' to 9,
        '轻' to 9, '鸦' to 9, '皆' to 9, '背' to 9, '战' to 9, '点' to 9, '临' to 9, '览' to 9,
        '竖' to 9, '省' to 9, '削' to 9, '尝' to 9, '是' to 9, '盼' to 9, '眨' to 9, '哄' to 9,
        '哑' to 9, '显' to 9, '映' to 9, '星' to 9, '昨' to 9, '咬' to 9, '咳' to 9, '哪' to 9,
        '罚' to 9, '贴' to 9, '骨' to 9, '幽' to 9, '看' to 9, '怎' to 9, '选' to 9, '适' to 9,
        '秒' to 9, '香' to 9, '种' to 9, '秋' to 9, '科' to 9, '重' to 9, '复' to 9, '段' to 9,
        '便' to 9, '俩' to 9, '保' to 9, '促' to 9, '俄' to 9, '俗' to 9, '信' to 9, '皇' to 9,
        '泉' to 9, '追' to 9, '俊' to 9, '盾' to 9, '待' to 9, '律' to 9, '很' to 9, '须' to 9,
        '叙' to 9, '剑' to 9, '逃' to 9, '食' to 9, '盆' to 9, '胆' to 9, '胜' to 9, '胖' to 9,
        '脉' to 9, '勉' to 9, '狭' to 9, '狮' to 9, '独' to 9, '急' to 9, '亮' to 9, '度' to 9,
        '庭' to 9, '疯' to 9, '疫' to 9, '亲' to 9, '音' to 9, '帝' to 9, '施' to 9, '闻' to 9,
        '阀' to 9, '阁' to 9, '差' to 9, '养' to 9, '美' to 9, '姜' to 9, '叛' to 9, '送' to 9,
        '类' to 9, '迷' to 9, '前' to 9, '首' to 9, '逆' to 9, '总' to 9, '炼' to 9, '烂' to 9,
        '洁' to 9, '洪' to 9, '洒' to 9, '浇' to 9, '浊' to 9, '洞' to 9, '测' to 9, '洗' to 9,
        '活' to 9, '派' to 9, '染' to 9, '济' to 9, '洋' to 9, '洲' to 9, '浑' to 9, '浓' to 9,
        '觉' to 9, '室' to 9, '宫' to 9, '宪' to 9, '突' to 9, '穿' to 9, '窃' to 9, '客' to 9,
        '冠' to 9, '语' to 9, '扁' to 9, '祖' to 9, '神' to 9, '祝' to 9, '误' to 9, '诱' to 9,
        '说' to 9, '诵' to 9, '屋' to 9, '退' to 9, '既' to 9, '费' to 9, '陡' to 9, '除' to 9,
        '险' to 9, '院' to 9, '娃' to 9, '姨' to 9, '姻' to 9, '娇' to 9, '怒' to 9, '架' to 9,
        '贺' to 9, '盈' to 9, '勇' to 9, '怠' to 9, '柔' to 9, '绑' to 9, '绒' to 9, '结' to 9,
        '绕' to 9, '骄' to 9, '绘' to 9, '给' to 9, '络' to 9, '绝' to 9,
        // 10+ strokes
        '班' to 10, '起' to 10, '都' to 10, '真' to 10, '桌' to 10, '校' to 10, '样' to 10, '根' to 10,
        '哥' to 10, '速' to 10, '夏' to 10, '破' to 10, '原' to 10, '顾' to 10, '较' to 10, '顿' to 10,
        '紧' to 10, '桌' to 10, '哭' to 10, '恩' to 10, '啊' to 10, '峰' to 10, '圆' to 10, '钱' to 10,
        '铁' to 10, '铅' to 10, '缺' to 10, '笑' to 10, '笔' to 10, '借' to 10, '倒' to 10, '候' to 10,
        '息' to 10, '拿' to 10, '爱' to 10, '脏' to 10, '脑' to 10, '留' to 10, '饿' to 10, '高' to 10,
        '座' to 10, '病' to 10, '疲' to 10, '疼' to 10, '站' to 10, '竞' to 10, '部' to 10, '旁' to 10,
        '旅' to 10, '阅' to 10, '烦' to 10, '烧' to 10, '烤' to 10, '烟' to 10, '酒' to 10, '浙' to 10,
        '消' to 10, '海' to 10, '浮' to 10, '流' to 10, '涨' to 10, '烫' to 10, '家' to 10, '宾' to 10,
        '宿' to 10, '容' to 10, '宽' to 10, '席' to 10, '请' to 10, '诸' to 10, '读' to 10, '扇' to 10,
        '课' to 10, '谁' to 10, '调' to 10, '谅' to 10, '谈' to 10, '谊' to 10, '展' to 10, '弱' to 10,
        '陪' to 10, '娘' to 10, '通' to 10, '能' to 10, '难' to 10, '预' to 10, '桑' to 10,
        '黄' to 11, '教' to 11, '菜' to 11, '掉' to 11, '推' to 11, '接' to 11, '排' to 11, '雪' to 11,
        '职' to 11, '基' to 11, '勘' to 11, '聊' to 11, '娶' to 11, '常' to 11, '虚' to 11, '晨' to 11,
        '睁' to 11, '眼' to 11, '睡' to 13, '晚' to 11, '唱' to 11, '唯' to 11, '啤' to 11, '啥' to 11,
        '圈' to 11, '铜' to 11, '银' to 11, '甜' to 11, '梨' to 11, '移' to 11, '笼' to 11, '第' to 11,
        '符' to 11, '做' to 11, '停' to 11, '假' to 11, '得' to 11, '衔' to 11, '盘' to 11, '船' to 11,
        '斜' to 11, '盒' to 11, '鸽' to 11, '彩' to 11, '领' to 11, '脚' to 11, '脸' to 11, '脱' to 11,
        '象' to 11, '够' to 11, '猜' to 11, '猪' to 11, '猫' to 11, '馆' to 11, '康' to 11, '鹿' to 11,
        '商' to 11, '望' to 11, '着' to 11, '盖' to 11, '粒' to 11, '粗' to 11, '断' to 11, '剪' to 11,
        '清' to 11, '添' to 11, '淋' to 11, '淹' to 11, '渴' to 12, '渐' to 11, '混' to 11, '渔' to 11,
        '深' to 11, '婆' to 11, '梁' to 11, '情' to 11, '惜' to 11, '惭' to 11, '悼' to 11, '惧' to 11,
        '惊' to 11, '寄' to 11, '寅' to 11, '密' to 11, '宿' to 11, '章' to 11, '竟' to 11, '商' to 11,
        '族' to 11, '弹' to 11, '随' to 11, '蛋' to 11, '婚' to 11, '绿' to 11,
        '喜' to 12, '期' to 12, '散' to 12, '落' to 12, '懂' to 15, '喝' to 12, '喂' to 12, '黑' to 12,
        '跑' to 12, '跟' to 13, '路' to 13, '跳' to 13, '踢' to 15, '谢' to 12, '想' to 13, '意' to 13,
        '新' to 13, '零' to 13, '雷' to 13, '雾' to 13, '慢' to 14, '漂' to 14, '演' to 14, '赛' to 14,
        '题' to 15, '影' to 15, '鞋' to 15, '戴' to 17, '赢' to 17,
    )
}
