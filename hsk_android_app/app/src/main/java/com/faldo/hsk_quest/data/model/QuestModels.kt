package com.faldo.hsk_quest.data.model

import com.google.gson.annotations.SerializedName

data class DailyQuestItemResponse(
    @SerializedName("quest_key") val questKey: String,
    val title: String,
    val description: String,
    val icon: String,
    val progress: Int,
    val target: Int,
    @SerializedName("reward_diamonds") val rewardDiamonds: Int,
    @SerializedName("reward_coins") val rewardCoins: Int,
    @SerializedName("is_completed") val isCompleted: Boolean,
    @SerializedName("is_claimed") val isClaimed: Boolean,
    @SerializedName("can_claim") val canClaim: Boolean,
)

data class DailyQuestListResponse(
    @SerializedName("quest_date") val questDate: String,
    @SerializedName("completed_count") val completedCount: Int,
    @SerializedName("total_base_quests") val totalBaseQuests: Int,
    @SerializedName("claimable_count") val claimableCount: Int,
    @SerializedName("claimable_diamonds") val claimableDiamonds: Int,
    @SerializedName("total_diamonds") val totalDiamonds: Int,
    @SerializedName("total_coins") val totalCoins: Int,
    val quests: List<DailyQuestItemResponse>,
)

data class ClaimQuestResponse(
    val success: Boolean,
    @SerializedName("claimed_keys") val claimedKeys: List<String>,
    @SerializedName("diamonds_awarded") val diamondsAwarded: Int,
    @SerializedName("coins_awarded") val coinsAwarded: Int,
    @SerializedName("total_diamonds") val totalDiamonds: Int,
    @SerializedName("total_coins") val totalCoins: Int,
    val message: String,
    val daily: DailyQuestListResponse,
)
