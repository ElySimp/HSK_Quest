package com.faldo.hsk_quest.data.repository

import com.faldo.hsk_quest.data.local.TokenManager
import com.faldo.hsk_quest.data.local.dao.PlayerDao
import com.faldo.hsk_quest.data.local.dao.StageProgressDao
import com.faldo.hsk_quest.data.local.entity.StageProgressEntity
import com.faldo.hsk_quest.data.model.AreaInfo
import com.faldo.hsk_quest.data.model.MonsterType
import com.faldo.hsk_quest.data.model.StageNode
import com.faldo.hsk_quest.data.model.StageStatus
import com.faldo.hsk_quest.engine.MonsterStats
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Repository coordinating stage progression, sequential stage unlocks,
 * Organic Gatekeeper Boss rules, and offline-first Room persistence scoped per user.
 */
class StageRepository(
    private val stageDao: StageProgressDao,
    private val tokenManager: TokenManager,
    private val playerDao: PlayerDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private data class AreaMetadata(
        val title: String,
        val chineseTitle: String,
        val description: String,
        val recommendedLevel: Int,
    )

    private val areaMetadata = mapOf(
        1 to AreaMetadata("Bamboo Grove", "竹林幽径", "Serene mist hides fledgling wood beasts.", 1),
        2 to AreaMetadata("Peach Blossom Valley", "桃花源谷", "Blossoms mask deceptive serpent spirits.", 3),
        3 to AreaMetadata("Misty Mountain Shrine", "云雾古刹", "Ancient statues awaken with forgotten words.", 5),
        4 to AreaMetadata("Dragon Gate Rapids", "龙门飞瀑", "Roaring waters test courageous adventurers.", 8),
        5 to AreaMetadata("Phoenix Summit", "凤凰天顶", "Scorching volcanic trials of celestial flames.", 12),
        6 to AreaMetadata("Jade Palace Ruins", "玉虚古殿", "Imperial ruins guarded by stone warriors.", 16),
        7 to AreaMetadata("Abyssal Netherlands", "九幽渊薮", "Perilous dark realm of twisted phantoms.", 20),
        8 to AreaMetadata("Celestial Sky Pagoda", "通天浮屠", "High altar touching the realm of deities.", 25),
        9 to AreaMetadata("Primal Chaos Void", "太虚归一", "The ultimate expanse where master scholars reign.", 30),
    )

    fun getRecommendedLevel(areaId: Int): Int =
        areaMetadata[areaId]?.recommendedLevel ?: (areaId * 2)

    private suspend fun currentUsername(): String =
        tokenManager.getUsername() ?: "guest"

    fun getAllAreasInfoFlow(): Flow<List<AreaInfo>> = flow {
        val user = currentUsername()
        emitAll(
            stageDao.getAllProgressFlow(user).map { allProgress ->
                val clearedMap = allProgress.filter { it.isCleared }.groupBy { it.areaId }
                val bossClearedMap = allProgress.filter { it.isCleared && it.monsterType == "boss" }
                    .associateBy { it.areaId }

                val cachedPlayer = playerDao.getByUsername(user)
                val serverHighest = cachedPlayer?.highestAreaCleared ?: 0

                (1..9).map { areaId ->
                    val meta = areaMetadata[areaId] ?: AreaMetadata("HSK $areaId", "第${areaId}关", "", areaId * 2)
                    val isUnlocked = (areaId == 1) || (areaId <= serverHighest + 1) || (bossClearedMap.containsKey(areaId - 1))
                    val clearedCount = clearedMap[areaId]?.size ?: 0
                    AreaInfo(
                        areaId = areaId,
                        title = meta.title,
                        chineseTitle = meta.chineseTitle,
                        description = meta.description,
                        recommendedLevel = meta.recommendedLevel,
                        isUnlocked = isUnlocked,
                        clearedCount = clearedCount,
                    )
                }
            }
        )
    }.flowOn(ioDispatcher)

    fun getStageNodesForAreaFlow(areaId: Int): Flow<List<StageNode>> = flow {
        val user = currentUsername()
        emitAll(
            stageDao.getAllProgressFlow(user).map { allProgress ->
                val progressMap = allProgress.associateBy { it.stageId }

                val cachedPlayer = playerDao.getByUsername(user)
                val serverHighest = cachedPlayer?.highestAreaCleared ?: 0

            val bossClearedPrevArea = if (areaId == 1) true else {
                (areaId <= serverHighest + 1) || allProgress.any { it.areaId == (areaId - 1) && it.monsterType == "boss" && it.isCleared }
            }
            val isAreaUnlocked = (areaId == 1) || bossClearedPrevArea

            val nodes = ArrayList<StageNode>(9)

            // 1. Normal Stages (1 to 4)
            for (idx in 1..4) {
                val stageKey = "${user}_area${areaId}_normal_$idx"
                val entity = progressMap[stageKey]
                val isCleared = entity?.isCleared == true

                val isUnlocked = when {
                    !isAreaUnlocked -> false
                    idx == 1 -> true
                    else -> progressMap["${user}_area${areaId}_normal_${idx - 1}"]?.isCleared == true
                }

                val status = when {
                    isCleared -> StageStatus.CLEARED
                    isUnlocked -> StageStatus.UNLOCKED
                    else -> StageStatus.LOCKED
                }

                val monster = MonsterStats.create(areaId, MonsterType.NORMAL, idx)
                nodes.add(
                    StageNode(
                        id = stageKey,
                        areaId = areaId,
                        monsterType = MonsterType.NORMAL,
                        index = idx,
                        title = "Stage $areaId-$idx · ${monster.name}",
                        subtitle = "HSK $areaId Vocabulary Normal Encounter",
                        maxHp = monster.maxHp,
                        def = monster.def,
                        dmg = monster.dmg,
                        xpReward = monster.xpReward,
                        coinReward = monster.coinReward,
                        status = status,
                        stars = entity?.stars ?: 0,
                        isGatekeeper = false,
                    )
                )
            }

            // 2. Elite Stages (1 to 4)
            val normal4Cleared = progressMap["${user}_area${areaId}_normal_4"]?.isCleared == true
            for (idx in 1..4) {
                val stageKey = "${user}_area${areaId}_elite_$idx"
                val entity = progressMap[stageKey]
                val isCleared = entity?.isCleared == true

                val isUnlocked = when {
                    !isAreaUnlocked -> false
                    !normal4Cleared -> false
                    idx == 1 -> true
                    else -> progressMap["${user}_area${areaId}_elite_${idx - 1}"]?.isCleared == true
                }

                val status = when {
                    isCleared -> StageStatus.CLEARED
                    isUnlocked -> StageStatus.UNLOCKED
                    else -> StageStatus.LOCKED
                }

                val monster = MonsterStats.create(areaId, MonsterType.ELITE, idx)
                nodes.add(
                    StageNode(
                        id = stageKey,
                        areaId = areaId,
                        monsterType = MonsterType.ELITE,
                        index = idx,
                        title = "Elite Trial $areaId-$idx · ${monster.name}",
                        subtitle = "+50% HP · +250% Dmg · Double Rewards",
                        maxHp = monster.maxHp,
                        def = monster.def,
                        dmg = monster.dmg,
                        xpReward = monster.xpReward,
                        coinReward = monster.coinReward,
                        status = status,
                        stars = entity?.stars ?: 0,
                        isGatekeeper = false,
                    )
                )
            }

            // 3. Boss Stage (Organic Gatekeeper)
            val bossStageKey = "${user}_area${areaId}_boss_1"
            val bossEntity = progressMap[bossStageKey]
            val isBossCleared = bossEntity?.isCleared == true

            // Organic Gatekeeper Rule: Always unlocked if the Area is unlocked!
            val bossStatus = when {
                !isAreaUnlocked -> StageStatus.LOCKED
                isBossCleared -> StageStatus.CLEARED
                else -> StageStatus.UNLOCKED
            }

            val bossMonster = MonsterStats.create(areaId, MonsterType.BOSS, 1)
            nodes.add(
                StageNode(
                    id = bossStageKey,
                    areaId = areaId,
                    monsterType = MonsterType.BOSS,
                    index = 1,
                    title = "👑 Gatekeeper Boss · ${bossMonster.name}",
                    subtitle = "Organic Gatekeeper: Defeat to unlock next area immediately!",
                    maxHp = bossMonster.maxHp,
                    def = bossMonster.def,
                    dmg = bossMonster.dmg,
                    xpReward = bossMonster.xpReward,
                    coinReward = bossMonster.coinReward,
                    status = bossStatus,
                    stars = bossEntity?.stars ?: 0,
                    isGatekeeper = true,
                )
            )

                nodes
            }
        )
    }.flowOn(ioDispatcher)

    suspend fun recordStageCleared(
        areaId: Int,
        monsterTypeStr: String,
        stageIndex: Int,
        stars: Int = 3,
        highestDamage: Int = 0,
    ) = withContext(ioDispatcher) {
        val user = currentUsername()
        val stageKey = "${user}_area${areaId}_${monsterTypeStr.lowercase()}_$stageIndex"
        val existing = stageDao.getStage(stageKey)
        val bestStars = maxOf(existing?.stars ?: 0, stars)
        val bestDamage = maxOf(existing?.highestDamage ?: 0, highestDamage)

        stageDao.saveStage(
            StageProgressEntity(
                stageId = stageKey,
                username = user,
                areaId = areaId,
                monsterType = monsterTypeStr.lowercase(),
                stageIndex = stageIndex,
                isCleared = true,
                clearedAt = System.currentTimeMillis(),
                stars = bestStars,
                highestDamage = bestDamage,
            )
        )
    }

    suspend fun isAreaUnlocked(areaId: Int): Boolean = withContext(ioDispatcher) {
        if (areaId <= 1) return@withContext true
        val user = currentUsername()
        val cachedPlayer = playerDao.getByUsername(user)
        if ((cachedPlayer?.highestAreaCleared ?: 0) >= areaId - 1) return@withContext true
        stageDao.isBossClearedForArea(user, areaId - 1) > 0
    }
}
