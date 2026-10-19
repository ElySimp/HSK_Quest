package com.faldo.hsk_quest.data.model

import com.google.gson.annotations.SerializedName

data class LeaderboardEntry(
    val rank: Int,
    @SerializedName("user_id") val userId: Int,
    val username: String,
    val level: Int,
    val league: String,
    @SerializedName("highest_area_cleared") val highestAreaCleared: Int = 0,
    @SerializedName("weekly_damage") val weeklyDamage: Int,
    @SerializedName("is_me") val isMe: Boolean = false,
)

data class WeeklyLeaderboardResponse(
    val league: String,
    @SerializedName("week_start") val weekStart: String,
    @SerializedName("week_end") val weekEnd: String,
    @SerializedName("seconds_until_reset") val secondsUntilReset: Int,
    val entries: List<LeaderboardEntry>,
    val me: LeaderboardEntry? = null,
    @SerializedName("my_league") val myLeague: String,
    @SerializedName("projected_reward_diamonds") val projectedRewardDiamonds: Int = 0,
    @SerializedName("projected_reward_title") val projectedRewardTitle: String? = null,
)
