package com.faldo.hsk_quest.ui.pet

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.faldo.hsk_quest.R
import com.faldo.hsk_quest.data.model.PetItem
import com.faldo.hsk_quest.databinding.ItemPetCardBinding
import com.faldo.hsk_quest.engine.PetBuffCatalog
import com.faldo.hsk_quest.util.SpriteAnimator

/**
 * Adapter for browsing and equipping rarity-sorted companions in the Sanctuary roster grid.
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
            val ctx = binding.root.context
            binding.tvPetThumbName.text = pet.name

            val (rarityLabel, rarityColorRes, borderRes) = when (pet.rarity.lowercase()) {
                "secret" -> Triple("🔮 SECRET", R.color.hq_crimson, R.drawable.bg_card_gold_border)
                "legendary" -> Triple("🟡 LEGENDARY", R.color.hq_gold, R.drawable.bg_card_gold_border)
                "epic" -> Triple("🟣 EPIC", R.color.hq_violet, R.drawable.bg_card_epic_border)
                "rare" -> Triple("🔵 RARE", R.color.hq_sky, R.drawable.bg_card_rare_border)
                else -> Triple("🟢 COMMON", R.color.hq_jade, R.drawable.bg_card_common_border)
            }
            binding.tvPetThumbRarity.text = rarityLabel
            binding.tvPetThumbRarity.setTextColor(ContextCompat.getColor(ctx, rarityColorRes))
            binding.cardPetRoot.setBackgroundResource(borderRes)

            val spec = PetBuffCatalog.resolveSpec(pet)
            binding.tvPetThumbBuff.text = "${spec.title} • ${spec.shortBadge}"

            val affInt = pet.affection.toInt().coerceIn(0, 100)
            binding.progressThumbAffection.progress = affInt
            binding.progressThumbAffection.setIndicatorColor(
                ContextCompat.getColor(
                    ctx,
                    if (pet.isSleeping) R.color.hq_crimson else R.color.hq_gold,
                )
            )
            binding.tvThumbAffection.text = "♥ $affInt"

            when {
                pet.isActive -> {
                    binding.tvPetThumbStatus.visibility = View.VISIBLE
                    binding.tvPetThumbStatus.text = if (pet.isSleeping) "★ SLEEP" else "★ ACTIVE"
                    binding.tvPetThumbStatus.setTextColor(
                        ContextCompat.getColor(ctx, if (pet.isSleeping) R.color.hq_crimson else R.color.hq_gold)
                    )
                }
                pet.isSleeping -> {
                    binding.tvPetThumbStatus.visibility = View.VISIBLE
                    binding.tvPetThumbStatus.text = "💤 REST"
                    binding.tvPetThumbStatus.setTextColor(ContextCompat.getColor(ctx, R.color.hq_crimson))
                }
                else -> {
                    binding.tvPetThumbStatus.visibility = View.GONE
                }
            }

            val drawableRes = SpriteAnimator.resolvePetDrawable(ctx, pet.petType, pet.spriteKey)
            val frames = SpriteAnimator.loadFrames(
                imageView = binding.ivPetThumb,
                drawableRes = drawableRes,
                rows = 3,
                cols = 4,
                targetRow = 0,
                frameCount = 1,
            )
            if (frames.isNotEmpty()) {
                binding.ivPetThumb.setImageBitmap(frames[0])
            } else {
                binding.ivPetThumb.setImageResource(drawableRes)
            }

            if (pet.isActive) {
                binding.btnEquipThumb.text = "Equipped ★"
                binding.btnEquipThumb.isEnabled = false
                binding.btnEquipThumb.setTextColor(ContextCompat.getColor(ctx, R.color.hq_gold))
                binding.btnEquipThumb.strokeColor = ColorStateList.valueOf(
                    ContextCompat.getColor(ctx, R.color.hq_stroke_gold)
                )
            } else {
                binding.btnEquipThumb.text = "Equip"
                binding.btnEquipThumb.isEnabled = true
                binding.btnEquipThumb.setTextColor(ContextCompat.getColor(ctx, R.color.hq_text_primary))
                binding.btnEquipThumb.strokeColor = ColorStateList.valueOf(
                    ContextCompat.getColor(ctx, rarityColorRes)
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
