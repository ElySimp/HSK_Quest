package com.faldo.hsk_quest.ui.pet

import android.content.res.ColorStateList
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.faldo.hsk_quest.MainActivity
import com.faldo.hsk_quest.R
import com.faldo.hsk_quest.data.model.PetItem
import com.faldo.hsk_quest.data.model.RpsResponse
import com.faldo.hsk_quest.databinding.FragmentPetBinding
import com.faldo.hsk_quest.databinding.SheetRpsGameBinding
import com.faldo.hsk_quest.util.SpriteAnimator
import com.faldo.hsk_quest.util.appContainer
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Companion Pet Sanctuary.
 * Handles Tamagotchi care, multi-frame sprite looping, tap-to-pet reactions,
 * food inventory bag feeding, Rock-Paper-Scissors minigames, and collection equipping.
 */
class PetFragment : Fragment() {

    private var _binding: FragmentPetBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PetViewModel by viewModels {
        PetViewModel.factory(
            appContainer.petRepository,
            appContainer.shopRepository,
            appContainer.playerRepository,
        )
    }

    private var petAdapter: PetAdapter? = null
    private var spriteAnimator: SpriteAnimator? = null
    private var rpsBottomSheet: BottomSheetDialog? = null
    private var feedBottomSheet: BottomSheetDialog? = null
    private var actionAnimJob: Job? = null
    private var isPlayingActionRow: Boolean = false
    private var tapSpeechIndex: Int = 0

    private val tapSpeeches = listOf(
        "✨ 谢谢主人! (Thank you, Master!)",
        "🐉 加油! Let's conquer HSK together!",
        "🍙 好饿呀~ Do you have dumplings?",
        "💖 摸摸头~ Being petted feels great!",
        "🛡️ 我会保护你! I'll guard you in battle!",
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentPetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupButtons()
        observeViewModel()
        (activity as? MainActivity)?.attachScrollListener(binding.petRoot)

        viewModel.loadPetData()
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadPetData()
    }

    private fun setupRecyclerView() {
        petAdapter = PetAdapter { selectedPet ->
            viewModel.equipPet(selectedPet.id)
        }
        binding.rvPetCollection.adapter = petAdapter
    }

    private fun setupButtons() {
        binding.btnGotoGacha.setOnClickListener {
            findNavController().navigate(R.id.gachaFragment)
        }

        binding.btnGoShop.setOnClickListener {
            findNavController().navigate(R.id.shopFragment)
        }

        binding.btnGoGacha.setOnClickListener {
            findNavController().navigate(R.id.gachaFragment)
        }

        binding.ivPetSprite.setOnClickListener {
            val speech = tapSpeeches[tapSpeechIndex % tapSpeeches.size]
            tapSpeechIndex++
            playPetActionReaction(speech, durationMs = 2200L)
        }

        binding.btnPlayRps.setOnClickListener {
            showRpsDialog()
        }

        binding.btnFeedPet.setOnClickListener {
            showFeedPickerSheet()
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.activePet.collect { pet ->
                        updatePetVisibility(pet, viewModel.isInitialHydrated.value)
                    }
                }

                launch {
                    viewModel.isInitialHydrated.collect { isHydrated ->
                        updatePetVisibility(viewModel.activePet.value, isHydrated)
                    }
                }

                launch {
                    viewModel.petList.collect { list ->
                        petAdapter?.submitList(list)
                    }
                }

                launch {
                    viewModel.player.collect { player ->
                        if (player != null) {
                            binding.tvPetPlayerHp.text = "❤️ ${player.stats.hp}/${player.stats.maxHp}"
                            binding.tvPetCoins.text = "🪙 %,d".format(player.stats.coins)
                            binding.tvPetDiamonds.text = "💎 %,d".format(player.stats.diamonds)
                        }
                    }
                }

                launch {
                    viewModel.foodBag.collect { bag ->
                        val totalDishes = bag.sumOf { it.quantity }
                        if (totalDishes > 0) {
                            binding.btnFeedPet.text = "🦴 Feed Companion (Bag: $totalDishes)"
                            binding.tvFoodBagSubtitle.text =
                                "🎒 Bag: $totalDishes treat(s) ready to serve!"
                        } else {
                            binding.btnFeedPet.text = "🦴 Feed Companion (Choose Treat)"
                            binding.tvFoodBagSubtitle.text =
                                "🎒 Bag: 0 treats · Or quick-buy Crispy Spirit Bone (15 🪙)"
                        }
                    }
                }

                launch {
                    viewModel.reactionEvent.collect { reactionText ->
                        playPetActionReaction(reactionText, durationMs = 2500L)
                    }
                }

                launch {
                    viewModel.actionFeedback.collect { message ->
                        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun updatePetVisibility(pet: PetItem?, isHydrated: Boolean) {
        if (!isHydrated && pet == null) {
            binding.pbPetLoading.visibility = View.VISIBLE
            binding.layoutNoPet.visibility = View.GONE
            binding.layoutHasPet.visibility = View.GONE
            return
        }
        binding.pbPetLoading.visibility = View.GONE
        renderActivePet(pet)
    }

    private fun playPetActionReaction(speech: String, durationMs: Long) {
        val pet = viewModel.activePet.value ?: return
        if (_binding == null) return

        binding.tvPetSpeech.text = speech

        // Play celebratory bounce + hop on the sprite ImageView
        binding.ivPetSprite.animate().cancel()
        binding.ivPetSprite.scaleX = 0.85f
        binding.ivPetSprite.scaleY = 0.85f
        binding.ivPetSprite.translationY = 0f
        binding.ivPetSprite.animate()
            .scaleX(1.14f)
            .scaleY(1.14f)
            .translationY(-18f)
            .setDuration(180L)
            .setInterpolator(OvershootInterpolator(2.0f))
            .withEndAction {
                if (_binding != null) {
                    binding.ivPetSprite.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .translationY(0f)
                        .setDuration(220L)
                        .start()
                }
            }
            .start()

        val drawableRes = SpriteAnimator.resolvePetDrawable(
            requireContext(),
            pet.petType,
            pet.spriteKey,
        )

        actionAnimJob?.cancel()
        isPlayingActionRow = true
        spriteAnimator?.stop()
        // Row 1 = Excited / Action animation, 4 frames
        spriteAnimator = SpriteAnimator.startAnimation(
            imageView = binding.ivPetSprite,
            drawableRes = drawableRes,
            rows = 3,
            cols = 4,
            targetRow = 1,
            frameCount = 4,
            fps = 8,
        )

        actionAnimJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(durationMs)
            isPlayingActionRow = false
            if (_binding != null) {
                startIdleOrSleepSprite(viewModel.activePet.value)
            }
        }
    }

    private fun renderActivePet(pet: PetItem?) {
        if (pet == null) {
            actionAnimJob?.cancel()
            isPlayingActionRow = false
            spriteAnimator?.stop()
            spriteAnimator = null
            binding.layoutNoPet.visibility = View.VISIBLE
            binding.layoutHasPet.visibility = View.GONE
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

        val buffDesc = when (pet.buffType) {
            "shield" -> "Buff: 🛡️ Shield (Absorbs 1 monster attack on wrong answer)"
            "time_extender" -> "Buff: ⏳ Time Extender (+3s to Critical Hit window)"
            "damage_boost" -> "Buff: ⚔️ Damage Boost (+10% base damage)"
            else -> "Buff: None"
        }
        binding.tvPetBuff.text = buffDesc

        val affVal = pet.affection.toInt().coerceIn(0, 100)
        binding.tvAffectionVal.text = "$affVal / 100"
        binding.progressAffection.progress = affVal

        if (pet.isSleeping) {
            binding.tvPetMood.text = "💤 Sleeping soundly... (Affection is low)"
            binding.tvPetMood.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.hq_text_muted)
            )
        } else {
            binding.tvPetMood.text = "✨ Feeling energetic and happy!"
            binding.tvPetMood.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.hq_jade)
            )
        }

        // Only reset sprite animation to idle/sleep if not currently playing an action reaction
        if (!isPlayingActionRow) {
            startIdleOrSleepSprite(pet)
        }
    }

    private fun startIdleOrSleepSprite(pet: PetItem?) {
        if (pet == null || _binding == null) return
        val drawableRes = SpriteAnimator.resolvePetDrawable(
            requireContext(),
            pet.petType,
            pet.spriteKey,
        )

        spriteAnimator?.stop()
        if (pet.isSleeping) {
            // Row 2 = Sleep animation, 4 frames
            spriteAnimator = SpriteAnimator.startAnimation(
                imageView = binding.ivPetSprite,
                drawableRes = drawableRes,
                rows = 3,
                cols = 4,
                targetRow = 2,
                frameCount = 4,
                fps = 4,
            )
        } else {
            // Row 0 = Idle animation, 4 frames
            spriteAnimator = SpriteAnimator.startAnimation(
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

    private fun showFeedPickerSheet() {
        feedBottomSheet?.dismiss()
        val ctx = requireContext()
        val sheet = BottomSheetDialog(ctx)

        val petName = viewModel.activePet.value?.name ?: "Companion"
        val bagItems = viewModel.foodBag.value
        val catalogMap = viewModel.foodCatalog.value.associateBy { it.id }
        val totalDishes = bagItems.sumOf { it.quantity }

        fun dp(value: Int): Int = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics,
        ).toInt()

        val rootScroll = ScrollView(ctx).apply {
            isFillViewport = true
            setBackgroundResource(R.drawable.bg_card_raised)
        }

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(20), dp(20), dp(24))
        }

        // Handle bar
        val handle = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(4)).apply {
                bottomMargin = dp(14)
            }
            setBackgroundResource(R.drawable.bg_card)
        }
        container.addView(handle)

        // Header title
        val tvTitle = TextView(ctx).apply {
            text = "🦴 Choose Treat for $petName"
            setTextColor(ContextCompat.getColor(ctx, R.color.hq_gold))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        container.addView(tvTitle)

        // Subtitle
        val tvSub = TextView(ctx).apply {
            text = "🎒 Treats in Bag: $totalDishes · Boosts Companion Pet Affection ❤️"
            setTextColor(ContextCompat.getColor(ctx, R.color.hq_text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(4)
                bottomMargin = dp(14)
            }
        }
        container.addView(tvSub)

        if (bagItems.isEmpty()) {
            val tvEmpty = TextView(ctx).apply {
                text = "Your treat bag is empty!\nUse a Quick Crispy Spirit Bone below or visit the Merchant Shop."
                setTextColor(ContextCompat.getColor(ctx, R.color.hq_text_muted))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.bg_card)
                setPadding(dp(16), dp(18), dp(16), dp(18))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    bottomMargin = dp(12)
                }
            }
            container.addView(tvEmpty)
        } else {
            for (item in bagItems) {
                val cat = catalogMap[item.itemId]
                val icon = item.iconEmoji.ifBlank { cat?.iconEmoji ?: "🦴" }
                val aff = if (item.affectionBoost > 0) item.affectionBoost else (cat?.affectionBoost ?: 15)
                val hp = if (item.hpRestore > 0) item.hpRestore else (cat?.hpRestore ?: 0)
                val statSummary = if (hp > 0) "+$aff ❤️ · +$hp HP" else "+$aff ❤️ Affection"

                val dishBtn = MaterialButton(ctx).apply {
                    isAllCaps = false
                    text = "$icon ${item.name} (x${item.quantity}) — $statSummary"
                    setTextColor(ContextCompat.getColor(ctx, R.color.hq_text_primary))
                    backgroundTintList = ColorStateList.valueOf(
                        ContextCompat.getColor(ctx, R.color.hq_surface_high)
                    )
                    strokeColor = ColorStateList.valueOf(
                        ContextCompat.getColor(ctx, R.color.hq_stroke_gold)
                    )
                    strokeWidth = dp(1)
                    cornerRadius = dp(12)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        bottomMargin = dp(8)
                    }
                    setOnClickListener {
                        sheet.dismiss()
                        viewModel.feedPet(item.itemId)
                    }
                }
                container.addView(dishBtn)
            }
        }

        // Footer button 1: Quick Buy & Feed Crispy Spirit Bone
        val btnQuickSnack = MaterialButton(ctx).apply {
            isAllCaps = false
            text = "🦴 Quick Buy & Feed Crispy Spirit Bone (15 🪙 · +15 ❤️)"
            setTextColor(ContextCompat.getColor(ctx, R.color.hq_bg_deep))
            backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(ctx, R.color.hq_gold)
            )
            cornerRadius = dp(14)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(6)
            }
            setOnClickListener {
                sheet.dismiss()
                viewModel.feedPet("quick_snack")
            }
        }
        container.addView(btnQuickSnack)

        // Footer button 2: Visit Merchant Shop
        val btnVisitShop = MaterialButton(ctx).apply {
            isAllCaps = false
            text = "🛒 Visit Merchant Shop"
            setTextColor(ContextCompat.getColor(ctx, R.color.hq_gold))
            backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(ctx, R.color.hq_surface)
            )
            strokeColor = ColorStateList.valueOf(
                ContextCompat.getColor(ctx, R.color.hq_stroke_gold)
            )
            strokeWidth = dp(1)
            cornerRadius = dp(14)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(6)
            }
            setOnClickListener {
                sheet.dismiss()
                findNavController().navigate(R.id.shopFragment)
            }
        }
        container.addView(btnVisitShop)

        rootScroll.addView(container)
        sheet.setContentView(rootScroll)
        feedBottomSheet = sheet
        sheet.show()
    }

    private fun showRpsDialog() {
        rpsBottomSheet?.dismiss()
        val sheet = BottomSheetDialog(requireContext())
        val sheetBinding = SheetRpsGameBinding.inflate(layoutInflater)
        sheet.setContentView(sheetBinding.root)

        // Single collector tied to the BottomSheetDialog lifecycle to prevent coroutine leaks
        val rpsCollectJob = viewLifecycleOwner.lifecycleScope.launch {
            viewModel.rpsResult.collect { result ->
                renderRpsResult(sheetBinding, result)
            }
        }

        sheet.setOnDismissListener {
            rpsCollectJob.cancel()
        }

        fun onPick(choice: String) {
            sheetBinding.layoutChoices.visibility = View.GONE
            sheetBinding.layoutOutcome.visibility = View.VISIBLE
            sheetBinding.btnPlayAgainRps.visibility = View.GONE
            sheetBinding.tvRpsVs.text = ""
            sheetBinding.tvRpsTitle.text = "Rock... Paper... Scissors! 🎲"
            sheetBinding.tvRpsMessage.text = "Your pet is thinking..."
            viewModel.playRps(choice)
        }

        sheetBinding.btnChoiceRock.setOnClickListener { onPick("rock") }
        sheetBinding.btnChoicePaper.setOnClickListener { onPick("paper") }
        sheetBinding.btnChoiceScissors.setOnClickListener { onPick("scissors") }

        sheetBinding.btnPlayAgainRps.setOnClickListener {
            sheetBinding.layoutChoices.visibility = View.VISIBLE
            sheetBinding.layoutOutcome.visibility = View.GONE
            sheetBinding.btnPlayAgainRps.visibility = View.GONE
        }

        sheetBinding.btnCloseRps.setOnClickListener {
            sheet.dismiss()
        }

        rpsBottomSheet = sheet
        sheet.show()
    }

    private fun renderRpsResult(sheetBinding: SheetRpsGameBinding, result: RpsResponse) {
        val pEmoji = when (result.playerChoice) {
            "rock" -> "🪨"
            "paper" -> "📄"
            else -> "✂️"
        }
        val petEmoji = when (result.petChoice) {
            "rock" -> "🪨"
            "paper" -> "📄"
            else -> "✂️"
        }

        sheetBinding.tvRpsVs.text =
            "You: $pEmoji ${result.playerChoice.uppercase()}  vs  Pet: $petEmoji ${result.petChoice.uppercase()}"

        when (result.outcome) {
            "win" -> {
                sheetBinding.tvRpsTitle.text = "VICTORY! 🎉"
                sheetBinding.tvRpsTitle.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.hq_gold)
                )
                playPetActionReaction("🎉 好棒! You won! +${result.affectionGained.toInt()} ❤️", 2200L)
            }
            "tie" -> {
                sheetBinding.tvRpsTitle.text = "DRAW! 🤝"
                sheetBinding.tvRpsTitle.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.hq_text_primary)
                )
                playPetActionReaction("🤝 平局! Great minds think alike! +${result.affectionGained.toInt()} ❤️", 2200L)
            }
            else -> {
                sheetBinding.tvRpsTitle.text = "PET WON! 🐾"
                sheetBinding.tvRpsTitle.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.hq_text_muted)
                )
            }
        }

        sheetBinding.tvRpsMessage.text = result.message
        sheetBinding.btnPlayAgainRps.visibility = View.VISIBLE
        sheetBinding.btnCloseRps.text = "Close"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        actionAnimJob?.cancel()
        actionAnimJob = null
        isPlayingActionRow = false
        spriteAnimator?.stop()
        spriteAnimator = null
        rpsBottomSheet?.dismiss()
        rpsBottomSheet = null
        feedBottomSheet?.dismiss()
        feedBottomSheet = null
        petAdapter = null
        _binding = null
    }
}
