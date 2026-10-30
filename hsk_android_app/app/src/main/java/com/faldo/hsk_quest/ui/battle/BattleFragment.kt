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
import com.faldo.hsk_quest.util.SpriteAnimator
import com.faldo.hsk_quest.util.appContainer
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
        )
    }

    private var spriteAnimator: SpriteAnimator? = null
    private var petSpriteAnimator: SpriteAnimator? = null
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

    private var currentQuestion: BattleQuestion? = null
    private var isAnswerLocked: Boolean = false

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startListeningWithSpeechRecognizer()
        } else {
            Toast.makeText(
                requireContext(),
                "Microphone permission declined. Switched to Listening mode.",
                Toast.LENGTH_SHORT,
            ).show()
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

        showBattleLoadingOverlay(stageLabel)
        initTextToSpeech()
        setupMonsterVisuals(areaId, monsterTypeStr)
        setupOptionButtons()
        setupMultimodalAndElixirControls()
        setupFleeButton()
        observeViewModel(areaId, monsterTypeStr, monsterIndex)

        viewModel.startBattle(areaId, monsterTypeStr, monsterIndex)
    }

    private fun showBattleLoadingOverlay(stageLabel: String) {
        val b = _binding ?: return
        loadingDismissRunnable?.let { b.layoutBattleLoading.removeCallbacks(it) }

        isLoadingOverlayActive = true
        viewModel.pauseTimerForSpeech()
        applyBattleArenaBlur(enabled = true)

        b.tvLoadingStageTitle.text = "⚔️ Entering $stageLabel..."
        b.tvBattleTip.text = BATTLE_TIPS.random()
        b.layoutBattleLoading.setBackgroundColor(Color.argb(215, 14, 14, 18))
        b.layoutBattleLoading.alpha = 1f
        b.layoutBattleLoading.visibility = View.VISIBLE

        b.layoutBattleLoading.setOnClickListener {
            dismissBattleLoadingOverlay()
        }

        val runnable = Runnable {
            dismissBattleLoadingOverlay()
        }
        loadingDismissRunnable = runnable
        b.layoutBattleLoading.postDelayed(runnable, 2_400L)
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

    private fun setupMonsterVisuals(areaId: Int, monsterTypeStr: String) {
        spriteAnimator?.stop()
        spriteAnimator = null
        idleBreathingAnimator?.cancel()
        idleBreathingAnimator = null

        val clampedArea = areaId.coerceIn(1, 9)
        val drawableName = when (monsterTypeStr.lowercase()) {
            "boss" -> "monster_boss_$clampedArea"
            "elite" -> "monster_elite_$clampedArea"
            else -> "monster_area_$clampedArea"
        }
        val resId = resources.getIdentifier(drawableName, "drawable", requireContext().packageName)

        if (resId != 0) {
            val cols = when (drawableName) {
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

        binding.btnCantSpeak.setOnClickListener {
            stopSpeechRecognizer()
            viewModel.disableSpeakingForBattle()
        }

        binding.btnSpeakMandarin.setOnClickListener {
            if (isAnswerLocked) return@setOnClickListener
            val hasPermission = ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED

            if (hasPermission) {
                startListeningWithSpeechRecognizer()
            } else {
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun openBattleInventoryPanel() {
        didDrinkInBagSession = false
        stopSpeechRecognizer()
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
                    viewModel.usePotionById(potion.itemId)
                }
            }

            card.addView(iconView)
            card.addView(infoCol)
            card.addView(drinkBtn)
            b.llBattleBagItems.addView(card)
        }
    }

    private fun startListeningWithSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(requireContext())) {
            Toast.makeText(
                requireContext(),
                "Speech recognition unavailable on this device. Switched to Listening mode.",
                Toast.LENGTH_SHORT,
            ).show()
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
                b.btnSpeakMandarin.text = "🎙️ Tap to Speak"
                b.btnSpeakMandarin.isEnabled = true
                b.tvSpeechFeedback.text =
                    "⚠️ Mic didn't catch that. Tap 🎙️ to retry or select the answer below."
                viewModel.resumeTimerAfterSpeech()
            }

            override fun onResults(results: Bundle?) {
                val b = _binding ?: return
                b.btnSpeakMandarin.text = "🎙️ Tap to Speak"
                b.btnSpeakMandarin.isEnabled = true

                val matches = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    .orEmpty()
                val eval = viewModel.submitSpeechResult(matches) ?: return
                isAnswerLocked = true
                b.tvSpeechFeedback.text = eval.feedback

                val correctIdx = currentQuestion?.correctIndex ?: -1
                val optionButtons = listOf(
                    b.btnOption0,
                    b.btnOption1,
                    b.btnOption2,
                    b.btnOption3,
                )
                if (correctIdx in optionButtons.indices) {
                    optionButtons[correctIdx].setBackgroundResource(R.drawable.bg_option_correct)
                    optionButtons[correctIdx].setTextColor(
                        ContextCompat.getColor(requireContext(), R.color.hq_jade)
                    )
                }
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
            binding.btnSpeakMandarin.text = "🎙️ Tap to Speak"
            binding.btnSpeakMandarin.isEnabled = true
            viewModel.resumeTimerAfterSpeech()
        }
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
                if (isAnswerLocked) return@setOnClickListener
                isAnswerLocked = true
                stopSpeechRecognizer()
                handleOptionSelection(index, textView)
            }
        }
    }

    private fun handleOptionSelection(selectedIndex: Int, selectedView: TextView) {
        val q = currentQuestion ?: return
        val isCorrect = q.isCorrect(selectedIndex)

        if (isCorrect) {
            selectedView.setBackgroundResource(R.drawable.bg_option_correct)
            selectedView.setTextColor(ContextCompat.getColor(requireContext(), R.color.hq_jade))
        } else {
            selectedView.setBackgroundResource(R.drawable.bg_option_wrong)
            selectedView.setTextColor(ContextCompat.getColor(requireContext(), R.color.hq_crimson))

            // Highlight the correct answer for player learning
            val correctIdx = q.correctIndex
            val optionButtons = listOf(
                binding.btnOption0,
                binding.btnOption1,
                binding.btnOption2,
                binding.btnOption3,
            )
            if (correctIdx in optionButtons.indices) {
                optionButtons[correctIdx].setBackgroundResource(R.drawable.bg_option_correct)
                optionButtons[correctIdx].setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.hq_jade)
                )
            }
        }

        viewModel.submitAnswer(selectedIndex)
    }

    private fun setupFleeButton() {
        binding.btnFlee.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Flee Battle?")
                .setMessage("Are you sure you want to retreat? You will forfeit rewards but protect your coins and pet affection.")
                .setPositiveButton("Flee") { _, _ ->
                    stopSpeechRecognizer()
                    viewModel.flee()
                }
                .setNegativeButton("Keep Fighting", null)
                .show()
        }
    }

    private fun observeViewModel(areaId: Int, monsterTypeStr: String, monsterIndex: Int) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.battleState.collect { state ->
                        state?.let { renderBattleState(it) }
                    }
                }

                launch {
                    viewModel.currentQuestion.collect { question ->
                        question?.let { renderQuestion(it) }
                    }
                }

                launch {
                    viewModel.timerProgressMs.collect { remainingMs ->
                        renderTimer(remainingMs)
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
                        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                    }
                }

                launch {
                    viewModel.serverResult.collect { result ->
                        showResultBottomSheet(result, areaId, monsterTypeStr, monsterIndex)
                    }
                }
            }
        }
    }

    private fun renderActivePet(pet: PetItem?) {
        petSpriteAnimator?.stop()
        petSpriteAnimator = null

        if (pet == null) {
            binding.layoutBattlePetBox.visibility = View.GONE
            return
        }

        binding.layoutBattlePetBox.visibility = View.VISIBLE
        val petDrawable = SpriteAnimator.resolvePetDrawable(
            requireContext(),
            pet.petType,
            pet.spriteKey,
        )
        petSpriteAnimator = SpriteAnimator.startAnimation(
            imageView = binding.ivBattlePetSprite,
            drawableRes = petDrawable,
            rows = 3,
            cols = 4,
            targetRow = if (pet.isSleeping) 2 else 0,
            frameCount = 4,
            fps = if (pet.isSleeping) 4 else 6,
        )

        binding.tvBattlePetBuff.text = if (pet.isSleeping) {
            "💤 Asleep"
        } else {
            when (pet.buffType) {
                "shield" -> "🛡️ Shield"
                "time_extender" -> "⏳ +3s"
                "damage_boost" -> "⚔️ +15%"
                else -> "🐾 Awake"
            }
        }
    }

    private fun renderBattleState(state: BattleState) {
        // Monster HP & Boss Enrage indicator
        binding.tvMonsterName.text = state.monster.name
        binding.tvMonsterHpNumeric.text = "${state.monsterHp}/${state.monster.maxHp}"
        binding.progressMonsterHp.progress = state.monsterHpPercent
        binding.tvBossEnrage.visibility = if (state.isBossEnraged) View.VISIBLE else View.GONE

        // Player HP & Buff label
        binding.tvPlayerHpNumeric.text = "❤️ ${state.playerHp}/${state.playerMaxHp} (${state.playerHpPercent}%)"
        binding.progressPlayerHp.progress = state.playerHpPercent

        val hpColorRes = when {
            state.playerHpPercent > 50 -> R.color.hq_jade
            state.playerHpPercent > 25 -> R.color.hq_gold
            else -> R.color.hq_crimson
        }
        val hpColor = ContextCompat.getColor(requireContext(), hpColorRes)
        binding.progressPlayerHp.setIndicatorColor(hpColor)
        binding.tvPlayerHpNumeric.setTextColor(hpColor)

        if (!state.activeBuffLabel.isNullOrBlank()) {
            binding.tvPlayerLabel.text = state.activeBuffLabel
        } else {
            binding.tvPlayerLabel.text = "🛡️ Hero Vitality"
        }

        if (didDrinkInBagSession &&
            binding.layoutBattleInventoryPanel.visibility == View.VISIBLE &&
            state.playerHp >= state.playerMaxHp
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

        // Damage popups & hit/slash animations
        if (state.lastDamageDealt > 0) {
            triggerDamagePopup(state.lastDamageDealt, state.wasCritical)
            triggerMonsterHitAndSlash()
        }
        if (state.lastDamageTaken > 0) {
            triggerPlayerDamageShake()
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

        binding.btnReplayAudio.visibility =
            if (question.type.isListening || question.type.isSpeaking) View.VISIBLE else View.GONE
        binding.btnSpeakMandarin.visibility =
            if (question.type.isSpeaking) View.VISIBLE else View.GONE
        binding.btnCantSpeak.visibility =
            if (question.type.isSpeaking) View.VISIBLE else View.GONE
        binding.tvSpeechFeedback.visibility =
            if (question.type.isSpeaking) View.VISIBLE else View.GONE

        if (question.type.isSpeaking) {
            binding.btnSpeakMandarin.text = "🎙️ Tap to Speak"
            binding.btnSpeakMandarin.isEnabled = true
            binding.tvSpeechFeedback.text =
                "🎙️ Tap 'Tap to Speak' and pronounce ${question.targetTerm.simplified} aloud — or select its Pinyin below"
        } else if (question.type.isListening) {
            speakMandarin(question.targetTerm.simplified)
        }

        val optionViews = listOf(
            binding.btnOption0,
            binding.btnOption1,
            binding.btnOption2,
            binding.btnOption3,
        )

        optionViews.forEachIndexed { i, view ->
            view.setBackgroundResource(R.drawable.bg_option_idle)
            view.setTextColor(ContextCompat.getColor(requireContext(), R.color.hq_text_primary))
            view.text = question.options.getOrElse(i) { "" }
            view.isEnabled = true
        }
    }

    private fun renderTimer(remainingMs: Long) {
        val limitMs = viewModel.currentQuestionLimitMs.value.coerceAtLeast(1L)
        val progress = ((remainingMs * 1000L) / limitMs).toInt().coerceIn(0, 1000)
        binding.progressTimer.progress = progress

        if (remainingMs < 2000L) {
            binding.progressTimer.setIndicatorColor(
                ContextCompat.getColor(requireContext(), R.color.hq_crimson)
            )
        } else {
            binding.progressTimer.setIndicatorColor(
                ContextCompat.getColor(requireContext(), R.color.hq_gold)
            )
        }
    }

    private fun triggerMonsterHitAndSlash() {
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

    private fun triggerDamagePopup(damage: Int, isCrit: Boolean) {
        binding.tvDamagePopup.text = "-$damage${if (isCrit) " CRIT!" else ""}"
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
            .setMessage("Your HP has dropped below 20%! If you are defeated, you will lose 10% Coins and your Pet will lose 10 Affection. Would you like to flee safely now?")
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

        sheetBinding.tvResultMessage.text = result.message
        sheetBinding.tvXpReward.text = "+${result.xpEarned}"
        sheetBinding.tvCoinsReward.text = "+${result.coinsEarned}"
        sheetBinding.tvDamageDealtStat.text = "${result.damageDealt}"

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
        stopSpeechRecognizer()
        tts?.stop()
        tts?.shutdown()
        tts = null
        isTtsReady = false
        idleBreathingAnimator?.cancel()
        idleBreathingAnimator = null
        spriteAnimator?.stop()
        spriteAnimator = null
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
            "💡 Tip: Red Healing Potions restore 25% to 100% of your Max HP instantly during combat. Tap the 🧪 button on your HP bar to drink one!",
            "💡 Tip: Normal monsters test pure Flashwords (Reading), while Elites & Bosses test Listening, Speaking, and Fill-in-the-Blank sentences with extra time!",
            "💡 Tip: Feed Pet Treats (Bones, Biscuits, Dried Koi) in the Sanctuary so your companion stays above 25% Affection and keeps its combat buff active!",
            "💡 Tip: Words you miss in battle are automatically saved to '🎯 Kata Sulit (Mistakes)' in the HSK Dictionary for focused review & Hanzi writing practice!",
            "💡 Tip: Bosses Enrage below 30% HP (+35% DMG)! Save a Greater or Supreme Healing Potion for the final phase of a Boss fight!",
        )
    }
}
