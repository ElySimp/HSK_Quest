package com.faldo.hsk_quest.ui.pet

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.faldo.hsk_quest.R
import com.faldo.hsk_quest.data.model.PetItem
import com.faldo.hsk_quest.databinding.ItemPetCardBinding
import com.faldo.hsk_quest.util.SpriteAnimator

/**
 * Adapter for browsing and equipping pets from the player's collection.
 */
class PetAdapter(
    private val onEquipClick: (PetItem) -> Unit,
) : ListAdapter<PetItem, PetAdapter.PetViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PetViewHolder {
        val binding = ItemPetCardBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return PetViewHolder(binding, onEquipClick)
    }

    override fun onBindViewHolder(holder: PetViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class PetViewHolder(
        private val binding: ItemPetCardBinding,
        private val onEquipClick: (PetItem) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(pet: PetItem) {
            binding.tvPetThumbName.text = pet.name

            val rarityEmoji = when (pet.rarity.lowercase()) {
                "legendary" -> "🟡 Legendary"
                "epic" -> "🟣 Epic"
                "rare" -> "🔵 Rare"
                else -> "🟢 Common"
            }
            binding.tvPetThumbRarity.text = rarityEmoji

            // Resolve drawable
            val drawableRes = if (pet.spriteKey == "pet_dummy_2") {
                R.drawable.pet_dummy_2
            } else {
                R.drawable.pet_dummy_1
            }

            // Slice static frame 0 from row 0
            val frames = SpriteAnimator.loadFrames(
                imageView = binding.ivPetThumb,
                drawableRes = drawableRes,
                rows = 3,
                cols = 4,
                targetRow = 0,
                frameCount = 1
            )
            if (frames.isNotEmpty()) {
                binding.ivPetThumb.setImageBitmap(frames[0])
            } else {
                binding.ivPetThumb.setImageResource(drawableRes)
            }

            if (pet.isActive) {
                binding.root.setBackgroundResource(R.drawable.bg_card_gold_border)
                binding.btnEquipThumb.text = "Active"
                binding.btnEquipThumb.isEnabled = false
                binding.btnEquipThumb.setTextColor(
                    ContextCompat.getColor(binding.root.context, R.color.hq_gold)
                )
            } else {
                binding.root.setBackgroundResource(R.drawable.bg_card)
                binding.btnEquipThumb.text = "Equip"
                binding.btnEquipThumb.isEnabled = true
                binding.btnEquipThumb.setTextColor(
                    ContextCompat.getColor(binding.root.context, R.color.hq_text_primary)
                )
                binding.btnEquipThumb.setOnClickListener {
                    onEquipClick(pet)
                }
            }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<PetItem>() {
        override fun areItemsTheSame(oldItem: PetItem, newItem: PetItem): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: PetItem, newItem: PetItem): Boolean =
            oldItem == newItem
    }
}
