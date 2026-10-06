package com.faldo.hsk_quest.data.repository

import com.faldo.hsk_quest.data.local.TokenManager
import com.faldo.hsk_quest.data.local.dao.PlayerDao
import com.faldo.hsk_quest.data.local.entity.PlayerEntity
import com.faldo.hsk_quest.data.model.BattleResultRequest
import com.faldo.hsk_quest.data.model.BattleResultResponse
import com.faldo.hsk_quest.data.remote.ApiErrorParser
import com.faldo.hsk_quest.data.remote.ApiService
import com.faldo.hsk_quest.util.Resource
import java.io.IOException

/**
 * Handles submitting battle outcomes and applying server-authoritative rewards to local state.
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
                // Update local Room cache
                val username = tokenManager.getUsername()
                if (username != null) {
                    val cached = playerDao.getByUsername(username)
                    if (cached != null) {
                        val current = cached.toModel()
                        val updatedStats = current.stats.copy(
                            xp = current.stats.xp + body.xpEarned,
                            coins = current.stats.coins + body.coinsEarned,
                            level = body.newLevel ?: current.stats.level,
                            unspentStatPoints = body.statPointsAvailable,
                            survivalHp = body.survivalHp,
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
