package com.faldo.hsk_quest.data.repository

import com.faldo.hsk_quest.data.local.TokenManager
import com.faldo.hsk_quest.data.local.dao.PlayerDao
import com.faldo.hsk_quest.data.local.entity.PlayerEntity
import com.faldo.hsk_quest.data.model.BattleResultRequest
import com.faldo.hsk_quest.data.model.BattleResultResponse
import com.faldo.hsk_quest.data.remote.ApiErrorParser
import com.faldo.hsk_quest.data.remote.ApiService
import com.faldo.hsk_quest.engine.XpCalculator
import com.faldo.hsk_quest.util.Resource
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

/**
 * Handles submitting battle outcomes and applying server-authoritative rewards and persistent HP to local state.
 */
class BattleRepository(
    private val api: ApiService,
    private val tokenManager: TokenManager,
    private val playerDao: PlayerDao,
) {

    suspend fun submitBattleResult(request: BattleResultRequest): Resource<BattleResultResponse> {
        return try {
            val response = api.submitBattleResult(request)
            val body = response.body()
            if (response.isSuccessful && body != null) {
                // Update local Room cache with persistent HP, coins, XP, and area progression
                val username = tokenManager.getUsername()
                if (username != null) {
                    val cached = playerDao.getByUsername(username)
                    if (cached != null) {
                        val current = cached.toModel()
                        val newCoins = if (request.result == "lose") {
                            val lost = min(500, (current.stats.coins * 0.10).toInt())
                            max(0, current.stats.coins - lost)
                        } else {
                            current.stats.coins + body.coinsEarned
                        }

                        val newLevel = body.newLevel ?: current.stats.level
                        val newHighestArea = if (request.result == "win" && request.monsterType == "boss") {
                            max(current.stats.highestAreaCleared, request.areaId)
                        } else {
                            current.stats.highestAreaCleared
                        }

                        val updatedStats = current.stats.copy(
                            xp = if (body.levelUp) 0 else (current.stats.xp + body.xpEarned),
                            xpToNextLevel = XpCalculator.xpToNextLevel(newLevel),
                            coins = newCoins,
                            level = newLevel,
                            unspentStatPoints = body.statPointsAvailable,
                            hp = body.survivalHp,
                            survivalHp = body.survivalHp,
                            highestAreaCleared = newHighestArea,
                        )
                        playerDao.upsert(PlayerEntity.fromModel(current.copy(stats = updatedStats)))
                    }
                }
                Resource.Success(body)
            } else {
                Resource.Error(ApiErrorParser.parse(response))
            }
        } catch (e: IOException) {
            Resource.Error(AuthRepository.NETWORK_ERROR)
        }
    }
}
