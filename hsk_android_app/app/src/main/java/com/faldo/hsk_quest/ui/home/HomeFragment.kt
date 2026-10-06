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
import com.faldo.hsk_quest.data.model.DailyStatusResponse
import com.faldo.hsk_quest.data.model.Player
import com.faldo.hsk_quest.databinding.FragmentHomeBinding
import com.faldo.hsk_quest.engine.XpCalculator
import com.faldo.hsk_quest.util.SpriteAnimator
import com.faldo.hsk_quest.util.appContainer
import com.faldo.hsk_quest.util.readableError
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HomeViewModel by viewModels {
        HomeViewModel.factory(appContainer.playerRepository, appContainer.authRepository)
    }

    private var petAnimator: SpriteAnimator? = null

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

        // Setup pet animation (row 0 = idle 1, 4 frames)
        petAnimator = SpriteAnimator.startAnimation(
            imageView = binding.ivPetSprite,
            drawableRes = R.drawable.pet_dummy_1,
            rows = 3,
            cols = 4,
            targetRow = 0,
            frameCount = 4,
            fps = 6,
        )

        val nav = findNavController()
        binding.btnStartAdventure.setOnClickListener {
            nav.navigate(R.id.action_home_to_worldmap)
        }

        binding.btnClaimDaily.setOnClickListener {
            viewModel.claimDaily()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::renderState) }
                launch { viewModel.dailyStatus.collect(::renderDaily) }
                launch {
                    viewModel.isClaimingDaily.collect { isClaiming ->
                        if (isClaiming) binding.btnClaimDaily.isEnabled = false
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
        petAnimator?.start()
    }

    override fun onPause() {
        super.onPause()
        petAnimator?.stop()
    }

    private fun renderState(state: HomeViewModel.UiState) {
        when (state) {
            HomeViewModel.UiState.Loading -> {}
            is HomeViewModel.UiState.Loaded -> bindPlayer(state.player, state.isCached)
            is HomeViewModel.UiState.Error -> {
                Toast.makeText(requireContext(), readableError(state.message), Toast.LENGTH_SHORT).show()
            }
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
            tvLevelLeague.text = "${getString(R.string.home_level_format, s.level)} · ${s.league.replaceFirstChar { it.uppercase() }}"

            progressXp.setProgressCompat(XpCalculator.progressPercent(s.level, s.xp), true)
            tvXpLabel.text = "${s.xp} / ${s.xpToNextLevel} XP"
        }
    }

    private fun renderDaily(status: DailyStatusResponse?) {
        if (status == null) {
            binding.cardDaily.visibility = View.GONE
            return
        }

        binding.cardDaily.visibility = View.VISIBLE
        binding.tvDailyStreak.text = getString(R.string.home_daily_streak_fmt, status.streak)
        binding.tvDailyRewardAmount.text = getString(R.string.home_daily_reward_fmt, status.coinsReward)

        if (status.canClaim) {
            binding.btnClaimDaily.isEnabled = true
            binding.btnClaimDaily.alpha = 1.0f
            binding.btnClaimDaily.text = getString(R.string.home_daily_claim_btn)
        } else {
            binding.btnClaimDaily.isEnabled = false
            binding.btnClaimDaily.alpha = 0.5f
            binding.btnClaimDaily.text = getString(R.string.home_daily_claimed)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        petAnimator?.stop()
        petAnimator = null
        _binding = null
    }
}
