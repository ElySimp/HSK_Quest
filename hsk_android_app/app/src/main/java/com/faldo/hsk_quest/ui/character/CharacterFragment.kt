package com.faldo.hsk_quest.ui.character

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
import com.faldo.hsk_quest.R
import com.faldo.hsk_quest.data.model.Player
import com.faldo.hsk_quest.databinding.FragmentCharacterBinding
import com.faldo.hsk_quest.util.appContainer
import com.faldo.hsk_quest.util.readableError
import kotlinx.coroutines.launch

class CharacterFragment : Fragment() {

    private var _binding: FragmentCharacterBinding? = null
    private val binding get() = _binding!!

    private val viewModel: CharacterViewModel by viewModels {
        CharacterViewModel.factory(appContainer.playerRepository)
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

        binding.btnAddStr.setOnClickListener { viewModel.allocateStat("str") }
        binding.btnAddDex.setOnClickListener { viewModel.allocateStat("dex") }
        binding.btnAddDef.setOnClickListener { viewModel.allocateStat("def") }
        binding.btnAddVit.setOnClickListener { viewModel.allocateStat("vit") }

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
            tvLeague.text = s.league.replaceFirstChar { it.uppercase() }

            tvDiamonds.text = "💎 ${s.diamonds}"
            tvCoins.text = "🪙 ${s.coins}"
            tvSurvivalHp.text = "❤️ ${s.survivalHp}/100"

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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
