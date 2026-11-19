package com.faldo.hsk_quest.data.model

import com.google.gson.annotations.SerializedName

data class PetItem(
    val id: Int,
    val name: String,
    @SerializedName("pet_type") val petType: String,
    val rarity: String,
    @SerializedName("buff_type") val buffType: String,
    @SerializedName("buff_title") val buffTitle: String? = null,
    @SerializedName("buff_description") val buffDescription: String? = null,
    val affection: Float,
    @SerializedName("is_active") val isActive: Boolean,
    @SerializedName("sprite_key") val spriteKey: String,
) {
    val isSleeping: Boolean get() = affection < 25f
}

data class PetListResponse(
    val pets: List<PetItem>,
    @SerializedName("active_pet") val activePet: PetItem?,
)

data class RpsRequest(
    val choice: String,
)

data class RpsResponse(
    @SerializedName("player_choice") val playerChoice: String,
    @SerializedName("pet_choice") val petChoice: String,
    val outcome: String,
    @SerializedName("affection_gained") val affectionGained: Float,
    @SerializedName("current_affection") val currentAffection: Float,
    @SerializedName("coins_dropped") val coinsDropped: Int,
    @SerializedName("remaining_coins") val remainingCoins: Int? = null,
    val message: String,
)

data class BlackjackActionRequest(
    @SerializedName("game_token") val gameToken: String,
)

data class BlackjackStateResponse(
    @SerializedName("game_token") val gameToken: String? = null,
    val status: String,
    @SerializedName("player_cards") val playerCards: List<String>,
    @SerializedName("dealer_cards") val dealerCards: List<String>,
    @SerializedName("player_score") val playerScore: Int,
    @SerializedName("dealer_score") val dealerScore: Int,
    @SerializedName("bet_coins") val betCoins: Int,
    @SerializedName("coins_delta") val coinsDelta: Int,
    @SerializedName("remaining_coins") val remainingCoins: Int,
    @SerializedName("affection_gained") val affectionGained: Float,
    @SerializedName("current_affection") val currentAffection: Float,
    val message: String,
)

data class FeedPetRequest(
    @SerializedName("food_id") val foodId: String? = null,
)

data class FeedPetResponse(
    val success: Boolean,
    @SerializedName("affection_gained") val affectionGained: Float,
    @SerializedName("current_affection") val currentAffection: Float,
    @SerializedName("hp_healed") val hpHealed: Int,
    @SerializedName("current_player_hp") val currentPlayerHp: Int,
    val message: String,
)

data class RpsSettleRequest(
    @SerializedName("round_id") val roundId: String,
    @SerializedName("player_choice") val playerChoice: String,
    @SerializedName("pet_choice") val petChoice: String,
    val outcome: String,
    @SerializedName("coins_dropped") val coinsDropped: Int,
)

data class BlackjackSettleRequest(
    @SerializedName("round_id") val roundId: String,
    @SerializedName("player_cards") val playerCards: List<String>,
    @SerializedName("dealer_cards") val dealerCards: List<String>,
    val outcome: String,
)
