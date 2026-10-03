package com.faldo.hsk_quest.data.model

enum class MonsterType(val apiName: String) {
    NORMAL("normal"),
    ELITE("elite"),
    BOSS("boss");

    companion object {
        fun fromApiName(name: String): MonsterType =
            entries.firstOrNull { it.apiName == name } ?: NORMAL
    }
}

/**
 * A monster instance for a battle. Stats come from [com.faldo.hsk_quest.engine.MonsterStats].
 */
data class Monster(
    val id: String,
    val name: String,
    val areaId: Int,
    val type: MonsterType,
    val maxHp: Int,
    val def: Int,
    val dmg: Int,
    val xpReward: Int,
    val coinReward: Int,
)
