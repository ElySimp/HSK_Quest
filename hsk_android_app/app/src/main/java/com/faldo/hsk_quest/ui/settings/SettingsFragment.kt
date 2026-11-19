package com.faldo.hsk_quest.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.faldo.hsk_quest.MainActivity
import com.faldo.hsk_quest.R
import com.faldo.hsk_quest.databinding.FragmentSettingsBinding
import com.faldo.hsk_quest.engine.QuestionEngineMode
import com.faldo.hsk_quest.engine.ResolvedQuestionEngine
import com.faldo.hsk_quest.util.appContainer
import com.faldo.hsk_quest.util.showHqToast
import kotlinx.coroutines.launch

/**
 * Settings screen allowing the user to toggle the CAT-QUEST battle question
 * engine between Rule-Based (Engine A) and ML Adaptive CAT (Engine B).
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        (activity as? MainActivity)?.attachScrollListener(binding.settingsRoot)

        val settingsManager = appContainer.engineSettingsManager
        binding.cardEngineRuleBased.setOnClickListener {
            if (settingsManager.selectedMode.value != QuestionEngineMode.RULE_BASED) {
                settingsManager.setSelectedMode(QuestionEngineMode.RULE_BASED)
                showHqToast("Question engine switched to Engine A (Rule-Based)")
            }
        }

        binding.cardEngineMlCat.setOnClickListener {
            if (settingsManager.selectedMode.value != QuestionEngineMode.ML_CAT) {
                settingsManager.setSelectedMode(QuestionEngineMode.ML_CAT)
                val msg = if (settingsManager.isMlCatModelAvailable) {
                    "Question engine switched to Engine B (ML Adaptive CAT)"
                } else {
                    "ML CAT selected (pending .tflite model — using Engine A fallback)"
                }
                showHqToast(msg)
            }
        }

        bindSensorStatus()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsManager.selectedMode.collect { mode ->
                    val resolved = ResolvedQuestionEngine.resolve(
                        configuredMode = mode,
                        isMlModelReady = settingsManager.isMlCatModelAvailable,
                    )
                    renderEngineSelection(resolved)
                }
            }
        }
    }

    private fun renderEngineSelection(resolved: ResolvedQuestionEngine) {
        val ctx = requireContext()
        val goldColor = ContextCompat.getColor(ctx, R.color.hq_gold)
        val jadeColor = ContextCompat.getColor(ctx, R.color.hq_jade)
        val violetColor = ContextCompat.getColor(ctx, R.color.hq_violet)
        val mutedColor = ContextCompat.getColor(ctx, R.color.hq_text_muted)

        val isRuleSelected = resolved.configuredMode == QuestionEngineMode.RULE_BASED

        binding.cardEngineRuleBased.setBackgroundResource(
            if (isRuleSelected) R.drawable.bg_card_gold_border else R.drawable.bg_card
        )
        binding.cardEngineMlCat.setBackgroundResource(
            if (!isRuleSelected) R.drawable.bg_card_gold_border else R.drawable.bg_card
        )

        binding.tvRuleRadio.text = if (isRuleSelected) "●" else "○"
        binding.tvRuleRadio.setTextColor(if (isRuleSelected) goldColor else mutedColor)

        binding.tvMlRadio.text = if (!isRuleSelected) "●" else "○"
        binding.tvMlRadio.setTextColor(if (!isRuleSelected) goldColor else mutedColor)

        binding.tvRuleStatusPill.text = if (isRuleSelected) "ACTIVE" else "READY"
        binding.tvRuleStatusPill.setTextColor(jadeColor)

        if (resolved.isMlModelReady) {
            binding.tvMlStatusPill.text = if (!isRuleSelected) "ACTIVE" else "READY"
            binding.tvMlStatusPill.setTextColor(jadeColor)
        } else {
            binding.tvMlStatusPill.text = "PENDING MODEL"
            binding.tvMlStatusPill.setTextColor(violetColor)
        }

        binding.tvSettingsActiveBadge.text = resolved.formatCompactBadge()
        binding.bannerMlFallbackNotice.visibility =
            if (resolved.isFallbackToRuleBased) View.VISIBLE else View.GONE
    }

    private fun bindSensorStatus() {
        val ctx = requireContext()
        val jadeColor = ContextCompat.getColor(ctx, R.color.hq_jade)
        val mutedColor = ContextCompat.getColor(ctx, R.color.hq_text_secondary)

        val isHwReady = appContainer.tfliteRecognizer?.isReady == true
        binding.tvHandwritingAiStatus.text = if (isHwReady) "Active (MobileNetV2 TFLite)" else "Heuristic Fallback"
        binding.tvHandwritingAiStatus.setTextColor(if (isHwReady) jadeColor else mutedColor)

        val isToneReady = appContainer.mandarinToneEvaluator != null
        binding.tvSpeakingAiStatus.text = if (isToneReady) "Active (ToneNet TFLite)" else "Android STT Fallback"
        binding.tvSpeakingAiStatus.setTextColor(if (isToneReady) jadeColor else mutedColor)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
