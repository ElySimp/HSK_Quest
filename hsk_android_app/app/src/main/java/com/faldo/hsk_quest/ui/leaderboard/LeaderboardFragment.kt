package com.faldo.hsk_quest.ui.leaderboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.faldo.hsk_quest.R
import com.faldo.hsk_quest.data.model.WeeklyLeaderboardResponse
import com.faldo.hsk_quest.databinding.FragmentLeaderboardBinding
import com.faldo.hsk_quest.util.appContainer
import com.faldo.hsk_quest.util.readableError
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

class LeaderboardFragment : Fragment() {

    private var _binding: FragmentLeaderboardBinding? = null
    private val binding get() = _binding!!

    private val viewModel: LeaderboardViewModel by viewModels {
        LeaderboardViewModel.factory(appContainer.leaderboardRepository)
    }

    private lateinit var adapter: LeaderboardAdapter
    private var suppressChipCallback = false
    private val numFmt = NumberFormat.getIntegerInstance(Locale.US)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentLeaderboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = LeaderboardAdapter()
        binding.rvLeaderboard.layoutManager = LinearLayoutManager(requireContext())
        binding.rvLeaderboard.adapter = adapter

        binding.btnRefreshLeaderboard.setOnClickListener {
            viewModel.loadLeaderboard(viewModel.selectedLeague.value)
        }
        binding.btnRetryLeaderboard.setOnClickListener {
            viewModel.loadLeaderboard(viewModel.selectedLeague.value)
        }

        binding.chipGroupLeagues.setOnCheckedStateChangeListener { _, checkedIds ->
            if (suppressChipCallback) return@setOnCheckedStateChangeListener
            val checkedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val league = when (checkedId) {
                R.id.chip_league_bronze -> "bronze"
                R.id.chip_league_silver -> "silver"
                R.id.chip_league_gold -> "gold"
                R.id.chip_league_radiant -> "radiant"
                else -> "bronze"
            }
            if (league != viewModel.selectedLeague.value) {
                viewModel.loadLeaderboard(league)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::renderState) }
                launch { viewModel.secondsRemaining.collect(::renderCountdown) }
            }
        }

        if (savedInstanceState == null) {
            viewModel.loadLeaderboard(null)
        }
    }

    private fun renderState(state: LeaderboardViewModel.UiState) {
        when (state) {
            LeaderboardViewModel.UiState.Loading -> {
                binding.pbLeaderboardLoading.visibility = View.VISIBLE
                binding.rvLeaderboard.visibility = View.GONE
                binding.layoutLeaderboardEmpty.visibility = View.GONE
                binding.layoutLeaderboardError.visibility = View.GONE
            }
            is LeaderboardViewModel.UiState.Loaded -> {
                binding.pbLeaderboardLoading.visibility = View.GONE
                binding.layoutLeaderboardError.visibility = View.GONE
                bindLeaderboard(state.data)
            }
            is LeaderboardViewModel.UiState.Error -> {
                binding.pbLeaderboardLoading.visibility = View.GONE
                binding.rvLeaderboard.visibility = View.GONE
                binding.layoutLeaderboardEmpty.visibility = View.GONE
                binding.layoutLeaderboardError.visibility = View.VISIBLE
                binding.tvLeaderboardErrorMsg.text = readableError(state.message)
            }
        }
    }

    private fun bindLeaderboard(data: WeeklyLeaderboardResponse) {
        // Sync selected chip with server's active league view without re-triggering listener
        val targetChipId = when (data.league.lowercase()) {
            "silver" -> R.id.chip_league_silver
            "gold" -> R.id.chip_league_gold
            "radiant" -> R.id.chip_league_radiant
            else -> R.id.chip_league_bronze
        }
        if (binding.chipGroupLeagues.checkedChipId != targetChipId) {
            suppressChipCallback = true
            binding.chipGroupLeagues.check(targetChipId)
            suppressChipCallback = false
        }

        // Bind player's own league banner
        val (badgeEmoji, leagueLabel, colorRes) = formatLeagueVisuals(data.myLeague)
        binding.tvMyLeagueTitle.text = "$badgeEmoji $leagueLabel"
        binding.tvMyLeagueTitle.setTextColor(ContextCompat.getColor(requireContext(), colorRes))
        val emblemRes = when (data.myLeague.lowercase()) {
            "radiant" -> R.drawable.ic_league_radiant
            "gold" -> R.drawable.ic_league_gold
            "silver" -> R.drawable.ic_league_silver
            else -> R.drawable.ic_league_bronze
        }
        binding.tvMyLeagueTitle.setCompoundDrawablesRelativeWithIntrinsicBounds(emblemRes, 0, 0, 0)
        binding.tvMyLeagueTitle.compoundDrawablePadding =
            (8 * resources.displayMetrics.density).toInt()

        val myRank = data.me?.rank
        val myDamage = data.me?.weeklyDamage ?: 0
        binding.tvMyRankValue.text = if (myRank != null && myRank > 0) "#$myRank" else "—"
        binding.tvMyWeeklyDamage.text = "⚔️ ${numFmt.format(myDamage)} DMG"

        val rewardText = when {
            data.projectedRewardDiamonds > 0 && !data.projectedRewardTitle.isNullOrBlank() ->
                "Projected: 💎 ${data.projectedRewardDiamonds} · \"${data.projectedRewardTitle}\""
            data.projectedRewardDiamonds > 0 ->
                "Projected: 💎 ${data.projectedRewardDiamonds} Gems"
            else ->
                "Win a battle to qualify for weekly rewards!"
        }
        binding.tvProjectedReward.text = rewardText

        val isViewingOwnLeague = data.league.equals(data.myLeague, ignoreCase = true)
        binding.tvLeagueScopeHint.text = if (isViewingOwnLeague) {
            "Competing in your tier (${leagueUnlockHint(data.myLeague)})"
        } else {
            "Viewing ${formatLeagueVisuals(data.league).second} (${leagueUnlockHint(data.league)}) · Read-only"
        }

        if (data.entries.isEmpty()) {
            binding.rvLeaderboard.visibility = View.GONE
            binding.layoutLeaderboardEmpty.visibility = View.VISIBLE
        } else {
            binding.layoutLeaderboardEmpty.visibility = View.GONE
            binding.rvLeaderboard.visibility = View.VISIBLE
            adapter.submitList(data.entries)
        }
    }

    private fun renderCountdown(seconds: Int) {
        val days = seconds / 86_400
        val hours = (seconds % 86_400) / 3_600
        val mins = (seconds % 3_600) / 60
        binding.tvResetCountdown.text = "⏳ Resets in ${days}d ${hours}h ${mins}m"
    }

    private fun formatLeagueVisuals(league: String): Triple<String, String, Int> =
        when (league.lowercase()) {
            "radiant" -> Triple("👑", "Radiant League", R.color.hq_violet)
            "gold" -> Triple("🥇", "Gold League", R.color.hq_gold)
            "silver" -> Triple("🥈", "Silver League", R.color.hq_silver)
            else -> Triple("🥉", "Bronze League", R.color.hq_bronze)
        }

    private fun leagueUnlockHint(league: String): String =
        when (league.lowercase()) {
            "radiant" -> "Areas 7–9"
            "gold" -> "Area 6"
            "silver" -> "Areas 4–5"
            else -> "Areas 0–3"
        }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
