package com.faldo.hsk_quest.ui.battle

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.TextView
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
import com.faldo.hsk_quest.databinding.FragmentBattleBinding
import com.faldo.hsk_quest.databinding.SheetBattleResultBinding
import com.faldo.hsk_quest.engine.BattleQuestion
import com.faldo.hsk_quest.util.SpriteAnimator
import com.faldo.hsk_quest.util.appContainer
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/**
 * Core battle arena fragment for flashcard combat.
 * Handles sprite animations, timer countdowns, answering feedback, low HP flee prompts,
 * and battle result modal presentation.
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
        )
    }

    private var spriteAnimator: SpriteAnimator? = null
    private var lowHpDialog: AlertDialog? = null
    private var resultBottomSheet: BottomSheetDialog? = null

    private var currentQuestion: BattleQuestion? = null
    private var isAnswerLocked: Boolean = false

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

        binding.tvStageLabel.text = "HSK $areaId · ${monsterTypeStr.replaceFirstChar { it.uppercase() }}"

        // Start 8-frame loop for monster sprite
        spriteAnimator = SpriteAnimator.startAnimation(
            imageView = binding.ivMonsterSprite,
            drawableRes = R.drawable.monster_dummy,
            rows = 1,
            cols = 8,
            fps = 8
        )

        setupOptionButtons()
        setupFleeButton()
        observeViewModel(areaId, monsterTypeStr)

        viewModel.startBattle(areaId, monsterTypeStr)
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
                .setMessage("Are you sure you want to retreat? You will forfeit rewards but keep all your equipment safely.")
                .setPositiveButton("Flee") { _, _ ->
                    viewModel.flee()
                }
                .setNegativeButton("Keep Fighting", null)
                .show()
        }
    }

    private fun observeViewModel(areaId: Int, monsterTypeStr: String) {
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
                    viewModel.serverResult.collect { result ->
                        showResultBottomSheet(result, areaId, monsterTypeStr)
                    }
                }
            }
        }
    }

    private fun renderBattleState(state: BattleState) {
        // Monster HP
        binding.tvMonsterName.text = state.monster.name
        binding.tvMonsterHpNumeric.text = "${state.monsterHp}/${state.monster.maxHp}"
        binding.progressMonsterHp.progress = state.monsterHpPercent

        // Player HP
        binding.tvPlayerHpNumeric.text = "${state.playerHp}/${state.playerMaxHp}"
        binding.progressPlayerHp.progress = state.playerHpPercent

        // Combo indicator
        if (state.combo > 1) {
            binding.tvCombo.visibility = View.VISIBLE
            binding.tvCombo.text = "${state.combo} COMBO"
        } else {
            binding.tvCombo.visibility = View.INVISIBLE
        }

        // Damage popups & shake animations
        if (state.lastDamageDealt > 0) {
            triggerDamagePopup(state.lastDamageDealt, state.wasCritical)
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
        currentQuestion = question
        isAnswerLocked = false

        binding.tvQuestionType.text = question.type.displayLabel.uppercase()
        binding.tvPromptPrimary.text = question.prompt
        binding.tvPromptSub.text = question.subPrompt.orEmpty()

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
        // Range 0 to 1000
        val progress = (remainingMs / 5).toInt().coerceIn(0, 1000)
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
            .setMessage("Your HP has dropped below 20%! If you are defeated, you risk losing random equipment. Would you like to flee safely now?")
            .setCancelable(false)
            .setPositiveButton("Flee Safely") { dialog, _ ->
                dialog.dismiss()
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
    ) {
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

        // Lost equipment penalty alert
        if (!result.lostItem.isNullOrBlank()) {
            sheetBinding.tvLostItemBanner.visibility = View.VISIBLE
            sheetBinding.tvLostItemBanner.text = "⚠️ Equipment Lost in Battle: ${result.lostItem}!"
        } else {
            sheetBinding.tvLostItemBanner.visibility = View.GONE
        }

        // Action buttons
        sheetBinding.btnFightAgain.setOnClickListener {
            sheet.dismiss()
            viewModel.startBattle(areaId, monsterTypeStr)
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
        spriteAnimator?.stop()
        spriteAnimator = null
        lowHpDialog?.dismiss()
        lowHpDialog = null
        resultBottomSheet?.dismiss()
        resultBottomSheet = null
        _binding = null
    }

    companion object {
        const val ARG_AREA_ID = "areaId"
        const val ARG_MONSTER_TYPE = "monsterType"
    }
}
