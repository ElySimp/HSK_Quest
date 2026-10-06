package com.faldo.hsk_quest.data.model

import com.google.gson.annotations.SerializedName

data class DailyStatusResponse(
    @SerializedName("can_claim") val canClaim: Boolean,
    val streak: Int,
    @SerializedName("coins_reward") val coinsReward: Int,
)

data class DailyClaimResponse(
    @SerializedName("coins_awarded") val coinsAwarded: Int,
    val streak: Int,
    @SerializedName("total_coins") val totalCoins: Int,
)
