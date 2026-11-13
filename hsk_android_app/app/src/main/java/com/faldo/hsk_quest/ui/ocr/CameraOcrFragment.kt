package com.faldo.hsk_quest.ui.ocr

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.faldo.hsk_quest.R
import com.faldo.hsk_quest.data.local.DictionaryEntry
import com.faldo.hsk_quest.databinding.FragmentCameraOcrBinding
import com.faldo.hsk_quest.engine.CameraOcrResult
import com.faldo.hsk_quest.engine.OcrPolarityMode
import com.faldo.hsk_quest.engine.OcrScanMode
import com.faldo.hsk_quest.ui.practice.HanziCandidate
import com.faldo.hsk_quest.util.appContainer
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Pleco-style real-time Camera OCR scanner powered by Google ML Kit Chinese Text Recognition.
 * Crops live CameraX frames strictly inside the reticle ROI, recognizes single Hanzi or full
 * Chinese phrases, and resolves Pinyin and English meanings against the HSK 1-9 dictionary.
 */
class CameraOcrFragment : Fragment() {

    private var _binding: FragmentCameraOcrBinding? = null
    private val binding get() = _binding!!

    private var cameraProvider: ProcessCameraProvider? = null
    private var boundCamera: Camera? = null
    private var analysisExecutor: ExecutorService? = null
    private var textRecognizer: TextRecognizer? = null

    @Volatile
    private var cachedEntries: List<DictionaryEntry> = emptyList()

    @Volatile
    private var entriesBySimplified: Map<String, DictionaryEntry> = emptyMap()

    @Volatile
    private var entriesByTraditional: Map<String, DictionaryEntry> = emptyMap()

    private var tts: TextToSpeech? = null
    private var isTtsReady: Boolean = false

    @Volatile
    private var scanMode: OcrScanMode = OcrScanMode.SINGLE

    @Volatile
    private var polarityMode: OcrPolarityMode = OcrPolarityMode.DARK_INK_ON_LIGHT

    @Volatile
    private var isFrozen: Boolean = false

    private var isTorchOn: Boolean = false
    private var boxSizeStep: Int = 1 // 0 = S, 1 = M, 2 = L
    private var activeDetectedText: String = ""

    private val isAnalyzing = AtomicBoolean(false)
    private var lastAnalysisMs: Long = 0L

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                binding.layoutOcrPermissionDenied.visibility = View.GONE
                startCamera()
            } else {
                binding.layoutOcrPermissionDenied.visibility = View.VISIBLE
                Toast.makeText(
                    requireContext(),
                    "Camera permission is required for live Hanzi OCR.",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentCameraOcrBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        analysisExecutor = Executors.newSingleThreadExecutor()
        textRecognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        initTextToSpeech()
        setupControls()

        viewLifecycleOwner.lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching { appContainer.hskJsonLoader.loadDictionaryEntries(null) }
                    .getOrDefault(emptyList())
            }
            val simpMap = HashMap<String, DictionaryEntry>(loaded.size)
            val tradMap = HashMap<String, DictionaryEntry>(loaded.size)
            withContext(Dispatchers.Default) {
                for (entry in loaded) {
                    simpMap.putIfAbsent(entry.term.simplified, entry)
                    val trad = entry.term.traditional?.trim().orEmpty()
                    if (trad.isNotEmpty()) {
                        tradMap.putIfAbsent(trad, entry)
                    }
                }
            }
            cachedEntries = loaded
            entriesBySimplified = simpMap
            entriesByTraditional = tradMap
        }

        ensureCameraPermissionAndStart()
    }

    private fun setupControls() {
        binding.btnOcrBack.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.btnGrantCameraPermission.setOnClickListener {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }

        binding.btnModeSingle.setOnClickListener {
            applyScanMode(OcrScanMode.SINGLE)
        }

        binding.btnModeMulti.setOnClickListener {
            applyScanMode(OcrScanMode.MULTI)
        }

        binding.btnOcrPolarity.setOnClickListener {
            polarityMode = if (polarityMode == OcrPolarityMode.DARK_INK_ON_LIGHT) {
                OcrPolarityMode.LIGHT_INK_ON_DARK
            } else {
                OcrPolarityMode.DARK_INK_ON_LIGHT
            }
            binding.btnOcrPolarity.text = if (polarityMode == OcrPolarityMode.DARK_INK_ON_LIGHT) {
                "🌓 Dark Ink"
            } else {
                "🌓 Light Ink"
            }
        }

        binding.btnOcrTorch.setOnClickListener {
            val cam = boundCamera ?: return@setOnClickListener
            if (!cam.cameraInfo.hasFlashUnit()) {
                Toast.makeText(requireContext(), "No flash unit available.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            isTorchOn = !isTorchOn
            cam.cameraControl.enableTorch(isTorchOn)
            binding.btnOcrTorch.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (isTorchOn) R.color.hq_gold else R.color.hq_text_primary,
                ),
            )
        }

        binding.btnRoiSize.setOnClickListener {
            boxSizeStep = (boxSizeStep + 1) % 3
            val (label, scale) = when (boxSizeStep) {
                0 -> "📐 Box: S" to 0.72f
                1 -> "📐 Box: M" to 1.00f
                else -> "📐 Box: L" to 1.28f
            }
            binding.btnRoiSize.text = label
            binding.ocrOverlayView.setBoxScale(scale)
        }

        binding.btnOcrFreeze.setOnClickListener {
            isFrozen = !isFrozen
            binding.btnOcrFreeze.text = if (isFrozen) "▶️ Live" else "⏸ Freeze"
            binding.btnOcrFreeze.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (isFrozen) R.color.hq_gold else R.color.hq_jade,
                ),
            )
        }

        binding.btnOcrSpeak.setOnClickListener {
            if (activeDetectedText.isNotEmpty()) {
                speakMandarin(activeDetectedText)
            }
        }

        binding.btnOcrOpenDict.setOnClickListener {
            val query = activeDetectedText.trim()
            if (query.isEmpty()) return@setOnClickListener
            parentFragmentManager.setFragmentResult(
                RESULT_KEY_OCR,
                Bundle().apply { putString(EXTRA_DETECTED_HANZI, query) },
            )
            findNavController().navigateUp()
        }

        applyScanMode(OcrScanMode.SINGLE)
    }

    private fun applyScanMode(mode: OcrScanMode) {
        scanMode = mode
        val isMulti = mode == OcrScanMode.MULTI
        binding.ocrOverlayView.setMultiCharMode(isMulti)

        val ctx = requireContext()
        binding.btnModeSingle.setBackgroundResource(if (!isMulti) R.drawable.bg_stat_box else R.drawable.bg_card)
        binding.btnModeSingle.setTextColor(
            ContextCompat.getColor(ctx, if (!isMulti) R.color.hq_gold else R.color.hq_text_secondary),
        )
        binding.btnModeMulti.setBackgroundResource(if (isMulti) R.drawable.bg_stat_box else R.drawable.bg_card)
        binding.btnModeMulti.setTextColor(
            ContextCompat.getColor(ctx, if (isMulti) R.color.hq_gold else R.color.hq_text_secondary),
        )
    }

    private fun ensureCameraPermissionAndStart() {
        val granted = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            binding.layoutOcrPermissionDenied.visibility = View.GONE
            startCamera()
        } else {
            binding.layoutOcrPermissionDenied.visibility = View.VISIBLE
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val ctx = requireContext()
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            val b = _binding ?: return@addListener
            val provider = runCatching { future.get() }.getOrNull() ?: return@addListener
            cameraProvider = provider

            val preview = Preview.Builder().build().also {
                it.surfaceProvider = b.previewViewOcr.surfaceProvider
            }

            val analyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            val executor = analysisExecutor ?: return@addListener
            analyzer.setAnalyzer(executor) { imageProxy ->
                val recognizer = textRecognizer
                val currentBinding = _binding
                val now = System.currentTimeMillis()
                if (
                    recognizer == null ||
                    currentBinding == null ||
                    isFrozen ||
                    (now - lastAnalysisMs) < ANALYSIS_INTERVAL_MS ||
                    !isAnalyzing.compareAndSet(false, true)
                ) {
                    imageProxy.close()
                    return@setAnalyzer
                }

                lastAnalysisMs = now
                val pvW = currentBinding.previewViewOcr.width
                val pvH = currentBinding.previewViewOcr.height
                val roi = currentBinding.ocrOverlayView.getNormalizedRoi()
                val modeSnapshot = scanMode
                val polaritySnapshot = polarityMode

                val roiBitmap = try {
                    cropRoiBitmap(imageProxy, pvW, pvH, roi, polaritySnapshot)
                } finally {
                    imageProxy.close()
                }

                if (roiBitmap == null) {
                    isAnalyzing.set(false)
                    return@setAnalyzer
                }

                val startNs = System.nanoTime()
                val inputImage = InputImage.fromBitmap(roiBitmap, 0)
                recognizer.process(inputImage)
                    .addOnSuccessListener { visionText ->
                        val latencyMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
                        val fragView = view
                        if (fragView == null || _binding == null) {
                            roiBitmap.recycle()
                            isAnalyzing.set(false)
                            return@addOnSuccessListener
                        }
                        viewLifecycleOwner.lifecycleScope.launch {
                            try {
                                val result = withContext(Dispatchers.Default) {
                                    buildMlKitOcrResult(
                                        visionText = visionText,
                                        roiW = roiBitmap.width,
                                        roiH = roiBitmap.height,
                                        scanMode = modeSnapshot,
                                        latencyMs = latencyMs,
                                        previewBitmap = roiBitmap,
                                    )
                                }
                                if (!isFrozen && _binding != null) {
                                    renderOcrResult(result)
                                }
                            } finally {
                                isAnalyzing.set(false)
                            }
                        }
                    }
                    .addOnFailureListener {
                        roiBitmap.recycle()
                        isAnalyzing.set(false)
                    }
                    .addOnCanceledListener {
                        roiBitmap.recycle()
                        isAnalyzing.set(false)
                    }
            }

            runCatching {
                provider.unbindAll()
                boundCamera = provider.bindToLifecycle(
                    viewLifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analyzer,
                )
            }.onFailure {
                Toast.makeText(ctx, "Failed to start camera preview.", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(ctx))
    }

    /**
     * Converts the CameraX ImageProxy to an upright Bitmap and extracts the exact
     * PreviewView FILL_CENTER sub-region matching [roiNorm].
     */
    private fun cropRoiBitmap(
        imageProxy: ImageProxy,
        previewWidth: Int,
        previewHeight: Int,
        roiNorm: RectF,
        polarity: OcrPolarityMode,
    ): Bitmap? {
        if (previewWidth <= 0 || previewHeight <= 0) return null
        val raw = runCatching { imageProxy.toBitmap() }.getOrNull() ?: return null
        val rot = ((imageProxy.imageInfo.rotationDegrees % 360) + 360) % 360

        val upright = if (rot != 0) {
            val matrix = Matrix().apply { postRotate(rot.toFloat()) }
            val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
            if (rotated !== raw) raw.recycle()
            rotated
        } else {
            raw
        }

        val uprightW = upright.width.toFloat()
        val uprightH = upright.height.toFloat()
        val scale = max(previewWidth / uprightW, previewHeight / uprightH)
        val visW = previewWidth / scale
        val visH = previewHeight / scale
        val visLeft = (uprightW - visW) / 2f
        val visTop = (uprightH - visH) / 2f

        val left = (visLeft + roiNorm.left * visW).roundToInt().coerceIn(0, upright.width - 2)
        val right = (visLeft + roiNorm.right * visW).roundToInt().coerceIn(left + 2, upright.width)
        val top = (visTop + roiNorm.top * visH).roundToInt().coerceIn(0, upright.height - 2)
        val bottom = (visTop + roiNorm.bottom * visH).roundToInt().coerceIn(top + 2, upright.height)
        val cropW = (right - left).coerceAtLeast(32)
        val cropH = (bottom - top).coerceAtLeast(32)
        if (left + cropW > upright.width || top + cropH > upright.height) {
            upright.recycle()
            return null
        }

        val cropped = Bitmap.createBitmap(upright, left, top, cropW, cropH)
        if (cropped !== upright) upright.recycle()

        if (polarity == OcrPolarityMode.DARK_INK_ON_LIGHT) {
            return cropped
        }

        // Invert bright text on dark signage so ML Kit reads dark ink on bright background
        val inverted = Bitmap.createBitmap(cropped.width, cropped.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(inverted)
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(INVERT_COLOR_MATRIX)
        }
        canvas.drawBitmap(cropped, 0f, 0f, paint)
        cropped.recycle()
        return inverted
    }

    private suspend fun buildMlKitOcrResult(
        visionText: Text,
        roiW: Int,
        roiH: Int,
        scanMode: OcrScanMode,
        latencyMs: Long,
        previewBitmap: Bitmap,
    ): CameraOcrResult {
        val cx = roiW / 2f
        val cy = roiH / 2f

        val cjkLines = visionText.textBlocks
            .flatMap { it.lines }
            .mapNotNull { line ->
                val clean = line.text.filter { isCjkChar(it) }
                if (clean.isEmpty()) return@mapNotNull null
                val box = line.boundingBox
                val dist = if (box != null) {
                    hypot((box.centerX() - cx).toDouble(), (box.centerY() - cy).toDouble())
                } else {
                    Double.MAX_VALUE
                }
                Pair(clean, dist)
            }
            .sortedBy { it.second }

        if (cjkLines.isEmpty()) {
            return CameraOcrResult(
                hasSignal = false,
                detectedText = "",
                pinyin = "",
                english = "",
                hskLevelLabel = null,
                confidence = 0f,
                inferenceLatencyMs = latencyMs,
                slotCount = 0,
                alternatives = emptyList(),
                matchedEntry = null,
                previewBitmap = previewBitmap,
            )
        }

        val cjkElements = visionText.textBlocks
            .flatMap { it.lines }
            .flatMap { it.elements }
            .mapNotNull { elem ->
                val clean = elem.text.filter { isCjkChar(it) }
                if (clean.isEmpty()) return@mapNotNull null
                val box = elem.boundingBox
                val dist = if (box != null) {
                    hypot((box.centerX() - cx).toDouble(), (box.centerY() - cy).toDouble())
                } else {
                    Double.MAX_VALUE
                }
                Triple(clean, box, dist)
            }
            .sortedBy { it.third }

        val primaryText: String = if (scanMode == OcrScanMode.SINGLE) {
            val closestElem = cjkElements.firstOrNull()
            val str = closestElem?.first ?: cjkLines.first().first
            if (str.length == 1) {
                str
            } else {
                // Pick the single character within the closest token aligned to the crosshair center
                val box = closestElem?.second
                if (box != null && box.width() > 0) {
                    val relX = ((cx - box.left) / box.width().toFloat()).coerceIn(0f, 0.999f)
                    val charIdx = (relX * str.length).toInt().coerceIn(0, str.length - 1)
                    str[charIdx].toString()
                } else {
                    str.first().toString()
                }
            }
        } else {
            cjkLines.first().first.take(12)
        }

        val exactEntry = lookupEntry(primaryText)
        val smart = if (primaryText.length >= 2) {
            appContainer.hskJsonLoader.searchSmartDictionary(primaryText, levelFilter = null, limit = 12)
        } else {
            null
        }

        val resolvedPinyin: String
        val resolvedEnglish: String
        val badgeLabel: String?
        if (exactEntry != null) {
            resolvedPinyin = exactEntry.term.pinyin
            resolvedEnglish = exactEntry.term.english
            badgeLabel = exactEntry.levelLabel
        } else if (smart != null && smart.segments.isNotEmpty()) {
            resolvedPinyin = smart.sentenceSummaryPinyin
            resolvedEnglish = smart.sentenceSummaryEnglish
            badgeLabel = "${smart.segments.size} Words"
        } else {
            val charInfos = primaryText.map { ch ->
                val s = ch.toString()
                s to lookupEntry(s)
            }
            resolvedPinyin = charInfos.joinToString(" ") { (ch, e) -> e?.term?.pinyin ?: ch }
            resolvedEnglish = charInfos.mapNotNull { (ch, e) ->
                e?.let { "$ch: ${it.term.english.split(";", "/").firstOrNull()?.trim()}" }
            }.joinToString("  •  ").ifBlank {
                "Not found in HSK 1-9 lexicon. Tap 'Open in Dictionary' to search."
            }
            badgeLabel = if (primaryText.length > 1) "${primaryText.length} Chars" else "OCR"
        }

        val altStrings = LinkedHashSet<String>().apply {
            add(primaryText)
            smart?.segments?.forEach { add(it.primaryEntry.term.simplified) }
            cjkElements.forEach { if (it.first.length in 1..4) add(it.first) }
            cjkLines.forEach { if (it.first.length in 1..6) add(it.first) }
            primaryText.forEach { add(it.toString()) }
        }

        val alternatives = altStrings
            .take(8)
            .mapIndexed { idx, token ->
                val e = lookupEntry(token)
                HanziCandidate(
                    hanzi = token,
                    pinyin = e?.term?.pinyin ?: if (token == primaryText) resolvedPinyin else "",
                    english = e?.term?.english ?: if (token == primaryText) resolvedEnglish else "Character",
                    score = (0.98f - idx * 0.04f).coerceAtLeast(0.50f),
                )
            }

        return CameraOcrResult(
            hasSignal = true,
            detectedText = primaryText,
            pinyin = resolvedPinyin,
            english = resolvedEnglish,
            hskLevelLabel = badgeLabel,
            confidence = 0.96f,
            inferenceLatencyMs = latencyMs,
            slotCount = primaryText.length,
            alternatives = alternatives,
            matchedEntry = exactEntry,
            previewBitmap = previewBitmap,
        )
    }

    private fun lookupEntry(hanzi: String): DictionaryEntry? {
        if (hanzi.isEmpty()) return null
        return entriesBySimplified[hanzi] ?: entriesByTraditional[hanzi]
    }

    private fun isCjkChar(c: Char): Boolean = c.code in 0x4E00..0x9FFF

    private fun renderOcrResult(result: CameraOcrResult) {
        val b = _binding ?: return

        result.previewBitmap?.let { bmp ->
            b.ivOcrModelPreview.setImageBitmap(bmp)
        }

        if (!result.hasSignal || result.detectedText.isEmpty()) {
            activeDetectedText = ""
            b.tvOcrHanzi.text = "—"
            b.tvOcrPinyin.text = if (scanMode == OcrScanMode.MULTI) {
                "Aim bar at Hanzi word or sign..."
            } else {
                "Aim box at 1 Hanzi..."
            }
            b.tvOcrEnglish.text = "Keep character centered inside the gold frame with clear lighting."
            b.tvOcrMeta.text = "Google ML Kit Chinese OCR · waiting for Hanzi"
            b.btnOcrOpenDict.isEnabled = false
            b.llOcrAlternatives.removeAllViews()
            return
        }

        applyCandidateDisplay(
            hanzi = result.detectedText,
            pinyin = result.pinyin,
            english = result.english,
            hskLevelLabel = result.hskLevelLabel,
            confidence = result.confidence,
            latencyMs = result.inferenceLatencyMs,
        )
        populateAlternativeChips(result.alternatives, result.inferenceLatencyMs)
    }

    private fun applyCandidateDisplay(
        hanzi: String,
        pinyin: String,
        english: String,
        hskLevelLabel: String?,
        confidence: Float,
        latencyMs: Long,
    ) {
        val b = _binding ?: return
        activeDetectedText = hanzi
        b.tvOcrHanzi.text = hanzi
        b.tvOcrPinyin.text = pinyin.ifBlank { "Detected Hanzi" }
        b.tvOcrEnglish.text = english.ifBlank { "Tap 'Open in Dictionary' to inspect examples." }

        val badge = hskLevelLabel?.let { "$it · " }.orEmpty()
        b.tvOcrMeta.text = "${badge}${latencyMs}ms ML Kit"
        b.btnOcrOpenDict.isEnabled = true
    }

    private fun populateAlternativeChips(
        alternatives: List<HanziCandidate>,
        latencyMs: Long,
    ) {
        val b = _binding ?: return
        b.llOcrAlternatives.removeAllViews()
        val ctx = requireContext()

        alternatives.forEachIndexed { index, cand ->
            val chip = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundResource(if (index == 0) R.drawable.bg_stat_box else R.drawable.bg_card)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    marginEnd = dp(8)
                }
                isClickable = true
                isFocusable = true
            }

            val tvChar = TextView(ctx).apply {
                text = cand.hanzi
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
                setTextColor(
                    ContextCompat.getColor(
                        ctx,
                        if (index == 0) R.color.hq_gold else R.color.hq_text_primary,
                    ),
                )
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            }

            val subText = cand.pinyin.ifBlank { "Inspect" }
            val tvSub = TextView(ctx).apply {
                text = subText
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                setTextColor(ContextCompat.getColor(ctx, R.color.hq_jade))
                gravity = Gravity.CENTER
            }

            chip.addView(tvChar)
            chip.addView(tvSub)

            chip.setOnClickListener {
                // Freeze automatically when user taps a specific alternative so they can read comfortably
                if (!isFrozen) {
                    isFrozen = true
                    b.btnOcrFreeze.text = "▶️ Live"
                    b.btnOcrFreeze.setTextColor(ContextCompat.getColor(ctx, R.color.hq_gold))
                }
                val entry = lookupEntry(cand.hanzi)
                applyCandidateDisplay(
                    hanzi = cand.hanzi,
                    pinyin = entry?.term?.pinyin ?: cand.pinyin,
                    english = entry?.term?.english ?: cand.english,
                    hskLevelLabel = entry?.levelLabel,
                    confidence = cand.score,
                    latencyMs = latencyMs,
                )
            }

            b.llOcrAlternatives.addView(chip)
        }
    }

    private fun initTextToSpeech() {
        tts = TextToSpeech(requireContext()) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val res = tts?.setLanguage(Locale.SIMPLIFIED_CHINESE)
                isTtsReady = res != TextToSpeech.LANG_MISSING_DATA &&
                    res != TextToSpeech.LANG_NOT_SUPPORTED
            }
        }
    }

    private fun speakMandarin(text: String) {
        if (!isTtsReady) {
            Toast.makeText(requireContext(), "Mandarin TTS is initializing...", Toast.LENGTH_SHORT).show()
            return
        }
        tts?.setSpeechRate(0.92f)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "hsk_ocr_${text.hashCode()}")
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics,
        ).roundToInt()
    }

    override fun onDestroyView() {
        cameraProvider?.unbindAll()
        boundCamera = null
        cameraProvider = null
        textRecognizer?.close()
        textRecognizer = null
        analysisExecutor?.shutdownNow()
        analysisExecutor = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val RESULT_KEY_OCR = "camera_ocr_result"
        const val EXTRA_DETECTED_HANZI = "detected_hanzi"
        private const val ANALYSIS_INTERVAL_MS = 220L
        private val INVERT_COLOR_MATRIX = ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
    }
}
