package com.faldo.hsk_quest.ui.shop

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.faldo.hsk_quest.R
import com.faldo.hsk_quest.data.model.InventoryItem
import com.faldo.hsk_quest.data.model.ShopItem
import com.faldo.hsk_quest.databinding.ItemShopCardBinding
import com.faldo.hsk_quest.util.SpriteAnimator

sealed class ShopUiItem {
    data class CatalogEntry(
        val item: ShopItem,
        val ownedQuantity: Int = 0,
        val cartQuantity: Int = 0,
    ) : ShopUiItem()

    data class BagEntry(
        val item: InventoryItem,
    ) : ShopUiItem()
}

private data class PotionSpriteMeta(
    @DrawableRes val drawableRes: Int,
    val cols: Int,
)

class ShopAdapter(
    private val onBuyClicked: (ShopItem) -> Unit = {},
    private val onUseClicked: (InventoryItem) -> Unit = {},
    private val onItemDetailClicked: ((ShopItem, Int) -> Unit)? = null,
    private val onCartDeltaClicked: ((ShopItem, Int) -> Unit)? = null,
) : ListAdapter<ShopUiItem, ShopAdapter.ShopViewHolder>(ShopDiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ShopViewHolder {
        val binding = ItemShopCardBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return ShopViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ShopViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: ShopViewHolder) {
        holder.stopAnimation()
        super.onViewRecycled(holder)
    }

    inner class ShopViewHolder(private val binding: ItemShopCardBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private var spriteAnimator: SpriteAnimator? = null

        fun stopAnimation() {
            spriteAnimator?.stop()
            spriteAnimator = null
        }

        private fun resolvePotionSprite(spriteRes: String?, itemId: String): PotionSpriteMeta? {
            return when {
                spriteRes == "potion_minor_red" || itemId == "minor_healing_potion" || itemId == "salve_elixir" ->
                    PotionSpriteMeta(R.drawable.potion_minor_red, cols = 9)
                spriteRes == "potion_regular_red" || itemId == "healing_potion" || itemId == "vitality_elixir" ->
                    PotionSpriteMeta(R.drawable.potion_regular_red, cols = 7)
                spriteRes == "potion_greater_red" || itemId == "greater_healing_potion" ->
                    PotionSpriteMeta(R.drawable.potion_greater_red, cols = 8)
                spriteRes == "potion_supreme_red" || itemId == "supreme_healing_potion" ->
                    PotionSpriteMeta(R.drawable.potion_supreme_red, cols = 13)
                else -> null
            }
        }

        private fun bindIconOrSprite(spriteRes: String?, itemId: String, iconEmoji: String) {
            val potionMeta = resolvePotionSprite(spriteRes, itemId)
            if (potionMeta != null) {
                binding.tvItemIcon.visibility = View.GONE
                binding.ivItemSprite.visibility = View.VISIBLE
                spriteAnimator = SpriteAnimator.startHorizontalStripAnimation(
                    imageView = binding.ivItemSprite,
                    drawableRes = potionMeta.drawableRes,
                    cols = potionMeta.cols,
                    fps = 8,
                    scaleFactor = 3,
                )
            } else {
                binding.ivItemSprite.visibility = View.GONE
                binding.tvItemIcon.visibility = View.VISIBLE
                binding.tvItemIcon.text = iconEmoji
            }
        }

        private fun bindHpBadge(hpRestorePct: Int, hpRestore: Int) {
            when {
                hpRestorePct > 0 -> {
                    binding.tvHpBadge.visibility = View.VISIBLE
                    binding.tvHpBadge.text = "+${hpRestorePct}% Max HP"
                }
                hpRestore > 0 -> {
                    binding.tvHpBadge.visibility = View.VISIBLE
                    binding.tvHpBadge.text = "+${hpRestore} HP"
                }
                else -> {
                    binding.tvHpBadge.visibility = View.GONE
                }
            }
        }

        private fun bindValueBadge(item: ShopItem) {
            when {
                item.hpRestore > 0 && item.priceCoins > 0 -> {
                    binding.tvValueBadge.visibility = View.VISIBLE
                    binding.tvValueBadge.text =
                        "⚡ ${"%.1f".format(item.hpRestore.toFloat() / item.priceCoins)} HP/🪙"
                }
                item.hpRestorePct > 0 -> {
                    binding.tvValueBadge.visibility = View.VISIBLE
                    binding.tvValueBadge.text = "⚡ Instant Battle Heal"
                }
                item.affectionBoost > 0 && item.priceCoins > 0 -> {
                    binding.tvValueBadge.visibility = View.VISIBLE
                    binding.tvValueBadge.text =
                        "⚡ ${"%.1f".format(item.affectionBoost.toFloat() / item.priceCoins)} ❤️/🪙"
                }
                else -> {
                    binding.tvValueBadge.visibility = View.GONE
                }
            }
        }

        fun bind(uiItem: ShopUiItem) {
            stopAnimation()
            when (uiItem) {
                is ShopUiItem.CatalogEntry -> {
                    val item = uiItem.item
                    bindIconOrSprite(item.spriteRes, item.id, item.iconEmoji)
                    binding.tvItemName.text = item.name
                    binding.tvItemNameZh.text = item.nameZh
                    binding.tvItemDesc.text = item.description

                    // HP badge
                    bindHpBadge(item.hpRestorePct, item.hpRestore)

                    // Affection badge
                    if (item.affectionBoost > 0) {
                        binding.tvAffectionBadge.visibility = View.VISIBLE
                        binding.tvAffectionBadge.text = "+${item.affectionBoost} ❤️"
                    } else {
                        binding.tvAffectionBadge.visibility = View.GONE
                    }

                    // Value efficiency badge
                    bindValueBadge(item)

                    // Price tag
                    binding.tvItemPriceTag.visibility = View.VISIBLE
                    binding.tvItemPriceTag.text = if (item.priceCoins > 0) {
                        "${item.priceCoins} 🪙"
                    } else {
                        "${item.priceDiamonds} 💎"
                    }

                    // Owned count
                    if (uiItem.ownedQuantity > 0) {
                        binding.tvItemOwnedCount.visibility = View.VISIBLE
                        binding.tvItemOwnedCount.text = "Owned: ${uiItem.ownedQuantity}"
                    } else {
                        binding.tvItemOwnedCount.visibility = View.GONE
                    }

                    // Root click opens item detail popup if configured
                    if (onItemDetailClicked != null) {
                        binding.root.setOnClickListener {
                            onItemDetailClicked.invoke(item, uiItem.ownedQuantity)
                        }
                    } else {
                        binding.root.setOnClickListener(null)
                    }

                    // Cart stepper or buy button
                    if (onCartDeltaClicked != null) {
                        if (uiItem.cartQuantity <= 0) {
                            binding.layoutCartStepper.visibility = View.GONE
                            binding.btnItemAction.visibility = View.VISIBLE
                            binding.btnItemAction.text = "+ Add"
                            binding.btnItemAction.isEnabled = true
                            binding.btnItemAction.setOnClickListener {
                                onCartDeltaClicked.invoke(item, 1)
                            }
                        } else {
                            binding.btnItemAction.visibility = View.GONE
                            binding.layoutCartStepper.visibility = View.VISIBLE
                            binding.tvCartQty.text = "${uiItem.cartQuantity}"
                            binding.btnCartMinus.setOnClickListener {
                                onCartDeltaClicked.invoke(item, -1)
                            }
                            binding.btnCartPlus.setOnClickListener {
                                onCartDeltaClicked.invoke(item, 1)
                            }
                        }
                    } else {
                        binding.layoutCartStepper.visibility = View.GONE
                        binding.btnItemAction.visibility = View.VISIBLE
                        binding.btnItemAction.apply {
                            text = if (item.priceCoins > 0) {
                                "${item.priceCoins} 🪙 Buy"
                            } else {
                                "${item.priceDiamonds} 💎 Buy"
                            }
                            isEnabled = true
                            setOnClickListener { onBuyClicked(item) }
                        }
                    }
                }

                is ShopUiItem.BagEntry -> {
                    val inv = uiItem.item
                    bindIconOrSprite(inv.spriteRes, inv.itemId, inv.iconEmoji)
                    binding.tvItemName.text = inv.name
                    binding.tvItemNameZh.text = inv.nameZh
                    binding.tvItemDesc.text = inv.description

                    // HP badge
                    bindHpBadge(inv.hpRestorePct, inv.hpRestore)

                    // Affection badge
                    if (inv.affectionBoost > 0) {
                        binding.tvAffectionBadge.visibility = View.VISIBLE
                        binding.tvAffectionBadge.text = "+${inv.affectionBoost} ❤️"
                    } else {
                        binding.tvAffectionBadge.visibility = View.GONE
                    }

                    binding.tvValueBadge.visibility = View.GONE
                    binding.tvItemPriceTag.visibility = View.GONE
                    binding.layoutCartStepper.visibility = View.GONE
                    binding.root.setOnClickListener(null)

                    // Quantity in bag
                    binding.tvItemOwnedCount.visibility = View.VISIBLE
                    binding.tvItemOwnedCount.text = "Bag: ${inv.quantity}x"

                    // Use button
                    binding.btnItemAction.visibility = View.VISIBLE
                    binding.btnItemAction.apply {
                        text = "Use"
                        isEnabled = true
                        setOnClickListener { onUseClicked(inv) }
                    }
                }
            }
        }
    }

    private object ShopDiffCallback : DiffUtil.ItemCallback<ShopUiItem>() {
        override fun areItemsTheSame(oldItem: ShopUiItem, newItem: ShopUiItem): Boolean {
            return when {
                oldItem is ShopUiItem.CatalogEntry && newItem is ShopUiItem.CatalogEntry -> {
                    oldItem.item.id == newItem.item.id
                }
                oldItem is ShopUiItem.BagEntry && newItem is ShopUiItem.BagEntry -> {
                    oldItem.item.id == newItem.item.id
                }
                else -> false
            }
        }

        override fun areContentsTheSame(oldItem: ShopUiItem, newItem: ShopUiItem): Boolean {
            return oldItem == newItem
        }
    }
}
