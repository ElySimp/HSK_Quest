package com.faldo.hsk_quest.ui.auth

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.faldo.hsk_quest.R
import com.faldo.hsk_quest.databinding.FragmentRegisterBinding
import com.faldo.hsk_quest.util.Constants
import com.faldo.hsk_quest.util.appContainer
import com.faldo.hsk_quest.util.applySystemBarsPadding
import com.google.android.material.chip.Chip
import kotlinx.coroutines.launch

class RegisterFragment : Fragment() {

    private var _binding: FragmentRegisterBinding? = null
    private val binding get() = _binding!!

    private val viewModel: AuthViewModel by viewModels {
        AuthViewModel.factory(appContainer.authRepository)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentRegisterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.root.applySystemBarsPadding()
        setupHskChips()

        binding.btnRegister.setOnClickListener {
            viewModel.register(
                username = binding.etUsername.text?.toString().orEmpty(),
                password = binding.etPassword.text?.toString().orEmpty(),
                hskTarget = selectedHskLevel(),
            )
        }
        binding.btnGoLogin.setOnClickListener { findNavController().navigateUp() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    renderAuthState(
                        state = state,
                        button = binding.btnRegister,
                        progress = binding.progress,
                        errorView = binding.tvError,
                        inputs = listOf(
                            binding.etUsername,
                            binding.etPassword,
                            binding.chipGroupHsk,
                            binding.btnGoLogin,
                        ),
                    )
                    if (state is AuthViewModel.UiState.Success) {
                        viewModel.consumed()
                        findNavController().navigate(R.id.action_register_to_home)
                    }
                }
            }
        }
    }

    /** Builds one selectable chip per HSK level (1-9); HSK 1 is selected by default. */
    private fun setupHskChips() {
        val group = binding.chipGroupHsk
        group.removeAllViews()
        for (level in Constants.HSK_MIN_LEVEL..Constants.HSK_MAX_LEVEL) {
            val chip = Chip(requireContext()).apply {
                id = View.generateViewId()
                tag = level
                text = getString(R.string.hsk_level_format, level)
                isCheckable = true
                isChecked = level == Constants.HSK_MIN_LEVEL
            }
            group.addView(chip)
        }
    }

    private fun selectedHskLevel(): Int {
        val group = binding.chipGroupHsk
        val chip = group.findViewById<Chip>(group.checkedChipId)
        return (chip?.tag as? Int) ?: Constants.HSK_MIN_LEVEL
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
