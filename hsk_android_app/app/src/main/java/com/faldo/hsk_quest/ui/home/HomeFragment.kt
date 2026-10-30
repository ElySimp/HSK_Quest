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
import androidx.recyclerview.widget.LinearLayoutManager
import com.faldo.hsk_quest.MainActivity
import com.faldo.hsk_quest.R
import com.faldo.hsk_quest.data.model.DailyStatusResponse
import com.faldo.hsk_quest.data.model.InventoryItem
import com.faldo.hsk_quest.data.model.PetItem
import com.faldo.hsk_quest.data.model.Player
import com.faldo.hsk_quest.databinding.FragmentHomeBinding
import com.faldo.hsk_quest.databinding.SheetHomeInventoryBinding
import com.faldo.hsk_quest.engine.XpCalculator
import com.faldo.hsk_quest.ui.shop.ShopAdapter
import com.faldo.hsk_quest.ui.shop.ShopUiItem
import com.faldo.hsk_quest.util.SpriteAnimator
import com.faldo.hsk_quest.util.appContainer
import com.faldo.hsk_quest.util.readableError
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HomeViewModel by viewModels {
        HomeViewModel.factory(
            appContainer.playerRepository,
            appContainer.authRepository,
            appContainer.petRepository,
            appContainer.shopRepository,
        )
    }

    private var petAnimator: SpriteAnimator? = null
    private var coinAnimator: SpriteAnimator? = null
    private var gemAnimator: SpriteAnimator? = null
    private var currentActivePet: PetItem? = null

    private var activeInventorySheet: BottomSheetDialog? = null
    private var activeInventoryBinding: SheetHomeInventoryBinding? = null
    private var activeInventoryAdapter: ShopAdapter? = null

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
        (activity as? MainActivity)?.attachScrollListener(binding.scrollHome)

        val nav = findNavController()
        binding.btnStartAdventure.setOnClickListener {
            nav.navigate(R.id.action_home_to_worldmap)
        }

        binding.btnClaimDaily.setOnClickListener {
            viewModel.claimDaily()
        }

        binding.cardHskDictionary.setOnClickListener {
            nav.navigate(R.id.action_home_to_practice)
        }

        binding.cardMerchantShop.setOnClickListener {
            nav.navigate(R.id.action_home_to_shop)
        }

        binding.cardInventoryBag.setOnClickListener {
            showInventoryBottomSheet()
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
                launch { viewModel.inventoryItems.collect(::renderInventorySummary) }
                launch {
                    viewModel.homeMessage.collect { msg ->
                        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                    }
                }
                launch {
                    viewModel.isClaimingDaily.collect { isClaiming ->
                        binding.btnClaimDaily.isEnabled = !isClaiming
                    }
                }
            }
        }
    }

    private fun setupCurrencyIcons() {
        gemAnimator?.stop()
        gemAnimator = SpriteAnimator.startColumnAnimation(
            imageView = binding.ivCurrencyDiamond,
            drawableRes = R.drawable.currency_gems,
            rows = 9,
            cols = 5,
            targetCol = 4,
            startRow = 0,
            frameCount = 9,
            fps = 7,
            scaleFactor = 4,
        )

        coinAnimator?.stop()
        coinAnimator = SpriteAnimator.startColumnAnimation(
            imageView = binding.ivCurrencyCoin,
            drawableRes = R.drawable.currency_coins,
            rows = 12,
            cols = 5,
            targetCol = 2,
            startRow = 0,
            frameCount = 7,
            fps = 8,
            scaleFactor = 4,
        )
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
        petAnimator?.start()
        coinAnimator?.start()
        gemAnimator?.start()
    }

    override fun onPause() {
        super.onPause()
        petAnimator?.stop()
        coinAnimator?.stop()
        gemAnimator?.stop()
    }

    private fun renderState(state: HomeViewModel.UiState) {
        when (state) {
            HomeViewModel.UiState.Loading -> {
                binding.layoutHomeContent.visibility = View.INVISIBLE
                binding.progressHomeLoading.visibility = View.VISIBLE
            }
            is HomeViewModel.UiState.Loaded -> {
                binding.progressHomeLoading.visibility = View.GONE
                binding.layoutHomeContent.visibility = View.VISIBLE
                bindPlayer(state.player, state.isCached)
            }
            is HomeViewModel.UiState.Error -> {
                binding.progressHomeLoading.visibility = View.GONE
                binding.layoutHomeContent.visibility = View.VISIBLE
                Toast.makeText(requireContext(), readableError(state.message), Toast.LENGTH_SHORT).show()
            }
            HomeViewModel.UiState.LoggedOut -> {
                binding.progressHomeLoading.visibility = View.GONE
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
        updateInventorySheetUi()
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

        val drawableRes = SpriteAnimator.resolvePetDrawable(
            requireContext(),
            pet.petType,
            pet.spriteKey,
        )

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
        if (status == null || !status.canClaim) {
            binding.cardDaily.visibility = View.GONE
            return
        }

        binding.cardDaily.visibility = View.VISIBLE
        binding.tvDailyStreak.text = getString(R.string.home_daily_streak_fmt, status.streak)
        binding.tvDailyRewardAmount.text = getString(R.string.home_daily_reward_fmt, status.coinsReward)
        binding.btnClaimDaily.isEnabled = !viewModel.isClaimingDaily.value
        binding.btnClaimDaily.alpha = 1.0f
        binding.btnClaimDaily.text = getString(R.string.home_daily_claim_btn)
    }

    private fun renderInventorySummary(items: List<InventoryItem>) {
        val validItems = items.filter { it.quantity > 0 }
        if (validItems.isEmpty()) {
            binding.tvHomeBagSummary.text = "Tap to view & eat food"
        } else {
            val mealsCount = validItems
                .filter { it.hpRestore > 0 && it.itemType != "elixir" && it.itemType != "potion" }
                .sumOf { it.quantity }
            val potionsCount = validItems
                .filter { it.itemType == "elixir" || it.itemType == "potion" }
                .sumOf { it.quantity }
            val treatsCount = validItems
                .filter { it.itemType == "pet_food" || (it.hpRestore <= 0 && it.affectionBoost > 0) }
                .sumOf { it.quantity }

            val parts = mutableListOf<String>()
            if (mealsCount > 0) parts.add("$mealsCount Meals")
            if (potionsCount > 0) parts.add("$potionsCount Potions")
            if (treatsCount > 0) parts.add("$treatsCount Treats")

            binding.tvHomeBagSummary.text = if (parts.isNotEmpty()) {
                parts.joinToString(" · ")
            } else {
                "${validItems.sumOf { it.quantity }} Items in Bag"
            }
        }
        updateInventorySheetUi()
    }

    private fun showInventoryBottomSheet() {
        activeInventorySheet?.dismiss()

        val ctx = requireContext()
        val sheet = BottomSheetDialog(ctx)
        val sheetBinding = SheetHomeInventoryBinding.inflate(layoutInflater)
        val bagAdapter = ShopAdapter(
            onBuyClicked = {},
            onUseClicked = { item -> viewModel.useInventoryItem(item) },
        )

        sheetBinding.rvHomeInventory.layoutManager = LinearLayoutManager(ctx)
        sheetBinding.rvHomeInventory.adapter = bagAdapter

        sheetBinding.btnSheetGoShop.setOnClickListener {
            sheet.dismiss()
            findNavController().navigate(R.id.action_home_to_shop)
        }

        sheetBinding.btnSheetOpenShop.setOnClickListener {
            sheet.dismiss()
            findNavController().navigate(R.id.action_home_to_shop)
        }

        sheetBinding.btnSheetClose.setOnClickListener {
            sheet.dismiss()
        }

        activeInventorySheet = sheet
        activeInventoryBinding = sheetBinding
        activeInventoryAdapter = bagAdapter

        updateInventorySheetUi()

        sheet.setOnDismissListener {
            if (activeInventorySheet === sheet) {
                activeInventorySheet = null
                activeInventoryBinding = null
                activeInventoryAdapter = null
            }
        }

        sheet.setContentView(sheetBinding.root)
        sheet.show()
    }

    private fun updateInventorySheetUi() {
        val sheetBinding = activeInventoryBinding ?: return
        val bagAdapter = activeInventoryAdapter ?: return

        val stats = (viewModel.state.value as? HomeViewModel.UiState.Loaded)?.player?.stats
        if (stats != null) {
            sheetBinding.tvSheetBagHp.text = "❤️ ${stats.hp}/${stats.maxHp} HP"
        }

        val bagEntries = viewModel.inventoryItems.value
            .filter { it.quantity > 0 }
            .map { ShopUiItem.BagEntry(it) }

        bagAdapter.submitList(bagEntries)

        if (bagEntries.isEmpty()) {
            sheetBinding.rvHomeInventory.visibility = View.GONE
            sheetBinding.llHomeBagEmpty.visibility = View.VISIBLE
        } else {
            sheetBinding.rvHomeInventory.visibility = View.VISIBLE
            sheetBinding.llHomeBagEmpty.visibility = View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        activeInventorySheet?.dismiss()
        activeInventorySheet = null
        activeInventoryBinding = null
        activeInventoryAdapter = null
        petAnimator?.stop()
        petAnimator = null
        coinAnimator?.stop()
        coinAnimator = null
        gemAnimator?.stop()
        gemAnimator = null
        _binding = null
    }
}
