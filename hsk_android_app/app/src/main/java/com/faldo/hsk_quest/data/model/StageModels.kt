package com.faldo.hsk_quest.data.model

enum class StageStatus {
    LOCKED,
    UNLOCKED,
    CLEARED,
}

data class StageNode(
    val id: String,
    val areaId: Int,
    val monsterType: MonsterType,
    val index: Int,
    val title: String,
    val subtitle: String,
    val maxHp: Int,
    val def: Int,
    val dmg: Int,
    val xpReward: Int,
    val coinReward: Int,
    val status: StageStatus,
    val stars: Int = 0,
    val isGatekeeper: Boolean = false,
) {
    val isLocked: Boolean get() = status == StageStatus.LOCKED
    val isCleared: Boolean get() = status == StageStatus.CLEARED
}

data class AreaInfo(
    val areaId: Int,
    val title: String,
    val chineseTitle: String,
    val description: String,
    val recommendedLevel: Int,
    val isUnlocked: Boolean,
    val clearedCount: Int,
    val totalCount: Int = 9,
) {
    val isFullyCleared: Boolean get() = clearedCount >= totalCount
}
