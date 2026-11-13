package com.faldo.hsk_quest.ui.gacha

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.faldo.hsk_quest.R
import com.faldo.hsk_quest.data.model.GachaMultiPullResponse
import com.faldo.hsk_quest.data.model.GachaPullResponse
import com.faldo.hsk_quest.data.model.GachaRatesResponse
import com.faldo.hsk_quest.databinding.DialogMultiSummonRevealBinding
import com.faldo.hsk_quest.databinding.DialogSummonRevealBinding
import com.faldo.hsk_quest.databinding.FragmentGachaBinding
import com.faldo.hsk_quest.databinding.ItemGachaUnitCardBinding
import com.faldo.hsk_quest.engine.PetBuffCatalog
import com.faldo.hsk_quest.util.Sfx
import com.faldo.hsk_quest.util.SpriteAnimator
import com.faldo.hsk_quest.util.appContainer
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Celestial Summoning Shrine UI.
 * Displays live drop rates, interactive featured spirit previews backed by PetBuffCatalog,
 * summoning rituals, and rarity-styled 1x / 10x reveal dialogs.
 */
class GachaFragment : Fragment() {

    private var _binding: FragmentGachaBinding? = null
    private val binding get() = _binding!!

    private val viewModel: GachaViewModel by viewModels {
        GachaViewModel.factory(
            appContainer.gachaRepository,
            appContainer.playerRepository,
            appContainer.petRepository,
        )
    }

    private var bannerAnimator: SpriteAnimator? = null
    private var activePreviewPetType: String? = null
    private var activeRevealAnimator: SpriteAnimator? = null
    private var ritualPulseAnimator: AnimatorSet? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentGachaBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnPrevFeatured.setOnClickListener { viewModel.prevFeatured() }
        binding.btnNextFeatured.setOnClickListener { viewModel.nextFeatured() }
        binding.btnPullSingle.setOnClickListener { viewModel.pullSingle() }
        binding.btnPullTen.setOnClickListener { viewModel.pullTen() }

        binding.btnGoPetSanctuary.setOnClickListener {
            val navOptions = androidx.navigation.NavOptions.Builder()
                .setLaunchSingleTop(true)
                .setPopUpTo(R.id.homeFragment, false)
                .setEnterAnim(R.anim.slide_in_left_fade)
                .setExitAnim(R.anim.slide_out_right_fade)
                .setPopEnterAnim(R.anim.slide_in_right_fade)
                .setPopExitAnim(R.anim.slide_out_left_fade)
                .build()
            findNavController().navigate(R.id.petFragment, null, navOptions)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.diamonds.collect { balance ->
                        binding.tvGachaDiamonds.text = "💎 $balance"
                    }
                }

                launch {
                    viewModel.rates.collect { rates ->
                        renderRatesCard(rates)
                        if (!viewModel.isLoading.value) {
                            renderPullButtonsIdle(rates)
                        }
                    }
                }

                launch {
                    combine(viewModel.featuredIndex, viewModel.rates) { index, rates ->
                        index to rates
                    }.collect { (index, rates) ->
                        renderFeaturedShowcase(index, rates)
                    }
                }

                launch {
                    viewModel.summoningMode.collect { mode ->
                        when (mode) {
                            1 -> binding.tvSummonStatus.text =
                                "✨ Channeling Celestial Jade... Summoning Spirit..."
                            10 -> binding.tvSummonStatus.text =
                                "🌟 Channeling 10x Celestial Convergence... Summoning Spirits..."
                        }
                    }
                }

                launch {
                    viewModel.isLoading.collect { loading ->
                        binding.btnPullSingle.isEnabled = !loading
                        binding.btnPullTen.isEnabled = !loading
                        if (loading) {
                            binding.layoutSummonRitual.visibility = View.VISIBLE
                            binding.btnPullSingle.text = "✨ Summoning..."
                            binding.btnPullTen.text = "✨ Summoning..."
                            startRitualPulseAnimation()
                        } else {
                            stopRitualPulseAnimation()
                            binding.layoutSummonRitual.visibility = View.GONE
                            renderPullButtonsIdle(viewModel.rates.value)
                        }
                    }
                }

                launch {
                    viewModel.singleSummonResult.collect { result ->
                        showSummonRevealDialog(result)
                    }
                }

                launch {
                    viewModel.multiSummonResult.collect { result ->
                        showMultiSummonDialog(result)
                    }
                }

                launch {
                    viewModel.feedbackMessage.collect { msg ->
                        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        viewModel.loadBalance()
    }

    private fun renderPullButtonsIdle(rates: GachaRatesResponse) {
        val singleCost = rates.costSingle
        val tenCost = rates.costTen
        val saved = (singleCost * 10 - tenCost).coerceAtLeast(0)
        binding.btnPullSingle.text = "✨ 1x Summon\n$singleCost 💎"
        binding.btnPullTen.text = if (saved > 0) {
            "🌟 10x Summon\n$tenCost 💎 (Save $saved!)"
        } else {
            "🌟 10x Summon\n$tenCost 💎"
        }
    }

    private fun renderFeaturedShowcase(index: Int, rates: GachaRatesResponse) {
        val spirits = viewModel.featuredSpirits
        if (spirits.isEmpty()) return
        val safeIndex = index.coerceIn(spirits.indices)
        val featured = spirits[safeIndex]
        val spec = PetBuffCatalog.resolveSpec(featured.petType)
        val tierRate = when (spec.rarity.lowercase()) {
            "secret" -> rates.secretRate
            "legendary" -> rates.legendaryRate
            "epic" -> rates.epicRate
            "rare" -> rates.rareRate
            else -> rates.commonRate
        }

        binding.cardFeaturedBanner.setBackgroundResource(getRarityBorderDrawable(spec.rarity))
        binding.tvFeaturedRarityPill.text = "${formatRarityLabel(spec.rarity)} · ${formatPercent(tierRate)}"
        binding.tvFeaturedRarityPill.setTextColor(getRarityColor(spec.rarity))
        binding.tvFeaturedName.text = "${featured.displayName} • ${featured.chineseName}"
        binding.tvFeaturedBuffTitle.text = "Blessing: ${spec.title}"
        binding.tvFeaturedBuffSummary.text = spec.summary

        if (activePreviewPetType != featured.petType) {
            activePreviewPetType = featured.petType
            bannerAnimator?.stop()
            bannerAnimator = SpriteAnimator.startAnimation(
                imageView = binding.ivAltarPreview,
                drawableRes = SpriteAnimator.resolvePetDrawable(
                    requireContext(),
                    featured.petType,
                    featured.spriteKey,
                ),
                rows = 3,
                cols = 4,
                targetRow = 0,
                frameCount = 4,
                fps = 5,
            )
        }

        renderFeaturedDots(spirits.size, safeIndex)
    }

    private fun renderFeaturedDots(totalCount: Int, selectedIndex: Int) {
        val container = binding.layoutFeaturedDots
        container.removeAllViews()
        val density = resources.displayMetrics.density
        for (i in 0 until totalCount) {
            val isSelected = i == selectedIndex
            val dot = View(requireContext()).apply {
                val widthDp = if (isSelected) 18 else 7
                layoutParams = LinearLayout.LayoutParams(
                    (widthDp * density).toInt(),
                    (7 * density).toInt(),
                ).apply {
                    marginEnd = if (i < totalCount - 1) (6 * density).toInt() else 0
                }
                setBackgroundResource(
                    if (isSelected) R.drawable.bg_card_gold_border else R.drawable.bg_stat_box
                )
                alpha = if (isSelected) 1.0f else 0.5f
                setOnClickListener { viewModel.selectFeatured(i) }
            }
            container.addView(dot)
        }
    }

    private fun renderRatesCard(rates: GachaRatesResponse) {
        binding.tvActivePoolBadge.text = "Active Pool ${formatPercent(rates.activeTotalRate)}"
        binding.tvRateCommon.text = "🟢 Common\n${formatPercent(rates.commonRate)}"
        binding.tvRateRare.text = "🔵 Rare\n${formatPercent(rates.rareRate)}"
        binding.tvRateEpic.text = "🟣 Epic\n${formatPercent(rates.epicRate)}"
        binding.tvRateLegendary.text = "🟡 Legendary\n${formatPercent(rates.legendaryRate)}"
        binding.tvRateSecret.text = "🔮 Secret · Soon\n${formatPercent(rates.secretRate)}"

        binding.tvRatesDetail.text = buildString {
            append("🔮 Secret (${formatPercent(rates.secretRate)}): ??? (Coming Soon)\n")
            append("🟡 Legendary (${formatPercent(rates.legendaryRate)}): Azure Dragon, Sacred Qilin, Vermilion Bird\n")
            append("🟣 Epic (${formatPercent(rates.epicRate)}): Immortal Fire Crane, Nine-Tail Sky Fox, Winged Qiongqi\n")
            append("🔵 Rare (${formatPercent(rates.rareRate)}): Guardian Fu Dog, Celestial Sika Deer, White Thunder Tiger, Black Tortoise Xuanwu\n")
            append("🟢 Common (${formatPercent(rates.commonRate)}): Wood Rat, Stone Rabbit, Bamboo Panda, Golden Koi, Cloud Fox Kit, Money Toad\n")
            append("🔄 Duplicate Bonus: +15 ❤️ Affection & +40 🪙 Coins (Active Odds: ${formatPercent(rates.activeTotalRate)})")
        }
    }

    private fun formatPercent(rate: Float): String {
        val pct = rate * 100f
        return String.format(Locale.US, "%.1f%%", pct)
    }

    private fun startRitualPulseAnimation() {
        ritualPulseAnimator?.cancel()

        val previewScaleX = ObjectAnimator.ofFloat(binding.ivAltarPreview, View.SCALE_X, 1.0f, 1.18f).apply {
            duration = 320L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }
        val previewScaleY = ObjectAnimator.ofFloat(binding.ivAltarPreview, View.SCALE_Y, 1.0f, 1.18f).apply {
            duration = 320L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }
        val previewWobble = ObjectAnimator.ofFloat(binding.ivAltarPreview, View.ROTATION, -6f, 6f).apply {
            duration = 240L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }

        val auraScaleX = ObjectAnimator.ofFloat(binding.viewAltarAura, View.SCALE_X, 1.0f, 1.18f).apply {
            duration = 320L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }
        val auraScaleY = ObjectAnimator.ofFloat(binding.viewAltarAura, View.SCALE_Y, 1.0f, 1.18f).apply {
            duration = 320L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }

        ritualPulseAnimator = AnimatorSet().apply {
            playTogether(previewScaleX, previewScaleY, previewWobble, auraScaleX, auraScaleY)
            start()
        }
    }

    private fun stopRitualPulseAnimation() {
        ritualPulseAnimator?.cancel()
        ritualPulseAnimator = null
        binding.ivAltarPreview.scaleX = 1.0f
        binding.ivAltarPreview.scaleY = 1.0f
        binding.ivAltarPreview.rotation = 0f

        val idleScaleX = ObjectAnimator.ofFloat(binding.viewAltarAura, View.SCALE_X, 0.96f, 1.05f).apply {
            duration = 1600L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }
        val idleScaleY = ObjectAnimator.ofFloat(binding.viewAltarAura, View.SCALE_Y, 0.96f, 1.05f).apply {
            duration = 1600L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }
        val idleAlpha = ObjectAnimator.ofFloat(binding.viewAltarAura, View.ALPHA, 0.82f, 1.0f).apply {
            duration = 1600L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }
        ritualPulseAnimator = AnimatorSet().apply {
            playTogether(idleScaleX, idleScaleY, idleAlpha)
            start()
        }
    }

    private fun showSummonRevealDialog(result: GachaPullResponse) {
        val pet = result.pet
        appContainer.soundPlayer.play(Sfx.GACHA_SUMMON)
        val dialogBinding = DialogSummonRevealBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogBinding.root)
            .setCancelable(true)
            .create()
        dialog.setCanceledOnTouchOutside(true)

        dialogBinding.revealDialogRoot.setBackgroundResource(getRarityBorderDrawable(pet.rarity))
        dialogBinding.tvRevealTitle.text = "SPIRIT AWAKENED! ✨"
        dialogBinding.tvRevealName.text = pet.name

        if (result.isNewCompanion) {
            dialogBinding.tvRevealStatusPill.text = "✨ NEW COMPANION!"
            dialogBinding.tvRevealStatusPill.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.hq_jade)
            )
        } else {
            dialogBinding.tvRevealStatusPill.text = "🔄 DUPLICATE (+15 ❤️ Affection, +40 🪙 Coins)"
            dialogBinding.tvRevealStatusPill.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.hq_gold)
            )
        }

        dialogBinding.tvRevealRarity.text = formatRarityLabel(pet.rarity)
        dialogBinding.tvRevealRarity.setTextColor(getRarityColor(pet.rarity))
        val buffSpec = PetBuffCatalog.resolveSpec(pet)
        dialogBinding.tvRevealBuff.text = "${buffSpec.title} — ${buffSpec.summary}"
        dialogBinding.tvRevealSubtext.text = result.message.ifBlank {
            if (result.isNewCompanion) {
                "Added to your Companion Sanctuary!"
            } else {
                "+15 Affection & +40 Bonus Coins awarded!"
            }
        }

        val drawableRes = SpriteAnimator.resolvePetDrawable(requireContext(), pet.petType, pet.spriteKey)
        activeRevealAnimator?.stop()
        val revealAnimator = SpriteAnimator.startAnimation(
            imageView = dialogBinding.ivRevealPetSprite,
            drawableRes = drawableRes,
            rows = 3,
            cols = 4,
            targetRow = 0,
            frameCount = 4,
            fps = 6,
        )
        activeRevealAnimator = revealAnimator

        dialog.setOnDismissListener {
            revealAnimator.stop()
            if (activeRevealAnimator === revealAnimator) {
                activeRevealAnimator = null
            }
        }

        dialogBinding.btnRevealConfirm.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        playDialogEntranceAnimation(dialogBinding.revealDialogRoot)
    }

    private fun showMultiSummonDialog(result: GachaMultiPullResponse) {
        appContainer.soundPlayer.play(Sfx.GACHA_SUMMON)
        val dialogBinding = DialogMultiSummonRevealBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogBinding.root)
            .setCancelable(true)
            .create()
        dialog.setCanceledOnTouchOutside(true)

        dialogBinding.multiRevealDialogRoot.setBackgroundResource(getRarityBorderDrawable(result.highestRarity))
        dialogBinding.tvMultiTitle.text = "10x CELESTIAL CONVERGENCE 🎊"

        val secretCount = result.pets.count { it.rarity.equals("secret", ignoreCase = true) }
        val legendaryCount = result.pets.count { it.rarity.equals("legendary", ignoreCase = true) }
        val epicCount = result.pets.count { it.rarity.equals("epic", ignoreCase = true) }
        val rareCount = result.pets.count { it.rarity.equals("rare", ignoreCase = true) }
        val commonCount = result.pets.count { it.rarity.equals("common", ignoreCase = true) }

        val breakdownList = mutableListOf<String>()
        if (secretCount > 0) breakdownList.add("$secretCount SECRET 🔮")
        if (legendaryCount > 0) breakdownList.add("$legendaryCount LEGENDARY 🟡")
        if (epicCount > 0) breakdownList.add("$epicCount EPIC 🟣")
        if (rareCount > 0) breakdownList.add("$rareCount RARE 🔵")
        if (commonCount > 0) breakdownList.add("$commonCount COMMON 🟢")

        val summaryText = breakdownList.joinToString(" · ").ifBlank {
            "Highest: ${result.highestRarity.uppercase()}"
        }
        dialogBinding.tvMultiStatusPill.text = summaryText
        dialogBinding.tvMultiSubtext.text = result.message.ifBlank {
            "10 mythical companions channeled into your Sanctuary!"
        }

        dialogBinding.containerUnitsGrid.removeAllViews()
        val rowCount = (result.pets.size + 1) / 2

        for (r in 0 until rowCount) {
            val rowLayout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    bottomMargin = (4 * resources.displayMetrics.density).toInt()
                }
            }

            for (c in 0..1) {
                val index = r * 2 + c
                if (index < result.pets.size) {
                    val petItem = result.pets[index]
                    val cardBinding = ItemGachaUnitCardBinding.inflate(layoutInflater, rowLayout, false)

                    cardBinding.tvUnitName.text = petItem.name
                    cardBinding.tvUnitRarity.text = formatRarityLabel(petItem.rarity)
                    cardBinding.tvUnitRarity.setTextColor(getRarityColor(petItem.rarity))
                    val unitSpec = PetBuffCatalog.resolveSpec(petItem)
                    cardBinding.tvUnitBuff.text = "${unitSpec.title} • ${unitSpec.shortBadge}"
                    cardBinding.cardUnitRoot.setBackgroundResource(getRarityBorderDrawable(petItem.rarity))

                    val drawableRes = SpriteAnimator.resolvePetDrawable(
                        requireContext(),
                        petItem.petType,
                        petItem.spriteKey,
                    )
                    val frames = SpriteAnimator.loadFrames(
                        imageView = cardBinding.ivUnitSprite,
                        drawableRes = drawableRes,
                        rows = 3,
                        cols = 4,
                        targetRow = 0,
                        frameCount = 1,
                    )
                    if (frames.isNotEmpty()) {
                        cardBinding.ivUnitSprite.setImageBitmap(frames[0])
                    } else {
                        cardBinding.ivUnitSprite.setImageResource(drawableRes)
                    }

                    rowLayout.addView(cardBinding.root)
                } else {
                    val emptySpacer = View(requireContext()).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            0,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            1f,
                        )
                    }
                    rowLayout.addView(emptySpacer)
                }
            }
            dialogBinding.containerUnitsGrid.addView(rowLayout)
        }

        dialogBinding.btnMultiConfirm.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        playDialogEntranceAnimation(dialogBinding.multiRevealDialogRoot)
    }

    private fun playDialogEntranceAnimation(targetView: View) {
        targetView.scaleX = 0.75f
        targetView.scaleY = 0.75f
        targetView.alpha = 0f
        targetView.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(420L)
            .setInterpolator(OvershootInterpolator(1.25f))
            .start()
    }

    private fun getRarityBorderDrawable(rarity: String): Int {
        return when (rarity.lowercase()) {
            "secret", "legendary" -> R.drawable.bg_card_gold_border
            "epic" -> R.drawable.bg_card_epic_border
            "rare" -> R.drawable.bg_card_rare_border
            else -> R.drawable.bg_card_common_border
        }
    }

    private fun formatRarityLabel(rarity: String): String {
        return when (rarity.lowercase()) {
            "secret" -> "SECRET 🔮"
            "legendary" -> "LEGENDARY 🟡"
            "epic" -> "EPIC 🟣"
            "rare" -> "RARE 🔵"
            else -> "COMMON 🟢"
        }
    }

    private fun getRarityColor(rarity: String): Int {
        val colorRes = when (rarity.lowercase()) {
            "secret" -> R.color.hq_crimson
            "legendary" -> R.color.hq_gold
            "epic" -> R.color.hq_violet
            "rare" -> R.color.hq_sky
            else -> R.color.hq_jade
        }
        return ContextCompat.getColor(requireContext(), colorRes)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        ritualPulseAnimator?.cancel()
        ritualPulseAnimator = null
        bannerAnimator?.stop()
        bannerAnimator = null
        activePreviewPetType = null
        activeRevealAnimator?.stop()
        activeRevealAnimator = null
        _binding = null
    }
}
