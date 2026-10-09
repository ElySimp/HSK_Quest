package com.faldo.hsk_quest.ui.pet

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
import com.faldo.hsk_quest.data.model.PetItem
import com.faldo.hsk_quest.data.model.RpsResponse
import com.faldo.hsk_quest.databinding.FragmentPetBinding
import com.faldo.hsk_quest.databinding.SheetRpsGameBinding
import com.faldo.hsk_quest.util.SpriteAnimator
import com.faldo.hsk_quest.util.appContainer
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.launch

/**
 * Companion Pet Sanctuary.
 * Handles Tamagotchi care, multi-frame sprite looping, Rock-Paper-Scissors minigames,
 * and collection party equipping.
 */
class PetFragment : Fragment() {

    private var _binding: FragmentPetBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PetViewModel by viewModels {
        PetViewModel.factory(
            appContainer.petRepository,
            appContainer.playerRepository,
        )
    }

    private var petAdapter: PetAdapter? = null
    private var spriteAnimator: SpriteAnimator? = null
    private var rpsBottomSheet: BottomSheetDialog? = null

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

        binding.btnPlayRps.setOnClickListener {
            showRpsDialog()
        }

        binding.btnFeedPet.setOnClickListener {
            viewModel.feedPet()
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.activePet.collect { pet ->
                        renderActivePet(pet)
                    }
                }

                launch {
                    viewModel.petList.collect { list ->
                        petAdapter?.submitList(list)
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

    private fun renderActivePet(pet: PetItem?) {
        if (pet == null) {
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

        // Determine animated row and mood text
        val drawableRes = if (pet.spriteKey == "pet_dummy_2") {
            R.drawable.pet_dummy_2
        } else {
            R.drawable.pet_dummy_1
        }

        spriteAnimator?.stop()

        if (pet.isSleeping) {
            binding.tvPetMood.text = "💤 Sleeping soundly... (Affection is low)"
            binding.tvPetMood.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.hq_text_muted)
            )
            // Row 2 = Sleep animation, 4 frames
            spriteAnimator = SpriteAnimator.startAnimation(
                imageView = binding.ivPetSprite,
                drawableRes = drawableRes,
                rows = 3,
                cols = 4,
                targetRow = 2,
                frameCount = 4,
                fps = 4
            )
        } else {
            binding.tvPetMood.text = "✨ Feeling energetic and happy!"
            binding.tvPetMood.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.hq_jade)
            )
            // Row 0 = Idle 1 animation, 4 frames
            spriteAnimator = SpriteAnimator.startAnimation(
                imageView = binding.ivPetSprite,
                drawableRes = drawableRes,
                rows = 3,
                cols = 4,
                targetRow = 0,
                frameCount = 4,
                fps = 6
            )
        }
    }

    private fun showRpsDialog() {
        val sheet = BottomSheetDialog(requireContext())
        val sheetBinding = SheetRpsGameBinding.inflate(layoutInflater)
        sheet.setContentView(sheetBinding.root)

        fun onPick(choice: String) {
            sheetBinding.layoutChoices.visibility = View.GONE
            sheetBinding.layoutOutcome.visibility = View.VISIBLE
            sheetBinding.tvRpsTitle.text = "Rock... Paper... Scissors! 🎲"
            sheetBinding.tvRpsMessage.text = "Your pet is thinking..."

            viewLifecycleOwner.lifecycleScope.launch {
                viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.rpsResult.collect { result ->
                        renderRpsResult(sheetBinding, result)
                    }
                }
            }
            viewModel.playRps(choice)
        }

        sheetBinding.btnChoiceRock.setOnClickListener { onPick("rock") }
        sheetBinding.btnChoicePaper.setOnClickListener { onPick("paper") }
        sheetBinding.btnChoiceScissors.setOnClickListener { onPick("scissors") }

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

        sheetBinding.tvRpsVs.text = "You: $pEmoji ${result.playerChoice.uppercase()}  vs  Pet: $petEmoji ${result.petChoice.uppercase()}"

        when (result.outcome) {
            "win" -> {
                sheetBinding.tvRpsTitle.text = "VICTORY! 🎉"
                sheetBinding.tvRpsTitle.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.hq_gold)
                )
            }
            "tie" -> {
                sheetBinding.tvRpsTitle.text = "DRAW! 🤝"
                sheetBinding.tvRpsTitle.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.hq_text_primary)
                )
            }
            else -> {
                sheetBinding.tvRpsTitle.text = "PET WON! 🐾"
                sheetBinding.tvRpsTitle.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.hq_text_muted)
                )
            }
        }

        sheetBinding.tvRpsMessage.text = result.message
        sheetBinding.btnCloseRps.text = "Play Again / Close"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        spriteAnimator?.stop()
        spriteAnimator = null
        rpsBottomSheet?.dismiss()
        rpsBottomSheet = null
        petAdapter = null
        _binding = null
    }
}
