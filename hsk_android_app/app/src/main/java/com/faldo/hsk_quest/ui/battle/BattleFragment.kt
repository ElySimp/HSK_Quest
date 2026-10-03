package com.faldo.hsk_quest.ui.battle

import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import com.faldo.hsk_quest.R
import com.faldo.hsk_quest.data.model.MonsterType
import com.faldo.hsk_quest.engine.MonsterStats
import com.faldo.hsk_quest.ui.common.PlaceholderFragment
import com.faldo.hsk_quest.util.appContainer
import kotlinx.coroutines.launch

/**
 * Battle screen. Phase 1A only verifies navigation arguments, monster stat
 * generation and HSK JSON loading. The full flashcard battle arrives in Phase 2.
 */
class BattleFragment : PlaceholderFragment() {
    override val icon = "⚔️"
    override val titleRes = R.string.title_battle
    override val phaseLabel = "Phase 2"

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val areaId = arguments?.getInt(ARG_AREA_ID, 1) ?: 1
        val type = MonsterType.fromApiName(arguments?.getString(ARG_MONSTER_TYPE) ?: "normal")
        val monster = MonsterStats.create(areaId, type)

        viewLifecycleOwner.lifecycleScope.launch {
            val terms = runCatching { appContainer.hskJsonLoader.loadLevel(areaId) }.getOrNull()
            val sample = terms?.shuffled()?.take(3)?.joinToString("\n") {
                "  ${it.simplified}  ${it.pinyin}  ${it.english.take(24)}"
            }.orEmpty()
            binding.tvDetail.visibility = View.VISIBLE
            binding.tvDetail.text = buildString {
                appendLine(monster.name)
                appendLine("HP ${monster.maxHp}  DEF ${monster.def}  DMG ${monster.dmg}")
                appendLine("Reward ${monster.xpReward} XP / ${monster.coinReward} coins")
                appendLine()
                appendLine("HSK $areaId vocabulary: ${terms?.size ?: 0} terms")
                append(sample)
            }
        }
    }

    companion object {
        // Must match the <argument> names in nav_graph.xml.
        const val ARG_AREA_ID = "areaId"
        const val ARG_MONSTER_TYPE = "monsterType"
    }
}
