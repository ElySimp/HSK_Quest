package com.faldo.hsk_quest.ui.home

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
import androidx.navigation.fragment.findNavController
import com.faldo.hsk_quest.R
import com.faldo.hsk_quest.data.model.Player
import com.faldo.hsk_quest.databinding.FragmentHomeBinding
import com.faldo.hsk_quest.engine.XpCalculator
import com.faldo.hsk_quest.util.appContainer
import com.faldo.hsk_quest.util.applySystemBarsPadding
import com.faldo.hsk_quest.util.readableError
import kotlinx.coroutines.launch

/**
 * Tamagotchi hub. Phase 1A shows the live player profile and navigation;
 * pet sprite, room art and mini-games arrive in Phase 3.
 */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HomeViewModel by viewModels {
        HomeViewModel.factory(appContainer.playerRepository, appContainer.authRepository)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.root.applySystemBarsPadding()

        val nav = findNavController()
        binding.btnWorldMap.setOnClickListener { nav.navigate(R.id.action_home_to_worldmap) }
        binding.btnShop.setOnClickListener { nav.navigate(R.id.action_home_to_shop) }
        binding.btnGacha.setOnClickListener { nav.navigate(R.id.action_home_to_gacha) }
        binding.btnLeaderboard.setOnClickListener { nav.navigate(R.id.action_home_to_leaderboard) }
        binding.btnLogout.setOnClickListener { viewModel.logout() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-fetch whenever the hub becomes visible (e.g. after returning from a battle).
        viewModel.refresh()
    }

    private fun render(state: HomeViewModel.UiState) {
        when (state) {
            HomeViewModel.UiState.Loading -> binding.tvGreeting.text = "..."
            is HomeViewModel.UiState.Loaded -> bindPlayer(state.player, state.isCached)
            is HomeViewModel.UiState.Error ->
                Toast.makeText(requireContext(), readableError(state.message), Toast.LENGTH_LONG).show()
            HomeViewModel.UiState.LoggedOut -> {
                val nav = findNavController()
                if (nav.currentDestination?.id == R.id.homeFragment) {
                    nav.navigate(R.id.action_home_to_login)
                }
            }
        }
    }

    private fun bindPlayer(player: Player, isCached: Boolean) {
        val s = player.stats
        with(binding) {
            tvDiamonds.text = "💎 ${s.diamonds}"
            tvCoins.text = "🪙 ${s.coins}"
            tvSurvivalHp.text = "❤️ ${s.survivalHp}"
            tvOffline.visibility = if (isCached) View.VISIBLE else View.GONE

            tvGreeting.text = getString(R.string.home_greeting, player.username)
            tvLevel.text = getString(R.string.home_level_format, s.level)
            tvLeague.text = s.league.replaceFirstChar { it.uppercase() }

            progressXp.setProgressCompat(XpCalculator.progressPercent(s.level, s.xp), true)
            tvXp.text = "${s.xp} / ${s.xpToNextLevel} XP"

            val points = if (s.unspentStatPoints > 0) "\n+${s.unspentStatPoints} stat points" else ""
            tvStats.text = "STR ${s.statStr}   DEX ${s.statDex}   DEF ${s.statDef}   VIT ${s.statVit}" +
                "\nHP ${s.hp}/${s.maxHp}   HSK target ${player.hskTarget}$points"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
