package com.faldo.hsk_quest.ui.shop

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.faldo.hsk_quest.data.model.InventoryItem
import com.faldo.hsk_quest.data.model.ShopItem
import com.faldo.hsk_quest.databinding.ItemShopCardBinding

sealed class ShopUiItem {
    data class CatalogEntry(
        val item: ShopItem,
        val ownedQuantity: Int = 0,
    ) : ShopUiItem()

    data class BagEntry(
        val item: InventoryItem,
    ) : ShopUiItem()
}

class ShopAdapter(
    private val onBuyClicked: (ShopItem) -> Unit,
    private val onUseClicked: (InventoryItem) -> Unit,
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

    inner class ShopViewHolder(private val binding: ItemShopCardBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(uiItem: ShopUiItem) {
            when (uiItem) {
                is ShopUiItem.CatalogEntry -> {
                    val item = uiItem.item
                    binding.tvItemIcon.text = item.iconEmoji
                    binding.tvItemName.text = item.name
                    binding.tvItemNameZh.text = item.nameZh
                    binding.tvItemDesc.text = item.description

                    // HP badge
                    if (item.hpRestore > 0) {
                        binding.tvHpBadge.visibility = View.VISIBLE
                        binding.tvHpBadge.text = "+${item.hpRestore} HP"
                    } else {
                        binding.tvHpBadge.visibility = View.GONE
                    }

                    // Affection badge
                    if (item.affectionBoost > 0) {
                        binding.tvAffectionBadge.visibility = View.VISIBLE
                        binding.tvAffectionBadge.text = "+${item.affectionBoost} ❤️"
                    } else {
                        binding.tvAffectionBadge.visibility = View.GONE
                    }

                    // Owned count
                    if (uiItem.ownedQuantity > 0) {
                        binding.tvItemOwnedCount.visibility = View.VISIBLE
                        binding.tvItemOwnedCount.text = "Owned: ${uiItem.ownedQuantity}"
                    } else {
                        binding.tvItemOwnedCount.visibility = View.GONE
                    }

                    // Price button
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

                is ShopUiItem.BagEntry -> {
                    val inv = uiItem.item
                    binding.tvItemIcon.text = inv.iconEmoji
                    binding.tvItemName.text = inv.name
                    binding.tvItemNameZh.text = inv.nameZh
                    binding.tvItemDesc.text = inv.description

                    // HP badge
                    if (inv.hpRestore > 0) {
                        binding.tvHpBadge.visibility = View.VISIBLE
                        binding.tvHpBadge.text = "+${inv.hpRestore} HP"
                    } else {
                        binding.tvHpBadge.visibility = View.GONE
                    }

                    // Affection badge
                    if (inv.affectionBoost > 0) {
                        binding.tvAffectionBadge.visibility = View.VISIBLE
                        binding.tvAffectionBadge.text = "+${inv.affectionBoost} ❤️"
                    } else {
                        binding.tvAffectionBadge.visibility = View.GONE
                    }

                    // Quantity in bag
                    binding.tvItemOwnedCount.visibility = View.VISIBLE
                    binding.tvItemOwnedCount.text = "Bag: ${inv.quantity}x"

                    // Use button
                    binding.btnItemAction.apply {
                        text = "Use (Gunakan)"
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
