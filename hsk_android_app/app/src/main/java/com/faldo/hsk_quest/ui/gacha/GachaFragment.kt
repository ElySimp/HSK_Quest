package com.faldo.hsk_quest.ui.gacha

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView
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
import com.faldo.hsk_quest.data.model.PetItem
import com.faldo.hsk_quest.databinding.DialogMultiSummonRevealBinding
import com.faldo.hsk_quest.databinding.DialogSummonRevealBinding
import com.faldo.hsk_quest.databinding.FragmentGachaBinding
import com.faldo.hsk_quest.databinding.ItemGachaUnitCardBinding
import com.faldo.hsk_quest.util.Sfx
import com.faldo.hsk_quest.util.SpriteAnimator
import com.faldo.hsk_quest.util.appContainer
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/**
 * Gacha Summoning Altar.
 * Enables single and 10x summoning for Chinese mythological companion beasts,
 * featuring an animated shrine centerpiece, summoning ritual loading state,
 * and animated 1x/10x reveal dialogs.
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

        // Animate the shrine altar preview pet across 4 idle frames at 5 FPS
        bannerAnimator = SpriteAnimator.startAnimation(
            imageView = binding.ivAltarPreview,
            drawableRes = SpriteAnimator.resolvePetDrawable(requireContext(), "azure_dragon", "pet_dummy_2"),
            rows = 3,
            cols = 4,
            targetRow = 0,
            frameCount = 4,
            fps = 5,
        )

        binding.btnPullSingle.setOnClickListener {
            viewModel.pullSingle()
        }

        binding.btnPullTen.setOnClickListener {
            viewModel.pullTen()
        }

        binding.btnGoPetSanctuary.setOnClickListener {
            findNavController().navigate(R.id.petFragment)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.diamonds.collect { balance ->
                        binding.tvGachaDiamonds.text = "💎 $balance"
                    }
                }

                launch {
                    viewModel.summoningMode.collect { mode ->
                        when (mode) {
                            1 -> {
                                binding.tvSummonStatus.text =
                                    "✨ Channeling Celestial Jade... Summoning Spirit..."
                            }
                            10 -> {
                                binding.tvSummonStatus.text =
                                    "🌟 Channeling 10x Celestial Convergence... Summoning Spirits..."
                            }
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
                            binding.btnPullSingle.text = "✨ 1x Summon\n100 💎"
                            binding.btnPullTen.text = "🌟 10x Summon\n900 💎 (Save 100!)"
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
        binding.viewAltarAura.scaleX = 1.0f
        binding.viewAltarAura.scaleY = 1.0f
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

        dialogBinding.tvRevealTitle.text = "SUMMON SUCCESSFUL! ✨"
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
        dialogBinding.tvRevealBuff.text = formatBuffDescription(pet.buffType)
        dialogBinding.tvRevealSubtext.text = result.message.ifBlank {
            if (result.isNewCompanion) {
                "✨ Added to your Companion Sanctuary!"
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

        dialogBinding.tvMultiTitle.text = "10x SUMMON COMPLETED! 🎊"

        // Compute breakdown of rarities
        val legendaryCount = result.pets.count { it.rarity.equals("legendary", ignoreCase = true) }
        val epicCount = result.pets.count { it.rarity.equals("epic", ignoreCase = true) }
        val rareCount = result.pets.count { it.rarity.equals("rare", ignoreCase = true) }
        val commonCount = result.pets.count { it.rarity.equals("common", ignoreCase = true) }

        val breakdownList = mutableListOf<String>()
        if (legendaryCount > 0) breakdownList.add("$legendaryCount LEGENDARY 🟡")
        if (epicCount > 0) breakdownList.add("$epicCount EPIC 🟣")
        if (rareCount > 0) breakdownList.add("$rareCount RARE 🔵")
        if (commonCount > 0) breakdownList.add("$commonCount COMMON 🟢")

        val summaryText = breakdownList.joinToString(" · ").ifBlank {
            "🌟 Highest: ${result.highestRarity.uppercase()}"
        }
        dialogBinding.tvMultiStatusPill.text = summaryText
        dialogBinding.tvMultiSubtext.text = result.message.ifBlank {
            "10 mythical companions channeled into your Sanctuary!"
        }

        // Render 2-column grid of 10 companion cards
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
                    val rarityColor = getRarityColor(petItem.rarity)
                    cardBinding.tvUnitRarity.setTextColor(rarityColor)
                    cardBinding.tvUnitBuff.text = formatShortBuff(petItem.buffType)

                    if (petItem.rarity.equals("legendary", ignoreCase = true)) {
                        cardBinding.cardUnitRoot.setBackgroundResource(R.drawable.bg_card_gold_border)
                    } else {
                        cardBinding.cardUnitRoot.setBackgroundResource(R.drawable.bg_card)
                    }

                    // Render sharp static idle frame
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

    private fun rarityRank(rarity: String): Int {
        return when (rarity.lowercase()) {
            "legendary" -> 4
            "epic" -> 3
            "rare" -> 2
            else -> 1
        }
    }

    private fun formatRarityLabel(rarity: String): String {
        return when (rarity.lowercase()) {
            "legendary" -> "LEGENDARY 🟡"
            "epic" -> "EPIC 🟣"
            "rare" -> "RARE 🔵"
            else -> "COMMON 🟢"
        }
    }

    private fun getRarityColor(rarity: String): Int {
        val colorRes = when (rarity.lowercase()) {
            "legendary" -> R.color.hq_gold
            "epic" -> R.color.hq_violet
            "rare" -> R.color.hq_sky
            else -> R.color.hq_jade
        }
        return ContextCompat.getColor(requireContext(), colorRes)
    }

    private fun formatBuffDescription(buffType: String): String {
        return when (buffType) {
            "shield" -> "Combat Buff: 🛡️ Shield (Absorbs 1 mistake)"
            "time_extender" -> "Combat Buff: ⏳ Time Extender (+3s Crit Window)"
            "damage_boost" -> "Combat Buff: ⚔️ Damage Boost (+10% Damage)"
            else -> "Combat Buff: None"
        }
    }

    private fun formatShortBuff(buffType: String): String {
        return when (buffType) {
            "shield" -> "🛡️ Shield (Absorbs 1 mistake)"
            "time_extender" -> "⏳ Time Extender (+3s Crit Window)"
            "damage_boost" -> "⚔️ Damage Boost (+10% Damage)"
            else -> "🐾 Loyal Sanctuary Companion"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopRitualPulseAnimation()
        bannerAnimator?.stop()
        bannerAnimator = null
        activeRevealAnimator?.stop()
        activeRevealAnimator = null
        _binding = null
    }
}
