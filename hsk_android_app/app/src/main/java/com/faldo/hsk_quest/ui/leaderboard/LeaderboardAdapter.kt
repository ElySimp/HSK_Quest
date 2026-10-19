package com.faldo.hsk_quest.ui.leaderboard

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.faldo.hsk_quest.R
import com.faldo.hsk_quest.data.model.LeaderboardEntry
import com.faldo.hsk_quest.databinding.ItemLeaderboardRankBinding
import java.text.NumberFormat
import java.util.Locale

class LeaderboardAdapter :
    ListAdapter<LeaderboardEntry, LeaderboardAdapter.RankViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RankViewHolder {
        val binding = ItemLeaderboardRankBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return RankViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RankViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class RankViewHolder(
        private val binding: ItemLeaderboardRankBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        private val numFmt = NumberFormat.getIntegerInstance(Locale.US)

        fun bind(entry: LeaderboardEntry) {
            val ctx = binding.root.context

            // Highlight the current player's row with the gold border card
            binding.rankCardRoot.setBackgroundResource(
                if (entry.isMe) R.drawable.bg_card_gold_border else R.drawable.bg_card
            )

            when (entry.rank) {
                1 -> {
                    binding.tvRankBadge.text = "🥇"
                    binding.tvRankBadge.textSize = 22f
                    binding.tvRankBadge.setTextColor(ContextCompat.getColor(ctx, R.color.hq_gold))
                }
                2 -> {
                    binding.tvRankBadge.text = "🥈"
                    binding.tvRankBadge.textSize = 22f
                    binding.tvRankBadge.setTextColor(ContextCompat.getColor(ctx, R.color.hq_silver))
                }
                3 -> {
                    binding.tvRankBadge.text = "🥉"
                    binding.tvRankBadge.textSize = 22f
                    binding.tvRankBadge.setTextColor(ContextCompat.getColor(ctx, R.color.hq_bronze))
                }
                else -> {
                    binding.tvRankBadge.text = "#${entry.rank}"
                    binding.tvRankBadge.textSize = 15f
                    binding.tvRankBadge.setTextColor(
                        ContextCompat.getColor(
                            ctx,
                            if (entry.isMe) R.color.hq_gold else R.color.hq_text_secondary
                        )
                    )
                }
            }

            binding.tvRankUsername.text = entry.username
            binding.tvRankUsername.setTextColor(
                ContextCompat.getColor(
                    ctx,
                    if (entry.isMe) R.color.hq_gold else R.color.hq_text_primary
                )
            )
            binding.tvYouBadge.visibility = if (entry.isMe) View.VISIBLE else View.GONE

            val areaLabel = if (entry.highestAreaCleared > 0) {
                "HSK ${entry.highestAreaCleared} Cleared"
            } else {
                "HSK 1 Challenger"
            }
            binding.tvRankSubtitle.text = "Lv. ${entry.level} · $areaLabel"

            binding.tvRankDamage.text = "⚔️ ${numFmt.format(entry.weeklyDamage)}"
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<LeaderboardEntry>() {
        override fun areItemsTheSame(oldItem: LeaderboardEntry, newItem: LeaderboardEntry): Boolean =
            oldItem.userId == newItem.userId

        override fun areContentsTheSame(oldItem: LeaderboardEntry, newItem: LeaderboardEntry): Boolean =
            oldItem == newItem
    }
}
