package com.faldo.hsk_quest.ui.worldmap

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.faldo.hsk_quest.R
import com.faldo.hsk_quest.data.model.MonsterType
import com.faldo.hsk_quest.databinding.FragmentWorldMapBinding
import com.faldo.hsk_quest.ui.battle.BattleFragment
import com.faldo.hsk_quest.util.Constants
import com.faldo.hsk_quest.util.applySystemBarsPadding
import com.google.android.material.button.MaterialButton

/**
 * World map skeleton: one row per HSK area with Normal / Elite / Boss entry points.
 * Lock/unlock progression and pixel-art map come in Phase 3B.
 */
class WorldMapFragment : Fragment() {

    private var _binding: FragmentWorldMapBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentWorldMapBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.root.applySystemBarsPadding()
        binding.btnBack.setOnClickListener { findNavController().navigateUp() }
        buildAreaRows()
    }

    private fun buildAreaRows() {
        val container = binding.areaContainer
        container.removeAllViews()
        val gap = resources.displayMetrics.density * 8

        for (area in Constants.HSK_MIN_LEVEL..Constants.HSK_MAX_LEVEL) {
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundResource(R.drawable.bg_card)
                val pad = (gap * 1.5f).toInt()
                setPadding(pad, pad, pad, pad)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = gap.toInt() }
            }

            MonsterType.entries.forEach { type ->
                row.addView(createMonsterButton(area, type, gap.toInt()))
            }
            container.addView(row)

            // Staggered slide-in for a livelier entrance.
            row.alpha = 0f
            row.translationX = 40f
            row.animate().alpha(1f).translationX(0f)
                .setStartDelay(area * 40L).setDuration(300).start()
        }
    }

    private fun createMonsterButton(area: Int, type: MonsterType, gap: Int): MaterialButton {
        val label = when (type) {
            MonsterType.NORMAL -> "HSK $area"
            MonsterType.ELITE -> "Elite"
            MonsterType.BOSS -> "Boss"
        }
        val style = if (type == MonsterType.NORMAL) {
            com.google.android.material.R.attr.materialButtonStyle
        } else {
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        }
        return MaterialButton(requireContext(), null, style).apply {
            text = label
            setAllCaps(false)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = if (type != MonsterType.BOSS) gap else 0 }
            setOnClickListener {
                findNavController().navigate(
                    R.id.action_worldmap_to_battle,
                    bundleOf(
                        BattleFragment.ARG_AREA_ID to area,
                        BattleFragment.ARG_MONSTER_TYPE to type.apiName,
                    ),
                )
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
