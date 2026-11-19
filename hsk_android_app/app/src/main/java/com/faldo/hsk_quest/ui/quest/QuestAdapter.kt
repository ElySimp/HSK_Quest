package com.faldo.hsk_quest.ui.quest

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.faldo.hsk_quest.R
import com.faldo.hsk_quest.data.model.DailyQuestItemResponse
import com.faldo.hsk_quest.databinding.ItemQuestCardBinding

/**
 * Adapter rendering today's Daily Quests, live progress bars, Diamond/Coin rewards, and claim actions.
 */
class QuestAdapter(
    private val onClaimClick: (DailyQuestItemResponse) -> Unit,
) : ListAdapter<DailyQuestItemResponse, QuestAdapter.QuestViewHolder>(DiffCallback) {

    var claimingKey: String? = null
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QuestViewHolder {
        val binding = ItemQuestCardBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return QuestViewHolder(binding) { claimingKey }
    }

    override fun onBindViewHolder(holder: QuestViewHolder, position: Int) {
        holder.bind(getItem(position), onClaimClick)
    }

    class QuestViewHolder(
        private val binding: ItemQuestCardBinding,
        private val currentClaimingKey: () -> String?,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(
            quest: DailyQuestItemResponse,
            onClaimClick: (DailyQuestItemResponse) -> Unit,
        ) {
            bindQuestCard(binding, quest, currentClaimingKey(), onClaimClick)
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<DailyQuestItemResponse>() {
        override fun areItemsTheSame(
            oldItem: DailyQuestItemResponse,
            newItem: DailyQuestItemResponse,
        ): Boolean = oldItem.questKey == newItem.questKey

        override fun areContentsTheSame(
            oldItem: DailyQuestItemResponse,
            newItem: DailyQuestItemResponse,
        ): Boolean = oldItem == newItem

        fun bindQuestCard(
            binding: ItemQuestCardBinding,
            quest: DailyQuestItemResponse,
            claimingKey: String?,
            onClaimClick: (DailyQuestItemResponse) -> Unit,
        ) {
            val ctx = binding.root.context
            val target = quest.target.coerceAtLeast(1)
            val progress = quest.progress.coerceIn(0, target)

            binding.tvQuestIcon.text = quest.icon.ifBlank { "📜" }
            binding.tvQuestTitle.text = quest.title
            binding.tvQuestDesc.text = quest.description
            binding.progressQuest.max = target
            binding.progressQuest.progress = progress
            binding.tvQuestProgress.text = "$progress / $target"

            binding.tvQuestRewards.text = buildString {
                if (quest.rewardDiamonds > 0) append("+${quest.rewardDiamonds} 💎")
                if (quest.rewardDiamonds > 0 && quest.rewardCoins > 0) append("   ")
                if (quest.rewardCoins > 0) append("+${quest.rewardCoins} 🪙")
            }

            val isBusyThis = claimingKey == quest.questKey || claimingKey == "__ALL__"

            when {
                quest.isClaimed -> {
                    binding.cardQuestRoot.setBackgroundResource(R.drawable.bg_card_common_border)
                    binding.cardQuestRoot.alpha = 0.80f
                    binding.progressQuest.setIndicatorColor(ContextCompat.getColor(ctx, R.color.hq_jade))
                    binding.btnQuestClaim.text = "Claimed ✓"
                    binding.btnQuestClaim.isEnabled = false
                    binding.btnQuestClaim.setOnClickListener(null)
                    binding.btnQuestClaim.backgroundTintList = ColorStateList.valueOf(
                        ContextCompat.getColor(ctx, R.color.hq_surface_high)
                    )
                    binding.btnQuestClaim.setTextColor(ContextCompat.getColor(ctx, R.color.hq_jade))
                }
                quest.canClaim -> {
                    binding.cardQuestRoot.setBackgroundResource(R.drawable.bg_card_gold_border)
                    binding.cardQuestRoot.alpha = 1.0f
                    binding.progressQuest.setIndicatorColor(ContextCompat.getColor(ctx, R.color.hq_gold))
                    binding.btnQuestClaim.text = if (isBusyThis) "..." else "Claim"
                    binding.btnQuestClaim.isEnabled = !isBusyThis
                    binding.btnQuestClaim.backgroundTintList = ColorStateList.valueOf(
                        ContextCompat.getColor(ctx, R.color.hq_gold)
                    )
                    binding.btnQuestClaim.setTextColor(ContextCompat.getColor(ctx, R.color.hq_bg_deep))
                    binding.btnQuestClaim.setOnClickListener { onClaimClick(quest) }
                }
                else -> {
                    binding.cardQuestRoot.setBackgroundResource(R.drawable.bg_card)
                    binding.cardQuestRoot.alpha = 1.0f
                    binding.progressQuest.setIndicatorColor(ContextCompat.getColor(ctx, R.color.hq_sky))
                    binding.btnQuestClaim.text = "$progress/$target"
                    binding.btnQuestClaim.isEnabled = false
                    binding.btnQuestClaim.setOnClickListener(null)
                    binding.btnQuestClaim.backgroundTintList = ColorStateList.valueOf(
                        ContextCompat.getColor(ctx, R.color.hq_surface_high)
                    )
                    binding.btnQuestClaim.setTextColor(ContextCompat.getColor(ctx, R.color.hq_text_muted))
                }
            }
        }
    }
}
