package com.faldo.hsk_quest.data.model

import com.google.gson.annotations.SerializedName

/**
 * Player stats as returned by GET /api/player/me (PlayerStatsResponse).
 */
data class PlayerStats(
    val level: Int,
    val xp: Int,
    @SerializedName("xp_to_next_level") val xpToNextLevel: Int,
    @SerializedName("unspent_stat_points") val unspentStatPoints: Int,
    @SerializedName("stat_str") val statStr: Int,
    @SerializedName("stat_dex") val statDex: Int,
    @SerializedName("stat_def") val statDef: Int,
    @SerializedName("stat_vit") val statVit: Int,
    val hp: Int,
    @SerializedName("max_hp") val maxHp: Int,
    @SerializedName("survival_hp") val survivalHp: Int,
    val coins: Int,
    val diamonds: Int,
    val league: String,
    @SerializedName("highest_area_cleared") val highestAreaCleared: Int,
)

/**
 * Full player profile as returned by GET /api/player/me (PlayerProfileResponse).
 */
data class Player(
    @SerializedName("user_id") val userId: Int,
    val username: String,
    @SerializedName("hsk_target") val hskTarget: Int,
    val stats: PlayerStats,
)

/** Stats that can receive allocated points on level-up. */
enum class StatType(val apiName: String) {
    STR("str"),
    DEX("dex"),
    DEF("def"),
    VIT("vit"),
}
