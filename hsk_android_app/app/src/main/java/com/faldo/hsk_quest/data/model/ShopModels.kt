package com.faldo.hsk_quest.data.model

import com.google.gson.annotations.SerializedName

/**
 * Catalog item available in the shop.
 */
data class ShopItem(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("name_zh") val nameZh: String,
    @SerializedName("category") val category: String, // "elixir" | "food" | "pet_food"
    @SerializedName("description") val description: String,
    @SerializedName("hp_restore") val hpRestore: Int,
    @SerializedName("hp_restore_pct") val hpRestorePct: Int = 0,
    @SerializedName("affection_boost") val affectionBoost: Int,
    @SerializedName("price_coins") val priceCoins: Int,
    @SerializedName("price_diamonds") val priceDiamonds: Int,
    @SerializedName("icon_emoji") val iconEmoji: String,
    @SerializedName("sprite_res") val spriteRes: String? = null,
)

data class ShopCatalogResponse(
    @SerializedName("items") val items: List<ShopItem>,
)

data class BuyItemRequest(
    @SerializedName("item_id") val itemId: String,
    @SerializedName("quantity") val quantity: Int = 1,
)

data class BuyItemResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("message") val message: String,
    @SerializedName("item_id") val itemId: String,
    @SerializedName("quantity_bought") val quantityBought: Int,
    @SerializedName("total_owned") val totalOwned: Int,
    @SerializedName("remaining_coins") val remainingCoins: Int,
    @SerializedName("remaining_diamonds") val remainingDiamonds: Int,
)

/**
 * Consumable item held in player inventory bag.
 */
data class InventoryItem(
    @SerializedName("id") val id: Int,
    @SerializedName("item_id") val itemId: String,
    @SerializedName("item_type") val itemType: String,
    @SerializedName("quantity") val quantity: Int,
    @SerializedName("name") val name: String,
    @SerializedName("name_zh") val nameZh: String,
    @SerializedName("description") val description: String,
    @SerializedName("hp_restore") val hpRestore: Int,
    @SerializedName("hp_restore_pct") val hpRestorePct: Int = 0,
    @SerializedName("affection_boost") val affectionBoost: Int,
    @SerializedName("icon_emoji") val iconEmoji: String,
    @SerializedName("sprite_res") val spriteRes: String? = null,
)

data class InventoryListResponse(
    @SerializedName("items") val items: List<InventoryItem>,
)

data class UseItemRequest(
    @SerializedName("item_id") val itemId: String,
)

data class UseItemResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("message") val message: String,
    @SerializedName("hp_restored") val hpRestored: Int,
    @SerializedName("current_hp") val currentHp: Int,
    @SerializedName("max_hp") val maxHp: Int,
    @SerializedName("affection_gained") val affectionGained: Int,
    @SerializedName("current_affection") val currentAffection: Int?,
    @SerializedName("remaining_quantity") val remainingQuantity: Int,
)
