package com.faldo.hsk_quest.ui.quest

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.faldo.hsk_quest.data.model.DailyQuestListResponse
import com.faldo.hsk_quest.databinding.FragmentQuestBinding
import com.faldo.hsk_quest.util.Sfx
import com.faldo.hsk_quest.util.appContainer
import kotlinx.coroutines.launch

/**
 * Daily Quest hub where players track daily milestones and claim Diamonds & Coins.
 */
class QuestFragment : Fragment() {

    private var _binding: FragmentQuestBinding? = null
    private val binding get() = _binding!!

    private val viewModel: QuestViewModel by viewModels {
        QuestViewModel.factory(
            appContainer.questRepository,
            appContainer.playerRepository,
        )
    }

    private var questAdapter: QuestAdapter? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentQuestBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        questAdapter = QuestAdapter { quest ->
            viewModel.claimQuest(quest.questKey)
        }
        binding.rvDailyQuests.layoutManager = LinearLayoutManager(requireContext())
        binding.rvDailyQuests.adapter = questAdapter

        binding.btnClaimAllQuests.setOnClickListener {
            viewModel.claimAll()
        }
        binding.btnQuestRetry.setOnClickListener {
            viewModel.loadQuests()
        }

        observeViewModel()
        viewModel.loadQuests()
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadQuests()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.player.collect { player ->
                        if (player != null) {
                            binding.tvQuestDiamonds.text = "💎 %,d".format(player.stats.diamonds)
                            binding.tvQuestCoins.text = "🪙 %,d".format(player.stats.coins)
                        }
                    }
                }

                launch {
                    viewModel.dailyQuests.collect { daily ->
                        if (daily != null) {
                            renderDailyQuests(daily)
                        }
                    }
                }

                launch {
                    viewModel.isLoading.collect { loading ->
                        binding.pbQuestLoading.visibility = if (loading) View.VISIBLE else View.GONE
                    }
                }

                launch {
                    viewModel.errorMessage.collect { error ->
                        if (error.isNullOrBlank()) {
                            binding.layoutQuestError.visibility = View.GONE
                        } else {
                            binding.layoutQuestError.visibility = View.VISIBLE
                            binding.tvQuestError.text = error
                        }
                    }
                }

                launch {
                    viewModel.claimingKey.collect { key ->
                        questAdapter?.claimingKey = key
                        val daily = viewModel.dailyQuests.value
                        if (daily != null) {
                            updateClaimAllButton(daily, key != null)
                        }
                    }
                }

                launch {
                    viewModel.claimSuccessEvent.collect {
                        appContainer.soundPlayer.play(Sfx.COIN)
                    }
                }

                launch {
                    viewModel.feedbackMessage.collect { msg ->
                        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun renderDailyQuests(daily: DailyQuestListResponse) {
        binding.tvQuestDiamonds.text = "💎 %,d".format(daily.totalDiamonds)
        binding.tvQuestCoins.text = "🪙 %,d".format(daily.totalCoins)
        binding.tvQuestDate.text = "UTC ${daily.questDate}"

        val baseTotal = daily.totalBaseQuests.coerceAtLeast(1)
        val completed = daily.completedCount.coerceIn(0, baseTotal)
        binding.progressDailyOverall.max = baseTotal
        binding.progressDailyOverall.progress = completed

        val readySuffix = if (daily.claimableCount > 0) {
            " · ${daily.claimableCount} Ready to Claim"
        } else {
            ""
        }
        binding.tvQuestCompletionSummary.text =
            "$completed / $baseTotal Core Quests Completed$readySuffix"

        updateClaimAllButton(daily, viewModel.claimingKey.value != null)
        questAdapter?.submitList(daily.quests)
    }

    private fun updateClaimAllButton(daily: DailyQuestListResponse, isBusy: Boolean) {
        val allClaimed = daily.quests.isNotEmpty() && daily.quests.all { it.isClaimed }
        when {
            daily.claimableCount > 0 -> {
                binding.btnClaimAllQuests.isEnabled = !isBusy
                binding.btnClaimAllQuests.text = if (isBusy) {
                    "Claiming Rewards..."
                } else {
                    "✨ Claim All (+${daily.claimableDiamonds} 💎)"
                }
            }
            allClaimed -> {
                binding.btnClaimAllQuests.isEnabled = false
                binding.btnClaimAllQuests.text = "✓ All Daily Quests Claimed Today"
            }
            else -> {
                binding.btnClaimAllQuests.isEnabled = false
                binding.btnClaimAllQuests.text = "Complete Quests to Earn 💎"
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        questAdapter = null
        _binding = null
    }
}
