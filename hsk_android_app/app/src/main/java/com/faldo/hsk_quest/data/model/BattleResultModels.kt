package com.faldo.hsk_quest.data.model

import com.google.gson.annotations.SerializedName

data class BattleResultRequest(
    @SerializedName("monster_id") val monsterId: Int,
    @SerializedName("area_id") val areaId: Int,
    @SerializedName("monster_type") val monsterType: String,
    val result: String,
    @SerializedName("damage_dealt") val damageDealt: Int,
    @SerializedName("damage_taken") val damageTaken: Int,
    @SerializedName("questions_answered") val questionsAnswered: Int,
    @SerializedName("correct_answers") val correctAnswers: Int,
    @SerializedName("skill_type") val skillType: String = "reading",
)

data class BattleResultResponse(
    val result: String,
    @SerializedName("xp_earned") val xpEarned: Int,
    @SerializedName("coins_earned") val coinsEarned: Int,
    @SerializedName("damage_dealt") val damageDealt: Int,
    @SerializedName("level_up") val levelUp: Boolean,
    @SerializedName("new_level") val newLevel: Int?,
    @SerializedName("stat_points_available") val statPointsAvailable: Int,
    @SerializedName("survival_hp") val survivalHp: Int,
    @SerializedName("lost_item") val lostItem: String?,
    val message: String,
)
