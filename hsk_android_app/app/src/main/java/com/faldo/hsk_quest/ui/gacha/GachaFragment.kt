package com.faldo.hsk_quest.ui.gacha

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
import com.faldo.hsk_quest.R
import com.faldo.hsk_quest.data.model.GachaMultiPullResponse
import com.faldo.hsk_quest.data.model.GachaPullResponse
import com.faldo.hsk_quest.data.model.PetItem
import com.faldo.hsk_quest.databinding.DialogSummonRevealBinding
import com.faldo.hsk_quest.databinding.FragmentGachaBinding
import com.faldo.hsk_quest.util.SpriteAnimator
import com.faldo.hsk_quest.util.appContainer
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/**
 * Gacha Summoning Altar.
 * Enables single and 10x summoning for Chinese mythological companion beasts.
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

        // Loop featured banner pet (Row 0, 4 frames of pet_dummy_2)
        bannerAnimator = SpriteAnimator.startAnimation(
            imageView = binding.ivBannerPreview,
            drawableRes = R.drawable.pet_dummy_2,
            rows = 3,
            cols = 4,
            targetRow = 0,
            frameCount = 4,
            fps = 6
        )

        binding.btnPullSingle.setOnClickListener {
            viewModel.pullSingle()
        }

        binding.btnPullTen.setOnClickListener {
            viewModel.pullTen()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.diamonds.collect { balance ->
                        binding.tvGachaDiamonds.text = "💎 $balance"
                    }
                }

                launch {
                    viewModel.isLoading.collect { loading ->
                        binding.btnPullSingle.isEnabled = !loading
                        binding.btnPullTen.isEnabled = !loading
                    }
                }

                launch {
                    viewModel.singleSummonResult.collect { result ->
                        showSummonRevealDialog(result.pet, result.isNewCompanion)
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

    private fun showSummonRevealDialog(pet: PetItem, isNewCompanion: Boolean) {
        val dialogBinding = DialogSummonRevealBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogBinding.root)
            .setCancelable(false)
            .create()

        dialogBinding.tvRevealName.text = pet.name

        val rarityStr = when (pet.rarity.lowercase()) {
            "legendary" -> "LEGENDARY 🟡"
            "epic" -> "EPIC 🟣"
            "rare" -> "RARE 🔵"
            else -> "COMMON 🟢"
        }
        dialogBinding.tvRevealRarity.text = rarityStr

        val buffDesc = when (pet.buffType) {
            "shield" -> "Combat Buff: 🛡️ Shield (Absorbs 1 mistake)"
            "time_extender" -> "Combat Buff: ⏳ Time Extender (+3s Crit Window)"
            "damage_boost" -> "Combat Buff: ⚔️ Damage Boost (+10% Damage)"
            else -> "Combat Buff: None"
        }
        dialogBinding.tvRevealBuff.text = buffDesc

        dialogBinding.tvRevealStatusNote.text = if (isNewCompanion) {
            "✨ Automatically equipped as your active companion!"
        } else {
            "Added to your companion sanctuary collection."
        }

        val drawableRes = if (pet.spriteKey == "pet_dummy_2") {
            R.drawable.pet_dummy_2
        } else {
            R.drawable.pet_dummy_1
        }

        // Preview static frame 0
        val frames = SpriteAnimator.loadFrames(
            imageView = dialogBinding.ivRevealSprite,
            drawableRes = drawableRes,
            rows = 3,
            cols = 4,
            targetRow = 0,
            frameCount = 1
        )
        if (frames.isNotEmpty()) {
            dialogBinding.ivRevealSprite.setImageBitmap(frames[0])
        } else {
            dialogBinding.ivRevealSprite.setImageResource(drawableRes)
        }

        dialogBinding.btnRevealConfirm.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showMultiSummonDialog(result: GachaMultiPullResponse) {
        // Show highest rarity pet in detail, with count summary
        val bestPet = result.pets.maxByOrNull {
            when (it.rarity.lowercase()) {
                "legendary" -> 4
                "epic" -> 3
                "rare" -> 2
                else -> 1
            }
        } ?: result.pets.first()

        val dialogBinding = DialogSummonRevealBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogBinding.root)
            .setCancelable(false)
            .create()

        dialogBinding.tvRevealTitle.text = "10x SUMMON COMPLETED! 🎊"
        dialogBinding.tvRevealName.text = "Highest: ${bestPet.name}"

        val rarityStr = when (bestPet.rarity.lowercase()) {
            "legendary" -> "LEGENDARY 🟡"
            "epic" -> "EPIC 🟣"
            "rare" -> "RARE 🔵"
            else -> "COMMON 🟢"
        }
        dialogBinding.tvRevealRarity.text = rarityStr

        val petNames = result.pets.joinToString(", ") { it.name.substringBefore(" ") }
        dialogBinding.tvRevealBuff.text = "Summoned: $petNames"
        dialogBinding.tvRevealStatusNote.text = "10 companions added to your Sanctuary collection!"

        val drawableRes = if (bestPet.spriteKey == "pet_dummy_2") {
            R.drawable.pet_dummy_2
        } else {
            R.drawable.pet_dummy_1
        }

        val frames = SpriteAnimator.loadFrames(
            imageView = dialogBinding.ivRevealSprite,
            drawableRes = drawableRes,
            rows = 3,
            cols = 4,
            targetRow = 0,
            frameCount = 1
        )
        if (frames.isNotEmpty()) {
            dialogBinding.ivRevealSprite.setImageBitmap(frames[0])
        } else {
            dialogBinding.ivRevealSprite.setImageResource(drawableRes)
        }

        dialogBinding.btnRevealConfirm.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        bannerAnimator?.stop()
        bannerAnimator = null
        _binding = null
    }
}
