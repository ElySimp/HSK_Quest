package com.faldo.hsk_quest.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.faldo.hsk_quest.R
import com.faldo.hsk_quest.data.model.DailyStatusResponse
import com.faldo.hsk_quest.data.model.PetItem
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
        HomeViewModel.factory(
            appContainer.playerRepository,
            appContainer.authRepository,
            appContainer.petRepository,
        )
    }

    private var petAnimator: SpriteAnimator? = null
    private var currentActivePet: PetItem? = null

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

        setupCurrencyIcons()

        val nav = findNavController()
        binding.btnStartAdventure.setOnClickListener {
            nav.navigate(R.id.action_home_to_worldmap)
        }

        binding.btnClaimDaily.setOnClickListener {
            viewModel.claimDaily()
        }

        binding.cardCompanionPet.setOnClickListener {
            when (val pState = viewModel.petState.value) {
                HomeViewModel.PetState.Loading -> {
                    // Wait until pet finishes loading
                }
                is HomeViewModel.PetState.Error -> {
                    viewModel.retryPetLoad()
                }
                is HomeViewModel.PetState.Loaded -> {
                    if (pState.pet != null) {
                        nav.navigate(R.id.action_home_to_pet)
                    } else {
                        nav.navigate(R.id.action_home_to_gacha)
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::renderState) }
                launch { viewModel.dailyStatus.collect(::renderDaily) }
                launch { viewModel.petState.collect(::renderPetState) }
                launch {
                    viewModel.isClaimingDaily.collect { isClaiming ->
                        if (isClaiming) binding.btnClaimDaily.isEnabled = false
                    }
                }
            }
        }
    }

    private fun setupCurrencyIcons() {
        val gemBitmap = SpriteAnimator.extractFrame(
            context = requireContext(),
            drawableRes = R.drawable.currency_gems,
            rows = 9,
            cols = 5,
            targetRow = 0,
            targetCol = 4,
        )
        if (gemBitmap != null) {
            binding.ivCurrencyDiamond.setImageBitmap(gemBitmap)
        }

        val coinBitmap = SpriteAnimator.extractFrame(
            context = requireContext(),
            drawableRes = R.drawable.currency_coins,
            rows = 9,
            cols = 5,
            targetRow = 0,
            targetCol = 0,
        )
        if (coinBitmap != null) {
            binding.ivCurrencyCoin.setImageBitmap(coinBitmap)
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
            tvDiamonds.text = "${s.diamonds}"
            tvCoins.text = "${s.coins}"
            tvSurvivalHp.text = "${s.hp}/${s.maxHp}"
            tvOffline.visibility = if (isCached) View.VISIBLE else View.GONE

            tvGreeting.text = getString(R.string.home_greeting, player.username)
            tvLevelLeague.text = "${getString(R.string.home_level_format, s.level)} · ${formatLeagueBadge(s.league)}"

            progressXp.setProgressCompat(XpCalculator.progressPercent(s.level, s.xp), true)
            tvXpLabel.text = "${s.xp} / ${s.xpToNextLevel} XP"
        }
    }

    private fun formatLeagueBadge(rawLeague: String): String {
        val norm = rawLeague.lowercase()
        return when {
            norm.startsWith("radiant") -> "👑 Radiant League"
            norm.startsWith("gold") -> "🥇 Gold League"
            norm.startsWith("silver") -> "🥈 Silver League"
            else -> "🥉 Bronze League"
        }
    }

    private fun renderPetState(petState: HomeViewModel.PetState) {
        when (petState) {
            HomeViewModel.PetState.Loading -> {
                currentActivePet = null
                petAnimator?.stop()
                petAnimator = null
                binding.layoutHasPet.visibility = View.GONE
                binding.layoutNoPet.visibility = View.VISIBLE
                binding.tvNoPetIcon.text = "⏳"
                binding.tvNoPetTitle.text = "Calling Your Companion..."
                binding.tvNoPetSubtitle.text = "Connecting to the Tamagotchi Sanctuary..."
            }
            is HomeViewModel.PetState.Error -> {
                currentActivePet = null
                petAnimator?.stop()
                petAnimator = null
                binding.layoutHasPet.visibility = View.GONE
                binding.layoutNoPet.visibility = View.VISIBLE
                binding.tvNoPetIcon.text = "⚠️"
                binding.tvNoPetTitle.text = "Sanctuary Connection Lost"
                binding.tvNoPetSubtitle.text = "Tap to retry loading your companion"
            }
            is HomeViewModel.PetState.Loaded -> {
                renderPet(petState.pet)
            }
        }
    }

    private fun renderPet(pet: PetItem?) {
        currentActivePet = pet
        if (pet == null) {
            petAnimator?.stop()
            petAnimator = null
            binding.layoutNoPet.visibility = View.VISIBLE
            binding.layoutHasPet.visibility = View.GONE
            binding.tvNoPetIcon.text = "🥚"
            binding.tvNoPetTitle.text = "Nest is Empty"
            binding.tvNoPetSubtitle.text = "Tap to summon your companion at the Celestial Gacha Altar!"
            return
        }

        binding.layoutNoPet.visibility = View.GONE
        binding.layoutHasPet.visibility = View.VISIBLE

        binding.tvPetName.text = pet.name

        val rarityStr = when (pet.rarity.lowercase()) {
            "legendary" -> "🟡 Legendary"
            "epic" -> "🟣 Epic"
            "rare" -> "🔵 Rare"
            else -> "🟢 Common"
        }
        binding.tvPetRarity.text = rarityStr

        val affVal = pet.affection.toInt().coerceIn(0, 100)
        binding.tvPetAffectionVal.text = "❤️ $affVal%"
        binding.progressPetAffection.progress = affVal

        val drawableRes = if (pet.spriteKey == "pet_dummy_2") {
            R.drawable.pet_dummy_2
        } else {
            R.drawable.pet_dummy_1
        }

        petAnimator?.stop()

        if (pet.isSleeping) {
            binding.tvPetMood.text = "💤 Sleeping soundly... (Buffs inactive < 25%)"
            binding.tvPetMood.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.hq_text_muted)
            )
            // Row 2 = Sleep animation, 4 frames
            petAnimator = SpriteAnimator.startAnimation(
                imageView = binding.ivPetSprite,
                drawableRes = drawableRes,
                rows = 3,
                cols = 4,
                targetRow = 2,
                frameCount = 4,
                fps = 4,
            )
        } else {
            val buffLabel = when (pet.buffType) {
                "shield" -> "🛡️ Shield Active"
                "time_extender" -> "⏳ +3s Crit Active"
                "damage_boost" -> "⚔️ +15% Dmg Active"
                else -> "✨ Energetic & Happy!"
            }
            binding.tvPetMood.text = buffLabel
            binding.tvPetMood.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.hq_jade)
            )
            // Row 0 = Idle 1 animation, 4 frames
            petAnimator = SpriteAnimator.startAnimation(
                imageView = binding.ivPetSprite,
                drawableRes = drawableRes,
                rows = 3,
                cols = 4,
                targetRow = 0,
                frameCount = 4,
                fps = 6,
            )
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
