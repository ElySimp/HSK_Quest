package com.faldo.hsk_quest.ui.pet

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
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
import androidx.recyclerview.widget.GridLayoutManager
import com.faldo.hsk_quest.MainActivity
import com.faldo.hsk_quest.R
import com.faldo.hsk_quest.data.model.BlackjackStateResponse
import com.faldo.hsk_quest.data.model.PetItem
import com.faldo.hsk_quest.data.model.RpsResponse
import com.faldo.hsk_quest.databinding.FragmentPetBinding
import com.faldo.hsk_quest.databinding.SheetBlackjackGameBinding
import com.faldo.hsk_quest.databinding.SheetPetInventoryBinding
import com.faldo.hsk_quest.databinding.SheetPlayPickerBinding
import com.faldo.hsk_quest.databinding.SheetRpsGameBinding
import com.faldo.hsk_quest.engine.PetBuffCatalog
import com.faldo.hsk_quest.util.Sfx
import com.faldo.hsk_quest.util.SpriteAnimator
import com.faldo.hsk_quest.util.appContainer
import com.faldo.hsk_quest.util.showHqToast
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Companion Pet Sanctuary.
 * Handles Tamagotchi care, multi-frame sprite looping, tap-to-pet reactions,
 * food inventory bag feeding, Rock-Paper-Scissors & Blackjack mini-games,
 * and a rarity-sorted Pet Inventory overlay.
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

    private var inventorySheetAdapter: PetAdapter? = null
    private var inventorySheetCountView: TextView? = null
    private var petInventoryBottomSheet: BottomSheetDialog? = null
    private var spriteAnimator: SpriteAnimator? = null
    private var playPickerBottomSheet: BottomSheetDialog? = null
    private var rpsBottomSheet: BottomSheetDialog? = null
    private var blackjackBottomSheet: BottomSheetDialog? = null
    private var feedBottomSheet: BottomSheetDialog? = null
    private val feedSheetAnimators = mutableListOf<SpriteAnimator>()
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

        setupButtons()
        observeViewModel()
        (activity as? MainActivity)?.attachScrollListener(binding.petRoot)

        viewModel.loadPetData()
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadPetData()
    }

    private fun setupButtons() {
        val subScreenNavOptions = androidx.navigation.NavOptions.Builder()
            .setEnterAnim(R.anim.screen_enter)
            .setExitAnim(R.anim.screen_exit)
            .setPopEnterAnim(R.anim.screen_pop_enter)
            .setPopExitAnim(R.anim.screen_pop_exit)
            .build()

        binding.btnGotoGacha.setOnClickListener {
            findNavController().navigate(R.id.gachaFragment, null, subScreenNavOptions)
        }

        binding.btnGoShop.setOnClickListener {
            findNavController().navigate(R.id.shopFragment, null, subScreenNavOptions)
        }

        binding.btnGoGacha.setOnClickListener {
            findNavController().navigate(R.id.gachaFragment, null, subScreenNavOptions)
        }

        binding.ivPetSprite.setOnClickListener {
            val speech = tapSpeeches[tapSpeechIndex % tapSpeeches.size]
            tapSpeechIndex++
            appContainer.soundPlayer.play(Sfx.HIT)
            playPetActionReaction(speech, durationMs = 2200L)
        }

        binding.btnPlayPet.setOnClickListener {
            showPlayPickerSheet()
        }

        binding.btnFeedPet.setOnClickListener {
            showFeedPickerSheet()
        }

        binding.cardPetInventory.setOnClickListener {
            showPetInventorySheet()
        }

        binding.btnOpenPetInventory.setOnClickListener {
            showPetInventorySheet()
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
                        val count = list.size
                        val countLabel = "$count Spirit${if (count == 1) "" else "s"}"
                        binding.tvRosterCount.text = countLabel
                        val topPet = list.firstOrNull()
                        binding.tvInventorySummary.text = if (topPet != null) {
                            "Highest rarity: ${topPet.rarity.replaceFirstChar { it.uppercase() }} (${topPet.name}) · Tap to equip."
                        } else {
                            "View all owned companions sorted by rarity and swap active buffs."
                        }
                        inventorySheetAdapter?.submitList(list)
                        inventorySheetCountView?.text = countLabel
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
                            binding.btnFeedPet.text = "🦴 Feed ($totalDishes)"
                            binding.tvFoodBagSubtitle.text =
                                "🎮 RPS or Blackjack · 🎒 Bag: $totalDishes treat(s) ready!"
                        } else {
                            binding.btnFeedPet.text = "🦴 Feed"
                            binding.tvFoodBagSubtitle.text =
                                "🎮 RPS or Blackjack · 🎒 Bag: 0 treats (Quick-buy bone 15 🪙)"
                        }
                    }
                }

                launch {
                    viewModel.reactionEvent.collect { reactionText ->
                        appContainer.soundPlayer.play(Sfx.POTION)
                        playPetActionReaction(reactionText, durationMs = 2500L)
                    }
                }

                launch {
                    viewModel.actionFeedback.collect { message ->
                        showHqToast(message)
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

        val (rarityStr, rarityColorRes, stageBorderRes) = when (pet.rarity.lowercase()) {
            "secret" -> Triple("🔮 Secret", R.color.hq_crimson, R.drawable.bg_card_gold_border)
            "legendary" -> Triple("🟡 Legendary", R.color.hq_gold, R.drawable.bg_card_gold_border)
            "epic" -> Triple("🟣 Epic", R.color.hq_violet, R.drawable.bg_card_epic_border)
            "rare" -> Triple("🔵 Rare", R.color.hq_sky, R.drawable.bg_card_rare_border)
            else -> Triple("🟢 Common", R.color.hq_jade, R.drawable.bg_card_common_border)
        }
        binding.tvPetRarity.text = rarityStr
        binding.tvPetRarity.setTextColor(ContextCompat.getColor(requireContext(), rarityColorRes))
        binding.cardPetStage.setBackgroundResource(stageBorderRes)

        val buffSpec = PetBuffCatalog.resolveSpec(pet)
        binding.tvPetBuff.text = if (pet.isSleeping) {
            "💤 Buff Resting (Feed ≥ 25 ❤️): ${buffSpec.title} — ${buffSpec.summary}"
        } else {
            "✨ ${buffSpec.title}: ${buffSpec.summary}"
        }
        binding.tvPetBuff.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (pet.isSleeping) R.color.hq_crimson else R.color.hq_jade,
            )
        )

        val affVal = pet.affection.toInt().coerceIn(0, 100)
        binding.tvAffectionVal.text = "$affVal / 100"
        binding.progressAffection.progress = affVal

        if (pet.isSleeping) {
            binding.tvPetMood.text = "💤 Sleeping soundly... (Feed ≥ 25 Affection to awaken buff)"
            binding.tvPetMood.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.hq_crimson)
            )
        } else {
            binding.tvPetMood.text = "✨ Feeling energetic and happy!"
            binding.tvPetMood.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.hq_jade)
            )
        }

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

    private fun showPlayPickerSheet() {
        playPickerBottomSheet?.dismiss()
        val sheet = BottomSheetDialog(requireContext())
        val pickerBinding = SheetPlayPickerBinding.inflate(layoutInflater)
        sheet.setContentView(pickerBinding.root)

        val petName = viewModel.activePet.value?.name ?: "your companion"
        pickerBinding.tvPlayPickerSubtitle.text =
            "Choose a mini-game to play with $petName!"

        pickerBinding.cardPickRps.setOnClickListener {
            sheet.dismiss()
            showRpsDialog()
        }

        pickerBinding.cardPickBlackjack.setOnClickListener {
            val coins = viewModel.player.value?.stats?.coins ?: 0
            if (coins < 5) {
                Toast.makeText(
                    requireContext(),
                    "Need 5 Coins to play Lucky Blackjack!",
                    Toast.LENGTH_SHORT,
                ).show()
                return@setOnClickListener
            }
            sheet.dismiss()
            showBlackjackDialog()
        }

        pickerBinding.btnClosePlayPicker.setOnClickListener {
            sheet.dismiss()
        }

        sheet.setOnDismissListener {
            if (playPickerBottomSheet === sheet) {
                playPickerBottomSheet = null
            }
        }
        playPickerBottomSheet = sheet
        sheet.show()
    }

    private fun showBlackjackDialog() {
        blackjackBottomSheet?.dismiss()
        viewModel.resetBlackjack()

        val sheet = BottomSheetDialog(requireContext())
        val bjBinding = SheetBlackjackGameBinding.inflate(layoutInflater)
        sheet.setContentView(bjBinding.root)

        val petName = viewModel.activePet.value?.name ?: "Companion"
        bjBinding.tvBjDealerLabel.text = "$petName's Hand"

        var lastSettledTokenOrStatus: String? = null

        val collectJob = viewLifecycleOwner.lifecycleScope.launch {
            launch {
                viewModel.player.collect { player ->
                    val coins = player?.stats?.coins ?: 0
                    bjBinding.tvBjCoins.text = "🪙 %,d".format(coins)
                }
            }
            launch {
                viewModel.isBlackjackBusy.collect { busy ->
                    bjBinding.btnBjHit.isEnabled = !busy
                    bjBinding.btnBjStand.isEnabled = !busy
                    bjBinding.btnBjPlayAgain.isEnabled = !busy
                }
            }
            launch {
                viewModel.blackjackState.collect { state ->
                    if (state != null) {
                        renderBlackjackState(bjBinding, state, lastSettledTokenOrStatus) { soundKey ->
                            lastSettledTokenOrStatus = soundKey
                        }
                    } else {
                        bjBinding.tvBjDealerScore.text = "Dealing..."
                        bjBinding.tvBjPlayerScore.text = "Dealing..."
                        bjBinding.tvBjMessage.text = "Shuffling celestial deck..."
                        bjBinding.layoutBjPlayActions.visibility = View.GONE
                        bjBinding.btnBjPlayAgain.visibility = View.GONE
                    }
                }
            }
        }

        sheet.setOnDismissListener {
            collectJob.cancel()
            viewModel.resetBlackjack()
            if (blackjackBottomSheet === sheet) {
                blackjackBottomSheet = null
            }
        }

        bjBinding.btnBjHit.setOnClickListener { viewModel.hitBlackjack() }
        bjBinding.btnBjStand.setOnClickListener { viewModel.standBlackjack() }
        bjBinding.btnBjPlayAgain.setOnClickListener {
            lastSettledTokenOrStatus = null
            viewModel.startBlackjack()
        }
        bjBinding.btnBjClose.setOnClickListener { sheet.dismiss() }

        blackjackBottomSheet = sheet
        sheet.show()
        viewModel.startBlackjack()
    }

    private fun renderBlackjackState(
        bjBinding: SheetBlackjackGameBinding,
        state: BlackjackStateResponse,
        lastSoundKey: String?,
        markSoundKey: (String) -> Unit,
    ) {
        val ctx = requireContext()
        bjBinding.tvBjCoins.text = "🪙 %,d".format(state.remainingCoins)
        bjBinding.tvBjPlayerScore.text = "Score: ${state.playerScore}"
        val dealerHasHidden = state.dealerCards.any { it == "??" }
        bjBinding.tvBjDealerScore.text = if (dealerHasHidden) {
            "Showing: ${state.dealerScore} + ?"
        } else {
            "Score: ${state.dealerScore}"
        }

        renderBlackjackCardsRow(bjBinding.layoutBjDealerCards, state.dealerCards)
        renderBlackjackCardsRow(bjBinding.layoutBjPlayerCards, state.playerCards)

        bjBinding.tvBjMessage.text = state.message
        val isPlaying = state.status == "playing"
        bjBinding.layoutBjPlayActions.visibility = if (isPlaying) View.VISIBLE else View.GONE
        bjBinding.btnBjPlayAgain.visibility = if (isPlaying) View.GONE else View.VISIBLE

        val msgColorRes = when (state.status) {
            "player_win" -> R.color.hq_gold
            "push" -> R.color.hq_sky
            "pet_win", "player_bust" -> R.color.hq_crimson
            else -> R.color.hq_text_primary
        }
        bjBinding.tvBjMessage.setTextColor(ContextCompat.getColor(ctx, msgColorRes))

        if (!isPlaying) {
            val soundKey = "${state.status}_${state.playerCards.joinToString()}_${state.dealerCards.joinToString()}"
            if (lastSoundKey != soundKey) {
                markSoundKey(soundKey)
                val sfx = when (state.status) {
                    "player_win" -> Sfx.COIN
                    "push" -> Sfx.CORRECT
                    else -> Sfx.WRONG
                }
                appContainer.soundPlayer.play(sfx)
            }
        }
    }

    private fun renderBlackjackCardsRow(container: LinearLayout, cards: List<String>) {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        container.removeAllViews()
        cards.forEachIndexed { index, cardLabel ->
            val isHidden = cardLabel == "??"
            val isRed = cardLabel.contains("♥") || cardLabel.contains("♦")
            val cardView = TextView(ctx).apply {
                text = if (isHidden) "?" else cardLabel
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(
                    ContextCompat.getColor(
                        ctx,
                        when {
                            isHidden -> R.color.hq_gold
                            isRed -> R.color.hq_crimson
                            else -> R.color.hq_text_primary
                        }
                    )
                )
                setBackgroundResource(
                    if (isHidden) R.drawable.bg_stat_box else R.drawable.bg_card_raised
                )
                layoutParams = LinearLayout.LayoutParams(
                    (46 * density).toInt(),
                    (62 * density).toInt(),
                ).apply {
                    marginEnd = if (index < cards.size - 1) (8 * density).toInt() else 0
                }
            }
            container.addView(cardView)
        }
    }

    private fun showFeedPickerSheet() {
        feedBottomSheet?.dismiss()
        feedSheetAnimators.forEach { it.stop() }
        feedSheetAnimators.clear()

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

        val handle = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(4)).apply {
                bottomMargin = dp(14)
            }
            setBackgroundResource(R.drawable.bg_card)
        }
        container.addView(handle)

        val tvTitle = TextView(ctx).apply {
            text = "🦴 Choose Treat for $petName"
            setTextColor(ContextCompat.getColor(ctx, R.color.hq_gold))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        container.addView(tvTitle)

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

                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundResource(R.drawable.bg_card)
                    val pad = dp(10)
                    setPadding(pad, pad, pad, pad)
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

                val iconBox = FrameLayout(ctx).apply {
                    setBackgroundResource(R.drawable.bg_stat_box)
                    layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                }
                val spriteMeta = SpriteAnimator.resolveShopItemSprite(
                    ctx,
                    item.spriteRes ?: cat?.spriteRes,
                    item.itemId,
                )
                if (spriteMeta != null) {
                    val iv = ImageView(ctx).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        layoutParams = FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER)
                    }
                    iconBox.addView(iv)
                    feedSheetAnimators.add(
                        SpriteAnimator.startHorizontalStripAnimation(
                            imageView = iv,
                            drawableRes = spriteMeta.drawableRes,
                            cols = spriteMeta.cols,
                            fps = 8,
                            scaleFactor = 3,
                        )
                    )
                } else {
                    val tvEmoji = TextView(ctx).apply {
                        text = icon
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                        gravity = Gravity.CENTER
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            Gravity.CENTER,
                        )
                    }
                    iconBox.addView(tvEmoji)
                }
                row.addView(iconBox)

                val infoCol = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f,
                    ).apply {
                        marginStart = dp(10)
                    }
                }
                val tvTreatName = TextView(ctx).apply {
                    text = "${item.name} (x${item.quantity})"
                    setTextColor(ContextCompat.getColor(ctx, R.color.hq_text_primary))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    typeface = Typeface.DEFAULT_BOLD
                }
                val tvTreatStat = TextView(ctx).apply {
                    text = statSummary
                    setTextColor(ContextCompat.getColor(ctx, R.color.hq_gold))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                }
                infoCol.addView(tvTreatName)
                infoCol.addView(tvTreatStat)
                row.addView(infoCol)

                val btnFeedRow = MaterialButton(ctx).apply {
                    isAllCaps = false
                    text = "Feed"
                    setTextColor(ContextCompat.getColor(ctx, R.color.hq_bg_deep))
                    backgroundTintList = ColorStateList.valueOf(
                        ContextCompat.getColor(ctx, R.color.hq_gold)
                    )
                    cornerRadius = dp(10)
                    minHeight = dp(34)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        dp(36),
                    )
                    setOnClickListener {
                        sheet.dismiss()
                        viewModel.feedPet(item.itemId)
                    }
                }
                row.addView(btnFeedRow)

                container.addView(row)
            }
        }

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
        sheet.setOnDismissListener {
            feedSheetAnimators.forEach { it.stop() }
            feedSheetAnimators.clear()
        }
        feedBottomSheet = sheet
        sheet.show()
    }

    private fun showRpsDialog() {
        rpsBottomSheet?.dismiss()
        val sheet = BottomSheetDialog(requireContext())
        val sheetBinding = SheetRpsGameBinding.inflate(layoutInflater)
        sheet.setContentView(sheetBinding.root)

        val rpsCollectJob = viewLifecycleOwner.lifecycleScope.launch {
            viewModel.rpsResult.collect { result ->
                renderRpsResult(sheetBinding, result)
            }
        }

        sheet.setOnDismissListener {
            rpsCollectJob.cancel()
            if (rpsBottomSheet === sheet) {
                rpsBottomSheet = null
            }
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
                appContainer.soundPlayer.play(if (result.coinsDropped > 0) Sfx.COIN else Sfx.CORRECT)
                sheetBinding.tvRpsTitle.text = "VICTORY! 🎉"
                sheetBinding.tvRpsTitle.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.hq_gold)
                )
                playPetActionReaction("🎉 好棒! You won! +${result.affectionGained.toInt()} ❤️", 2200L)
            }
            "tie" -> {
                appContainer.soundPlayer.play(Sfx.CORRECT)
                sheetBinding.tvRpsTitle.text = "DRAW! 🤝"
                sheetBinding.tvRpsTitle.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.hq_text_primary)
                )
                playPetActionReaction("🤝 平局! Great minds think alike! +${result.affectionGained.toInt()} ❤️", 2200L)
            }
            else -> {
                appContainer.soundPlayer.play(Sfx.WRONG)
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

    private fun showPetInventorySheet() {
        petInventoryBottomSheet?.dismiss()
        val sheet = BottomSheetDialog(requireContext())
        val sheetBinding = SheetPetInventoryBinding.inflate(layoutInflater)
        sheet.setContentView(sheetBinding.root)

        val adapter = PetAdapter { selectedPet ->
            viewModel.equipPet(selectedPet.id)
            sheet.dismiss()
        }
        sheetBinding.rvInventoryPets.layoutManager = GridLayoutManager(requireContext(), 2)
        sheetBinding.rvInventoryPets.adapter = adapter

        val currentPets = viewModel.petList.value
        val count = currentPets.size
        val countLabel = "$count Spirit${if (count == 1) "" else "s"}"
        sheetBinding.tvSheetInventoryCount.text = countLabel
        sheetBinding.tvSheetInventoryEmpty.visibility =
            if (currentPets.isEmpty()) View.VISIBLE else View.GONE
        sheetBinding.rvInventoryPets.visibility =
            if (currentPets.isEmpty()) View.GONE else View.VISIBLE
        adapter.submitList(currentPets)

        inventorySheetAdapter = adapter
        inventorySheetCountView = sheetBinding.tvSheetInventoryCount

        sheetBinding.btnClosePetInventory.setOnClickListener {
            sheet.dismiss()
        }
        sheet.setOnDismissListener {
            if (inventorySheetAdapter === adapter) {
                inventorySheetAdapter = null
            }
            if (inventorySheetCountView === sheetBinding.tvSheetInventoryCount) {
                inventorySheetCountView = null
            }
            if (petInventoryBottomSheet === sheet) {
                petInventoryBottomSheet = null
            }
        }

        petInventoryBottomSheet = sheet
        sheet.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        actionAnimJob?.cancel()
        actionAnimJob = null
        isPlayingActionRow = false
        spriteAnimator?.stop()
        spriteAnimator = null
        petInventoryBottomSheet?.dismiss()
        petInventoryBottomSheet = null
        inventorySheetAdapter = null
        inventorySheetCountView = null
        playPickerBottomSheet?.dismiss()
        playPickerBottomSheet = null
        blackjackBottomSheet?.dismiss()
        blackjackBottomSheet = null
        rpsBottomSheet?.dismiss()
        rpsBottomSheet = null
        feedSheetAnimators.forEach { it.stop() }
        feedSheetAnimators.clear()
        feedBottomSheet?.dismiss()
        feedBottomSheet = null
        _binding = null
    }
}
