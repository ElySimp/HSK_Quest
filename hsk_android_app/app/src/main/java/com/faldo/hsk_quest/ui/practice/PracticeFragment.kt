package com.faldo.hsk_quest.ui.practice

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.faldo.hsk_quest.MainActivity
import com.faldo.hsk_quest.R
import com.faldo.hsk_quest.data.local.DictionaryEntry
import com.faldo.hsk_quest.data.local.HskJsonLoader
import com.faldo.hsk_quest.data.model.HskTerm
import com.faldo.hsk_quest.databinding.FragmentPracticeBinding
import com.faldo.hsk_quest.ui.battle.AudioRecorderHelper
import com.faldo.hsk_quest.ui.battle.CanvasWritingView
import com.faldo.hsk_quest.util.appContainer
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * Pleco-Style HSK 1-9 Searchable Dictionary & Writing Lab.
 * Supports instant Hanzi / Pinyin (tone-insensitive & space-insensitive) / English search,
 * Mandarin TextToSpeech pronunciation, voice recording comparison, and Hanzi watermark stroke tracing.
 */
class PracticeFragment : Fragment() {

    private var _binding: FragmentPracticeBinding? = null
    private val binding get() = _binding!!

    private lateinit var audioHelper: AudioRecorderHelper
    private var recordedAudioFile: File? = null
    private var pendingRecordingCallback: (() -> Unit)? = null

    private var tts: TextToSpeech? = null
    private var isTtsReady: Boolean = false

    private val dictionaryAdapter = DictionaryAdapter()
    private var selectedLevelFilter: Int? = 1
    private var searchJob: Job? = null
    private var activePracticeSheet: BottomSheetDialog? = null

    private val requestAudioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                pendingRecordingCallback?.invoke()
                pendingRecordingCallback = null
            } else {
                pendingRecordingCallback = null
                Toast.makeText(
                    requireContext(),
                    "Microphone permission is required to record pronunciation.",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentPracticeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        audioHelper = AudioRecorderHelper(requireContext())

        initTextToSpeech()
        setupRecyclerView()
        setupSearchAndFilters()
        executeSearch(immediate = true)
        (activity as? MainActivity)?.attachRecyclerScrollListener(binding.rvDictionary)
    }

    private fun initTextToSpeech() {
        tts = TextToSpeech(requireContext().applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val engine = tts ?: return@TextToSpeech
                val result = engine.setLanguage(Locale.SIMPLIFIED_CHINESE)
                isTtsReady = if (
                    result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    val fallback = engine.setLanguage(Locale.CHINA)
                    fallback != TextToSpeech.LANG_MISSING_DATA &&
                        fallback != TextToSpeech.LANG_NOT_SUPPORTED
                } else {
                    true
                }
            }
        }
    }

    private fun speakMandarin(hanzi: String) {
        val engine = tts
        if (engine != null && isTtsReady) {
            engine.speak(
                hanzi,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "hsk_tts_${System.currentTimeMillis()}",
            )
        } else {
            Toast.makeText(
                requireContext(),
                "Speaking: $hanzi (Mandarin TTS initializing or voice pack unavailable)",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun setupRecyclerView() {
        binding.rvDictionary.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = dictionaryAdapter
            setHasFixedSize(false)
        }
    }

    private fun setupSearchAndFilters() {
        binding.etDictSearch.doAfterTextChanged { editable ->
            val hasText = !editable.isNullOrEmpty()
            binding.btnClearSearch.visibility = if (hasText) View.VISIBLE else View.GONE
            executeSearch(immediate = false)
        }

        binding.btnClearSearch.setOnClickListener {
            binding.etDictSearch.setText("")
            executeSearch(immediate = true)
        }

        binding.chipGroupDictLevel.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: R.id.chip_level_1
            selectedLevelFilter = when (checkedId) {
                R.id.chip_level_all -> null
                R.id.chip_level_weak -> -1
                R.id.chip_level_1 -> 1
                R.id.chip_level_2 -> 2
                R.id.chip_level_3 -> 3
                R.id.chip_level_4 -> 4
                R.id.chip_level_5 -> 5
                R.id.chip_level_6 -> 6
                R.id.chip_level_7 -> 7
                else -> 1
            }
            executeSearch(immediate = true)
        }
    }

    private fun executeSearch(immediate: Boolean) {
        searchJob?.cancel()
        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            if (!immediate) {
                delay(120L)
            }
            val query = _binding?.etDictSearch?.text?.toString().orEmpty()
            val filter = selectedLevelFilter
            val results = if (filter == -1) {
                val username = appContainer.tokenManager.getUsername() ?: "guest"
                val weakWords = appContainer.vocabMasteryDao.getWeakWords(username)
                val qNorm = HskJsonLoader.normalizePinyin(query.trim())
                weakWords.mapIndexed { idx, w ->
                    val label = if (w.hskLevel in 7..9) "HSK 7–9" else "HSK ${w.hskLevel}"
                    val norm = HskJsonLoader.normalizePinyin(w.pinyin)
                    DictionaryEntry(
                        levelLabel = label,
                        levelKey = w.hskLevel,
                        term = HskTerm(
                            number = idx + 1,
                            simplified = w.hanzi,
                            traditional = null,
                            pinyin = w.pinyin,
                            english = w.english,
                            partOfSpeech = "⚠️ Wrong: ${w.wrongCount}x · Mastery: ${w.masteryScore}%",
                        ),
                        normalizedPinyin = norm,
                        compactPinyin = norm.replace(" ", ""),
                    )
                }.filter { entry ->
                    if (qNorm.isBlank()) {
                        true
                    } else {
                        entry.term.simplified.contains(query.trim()) ||
                            entry.normalizedPinyin.contains(qNorm) ||
                            entry.compactPinyin.contains(qNorm.replace(" ", "")) ||
                            entry.term.english.lowercase().contains(qNorm)
                    }
                }
            } else {
                appContainer.hskJsonLoader.searchDictionary(
                    query = query,
                    levelFilter = filter,
                    limit = 120,
                )
            }

            val b = _binding ?: return@launch
            dictionaryAdapter.submitList(results)

            val levelDesc = when (filter) {
                null -> "All HSK 1–9"
                -1 -> "🎯 Weak Words (Mistakes)"
                in 1..6 -> "HSK $filter"
                else -> "HSK 7–9"
            }

            b.tvDictCount.text = if (query.isBlank()) {
                "Showing ${results.size} words ($levelDesc) · Tap any word to listen & trace"
            } else {
                "Found ${results.size} matches in $levelDesc · Tap any word to listen & trace"
            }

            if (results.isEmpty()) {
                b.tvDictEmpty.text = if (filter == -1) {
                    "No weak words recorded yet! 🎉\nAny HSK words you miss during combat will automatically appear here for focused review."
                } else {
                    "No matching HSK vocabulary found.\nTry searching by Hanzi (你好), Pinyin (ni hao / nihao), or English."
                }
                b.tvDictEmpty.visibility = View.VISIBLE
                b.rvDictionary.visibility = View.GONE
            } else {
                b.tvDictEmpty.visibility = View.GONE
                b.rvDictionary.visibility = View.VISIBLE
            }
        }
    }

    private fun showWordPracticeSheet(entry: DictionaryEntry) {
        activePracticeSheet?.dismiss()
        audioHelper.releaseAll()

        val ctx = requireContext()
        val sheet = BottomSheetDialog(ctx)
        activePracticeSheet = sheet

        val scrollRoot = ScrollView(ctx).apply {
            isFillViewport = true
            setBackgroundResource(R.drawable.bg_card_raised)
        }

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val padH = dp(18)
            val padV = dp(18)
            setPadding(padH, padV, padH, padV)
        }
        scrollRoot.addView(
            container,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        // Top Header: Large Hanzi + Traditional (if distinct), Pinyin, Badges, English
        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val hanziDisplay = buildHanziDisplay(entry)
        val tvHanzi = TextView(ctx).apply {
            text = hanziDisplay
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f)
            setTextColor(ContextCompat.getColor(ctx, R.color.hq_gold))
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val badgeText = buildString {
            append(entry.levelLabel)
            if (!entry.term.partOfSpeech.isNullOrBlank()) {
                append(" · [")
                append(entry.term.partOfSpeech)
                append("]")
            }
        }
        val tvBadge = TextView(ctx).apply {
            text = badgeText
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(ContextCompat.getColor(ctx, R.color.hq_gold))
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            typeface = Typeface.DEFAULT_BOLD
        }

        headerRow.addView(tvHanzi)
        headerRow.addView(tvBadge)
        container.addView(headerRow)

        val tvPinyin = TextView(ctx).apply {
            text = entry.term.pinyin
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(ContextCompat.getColor(ctx, R.color.hq_jade))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, 0)
        }
        container.addView(tvPinyin)

        val tvEnglish = TextView(ctx).apply {
            text = entry.term.english
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(ContextCompat.getColor(ctx, R.color.hq_text_primary))
            setPadding(0, dp(6), 0, dp(12))
        }
        container.addView(tvEnglish)

        // Audio Bar Card: TTS + Voice Recording & Playback
        val audioCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            val p = dp(12)
            setPadding(p, p, p, p)
        }

        val btnListenTts = MaterialButton(ctx).apply {
            text = "🔊 Listen (TTS): ${entry.term.simplified}"
            isAllCaps = false
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.hq_gold))
            setTextColor(ContextCompat.getColor(ctx, R.color.hq_bg_deep))
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener {
                speakMandarin(entry.term.simplified)
            }
        }
        audioCard.addView(
            btnListenTts,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val tvAudioStatus = TextView(ctx).apply {
            text = "Compare your pronunciation with native Mandarin TTS:"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(ContextCompat.getColor(ctx, R.color.hq_text_secondary))
            setPadding(0, dp(8), 0, dp(6))
        }
        audioCard.addView(tvAudioStatus)

        val recordRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val btnRecord = MaterialButton(ctx).apply {
            text = "🎤 Record Voice"
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.hq_surface_high))
            setTextColor(ContextCompat.getColor(ctx, R.color.hq_text_primary))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f).apply {
                marginEnd = dp(4)
            }
        }

        val btnStop = MaterialButton(ctx).apply {
            text = "⏹ Stop"
            isAllCaps = false
            isEnabled = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.hq_surface_high))
            setTextColor(ContextCompat.getColor(ctx, R.color.hq_text_primary))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f).apply {
                marginStart = dp(2)
                marginEnd = dp(2)
            }
        }

        val btnPlayMine = MaterialButton(ctx).apply {
            text = "▶️ Play Mine"
            isAllCaps = false
            isEnabled = recordedAudioFile?.exists() == true
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.hq_surface_high))
            setTextColor(ContextCompat.getColor(ctx, R.color.hq_jade))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f).apply {
                marginStart = dp(4)
            }
        }

        val startRecordingAction = {
            val target = File(ctx.cacheDir, "dict_voice_${System.currentTimeMillis()}.m4a")
            val started = audioHelper.startRecording(target)
            if (started) {
                btnRecord.isEnabled = false
                btnStop.isEnabled = true
                btnPlayMine.isEnabled = false
                tvAudioStatus.text = "🔴 Recording... Say \"${entry.term.simplified}\" (${entry.term.pinyin})"
                tvAudioStatus.setTextColor(ContextCompat.getColor(ctx, R.color.hq_crimson))
            } else {
                Toast.makeText(ctx, "Unable to start microphone recorder.", Toast.LENGTH_SHORT).show()
            }
        }

        btnRecord.setOnClickListener {
            if (hasAudioPermission()) {
                startRecordingAction()
            } else {
                pendingRecordingCallback = startRecordingAction
                requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        btnStop.setOnClickListener {
            val file = audioHelper.stopRecording()
            recordedAudioFile = file
            btnRecord.isEnabled = true
            btnStop.isEnabled = false
            val validFile = file != null && file.exists() && file.length() > 0L
            btnPlayMine.isEnabled = validFile
            if (validFile) {
                tvAudioStatus.text = "✓ Voice captured! Tap '▶️ Play Mine' or '🔊 Listen (TTS)' to compare."
                tvAudioStatus.setTextColor(ContextCompat.getColor(ctx, R.color.hq_jade))
            } else {
                tvAudioStatus.text = "Recording stopped."
                tvAudioStatus.setTextColor(ContextCompat.getColor(ctx, R.color.hq_text_muted))
            }
        }

        btnPlayMine.setOnClickListener {
            val file = recordedAudioFile ?: return@setOnClickListener
            btnPlayMine.isEnabled = false
            tvAudioStatus.text = "🔊 Playing back your voice recording..."
            tvAudioStatus.setTextColor(ContextCompat.getColor(ctx, R.color.hq_gold))
            val started = audioHelper.playAudio(file) {
                btnPlayMine.isEnabled = true
                tvAudioStatus.text = "✓ Playback finished."
                tvAudioStatus.setTextColor(ContextCompat.getColor(ctx, R.color.hq_jade))
            }
            if (!started) {
                btnPlayMine.isEnabled = true
                tvAudioStatus.text = "Unable to play recording."
            }
        }

        recordRow.addView(btnRecord)
        recordRow.addView(btnStop)
        recordRow.addView(btnPlayMine)
        audioCard.addView(recordRow)
        container.addView(audioCard)

        // Hanzi Trace & Writing Pad Section
        val tvTraceTitle = TextView(ctx).apply {
            text = "✍️ Hanzi Stroke Tracing Pad (${entry.term.simplified})"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(ContextCompat.getColor(ctx, R.color.hq_gold))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(14), 0, dp(4))
        }
        container.addView(tvTraceTitle)

        val canvasView = CanvasWritingView(ctx).apply {
            setBackgroundResource(R.drawable.bg_card)
            setGuideText(entry.term.simplified)
        }
        container.addView(
            canvasView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)).apply {
                topMargin = dp(4)
            },
        )

        val traceFooter = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }

        val tvStrokeCounter = TextView(ctx).apply {
            text = "Strokes drawn: 0"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(ContextCompat.getColor(ctx, R.color.hq_text_primary))
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnClearCanvas = MaterialButton(ctx).apply {
            text = "🧹 Clear Canvas"
            isAllCaps = false
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.hq_surface_high))
            setTextColor(ContextCompat.getColor(ctx, R.color.hq_gold))
            setOnClickListener {
                canvasView.clear()
            }
        }

        canvasView.onStrokeCountChanged = { count ->
            tvStrokeCounter.text = "Strokes drawn: $count"
            tvStrokeCounter.setTextColor(
                ContextCompat.getColor(
                    ctx,
                    if (count > 0) R.color.hq_jade else R.color.hq_text_primary,
                ),
            )
        }

        traceFooter.addView(tvStrokeCounter)
        traceFooter.addView(btnClearCanvas)
        container.addView(traceFooter)

        sheet.setOnDismissListener {
            audioHelper.releaseAll()
            if (activePracticeSheet === sheet) {
                activePracticeSheet = null
            }
        }

        sheet.setContentView(scrollRoot)
        sheet.show()
    }

    private fun buildHanziDisplay(entry: DictionaryEntry): String {
        val simp = entry.term.simplified
        val trad = entry.term.traditional
        return if (!trad.isNullOrBlank() && trad != simp) {
            "$simp / $trad"
        } else {
            simp
        }
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics,
        ).toInt()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        searchJob?.cancel()
        activePracticeSheet?.dismiss()
        activePracticeSheet = null
        audioHelper.releaseAll()
        tts?.stop()
        tts?.shutdown()
        tts = null
        _binding = null
    }

    /**
     * RecyclerView Adapter rendering Pleco-style HSK dictionary cards with
     * Hanzi, HSK level badge, Pinyin, part of speech, English definition,
     * quick TTS speaker button, and tap-to-practice trigger.
     */
    private inner class DictionaryAdapter :
        RecyclerView.Adapter<DictionaryAdapter.EntryViewHolder>() {

        private var items: List<DictionaryEntry> = emptyList()

        fun submitList(newItems: List<DictionaryEntry>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EntryViewHolder {
            val ctx = parent.context

            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_card_raised)
                val padH = dp(12)
                val padV = dp(12)
                setPadding(padH, padV, padH, padV)
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    bottomMargin = dp(8)
                }
            }

            // Left Column: Large Hanzi + HSK Badge
            val leftCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                minimumWidth = dp(76)
                setPadding(0, 0, dp(10), 0)
            }

            val tvHanzi = TextView(ctx).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                setTextColor(ContextCompat.getColor(ctx, R.color.hq_gold))
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            }

            val tvLevelBadge = TextView(ctx).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                setTextColor(ContextCompat.getColor(ctx, R.color.hq_text_secondary))
                setBackgroundResource(R.drawable.bg_card)
                setPadding(dp(6), dp(2), dp(6), dp(2))
                gravity = Gravity.CENTER
            }

            leftCol.addView(tvHanzi)
            leftCol.addView(
                tvLevelBadge,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = dp(4)
                },
            )

            // Middle Column: Pinyin + Part of Speech + English Definition
            val midCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val tvPinyinPos = TextView(ctx).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(ContextCompat.getColor(ctx, R.color.hq_jade))
                typeface = Typeface.DEFAULT_BOLD
            }

            val tvEnglish = TextView(ctx).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(ContextCompat.getColor(ctx, R.color.hq_text_primary))
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(3), 0, 0)
            }

            midCol.addView(tvPinyinPos)
            midCol.addView(tvEnglish)

            // Right Column: TTS Button + Practice Hint
            val rightCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(8), 0, 0, 0)
            }

            val btnSpeak = TextView(ctx).apply {
                text = "🔊"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.bg_card)
                setPadding(dp(10), dp(6), dp(10), dp(6))
            }

            val tvPracticeHint = TextView(ctx).apply {
                text = "✍️ Practice"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(ContextCompat.getColor(ctx, R.color.hq_gold))
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(4), 0, 0)
            }

            rightCol.addView(btnSpeak)
            rightCol.addView(tvPracticeHint)

            card.addView(leftCol)
            card.addView(midCol)
            card.addView(rightCol)

            return EntryViewHolder(
                root = card,
                tvHanzi = tvHanzi,
                tvLevelBadge = tvLevelBadge,
                tvPinyinPos = tvPinyinPos,
                tvEnglish = tvEnglish,
                btnSpeak = btnSpeak,
            )
        }

        override fun onBindViewHolder(holder: EntryViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class EntryViewHolder(
            private val root: View,
            private val tvHanzi: TextView,
            private val tvLevelBadge: TextView,
            private val tvPinyinPos: TextView,
            private val tvEnglish: TextView,
            private val btnSpeak: TextView,
        ) : RecyclerView.ViewHolder(root) {

            fun bind(entry: DictionaryEntry) {
                val term = entry.term
                val tradSuffix = if (!term.traditional.isNullOrBlank() && term.traditional != term.simplified) {
                    "\n/${term.traditional}"
                } else {
                    ""
                }
                tvHanzi.text = "${term.simplified}$tradSuffix"
                tvLevelBadge.text = entry.levelLabel

                val posSuffix = if (!term.partOfSpeech.isNullOrBlank()) {
                    "  [${term.partOfSpeech}]"
                } else {
                    ""
                }
                tvPinyinPos.text = "${term.pinyin}$posSuffix"
                tvEnglish.text = term.english

                btnSpeak.setOnClickListener {
                    speakMandarin(term.simplified)
                }

                root.setOnClickListener {
                    showWordPracticeSheet(entry)
                }
            }
        }
    }
}
