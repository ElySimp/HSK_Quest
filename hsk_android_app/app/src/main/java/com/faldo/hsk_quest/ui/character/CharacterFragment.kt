package com.faldo.hsk_quest.ui.character

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.faldo.hsk_quest.MainActivity
import com.faldo.hsk_quest.R
import com.faldo.hsk_quest.data.model.Player
import com.faldo.hsk_quest.databinding.FragmentCharacterBinding
import com.faldo.hsk_quest.util.Constants
import com.faldo.hsk_quest.util.appContainer
import com.faldo.hsk_quest.util.readableError
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch

class CharacterFragment : Fragment() {

    private var _binding: FragmentCharacterBinding? = null
    private val binding get() = _binding!!

    private val viewModel: CharacterViewModel by viewModels {
        CharacterViewModel.factory(appContainer.playerRepository, appContainer.authRepository)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentCharacterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        (activity as? MainActivity)?.attachScrollListener(binding.characterRoot)

        binding.btnAddStr.setOnClickListener { viewModel.allocateStat("str") }
        binding.btnAddDex.setOnClickListener { viewModel.allocateStat("dex") }
        binding.btnAddDef.setOnClickListener { viewModel.allocateStat("def") }
        binding.btnAddVit.setOnClickListener { viewModel.allocateStat("vit") }

        binding.btnEditProfile.setOnClickListener {
            val player = (viewModel.state.value as? CharacterViewModel.UiState.Loaded)?.player
            if (player != null) {
                showEditProfileDialog(player)
            }
        }

        binding.btnLogout.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                appContainer.authRepository.logout()
                val navOptions = NavOptions.Builder()
                    .setPopUpTo(R.id.nav_graph, true)
                    .build()
                findNavController().navigate(R.id.loginFragment, null, navOptions)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::renderState) }
                launch {
                    viewModel.isAllocating.collect { isAllocating ->
                        listOf(binding.btnAddStr, binding.btnAddDex, binding.btnAddDef, binding.btnAddVit).forEach {
                            if (isAllocating) it.isEnabled = false
                        }
                    }
                }
                launch {
                    viewModel.toastMessage.collect { msg ->
                        if (msg != null) {
                            Toast.makeText(requireContext(), readableError(msg), Toast.LENGTH_SHORT).show()
                            viewModel.clearToastMessage()
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadProfile()
    }

    private fun renderState(state: CharacterViewModel.UiState) {
        when (state) {
            CharacterViewModel.UiState.Loading -> {}
            is CharacterViewModel.UiState.Loaded -> bindPlayer(state.player)
            is CharacterViewModel.UiState.Error -> {
                Toast.makeText(requireContext(), readableError(state.message), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun bindPlayer(player: Player) {
        val s = player.stats
        with(binding) {
            tvUsername.text = player.username
            tvLevel.text = getString(R.string.home_level_format, s.level)
            tvHskTarget.text = "🎯 HSK Target: Level ${player.hskTarget}"
            tvLeague.text = s.league.replaceFirstChar { it.uppercase() }

            tvDiamonds.text = "💎 ${s.diamonds}"
            tvCoins.text = "🪙 ${s.coins}"
            tvSurvivalHp.text = "❤️ ${s.survivalHp}/${s.maxHp}"

            val pointsAvailable = s.unspentStatPoints
            tvUnspentPoints.text = getString(R.string.stat_points_available, pointsAvailable)
            bannerUnspentPoints.visibility = if (pointsAvailable > 0) View.VISIBLE else View.GONE

            tvStrValue.text = s.statStr.toString()
            tvDexValue.text = s.statDex.toString()
            tvDefValue.text = s.statDef.toString()
            tvVitValue.text = s.statVit.toString()

            val canUpgrade = pointsAvailable > 0
            btnAddStr.isEnabled = canUpgrade
            btnAddDex.isEnabled = canUpgrade
            btnAddDef.isEnabled = canUpgrade
            btnAddVit.isEnabled = canUpgrade

            val alpha = if (canUpgrade) 1.0f else 0.35f
            btnAddStr.alpha = alpha
            btnAddDex.alpha = alpha
            btnAddDef.alpha = alpha
            btnAddVit.alpha = alpha
        }
    }

    private fun showEditProfileDialog(player: Player) {
        val ctx = requireContext()
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }

        val tilUsername = TextInputLayout(ctx).apply {
            hint = getString(R.string.auth_username)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        }

        val etUsername = TextInputEditText(tilUsername.context).apply {
            setText(player.username)
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
            setTextColor(ContextCompat.getColor(ctx, R.color.hq_text_primary))
        }
        tilUsername.addView(etUsername)
        container.addView(tilUsername)

        val tvTargetLabel = TextView(ctx).apply {
            text = "Target HSK Level (1–9)"
            setTextColor(ContextCompat.getColor(ctx, R.color.hq_text_secondary))
            textSize = 14f
            setPadding(0, dp(16), 0, dp(8))
        }
        container.addView(tvTargetLabel)

        val chipGroup = ChipGroup(ctx).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }

        for (level in Constants.HSK_MIN_LEVEL..Constants.HSK_MAX_LEVEL) {
            val chip = Chip(ctx).apply {
                id = View.generateViewId()
                tag = level
                text = getString(R.string.hsk_level_format, level)
                isCheckable = true
                isChecked = level == player.hskTarget
            }
            chipGroup.addView(chip)
        }
        container.addView(chipGroup)

        MaterialAlertDialogBuilder(ctx)
            .setTitle("Edit Hero Profile")
            .setView(container)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val newUsername = etUsername.text?.toString()?.trim().orEmpty()
                val checkedChip = chipGroup.findViewById<Chip>(chipGroup.checkedChipId)
                val selectedHsk = (checkedChip?.tag as? Int) ?: player.hskTarget
                viewModel.updateProfile(newUsername, selectedHsk)
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
