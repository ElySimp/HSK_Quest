package com.faldo.hsk_quest.data.model

import com.google.gson.annotations.SerializedName

data class GachaRatesResponse(
    @SerializedName("common_rate") val commonRate: Float,
    @SerializedName("rare_rate") val rareRate: Float,
    @SerializedName("epic_rate") val epicRate: Float,
    @SerializedName("legendary_rate") val legendaryRate: Float,
    @SerializedName("secret_rate") val secretRate: Float = 0.005f,
    @SerializedName("active_total_rate") val activeTotalRate: Float = 0.995f,
    @SerializedName("cost_single") val costSingle: Int,
    @SerializedName("cost_ten") val costTen: Int,
)

data class GachaPullResponse(
    val success: Boolean,
    val pet: PetItem,
    @SerializedName("remaining_diamonds") val remainingDiamonds: Int,
    @SerializedName("is_new_companion") val isNewCompanion: Boolean,
    val message: String,
)

data class GachaMultiPullResponse(
    val success: Boolean,
    val pets: List<PetItem>,
    @SerializedName("remaining_diamonds") val remainingDiamonds: Int,
    @SerializedName("highest_rarity") val highestRarity: String,
    val message: String,
)
