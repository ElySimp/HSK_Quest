package com.faldo.hsk_quest.ui.common

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.faldo.hsk_quest.R
import com.faldo.hsk_quest.databinding.FragmentPlaceholderBinding
import com.faldo.hsk_quest.util.applySystemBarsPadding

/**
 * Base class for screens that are scaffolded now and implemented in a later phase.
 */
abstract class PlaceholderFragment : Fragment() {

    private var _binding: FragmentPlaceholderBinding? = null
    protected val binding get() = _binding!!

    protected abstract val icon: String
    protected abstract val titleRes: Int
    protected abstract val phaseLabel: String

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentPlaceholderBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.root.applySystemBarsPadding()
        binding.tvIcon.text = icon
        binding.tvTitle.setText(titleRes)
        binding.tvSubtitle.text = getString(R.string.placeholder_coming, phaseLabel)
        binding.btnBack.setOnClickListener { findNavController().navigateUp() }

        binding.tvIcon.apply {
            scaleX = 0.8f
            scaleY = 0.8f
            animate().scaleX(1f).scaleY(1f).setDuration(350).start()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
