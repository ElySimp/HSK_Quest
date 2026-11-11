package com.faldo.hsk_quest.ui.worldmap

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.faldo.hsk_quest.MainActivity
import com.faldo.hsk_quest.R
import com.faldo.hsk_quest.data.model.AreaInfo
import com.faldo.hsk_quest.data.model.MonsterType
import com.faldo.hsk_quest.data.model.StageNode
import com.faldo.hsk_quest.databinding.FragmentWorldMapBinding
import com.faldo.hsk_quest.databinding.ItemStageCardBinding
import com.faldo.hsk_quest.ui.battle.BattleFragment
import com.faldo.hsk_quest.util.appContainer
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/**
 * World Map Screen featuring:
 * - 9 thematic HSK realms with animated realm transitions
 * - Sequential stage unlocking (Normal 1..4 -> Elite 1..4)
 * - Organic Gatekeeper Boss mechanism (direct skip challenge with 1-Hit KO safety warning)
 * - Offline-first Room stage progress persistence scoped per user
 */
class WorldMapFragment : Fragment() {

    private var _binding: FragmentWorldMapBinding? = null
    private val binding get() = _binding!!

    private val viewModel: WorldMapViewModel by viewModels {
        WorldMapViewModel.factory(appContainer.stageRepository, appContainer.playerRepository)
    }

    private var lastRenderedAreaId: Int = -1

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

        (activity as? MainActivity)?.attachScrollListener(binding.scrollWorldMap)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.areas.collect { renderAreaTabs(it, viewModel.selectedAreaId.value) } }
                launch { viewModel.selectedAreaId.collect { renderAreaTabs(viewModel.areas.value, it) } }
                launch { viewModel.currentAreaInfo.collect(::renderAreaBanner) }
                launch { viewModel.currentStages.collect(::renderStagesList) }
            }
        }
    }

    private fun renderAreaTabs(areas: List<AreaInfo>, selectedId: Int) {
        val container = binding.areaTabsContainer
        container.removeAllViews()
        val density = resources.displayMetrics.density

        areas.forEach { area ->
            val isSelected = area.areaId == selectedId
            val tab = MaterialButton(requireContext()).apply {
                val label = if (area.isUnlocked) {
                    if (area.isFullyCleared) "★ HSK ${area.areaId}" else "HSK ${area.areaId}"
                } else {
                    "🔒 HSK ${area.areaId}"
                }
                text = label
                isAllCaps = false
                textSize = 12f
                insetTop = 0
                insetBottom = 0
                cornerRadius = (density * 12).toInt()
                val heightPx = (density * 36).toInt()
                minHeight = heightPx
                minimumHeight = heightPx
                val padH = (density * 14).toInt()
                setPadding(padH, 0, padH, 0)

                if (isSelected) {
                    setBackgroundColor(ContextCompat.getColor(context, R.color.hq_gold_glow))
                    setTextColor(ContextCompat.getColor(context, R.color.hq_gold))
                    strokeColor = ContextCompat.getColorStateList(context, R.color.hq_gold)
                    strokeWidth = (density * 1.5f).toInt()
                } else {
                    setBackgroundColor(ContextCompat.getColor(context, R.color.hq_surface))
                    setTextColor(
                        ContextCompat.getColor(
                            context,
                            if (area.isUnlocked) R.color.hq_text_secondary else R.color.hq_text_muted
                        )
                    )
                    strokeColor = ContextCompat.getColorStateList(context, R.color.hq_stroke)
                    strokeWidth = (density * 1.0f).toInt()
                }

                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    heightPx,
                ).apply {
                    marginEnd = (density * 8).toInt()
                }

                setOnClickListener {
                    if (area.areaId != viewModel.selectedAreaId.value) {
                        viewModel.selectArea(area.areaId)
                    }
                }
            }
            container.addView(tab)
        }
    }

    private fun renderAreaBanner(info: AreaInfo?) {
        if (info == null) return
        with(binding) {
            tvAreaTitle.text = "HSK ${info.areaId} · ${info.title} (${info.chineseTitle})"
            tvAreaDesc.text = info.description
            tvAreaDesc.visibility = if (info.description.isNotBlank()) View.VISIBLE else View.GONE
            tvAreaClearedStars.text = "⭐ ${info.clearedCount}/${info.totalCount}"
            tvAreaRecLevel.text = "🛡️ Rec. Lv. ${info.recommendedLevel}"

            if (info.isUnlocked) {
                tvAreaLockStatus.text = "🔓 Realm Open"
                tvAreaLockStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.hq_jade))
            } else {
                tvAreaLockStatus.text = "🔒 Realm Locked"
                tvAreaLockStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.hq_crimson))
            }

            if (lastRenderedAreaId != -1 && lastRenderedAreaId != info.areaId) {
                cardAreaBanner.alpha = 0.4f
                cardAreaBanner.translationY = -8f
                cardAreaBanner.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(220L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            lastRenderedAreaId = info.areaId
        }
    }

    private fun renderStagesList(stages: List<StageNode>) {
        val container = binding.stagesContainer
        container.removeAllViews()

        if (stages.isEmpty()) return

        var animIndex = 0

        // 1. Normal Encounters Section
        addSectionHeader("⚔️ Normal Encounters · Stages 1–4")
        stages.filter { it.monsterType == MonsterType.NORMAL }.forEach { node ->
            addStageCard(node, animIndex++)
        }

        // 2. Elite Trials Section
        addSectionHeader("💠 Elite Trials · Stages 5–8 (2x Rewards)")
        stages.filter { it.monsterType == MonsterType.ELITE }.forEach { node ->
            addStageCard(node, animIndex++)
        }

        // 3. Organic Gatekeeper Boss Section
        addSectionHeader("👑 Realm Gatekeeper Boss · Direct Realm Skip")
        stages.filter { it.monsterType == MonsterType.BOSS }.forEach { node ->
            addStageCard(node, animIndex++)
        }
    }

    private fun addSectionHeader(title: String) {
        val header = TextView(requireContext()).apply {
            text = title
            setTextColor(ContextCompat.getColor(context, R.color.hq_gold))
            textSize = 12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            val padV = (resources.displayMetrics.density * 10).toInt()
            setPadding(4, padV, 4, (padV * 0.5f).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        binding.stagesContainer.addView(header)
    }

    private fun addStageCard(node: StageNode, staggerIndex: Int) {
        val cardBinding = ItemStageCardBinding.inflate(layoutInflater, binding.stagesContainer, false)
        val ctx = requireContext()

        with(cardBinding) {
            when {
                node.isGatekeeper -> {
                    stageCardRoot.setBackgroundResource(R.drawable.bg_card_gold_border)
                    tvStageNumber.text = "👑 BOSS"
                    tvStageNumber.setTextColor(ContextCompat.getColor(ctx, R.color.hq_gold))
                }
                node.monsterType == MonsterType.ELITE -> {
                    stageCardRoot.setBackgroundResource(R.drawable.bg_card_sky_border)
                    tvStageNumber.text = "ELITE ${node.index}"
                    tvStageNumber.setTextColor(ContextCompat.getColor(ctx, R.color.hq_sky))
                }
                else -> {
                    stageCardRoot.setBackgroundResource(R.drawable.bg_card)
                    tvStageNumber.text = "${node.areaId}-${node.index}"
                    tvStageNumber.setTextColor(ContextCompat.getColor(ctx, R.color.hq_text_primary))
                }
            }

            tvStageTitle.text = node.title
            tvStageSubtitle.text = node.subtitle
            tvMonsterStats.text = "❤️ ${node.maxHp} HP · 🛡️ ${node.def} DEF · ⚔️ ${node.dmg} Dmg"
            tvRewardsPreview.text = "+${node.xpReward} XP · +${node.coinReward} 🪙"

            if (node.isCleared) {
                tvStageStars.visibility = View.VISIBLE
                tvStageStars.text = "⭐".repeat(node.stars.coerceAtLeast(1))
                btnStageAction.isEnabled = true
                btnStageAction.alpha = 1.0f
                btnStageAction.text = "Rematch"
                btnStageAction.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(ctx, R.color.hq_surface_light)
                )
                btnStageAction.setTextColor(ContextCompat.getColor(ctx, R.color.hq_gold))
                btnStageAction.setOnClickListener { launchBattle(node) }
                stageCardRoot.setOnClickListener { launchBattle(node) }
            } else if (node.isLocked) {
                tvStageStars.visibility = View.GONE
                btnStageAction.isEnabled = false
                btnStageAction.alpha = 0.45f
                btnStageAction.text = "🔒 Locked"
                btnStageAction.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(ctx, R.color.hq_surface_high)
                )
                btnStageAction.setTextColor(ContextCompat.getColor(ctx, R.color.hq_text_muted))
            } else {
                tvStageStars.visibility = View.GONE
                btnStageAction.isEnabled = true
                btnStageAction.alpha = 1.0f
                btnStageAction.text = if (node.isGatekeeper) "👑 Boss" else "⚔️ Battle"
                btnStageAction.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(ctx, R.color.hq_gold)
                )
                btnStageAction.setTextColor(ContextCompat.getColor(ctx, R.color.hq_bg_deep))
                val clickAction = {
                    if (viewModel.shouldWarnGatekeeperBoss(node)) {
                        showGatekeeperWarningDialog(node)
                    } else {
                        launchBattle(node)
                    }
                }
                btnStageAction.setOnClickListener { clickAction() }
                stageCardRoot.setOnClickListener { clickAction() }
            }

            // Smooth staggered card entrance
            root.alpha = 0f
            root.translationY = 18f
            root.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay((staggerIndex * 24L).coerceAtMost(180L))
                .setDuration(220L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }

        binding.stagesContainer.addView(cardBinding.root)
    }

    private fun showGatekeeperWarningDialog(node: StageNode) {
        val playerLvl = viewModel.playerLevel.value
        val recLvl = viewModel.currentAreaInfo.value?.recommendedLevel ?: (node.areaId * 2)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("⚠️ Gatekeeper Boss Trial")
            .setMessage(
                "You are challenging the HSK ${node.areaId} Gatekeeper Boss at Lv. $playerLvl " +
                    "(Recommended: Lv. $recLvl).\n\n" +
                    "Gatekeepers allow skilled scholars to unlock the next HSK realm immediately, " +
                    "but wrong answers trigger a 1-Hit KO Crushing Blow unless absorbed by a Pet Shield!\n\n" +
                    "Do you dare proceed?"
            )
            .setPositiveButton("⚔️ Challenge Boss") { _, _ ->
                launchBattle(node)
            }
            .setNegativeButton("Train More", null)
            .show()
    }

    private fun launchBattle(node: StageNode) {
        val args = Bundle().apply {
            putInt(BattleFragment.ARG_AREA_ID, node.areaId)
            putString(BattleFragment.ARG_MONSTER_TYPE, node.monsterType.apiName)
            putInt(BattleFragment.ARG_MONSTER_INDEX, node.index)
        }
        findNavController().navigate(R.id.action_worldmap_to_battle, args)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
