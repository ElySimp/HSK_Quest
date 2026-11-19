package com.faldo.hsk_quest.ui.battle

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.faldo.hsk_quest.R
import com.faldo.hsk_quest.data.model.BattleResultResponse
import com.faldo.hsk_quest.data.model.BattleState
import com.faldo.hsk_quest.data.model.PetItem
import com.faldo.hsk_quest.databinding.FragmentBattleBinding
import com.faldo.hsk_quest.databinding.SheetBattleResultBinding
import com.faldo.hsk_quest.engine.BattleQuestion
import com.faldo.hsk_quest.engine.DamageCalculator
import com.faldo.hsk_quest.engine.PetBuffCatalog
import com.faldo.hsk_quest.engine.QuestionType
import com.faldo.hsk_quest.util.Sfx
import com.faldo.hsk_quest.util.SpriteAnimator
import com.faldo.hsk_quest.util.appContainer
import com.faldo.hsk_quest.util.showHqToast
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Core battle arena fragment for multi-modal flashcard combat.
 * Handles Pipoya monster sprites and slash VFX, Mandarin TTS/STT controls,
 * quick elixir healing, timer countdowns, low HP flee prompts, and battle results.
 */
class BattleFragment : Fragment() {

    private var _binding: FragmentBattleBinding? = null
    private val binding get() = _binding!!

    private val viewModel: BattleViewModel by viewModels {
        BattleViewModel.factory(
            appContainer.hskJsonLoader,
            appContainer.battleRepository,
            appContainer.playerRepository,
            appContainer.petRepository,
            appContainer.stageRepository,
            appContainer.shopRepository,
            appContainer.vocabMasteryDao,
            appContainer.tokenManager,
            appContainer.mandarinToneEvaluator,
            appContainer.questionDeckManager,
            appContainer.engineSettingsManager,
        )
    }

    private var spriteAnimator: SpriteAnimator? = null
    private var currentMonsterSpriteKey: String? = null
    private var petSpriteAnimator: SpriteAnimator? = null
    private var petAttackResetRunnable: Runnable? = null
    private val bagPotionAnimators = mutableListOf<SpriteAnimator>()
    private var didDrinkInBagSession: Boolean = false
    private var isLoadingOverlayActive: Boolean = false
    private var loadingDismissRunnable: Runnable? = null
    private var idleBreathingAnimator: ObjectAnimator? = null
    private var lowHpDialog: AlertDialog? = null
    private var resultBottomSheet: BottomSheetDialog? = null

    private var tts: TextToSpeech? = null
    private var isTtsReady: Boolean = false
    private var pendingTtsText: String? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private val pcmAudioRecorder = Pcm16kAudioRecorder()
    private var isToneEvaluating: Boolean = false

    private var currentQuestion: BattleQuestion? = null
    private var isAnswerLocked: Boolean = false

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startSpeakingCapture()
        } else {
            showHqToast("Microphone permission declined. Switched to Listening mode.")
            viewModel.disableSpeakingForBattle()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentBattleBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val areaId = arguments?.getInt(ARG_AREA_ID, 1) ?: 1
        val monsterTypeStr = arguments?.getString(ARG_MONSTER_TYPE) ?: "normal"
        val monsterIndex = arguments?.getInt(ARG_MONSTER_INDEX, 1) ?: 1

        val stageLabel = if (monsterTypeStr.lowercase() == "boss") {
            "HSK $areaId · 👑 Gatekeeper Boss"
        } else {
            "HSK $areaId · ${monsterTypeStr.replaceFirstChar { it.uppercase() }} #$monsterIndex"
        }
        binding.tvStageLabel.text = stageLabel

        val enrageIconSize = (18 * resources.displayMetrics.density).toInt()
        ContextCompat.getDrawable(requireContext(), R.drawable.ic_vfx_enrage_flame)?.let { flame ->
            flame.setBounds(0, 0, enrageIconSize, enrageIconSize)
            binding.tvBossEnrage.setCompoundDrawablesRelative(flame, null, null, null)
            binding.tvBossEnrage.compoundDrawablePadding = enrageIconSize / 3
        }

        showBattleLoadingOverlay(stageLabel)
        initTextToSpeech()
        binding.ivMonsterSprite.setImageDrawable(null)
        setupOptionButtons()
        setupMultimodalAndElixirControls()
        setupFleeButton()
        observeViewModel(areaId, monsterTypeStr, monsterIndex)

        viewModel.startBattle(areaId, monsterTypeStr, monsterIndex)
    }

    private var isMinimumLoadingElapsed: Boolean = false
    private var hasDismissedInitialOverlay: Boolean = false

    private fun showBattleLoadingOverlay(stageLabel: String) {
        val b = _binding ?: return
        loadingDismissRunnable?.let { b.layoutBattleLoading.removeCallbacks(it) }

        isLoadingOverlayActive = true
        isMinimumLoadingElapsed = false
        hasDismissedInitialOverlay = false
        viewModel.pauseTimerForSpeech()
        applyBattleArenaBlur(enabled = true)

        b.tvLoadingStageTitle.text = "⚔️ Entering $stageLabel..."
        b.tvBattleTip.text = BATTLE_TIPS.random()
        b.layoutBattleLoading.setBackgroundColor(Color.argb(215, 14, 14, 18))
        b.layoutBattleLoading.alpha = 1f
        b.layoutBattleLoading.visibility = View.VISIBLE

        b.layoutBattleLoading.setOnClickListener {
            if (!viewModel.isSubmittingResult.value &&
                viewModel.battleState.value != null &&
                viewModel.currentQuestion.value != null
            ) {
                hasDismissedInitialOverlay = true
                dismissBattleLoadingOverlay()
            }
        }

        val runnable = Runnable {
            isMinimumLoadingElapsed = true
            maybeDismissInitialLoadingOverlay()
        }
        loadingDismissRunnable = runnable
        b.layoutBattleLoading.postDelayed(runnable, 450L)
    }

    private fun maybeDismissInitialLoadingOverlay() {
        if (hasDismissedInitialOverlay || viewModel.isSubmittingResult.value) return
        if (!isMinimumLoadingElapsed) return
        if (viewModel.battleState.value == null || viewModel.currentQuestion.value == null) return
        hasDismissedInitialOverlay = true
        dismissBattleLoadingOverlay()
    }

    private fun dismissBattleLoadingOverlay() {
        val b = _binding ?: return
        loadingDismissRunnable?.let { b.layoutBattleLoading.removeCallbacks(it) }
        loadingDismissRunnable = null
        if (!isLoadingOverlayActive && b.layoutBattleLoading.visibility == View.GONE) return

        isLoadingOverlayActive = false
        applyBattleArenaBlur(enabled = false)

        b.layoutBattleLoading.animate()
            .alpha(0f)
            .setDuration(220)
            .withEndAction {
                _binding?.layoutBattleLoading?.visibility = View.GONE
            }
            .start()

        if (!isAnswerLocked && b.layoutBattleInventoryPanel.visibility != View.VISIBLE) {
            viewModel.resumeTimerAfterSpeech()
        }
    }

    private fun applyBattleArenaBlur(enabled: Boolean) {
        val b = _binding ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val effect = if (enabled) {
                RenderEffect.createBlurEffect(12f, 12f, Shader.TileMode.CLAMP)
            } else {
                null
            }
            b.cardMonster.setRenderEffect(effect)
            b.cardPlayerStatus.setRenderEffect(effect)
            b.cardQuestion.setRenderEffect(effect)
            b.gridOptions.setRenderEffect(effect)
        }
    }

    private fun initTextToSpeech() {
        tts = TextToSpeech(requireContext()) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.SIMPLIFIED_CHINESE
                isTtsReady = true
                pendingTtsText?.let { text ->
                    pendingTtsText = null
                    speakMandarin(text)
                }
            }
        }
    }

    private fun speakMandarin(text: String) {
        if (text.isBlank()) return
        if (!isTtsReady) {
            pendingTtsText = text
            return
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "hsk_battle_tts")
    }

    private fun setupMonsterVisuals(
        areaId: Int,
        monsterTypeStr: String,
        preferredSpriteKey: String? = null,
    ) {
        val clampedArea = areaId.coerceIn(1, 9)
        val fallbackName = when (monsterTypeStr.lowercase()) {
            "boss" -> "monster_boss_$clampedArea"
            "elite" -> "monster_elite_$clampedArea"
            else -> "monster_area_$clampedArea"
        }
        val drawableName = preferredSpriteKey?.takeIf { it.isNotBlank() } ?: fallbackName
        if (drawableName == currentMonsterSpriteKey && spriteAnimator != null) return
        currentMonsterSpriteKey = drawableName

        spriteAnimator?.stop()
        spriteAnimator = null
        idleBreathingAnimator?.cancel()
        idleBreathingAnimator = null

        val pkg = requireContext().packageName
        var resId = resources.getIdentifier(drawableName, "drawable", pkg)
        var resolvedName = drawableName
        if (resId == 0 && drawableName != fallbackName) {
            resId = resources.getIdentifier(fallbackName, "drawable", pkg)
            resolvedName = fallbackName
        }

        if (resId != 0) {
            val cols = when (resolvedName) {
                "monster_elite_9" -> 7
                "monster_boss_9" -> 6
                else -> 4
            }
            spriteAnimator = SpriteAnimator.startHorizontalStripAnimation(
                imageView = binding.ivMonsterSprite,
                drawableRes = resId,
                cols = cols,
                fps = 7,
                scaleFactor = 4,
            )
        } else {
            spriteAnimator = SpriteAnimator.startAnimation(
                imageView = binding.ivMonsterSprite,
                drawableRes = R.drawable.monster_dummy,
                rows = 1,
                cols = 8,
                fps = 8,
            )
        }
    }

    private fun setupMultimodalAndElixirControls() {
        binding.btnQuickElixir.setOnClickListener {
            if (binding.layoutBattleInventoryPanel.visibility == View.VISIBLE) {
                closeBattleInventoryPanel()
            } else {
                openBattleInventoryPanel()
            }
        }

        binding.btnCloseBattleBag.setOnClickListener {
            closeBattleInventoryPanel()
        }

        binding.btnReplayAudio.setOnClickListener {
            currentQuestion?.let { speakMandarin(it.targetTerm.simplified) }
        }

        binding.btnShowPinyinHint.setOnClickListener {
            val q = currentQuestion ?: return@setOnClickListener
            val hint = q.pinyinHint ?: return@setOnClickListener
            viewModel.activatePinyinHintForCurrentQuestion()
            if (q.type.isWriting) {
                val activeSlot = viewModel.writingCharIndex.value
                val slotGuide = q.targetTerm.simplified.trim().getOrNull(activeSlot)?.toString()
                    ?: q.targetTerm.simplified
                binding.canvasBattleWriting.setGuideText(slotGuide)
                binding.tvPromptSub.text = "Trace guide active (${q.targetTerm.pinyin})"
                binding.tvPromptSub.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.hq_gold)
                )
                binding.btnShowPinyinHint.text = "💡 Trace Active (-50% DMG)"
            } else {
                binding.tvPromptSub.text = hint
                binding.tvPromptSub.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.hq_gold)
                )
                binding.btnShowPinyinHint.text = "💡 Pinyin Active (-50% DMG)"
            }
            binding.btnShowPinyinHint.isEnabled = false
        }

        binding.canvasBattleWriting.onStrokeCountChanged = { count ->
            val wordLen = currentQuestion?.targetTerm?.simplified?.trim()?.length ?: 1
            val slotIdx = viewModel.writingCharIndex.value.coerceIn(0, (wordLen - 1).coerceAtLeast(0))
            val prefix = if (wordLen > 1) "Char ${slotIdx + 1}/$wordLen · " else ""
            binding.tvWritingStatus.text = if (count > 0) {
                "🖌️ ${prefix}Strokes: $count · Tap Submit when ready"
            } else {
                "🖌️ ${prefix}Draw Hanzi strokes in the box below"
            }
            binding.btnWritingSubmit.isEnabled = count > 0 && !isAnswerLocked
        }

        binding.btnWritingUndo.setOnClickListener {
            if (isAnswerLocked) return@setOnClickListener
            binding.canvasBattleWriting.undoLastStroke()
        }

        binding.btnWritingClear.setOnClickListener {
            if (isAnswerLocked) return@setOnClickListener
            binding.canvasBattleWriting.clearCanvas()
        }

        binding.btnWritingSubmit.setOnClickListener {
            if (isAnswerLocked) return@setOnClickListener
            val strokes = binding.canvasBattleWriting.getStrokeVectors()
            if (strokes.isEmpty()) {
                binding.tvWritingStatus.text = "⚠️ Draw at least 1 stroke before submitting"
                return@setOnClickListener
            }
            isAnswerLocked = true
            binding.btnWritingSubmit.isEnabled = false
            binding.btnWritingUndo.isEnabled = false
            binding.btnWritingClear.isEnabled = false

            viewModel.submitWritingStrokes(strokes) { matched, hasMoreChars, charIdx, totalChars, recognizedHanzi, engineId, latencyMs ->
                val b = _binding ?: return@submitWritingStrokes
                if (matched && hasMoreChars) {
                    appContainer.soundPlayer.play(Sfx.CORRECT)
                    isAnswerLocked = false
                    b.canvasBattleWriting.clearCanvas()
                    if (viewModel.isPinyinHintActive.value) {
                        val nextGuide = currentQuestion?.targetTerm?.simplified?.trim()
                            ?.getOrNull(charIdx)?.toString()
                        b.canvasBattleWriting.setGuideText(nextGuide)
                    }
                    b.btnWritingUndo.isEnabled = true
                    b.btnWritingClear.isEnabled = true
                    b.btnWritingSubmit.isEnabled = false
                    b.tvWritingStatus.text = "🖌️ Char ${charIdx + 1}/$totalChars · Draw next Hanzi"
                    b.tvSpeechFeedback.text =
                        "✅ '$recognizedHanzi' matched! Now draw char ${charIdx + 1}/$totalChars ($engineId · ${latencyMs}ms)"
                } else if (matched) {
                    appContainer.soundPlayer.play(Sfx.CORRECT)
                    b.tvSpeechFeedback.text =
                        "✅ Matched $recognizedHanzi! ($engineId · ${latencyMs}ms)"
                } else {
                    appContainer.soundPlayer.play(Sfx.WRONG)
                    val targetWord = currentQuestion?.targetTerm
                    val expectedStr = targetWord?.simplified.orEmpty()
                    val detected = recognizedHanzi.ifBlank { "?" }
                    b.tvSpeechFeedback.text = if (targetWord != null) {
                        "❌ Detected '$detected' — Expected: $expectedStr (${targetWord.pinyin} · ${targetWord.english})"
                    } else {
                        "❌ Detected '$detected', expected '$expectedStr'"
                    }
                }
            }
        }

        binding.btnCantSpeak.setOnClickListener {
            stopSpeechCapture()
            viewModel.skipSpeakingFor15Minutes()
        }

        binding.btnSpeakMandarin.setOnClickListener {
            if (isAnswerLocked || isToneEvaluating) return@setOnClickListener
            val hasPermission = ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED

            if (hasPermission) {
                startSpeakingCapture()
            } else {
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun openBattleInventoryPanel() {
        didDrinkInBagSession = false
        stopSpeechCapture()
        viewModel.pauseTimerForSpeech()
        binding.layoutBattleInventoryPanel.visibility = View.VISIBLE
        renderBattleInventoryItems(viewModel.ownedPotions.value)
    }

    private fun closeBattleInventoryPanel() {
        bagPotionAnimators.forEach { it.stop() }
        bagPotionAnimators.clear()
        if (_binding?.layoutBattleInventoryPanel?.visibility == View.VISIBLE) {
            binding.layoutBattleInventoryPanel.visibility = View.GONE
            if (!isAnswerLocked && !isLoadingOverlayActive) {
                viewModel.resumeTimerAfterSpeech()
            }
        }
    }

    private fun renderBattleInventoryItems(potions: List<OwnedPotion>) {
        val b = _binding ?: return
        bagPotionAnimators.forEach { it.stop() }
        bagPotionAnimators.clear()
        b.llBattleBagItems.removeAllViews()

        val density = resources.displayMetrics.density
        fun Int.dp(): Int = (this * density).toInt()

        if (potions.isEmpty()) {
            val emptyCard = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.bg_card_raised)
                setPadding(16.dp(), 18.dp(), 16.dp(), 18.dp())
            }
            val emptyText = TextView(requireContext()).apply {
                text = "Your Battle Satchel has no Healing Potions!\nVisit the Crimson Cauldron Apothecary in the Merchant Shop to buy % Max HP potions."
                gravity = Gravity.CENTER
                textSize = 12f
                setTextColor(ContextCompat.getColor(requireContext(), R.color.hq_text_muted))
            }
            emptyCard.addView(emptyText)
            b.llBattleBagItems.addView(emptyCard)
            return
        }

        potions.forEach { potion ->
            val card = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_card_raised)
                setPadding(10.dp(), 8.dp(), 10.dp(), 8.dp())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    bottomMargin = 8.dp()
                }
            }

            val (drawableRes, cols) = when (potion.itemId) {
                "minor_healing_potion", "salve_elixir" -> R.drawable.potion_minor_red to 9
                "healing_potion", "vitality_elixir" -> R.drawable.potion_regular_red to 7
                "greater_healing_potion" -> R.drawable.potion_greater_red to 8
                "supreme_healing_potion" -> R.drawable.potion_supreme_red to 13
                else -> R.drawable.potion_minor_red to 9
            }

            val iconView = ImageView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(40.dp(), 40.dp())
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = potion.name
            }
            val anim = SpriteAnimator.startHorizontalStripAnimation(
                imageView = iconView,
                drawableRes = drawableRes,
                cols = cols,
                fps = 8,
                scaleFactor = 4,
            )
            bagPotionAnimators.add(anim)

            val infoCol = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = 10.dp()
                    marginEnd = 8.dp()
                }
            }

            val cleanTitle = potion.name.substringBefore(" (")
            val tvName = TextView(requireContext()).apply {
                text = cleanTitle
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(ContextCompat.getColor(requireContext(), R.color.hq_text_primary))
            }

            val tvMeta = TextView(requireContext()).apply {
                text = "+${potion.healPct}% Max HP · Owned: ${potion.quantity}x"
                textSize = 11f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(ContextCompat.getColor(requireContext(), R.color.hq_jade))
            }

            infoCol.addView(tvName)
            infoCol.addView(tvMeta)

            val drinkBtn = MaterialButton(requireContext()).apply {
                text = "Drink"
                textSize = 11f
                minHeight = 34.dp()
                minimumHeight = 34.dp()
                setPadding(12.dp(), 0, 12.dp(), 0)
                setTextColor(ContextCompat.getColor(requireContext(), R.color.hq_bg_deep))
                setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.hq_jade))
                setOnClickListener {
                    didDrinkInBagSession = true
                    appContainer.soundPlayer.play(Sfx.POTION)
                    viewModel.usePotionById(potion.itemId)
                }
            }

            card.addView(iconView)
            card.addView(infoCol)
            card.addView(drinkBtn)
            b.llBattleBagItems.addView(card)
        }
    }

    private fun startSpeakingCapture() {
        if (pcmAudioRecorder.isRecording) {
            pcmAudioRecorder.stop()
            return
        }
        if (viewModel.shouldUseToneNetForCurrentQuestion()) {
            startToneNetRecording()
        } else {
            startListeningWithSpeechRecognizer()
        }
    }

    private fun startToneNetRecording() {
        val question = currentQuestion ?: return
        stopSpeechCapture()
        viewModel.pauseTimerForSpeech()

        binding.btnSpeakMandarin.text = "⏹️ Tap to Stop (Listening...)"
        binding.btnSpeakMandarin.isEnabled = true
        binding.tvSpeechFeedback.visibility = View.VISIBLE
        binding.tvSpeechFeedback.setTextColor(ContextCompat.getColor(requireContext(), R.color.hq_gold))
        binding.tvSpeechFeedback.text =
            "🎙️ Speak '${question.targetTerm.simplified}' clearly now (ToneNet listening)..."

        pcmAudioRecorder.start(object : Pcm16kAudioRecorder.Listener {
            override fun onRecordingStarted() {
                val b = _binding ?: return
                b.btnSpeakMandarin.text = "⏹️ Tap to Stop (Listening...)"
            }

            override fun onAmplitude(rmsNormalized: Float) {
                // Keep UI steady while recording without layout jitter
            }

            override fun onRecordingCompleted(samples: FloatArray) {
                val b = _binding ?: return
                isToneEvaluating = true
                b.btnSpeakMandarin.text = "⏳ Analyzing tones..."
                b.btnSpeakMandarin.isEnabled = false

                viewModel.submitToneAudio(
                    wav16k = samples,
                    onNoSpeechDetected = {
                        isToneEvaluating = false
                        val innerBinding = _binding ?: return@submitToneAudio
                        innerBinding.btnSpeakMandarin.text = "🎙️ Tap to Speak Mandarin"
                        innerBinding.btnSpeakMandarin.isEnabled = true
                        innerBinding.tvSpeechFeedback.visibility = View.VISIBLE
                        innerBinding.tvSpeechFeedback.setTextColor(
                            ContextCompat.getColor(requireContext(), R.color.hq_gold)
                        )
                        innerBinding.tvSpeechFeedback.text =
                            "⚠️ Mic didn't catch clear speech. Tap to try again or 'Can't Speak Now' to skip."
                    },
                    onFallbackRequired = {
                        isToneEvaluating = false
                        if (_binding != null && !isAnswerLocked) {
                            startListeningWithSpeechRecognizer()
                        }
                    },
                    onEvaluated = { eval ->
                        isToneEvaluating = false
                        isAnswerLocked = true
                        val innerBinding = _binding ?: return@submitToneAudio
                        innerBinding.btnSpeakMandarin.text = "🎙️ Tap to Speak Mandarin"
                        innerBinding.btnSpeakMandarin.isEnabled = false
                        innerBinding.tvSpeechFeedback.visibility = View.VISIBLE
                        innerBinding.tvSpeechFeedback.text = eval.feedback
                    },
                )
            }

            override fun onRecordingError(message: String) {
                isToneEvaluating = false
                if (_binding != null && !isAnswerLocked) {
                    startListeningWithSpeechRecognizer()
                }
            }
        })
    }

    private fun startListeningWithSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(requireContext())) {
            showHqToast("Speech recognition unavailable on this device. Switched to Listening mode.")
            viewModel.disableSpeakingForBattle()
            return
        }

        stopSpeechRecognizer()
        viewModel.pauseTimerForSpeech()

        binding.btnSpeakMandarin.text = "🔴 Listening..."
        binding.btnSpeakMandarin.isEnabled = false
        binding.tvSpeechFeedback.visibility = View.VISIBLE
        binding.tvSpeechFeedback.text = "🎙️ Speak Mandarin clearly now..."

        val recognizer = SpeechRecognizer.createSpeechRecognizer(requireContext())
        speechRecognizer = recognizer

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                _binding?.btnSpeakMandarin?.text = "⏳ Checking..."
            }

            override fun onError(error: Int) {
                val b = _binding ?: return
                b.btnSpeakMandarin.text = "🎙️ Tap to Speak Mandarin"
                b.btnSpeakMandarin.isEnabled = true
                b.tvSpeechFeedback.text =
                    "⚠️ Mic didn't catch that. Tap 'Tap to Speak Mandarin' to retry or 'Can't Speak Now' to skip."
                viewModel.resumeTimerAfterSpeech()
            }

            override fun onResults(results: Bundle?) {
                val b = _binding ?: return
                b.btnSpeakMandarin.text = "🎙️ Tap to Speak Mandarin"
                b.btnSpeakMandarin.isEnabled = true

                val matches = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    .orEmpty()
                val eval = viewModel.submitSpeechResult(matches) ?: return
                isAnswerLocked = true
                b.tvSpeechFeedback.text = eval.feedback
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        }
        runCatching {
            recognizer.startListening(intent)
        }.onFailure {
            binding.btnSpeakMandarin.text = "🎙️ Tap to Speak Mandarin"
            binding.btnSpeakMandarin.isEnabled = true
            viewModel.resumeTimerAfterSpeech()
        }
    }

    private fun stopSpeechCapture() {
        isToneEvaluating = false
        pcmAudioRecorder.cancel()
        stopSpeechRecognizer()
    }

    private fun stopSpeechRecognizer() {
        runCatching {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        }
        speechRecognizer = null
    }

    private fun setupOptionButtons() {
        val optionButtons = listOf(
            binding.btnOption0,
            binding.btnOption1,
            binding.btnOption2,
            binding.btnOption3,
        )

        optionButtons.forEachIndexed { index, textView ->
            textView.setOnClickListener {
                handleOptionSelection(index, textView)
            }
        }
    }

    private fun handleOptionSelection(selectedIndex: Int, selectedView: TextView) {
        if (isAnswerLocked) return
        val q = currentQuestion ?: return
        if (q.type.isSpeaking || q.type.isWriting) return
        isAnswerLocked = true
        stopSpeechRecognizer()
        viewModel.submitAnswer(selectedIndex)
    }

    private var fleeDialog: androidx.appcompat.app.AlertDialog? = null

    private fun showFleeConfirmDialog() {
        val ctx = context ?: return
        if (fleeDialog?.isShowing == true) return
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle("Flee Battle?")
            .setMessage("Are you sure you want to retreat? You will forfeit rewards but protect your coins and pet affection.")
            .setPositiveButton("Flee") { _, _ ->
                stopSpeechRecognizer()
                viewModel.flee()
            }
            .setNegativeButton("Keep Fighting", null)
            .create()
        dialog.setOnDismissListener {
            if (fleeDialog === dialog) {
                fleeDialog = null
            }
        }
        fleeDialog = dialog
        dialog.show()
    }

    private fun setupFleeButton() {
        binding.btnFlee.setOnClickListener {
            showFleeConfirmDialog()
        }

        // Ensure dialog is dismissed cleanly if the view is destroyed while open
        viewLifecycleOwner.lifecycle.addObserver(
            androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_DESTROY) {
                    fleeDialog?.dismiss()
                    fleeDialog = null
                }
            },
        )

        // Intercept system Back press so retreating submits damage/HP instead of resetting HP
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val b = _binding ?: return
                    if (b.layoutBattleInventoryPanel.visibility == View.VISIBLE) {
                        bagPotionAnimators.forEach { it.stop() }
                        bagPotionAnimators.clear()
                        b.layoutBattleInventoryPanel.visibility = View.GONE
                        if (!isAnswerLocked && !isLoadingOverlayActive) {
                            viewModel.resumeTimerAfterSpeech()
                        }
                        return
                    }
                    if (isLoadingOverlayActive || isShowingSubmissionOverlay || resultBottomSheet?.isShowing == true) {
                        return
                    }
                    showFleeConfirmDialog()
                }
            },
        )
    }

    private var isShowingSubmissionOverlay: Boolean = false

    private fun observeViewModel(areaId: Int, monsterTypeStr: String, monsterIndex: Int) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.battleState.collect { state ->
                        state?.let {
                            renderBattleState(it)
                            maybeDismissInitialLoadingOverlay()
                        }
                    }
                }

                launch {
                    viewModel.currentQuestion.collect { question ->
                        question?.let {
                            resetUiBeforeQuestion(it)
                            renderQuestion(it)
                            maybeDismissInitialLoadingOverlay()
                        }
                    }
                }

                launch {
                    viewModel.timerProgressMs.collect { remainingMs ->
                        renderTimer(remainingMs)
                    }
                }

                launch {
                    viewModel.nextQuestionDelay.collect { delayState ->
                        renderNextQuestionDelay(delayState)
                    }
                }

                launch {
                    viewModel.answerFeedback.collect { event ->
                        renderAnswerFeedback(event)
                    }
                }

                launch {
                    viewModel.activePet.collect { pet ->
                        renderActivePet(pet)
                    }
                }

                launch {
                    viewModel.elixirCount.collect { count ->
                        binding.btnQuickElixir.text = "🎒 Bag ($count)"
                        binding.btnQuickElixir.alpha = if (count > 0) 1.0f else 0.75f
                    }
                }

                launch {
                    viewModel.ownedPotions.collect { potions ->
                        if (binding.layoutBattleInventoryPanel.visibility == View.VISIBLE) {
                            renderBattleInventoryItems(potions)
                            val state = viewModel.battleState.value
                            if (didDrinkInBagSession && (potions.isEmpty() || (state != null && state.playerHp >= state.playerMaxHp))) {
                                closeBattleInventoryPanel()
                            }
                        }
                    }
                }

                launch {
                    viewModel.battleToast.collect { msg ->
                        showHqToast(msg)
                    }
                }

                launch {
                    viewModel.isSubmittingResult.collect { submitting ->
                        renderSubmittingOverlay(submitting)
                    }
                }

                launch {
                    viewModel.serverResult.collect { result ->
                        renderSubmittingOverlay(false)
                        showResultBottomSheet(result, areaId, monsterTypeStr, monsterIndex)
                    }
                }
            }
        }
    }

    private fun resetUiBeforeQuestion(question: BattleQuestion) {
        val b = _binding ?: return
        stopSpeechCapture()
        isAnswerLocked = false
        b.progressTimer.visibility = View.VISIBLE
        b.progressTimer.setIndicatorColor(ContextCompat.getColor(requireContext(), R.color.hq_gold))
        b.tvSpeechFeedback.setTextColor(ContextCompat.getColor(requireContext(), R.color.hq_gold))
        b.tvSpeechFeedback.visibility = View.GONE
        b.btnCantSpeak.isEnabled = true
        b.btnWritingSubmit.isEnabled = true

        val optionButtons = listOf(b.btnOption0, b.btnOption1, b.btnOption2, b.btnOption3)
        optionButtons.forEach { btn ->
            btn.maxLines = 2
            btn.isEnabled = true
            btn.alpha = 1f
        }
    }

    private fun renderAnswerFeedback(event: AnswerFeedbackEvent) {
        val b = _binding ?: return
        isAnswerLocked = true
        b.btnSpeakMandarin.isEnabled = false
        b.btnCantSpeak.isEnabled = false
        b.btnWritingSubmit.isEnabled = false
        b.btnShowPinyinHint.isEnabled = false

        appContainer.soundPlayer.play(if (event.isCorrect) Sfx.CORRECT else Sfx.WRONG)

        val q = event.question
        val jade = ContextCompat.getColor(requireContext(), R.color.hq_jade)
        val crimson = ContextCompat.getColor(requireContext(), R.color.hq_crimson)
        val primary = ContextCompat.getColor(requireContext(), R.color.hq_text_primary)
        val muted = ContextCompat.getColor(requireContext(), R.color.hq_text_muted)

        // Reveal the answer cue under the prompt in muted gray (matching Show Pinyin slot)
        val promptReveal = formatPromptAnswerReveal(q)
        if (!promptReveal.isNullOrBlank()) {
            b.tvPromptSub.text = promptReveal
            b.tvPromptSub.setTextColor(muted)
        }

        if (!q.type.isSpeaking && !q.type.isWriting) {
            val optionButtons = listOf(b.btnOption0, b.btnOption1, b.btnOption2, b.btnOption3)
            optionButtons.forEachIndexed { idx, btn ->
                btn.isEnabled = false
                btn.maxLines = 2
                btn.alpha = 1f
                btn.text = formatOptionRevealText(q, idx)
                when {
                    idx == q.correctIndex -> {
                        btn.setBackgroundResource(R.drawable.bg_option_correct)
                        btn.setTextColor(jade)
                    }
                    !event.isCorrect && idx == event.selectedIndex -> {
                        btn.setBackgroundResource(R.drawable.bg_option_wrong)
                        btn.setTextColor(crimson)
                    }
                    else -> {
                        btn.setTextColor(primary)
                    }
                }
            }
            b.tvSpeechFeedback.visibility = View.GONE
        } else {
            val explanation = buildAnswerExplanation(event)
            b.tvSpeechFeedback.visibility = View.VISIBLE
            b.tvSpeechFeedback.text = explanation
            b.tvSpeechFeedback.setTextColor(if (event.isCorrect) jade else crimson)
        }
    }

    private fun formatPromptAnswerReveal(question: BattleQuestion): String? {
        val target = question.targetTerm
        return when (question.type) {
            QuestionType.HANZI_TO_PINYIN,
            QuestionType.HANZI_TO_ENGLISH,
            QuestionType.SPEAKING_CHALLENGE -> "Pinyin: ${target.pinyin}"
            QuestionType.CLOZE_SENTENCE ->
                question.pinyinHint?.replace("[ ___ ]", target.pinyin) ?: "Pinyin: ${target.pinyin}"
            QuestionType.ENGLISH_TO_HANZI,
            QuestionType.WRITING_CHALLENGE,
            QuestionType.LISTENING_TO_ENGLISH,
            QuestionType.LISTENING_TO_HANZI -> "${target.simplified} • ${target.pinyin}"
        }
    }

    private fun formatOptionRevealText(question: BattleQuestion, index: Int): String {
        val raw = question.options.getOrNull(index).orEmpty()
        val term = question.optionTerms.getOrNull(index) ?: return raw
        return when (question.type) {
            QuestionType.ENGLISH_TO_HANZI,
            QuestionType.LISTENING_TO_HANZI,
            QuestionType.CLOZE_SENTENCE,
            QuestionType.HANZI_TO_PINYIN -> "${term.simplified} • ${term.pinyin}"
            else -> raw
        }
    }

    private fun buildAnswerExplanation(event: AnswerFeedbackEvent): String {
        event.customFeedback?.let { custom ->
            if (custom.isNotBlank()) return custom
        }
        val target = event.question.targetTerm
        val targetSummary = "${target.simplified} (${target.pinyin}) — ${conciseGloss(target.english, maxLen = 48)}"
        return when {
            event.isCorrect -> "✓ $targetSummary"
            event.isTimeout -> "⏰ Time's up! Answer: $targetSummary"
            else -> {
                val chosen = event.question.optionTerms.getOrNull(event.selectedIndex)
                if (chosen != null && chosen.simplified != target.simplified) {
                    "✕ ${chosen.simplified} (${chosen.pinyin}) = ${conciseGloss(chosen.english, maxLen = 28)} · ✓ $targetSummary"
                } else {
                    "✕ Correct: $targetSummary"
                }
            }
        }
    }

    private fun conciseGloss(english: String, maxLen: Int = 34): String {
        val primary = english.split(";", "/").firstOrNull()?.trim().orEmpty().ifBlank { english.trim() }
        return if (primary.length > maxLen) "${primary.take(maxLen - 1)}…" else primary
    }

    private fun renderNextQuestionDelay(delayState: NextQuestionDelayState?) {
        val b = _binding ?: return
        if (delayState == null || delayState.totalMs <= 0L) {
            return
        }
        val color = ContextCompat.getColor(
            requireContext(),
            if (delayState.isCorrect) R.color.hq_jade else R.color.hq_crimson,
        )
        b.progressTimer.visibility = View.VISIBLE
        b.progressTimer.setIndicatorColor(color)
        b.progressTimer.progress =
            ((delayState.remainingMs.toFloat() / delayState.totalMs) * 1000f).toInt().coerceIn(0, 1000)
    }

    private fun renderSubmittingOverlay(submitting: Boolean) {
        val b = _binding ?: return
        if (submitting) {
            isShowingSubmissionOverlay = true
            isLoadingOverlayActive = true
            loadingDismissRunnable?.let { b.layoutBattleLoading.removeCallbacks(it) }
            val phase = viewModel.battleState.value?.phase
            b.tvLoadingStageTitle.text = when (phase) {
                BattleState.Phase.FLED -> "🏃 Retreating Safely..."
                BattleState.Phase.VICTORY -> "🏆 Recording Victory..."
                BattleState.Phase.DEFEAT -> "💀 Recording Battle Result..."
                else -> "⚔️ Syncing Battle Result..."
            }
            b.tvBattleTip.text = "Syncing XP, Coins & Vocab Mastery..."
            b.layoutBattleLoading.setBackgroundColor(Color.argb(215, 14, 14, 18))
            b.layoutBattleLoading.alpha = 1f
            b.layoutBattleLoading.visibility = View.VISIBLE
            b.layoutBattleLoading.setOnClickListener { }
            applyBattleArenaBlur(enabled = true)
        } else if (isShowingSubmissionOverlay) {
            isShowingSubmissionOverlay = false
            isLoadingOverlayActive = false
            b.layoutBattleLoading.visibility = View.GONE
            applyBattleArenaBlur(enabled = false)
        }
    }

    private fun renderActivePet(pet: PetItem?) {
        val b = _binding ?: return
        petAttackResetRunnable?.let { b.ivBattlePetSprite.removeCallbacks(it) }
        petAttackResetRunnable = null
        petSpriteAnimator?.stop()
        petSpriteAnimator = null

        b.layoutBattlePetBox.visibility = View.VISIBLE
        if (pet == null) {
            val fallbackDrawable = SpriteAnimator.resolvePetDrawable(
                requireContext(),
                "wood_rat",
                "pet_wood_rat",
            )
            petSpriteAnimator = SpriteAnimator.startAnimation(
                imageView = b.ivBattlePetSprite,
                drawableRes = fallbackDrawable,
                rows = 3,
                cols = 4,
                targetRow = 0,
                frameCount = 4,
                fps = 6,
            )
            b.tvBattlePetName.text = "Spirit Beast"
            b.tvBattlePetBuff.text = "🐾 Ready"
            b.ivPetShieldVfx.alpha = 0f
            return
        }

        val petDrawable = SpriteAnimator.resolvePetDrawable(
            requireContext(),
            pet.petType,
            pet.spriteKey,
        )
        petSpriteAnimator = SpriteAnimator.startAnimation(
            imageView = b.ivBattlePetSprite,
            drawableRes = petDrawable,
            rows = 3,
            cols = 4,
            targetRow = if (pet.isSleeping) 2 else 0,
            frameCount = 4,
            fps = if (pet.isSleeping) 4 else 6,
        )

        b.tvBattlePetName.text = pet.name.substringBefore(" (").ifBlank { pet.name }
        val spec = PetBuffCatalog.resolveSpec(pet)
        b.tvBattlePetBuff.text = if (pet.isSleeping) {
            "💤 Asleep"
        } else {
            "✦ ${spec.shortBadge}"
        }

        val shieldsLeft = viewModel.battleState.value?.shieldsRemaining ?: spec.shieldCharges
        val shieldReady = !pet.isSleeping && spec.shieldCharges > 0 && shieldsLeft > 0
        b.ivPetShieldVfx.alpha = if (shieldReady) 0.28f else 0f
    }

    private fun renderBattleState(state: BattleState) {
        // Monster sprite, HP & Boss Enrage indicator
        setupMonsterVisuals(
            areaId = state.monster.areaId,
            monsterTypeStr = state.monster.type.apiName,
            preferredSpriteKey = state.monster.spriteKey,
        )
        binding.tvMonsterName.text = state.monster.name
        binding.tvMonsterHpNumeric.text = "${DamageCalculator.formatDecimal(state.monsterHp)}/${state.monster.maxHp}"
        binding.progressMonsterHp.progress = state.monsterHpPercent
        binding.tvBossEnrage.visibility = if (state.isBossEnraged) View.VISIBLE else View.GONE
        binding.tvMonsterRoleTag.text = when (state.monster.type.apiName.lowercase()) {
            "boss" -> "👑 Gatekeeper Boss"
            "elite" -> "⚔️ Elite Guardian"
            else -> "👹 Realm Fiend"
        }

        // Update ambient shield aura when shield is active and not mid-burst
        if (!state.wasShieldBlocked) {
            binding.ivPetShieldVfx.alpha = if (state.shieldAvailable) 0.28f else 0f
        }

        // Player HP & Buff label
        binding.tvPlayerHpNumeric.text = "❤️ ${DamageCalculator.formatDecimal(state.playerHp)}/${state.playerMaxHp} (${state.playerHpPercent}%)"
        binding.progressPlayerHp.progress = state.playerHpPercent

        val hpColorRes = when {
            state.playerHpPercent > 50 -> R.color.hq_jade
            state.playerHpPercent > 25 -> R.color.hq_gold
            else -> R.color.hq_crimson
        }
        val hpColor = ContextCompat.getColor(requireContext(), hpColorRes)
        binding.progressPlayerHp.setIndicatorColor(hpColor)
        binding.tvPlayerHpNumeric.setTextColor(hpColor)

        binding.btnFlee.isEnabled = !state.isFinished
        binding.btnQuickElixir.isEnabled = !state.isFinished
        if (state.isFinished) {
            closeBattleInventoryPanel()
        }

        if (!state.activeBuffLabel.isNullOrBlank()) {
            binding.tvPlayerLabel.text = state.activeBuffLabel
        } else {
            binding.tvPlayerLabel.text = "🛡️ Spirit Bond Vitality"
        }

        if (didDrinkInBagSession &&
            binding.layoutBattleInventoryPanel.visibility == View.VISIBLE &&
            state.playerHp >= state.playerMaxHp.toDouble()
        ) {
            closeBattleInventoryPanel()
        }

        // Warning banner for Underleveled Gatekeeper Boss
        if (state.isGatekeeperPenaltyActive) {
            binding.tvStageLabel.setTextColor(ContextCompat.getColor(requireContext(), R.color.hq_crimson))
        }

        // Combo indicator
        if (state.combo > 1) {
            binding.tvCombo.visibility = View.VISIBLE
            binding.tvCombo.text = "${state.combo} COMBO"
        } else {
            binding.tvCombo.visibility = View.INVISIBLE
        }

        val currentPet = viewModel.activePet.value
        val currentPetSpec = currentPet?.let { PetBuffCatalog.resolveSpec(it) }
        val hasShieldAura = currentPet != null &&
            !currentPet.isSleeping &&
            (currentPetSpec?.shieldCharges ?: 0) > 0 &&
            state.shieldsRemaining > 0
        binding.ivPetShieldVfx.alpha = if (hasShieldAura) 0.28f else 0f

        // Pokémon-style Duel Turn Animations: Pet Lunge vs Monster Counter
        if (state.lastDamageDealt > 0.0) {
            appContainer.soundPlayer.play(if (state.wasCritical) Sfx.CRIT else Sfx.HIT)
            triggerPetAttackLunge(state.wasCritical)
            triggerDamagePopup(state.lastDamageDealt, state.wasCritical)
            triggerMonsterHitAndSlash(state.wasCritical)
        }
        if (state.wasShieldBlocked) {
            appContainer.soundPlayer.play(Sfx.CORRECT)
            triggerMonsterCounterAndPetShieldBlock()
        } else if (state.lastDamageTaken > 0.0) {
            triggerMonsterCounterAndPetHit(state.lastDamageTaken)
            triggerPlayerDamageShake()
        }
        if (state.lastRebirthTriggered) {
            binding.tvPetDamagePopup.animate().cancel()
            binding.tvPetDamagePopup.text = "🔥 PHOENIX REVIVE!"
            binding.tvPetDamagePopup.setTextColor(ContextCompat.getColor(requireContext(), R.color.hq_gold))
            binding.tvPetDamagePopup.alpha = 1f
            binding.tvPetDamagePopup.translationY = 0f
            binding.tvPetDamagePopup.animate().alpha(0f).translationY(-32f).setDuration(1100L).start()
        } else if (state.lastPetHeal > 0.0 && state.lastDamageTaken == 0.0 && !state.wasShieldBlocked) {
            binding.tvPetDamagePopup.animate().cancel()
            binding.tvPetDamagePopup.text = "+${DamageCalculator.formatDecimal(state.lastPetHeal)} HP 💚"
            binding.tvPetDamagePopup.setTextColor(ContextCompat.getColor(requireContext(), R.color.hq_jade))
            binding.tvPetDamagePopup.alpha = 1f
            binding.tvPetDamagePopup.translationY = 0f
            binding.tvPetDamagePopup.animate().alpha(0f).translationY(-26f).setDuration(900L).start()
        }

        // Low HP Warning Dialog (< 20% HP)
        if (state.isLowHpWarningActive && (lowHpDialog == null || !lowHpDialog!!.isShowing)) {
            showLowHpWarningDialog()
        }
    }

    private fun renderQuestion(question: BattleQuestion) {
        stopSpeechRecognizer()
        currentQuestion = question
        isAnswerLocked = false
        if (isLoadingOverlayActive || binding.layoutBattleInventoryPanel.visibility == View.VISIBLE) {
            viewModel.pauseTimerForSpeech()
        }

        binding.tvQuestionType.text = question.type.displayLabel.uppercase()
        binding.tvPromptPrimary.text = question.prompt
        binding.tvPromptSub.text = question.subPrompt.orEmpty()
        binding.tvPromptSub.setTextColor(
            ContextCompat.getColor(requireContext(), R.color.hq_text_muted)
        )

        // Reset per-question Pinyin/Trace hint button (-50% DMG for 1 question)
        if (!question.pinyinHint.isNullOrBlank()) {
            binding.btnShowPinyinHint.visibility = View.VISIBLE
            binding.btnShowPinyinHint.text = if (question.type.isWriting) {
                "💡 Show Trace (-50% DMG)"
            } else {
                "💡 Show Pinyin (-50% DMG)"
            }
            binding.btnShowPinyinHint.isEnabled = true
        } else {
            binding.btnShowPinyinHint.visibility = View.GONE
        }

        binding.btnReplayAudio.visibility =
            if (question.type.isListening) View.VISIBLE else View.GONE
        binding.tvSpeechFeedback.visibility =
            if (question.type.isSpeaking || question.type.isWriting) View.VISIBLE else View.GONE

        val optionViews = listOf(
            binding.btnOption0,
            binding.btnOption1,
            binding.btnOption2,
            binding.btnOption3,
        )

        if (question.type.isSpeaking) {
            optionViews.forEach { it.visibility = View.GONE }
            binding.layoutWritingActions.visibility = View.GONE
            binding.canvasBattleWriting.setGuideText(null)
            binding.canvasBattleWriting.clearCanvas()
            binding.layoutSpeakingActions.visibility = View.VISIBLE
            binding.btnSpeakMandarin.text = "🎙️ Tap to Speak Mandarin"
            binding.btnSpeakMandarin.isEnabled = true
            binding.tvSpeechFeedback.visibility = View.VISIBLE
            binding.tvSpeechFeedback.text = if (viewModel.shouldUseToneNetForCurrentQuestion()) {
                "🎙️ Tap 'Tap to Speak Mandarin' and pronounce ${question.targetTerm.simplified} with clear tones"
            } else {
                "🎙️ Tap 'Tap to Speak Mandarin' below and pronounce ${question.targetTerm.simplified} aloud"
            }
        } else if (question.type.isWriting) {
            optionViews.forEach { it.visibility = View.GONE }
            binding.layoutSpeakingActions.visibility = View.GONE
            binding.layoutWritingActions.visibility = View.VISIBLE
            binding.canvasBattleWriting.setGuideText(null)
            binding.canvasBattleWriting.clearCanvas()
            val totalChars = question.targetTerm.simplified.trim().length.coerceAtLeast(1)
            val prefix = if (totalChars > 1) "Char 1/$totalChars · " else ""
            binding.tvWritingStatus.text = "🖌️ ${prefix}Draw Hanzi strokes in the box below"
            binding.btnWritingUndo.isEnabled = true
            binding.btnWritingClear.isEnabled = true
            binding.btnWritingSubmit.isEnabled = false
            binding.tvSpeechFeedback.text = if (totalChars > 1) {
                "✍️ Word has $totalChars characters — draw Char 1/$totalChars cleanly, then tap Submit"
            } else {
                "✍️ Draw the character cleanly, then tap Submit"
            }
        } else {
            binding.layoutSpeakingActions.visibility = View.GONE
            binding.layoutWritingActions.visibility = View.GONE
            binding.canvasBattleWriting.setGuideText(null)
            binding.canvasBattleWriting.clearCanvas()
            if (question.type.isListening) {
                speakMandarin(question.targetTerm.simplified)
            }
            optionViews.forEachIndexed { i, view ->
                view.visibility = View.VISIBLE
                view.setBackgroundResource(R.drawable.bg_option_idle)
                view.setTextColor(ContextCompat.getColor(requireContext(), R.color.hq_text_primary))
                view.text = question.options.getOrElse(i) { "" }
                view.alpha = 1f
                view.isEnabled = true
            }
        }
    }

    private fun renderTimer(remainingMs: Long) {
        if (viewModel.nextQuestionDelay.value != null) return
        val limitMs = viewModel.currentQuestionLimitMs.value.coerceAtLeast(1L)
        val progress = ((remainingMs * 1000L) / limitMs).toInt().coerceIn(0, 1000)
        binding.progressTimer.progress = progress
        binding.progressTimer.setIndicatorColor(
            ContextCompat.getColor(requireContext(), R.color.hq_gold)
        )
    }

    private fun triggerPetAttackLunge(isCrit: Boolean) {
        val b = _binding ?: return
        val pet = viewModel.activePet.value

        // Switch awake pet to Row 1 (Attack / Happy pose) during the lunge
        if (pet == null || !pet.isSleeping) {
            val petDrawable = SpriteAnimator.resolvePetDrawable(
                requireContext(),
                pet?.petType ?: "wood_rat",
                pet?.spriteKey ?: "pet_wood_rat",
            )
            petAttackResetRunnable?.let { b.ivBattlePetSprite.removeCallbacks(it) }
            petSpriteAnimator?.stop()
            petSpriteAnimator = SpriteAnimator.startAnimation(
                imageView = b.ivBattlePetSprite,
                drawableRes = petDrawable,
                rows = 3,
                cols = 4,
                targetRow = 1,
                frameCount = 4,
                fps = 10,
            )

            val resetRun = Runnable {
                val curBinding = _binding ?: return@Runnable
                val curPet = viewModel.activePet.value
                petSpriteAnimator?.stop()
                petSpriteAnimator = SpriteAnimator.startAnimation(
                    imageView = curBinding.ivBattlePetSprite,
                    drawableRes = SpriteAnimator.resolvePetDrawable(
                        curBinding.root.context,
                        curPet?.petType ?: "wood_rat",
                        curPet?.spriteKey ?: "pet_wood_rat",
                    ),
                    rows = 3,
                    cols = 4,
                    targetRow = if (curPet?.isSleeping == true) 2 else 0,
                    frameCount = 4,
                    fps = if (curPet?.isSleeping == true) 4 else 6,
                )
            }
            petAttackResetRunnable = resetRun
            b.ivBattlePetSprite.postDelayed(resetRun, 550L)
        }

        // Lunge pet right across the arena toward the monster
        val lungeDistance = if (isCrit) 46f else 36f
        val pvhX = PropertyValuesHolder.ofFloat(View.TRANSLATION_X, 0f, lungeDistance, -6f, 0f)
        val pvhY = PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0f, -12f, 0f)
        val pvhScaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, if (isCrit) 1.2f else 1.12f, 1f)
        val pvhScaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, if (isCrit) 1.2f else 1.12f, 1f)
        ObjectAnimator.ofPropertyValuesHolder(
            b.ivBattlePetSprite,
            pvhX,
            pvhY,
            pvhScaleX,
            pvhScaleY,
        ).apply {
            duration = 360
            interpolator = OvershootInterpolator(1.1f)
            start()
        }
    }

    private fun triggerMonsterCounterAndPetShieldBlock() {
        val b = _binding ?: return

        // Monster lunges left toward the frontline pet
        ObjectAnimator.ofFloat(
            b.ivMonsterSprite,
            View.TRANSLATION_X,
            0f, -38f, 6f, 0f,
        ).apply {
            duration = 340
            start()
        }

        // Pet shield aura flares brightly and absorbs the blow
        b.ivPetShieldVfx.apply {
            alpha = 1f
            scaleX = 0.65f
            scaleY = 0.65f
        }
        val pvhScaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 0.65f, 1.3f, 1.0f)
        val pvhScaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.65f, 1.3f, 1.0f)
        val pvhAlpha = PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0.9f, 0f)
        ObjectAnimator.ofPropertyValuesHolder(
            b.ivPetShieldVfx,
            pvhScaleX,
            pvhScaleY,
            pvhAlpha,
        ).apply {
            duration = 620
            start()
        }

        // Floating "🛡️ BLOCKED!" popup above the pet
        b.tvPetDamagePopup.text = "🛡️ BLOCKED!"
        b.tvPetDamagePopup.setTextColor(ContextCompat.getColor(requireContext(), R.color.hq_gold))
        b.tvPetDamagePopup.alpha = 1f
        b.tvPetDamagePopup.translationY = 0f
        ObjectAnimator.ofPropertyValuesHolder(
            b.tvPetDamagePopup,
            PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0f, -64f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0f),
        ).apply {
            duration = 750
            interpolator = OvershootInterpolator(1.2f)
            start()
        }
    }

    private fun triggerMonsterCounterAndPetHit(damageTaken: Double) {
        val b = _binding ?: return

        // Monster lunges left toward the frontline pet
        ObjectAnimator.ofFloat(
            b.ivMonsterSprite,
            View.TRANSLATION_X,
            0f, -38f, 6f, 0f,
        ).apply {
            duration = 340
            start()
        }

        // Frontline pet shakes and flashes crimson on impact
        ObjectAnimator.ofFloat(
            b.ivBattlePetSprite,
            View.TRANSLATION_X,
            0f, -16f, 16f, -12f, 12f, -6f, 6f, 0f,
        ).apply {
            duration = 380
            start()
        }
        b.ivBattlePetSprite.setColorFilter(Color.argb(140, 255, 50, 50))
        b.ivBattlePetSprite.postDelayed({
            _binding?.ivBattlePetSprite?.clearColorFilter()
        }, 190)

        // Floating "-X HP" popup above the pet
        b.tvPetDamagePopup.text = "-${DamageCalculator.formatDecimal(damageTaken)} HP"
        b.tvPetDamagePopup.setTextColor(ContextCompat.getColor(requireContext(), R.color.hq_crimson))
        b.tvPetDamagePopup.alpha = 1f
        b.tvPetDamagePopup.translationY = 0f
        ObjectAnimator.ofPropertyValuesHolder(
            b.tvPetDamagePopup,
            PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0f, -64f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0f),
        ).apply {
            duration = 750
            interpolator = OvershootInterpolator(1.2f)
            start()
        }
    }

    private fun triggerMonsterHitAndSlash(isCrit: Boolean) {
        ObjectAnimator.ofFloat(
            binding.ivMonsterSprite,
            View.TRANSLATION_X,
            0f, -18f, 18f, -14f, 14f, -8f, 8f, 0f,
        ).apply {
            duration = 380
            start()
        }

        binding.ivMonsterSprite.setColorFilter(Color.argb(140, 255, 50, 50))
        binding.ivMonsterSprite.postDelayed({
            _binding?.ivMonsterSprite?.clearColorFilter()
        }, 180)

        binding.ivSlashVfx.apply {
            setImageResource(if (isCrit) R.drawable.ic_vfx_crit_burst else R.drawable.ic_vfx_slash)
            alpha = 1f
            scaleX = 0.35f
            scaleY = 0.35f
            rotation = -20f
        }
        val pvhScaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 0.35f, 1.25f)
        val pvhScaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.35f, 1.25f)
        val pvhRot = PropertyValuesHolder.ofFloat(View.ROTATION, -20f, 15f)
        val pvhAlpha = PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0f)
        ObjectAnimator.ofPropertyValuesHolder(
            binding.ivSlashVfx,
            pvhScaleX,
            pvhScaleY,
            pvhRot,
            pvhAlpha,
        ).apply {
            duration = 360
            start()
        }
    }

    private fun triggerDamagePopup(damage: Double, isCrit: Boolean) {
        binding.tvDamagePopup.text = "-${DamageCalculator.formatDecimal(damage)}${if (isCrit) " CRIT!" else ""}"
        binding.tvDamagePopup.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (isCrit) R.color.hq_gold else R.color.hq_crimson
            )
        )
        binding.tvDamagePopup.alpha = 1f
        binding.tvDamagePopup.translationY = 0f

        val pvhY = PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0f, -80f)
        val pvhAlpha = PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0f)

        ObjectAnimator.ofPropertyValuesHolder(binding.tvDamagePopup, pvhY, pvhAlpha).apply {
            duration = 750
            interpolator = OvershootInterpolator(1.2f)
            start()
        }
    }

    private fun triggerPlayerDamageShake() {
        val shake = ObjectAnimator.ofFloat(
            binding.cardPlayerStatus,
            View.TRANSLATION_X,
            0f, -12f, 12f, -8f, 8f, -4f, 4f, 0f
        )
        shake.duration = 400
        shake.start()
    }

    private fun showLowHpWarningDialog() {
        lowHpDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("⚠️ Critical Health Warning!")
            .setMessage("Your HP has dropped below 20%! If you are defeated, you will lose 10% Coins and your Pet will lose 15 Affection (plus 1.5 per wrong answer). Would you like to flee safely now?")
            .setCancelable(false)
            .setPositiveButton("Flee Safely") { dialog, _ ->
                dialog.dismiss()
                stopSpeechRecognizer()
                viewModel.flee()
            }
            .setNegativeButton("Fight On (Risk Loss)") { dialog, _ ->
                dialog.dismiss()
                viewModel.dismissLowHpWarning()
            }
            .create()

        lowHpDialog?.show()
    }

    private fun showResultBottomSheet(
        result: BattleResultResponse,
        areaId: Int,
        monsterTypeStr: String,
        monsterIndex: Int,
    ) {
        stopSpeechRecognizer()
        resultBottomSheet?.dismiss()

        val sheet = BottomSheetDialog(requireContext())
        val sheetBinding = SheetBattleResultBinding.inflate(layoutInflater)
        sheet.setContentView(sheetBinding.root)
        sheet.setCancelable(false)

        when (result.result) {
            "win" -> {
                sheetBinding.tvResultIcon.text = "🏆"
                sheetBinding.tvResultTitle.text = "VICTORY!"
                sheetBinding.tvResultTitle.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.hq_gold)
                )
            }
            "lose" -> {
                sheetBinding.tvResultIcon.text = "💀"
                sheetBinding.tvResultTitle.text = "DEFEATED"
                sheetBinding.tvResultTitle.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.hq_crimson)
                )
            }
            else -> {
                sheetBinding.tvResultIcon.text = "🏃"
                sheetBinding.tvResultTitle.text = "TACTICAL RETREAT"
                sheetBinding.tvResultTitle.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.hq_text_muted)
                )
            }
        }

        if (result.levelUp) {
            appContainer.soundPlayer.play(Sfx.LEVEL_UP)
        } else if (result.result == "win") {
            appContainer.soundPlayer.play(Sfx.MONSTER_DEFEAT)
        }

        sheetBinding.tvResultMessage.text = result.message
        sheetBinding.tvXpReward.text = "+${result.xpEarned}"
        sheetBinding.tvCoinsReward.text = "+${result.coinsEarned}"
        sheetBinding.tvDamageDealtStat.text = DamageCalculator.formatDecimal(result.damageDealt)

        // Level up banner
        if (result.levelUp) {
            sheetBinding.tvLevelUpBanner.visibility = View.VISIBLE
            sheetBinding.tvLevelUpBanner.text = "🎉 Level Up! You reached Level ${result.newLevel ?: 2}!"
        } else {
            sheetBinding.tvLevelUpBanner.visibility = View.GONE
        }

        // Defeat penalty alert
        if (!result.lostItem.isNullOrBlank()) {
            sheetBinding.tvLostItemBanner.visibility = View.VISIBLE
            sheetBinding.tvLostItemBanner.text = "⚠️ Defeat Penalty: Lost ${result.lostItem}!"
        } else {
            sheetBinding.tvLostItemBanner.visibility = View.GONE
        }

        // Action buttons
        sheetBinding.btnFightAgain.setOnClickListener {
            sheet.dismiss()
            showBattleLoadingOverlay(binding.tvStageLabel.text.toString())
            viewModel.startBattle(areaId, monsterTypeStr, monsterIndex)
        }

        sheetBinding.btnReturnWorldMap.setOnClickListener {
            sheet.dismiss()
            findNavController().navigateUp()
        }

        resultBottomSheet = sheet
        sheet.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        loadingDismissRunnable?.let { _binding?.layoutBattleLoading?.removeCallbacks(it) }
        loadingDismissRunnable = null
        stopSpeechCapture()
        tts?.stop()
        tts?.shutdown()
        tts = null
        isTtsReady = false
        idleBreathingAnimator?.cancel()
        idleBreathingAnimator = null
        spriteAnimator?.stop()
        spriteAnimator = null
        currentMonsterSpriteKey = null
        petAttackResetRunnable?.let { _binding?.ivBattlePetSprite?.removeCallbacks(it) }
        petAttackResetRunnable = null
        petSpriteAnimator?.stop()
        petSpriteAnimator = null
        bagPotionAnimators.forEach { it.stop() }
        bagPotionAnimators.clear()
        lowHpDialog?.dismiss()
        lowHpDialog = null
        resultBottomSheet?.dismiss()
        resultBottomSheet = null
        _binding = null
    }

    companion object {
        const val ARG_AREA_ID = "areaId"
        const val ARG_MONSTER_TYPE = "monsterType"
        const val ARG_MONSTER_INDEX = "monsterIndex"

        private val BATTLE_TIPS = listOf(
            "💡 Tip: Eat Human Food from your Inventory outside battle for cheap 2x HP recovery! Save Red Potions for emergency % Max HP healing mid-battle!",
            "💡 Tip: Red Healing Potions restore 25% to 100% of your Max HP instantly during combat. Tap the 🎒 Bag button on your HP bar to drink one!",
            "💡 Tip: Normal monsters test pure Flashwords (Reading), while Elites & Bosses test Listening, Speaking, and Fill-in-the-Blank sentences with extra time!",
            "💡 Tip: Feed Pet Treats (Bones, Biscuits, Dried Koi) in the Sanctuary so your companion stays above 25% Affection and keeps its combat buff active!",
            "💡 Tip: Words you miss in battle are automatically saved to '🎯 Weak Words (Mistakes)' in the HSK Dictionary for focused review & Hanzi writing practice!",
            "💡 Tip: Bosses Enrage below 30% HP (+35% DMG)! Save a Greater or Supreme Healing Potion for the final phase of a Boss fight!",
        )
    }
}
