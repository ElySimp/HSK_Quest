package com.faldo.hsk_quest.data.repository

import com.faldo.hsk_quest.data.local.TokenManager
import com.faldo.hsk_quest.data.local.dao.PlayerDao
import com.faldo.hsk_quest.data.local.entity.PlayerEntity
import com.faldo.hsk_quest.data.model.AllocateStatRequest
import com.faldo.hsk_quest.data.model.DailyClaimResponse
import com.faldo.hsk_quest.data.model.DailyStatusResponse
import com.faldo.hsk_quest.data.model.Player
import com.faldo.hsk_quest.data.model.PlayerProfileResponse
import com.faldo.hsk_quest.data.model.PlayerStats
import com.faldo.hsk_quest.data.model.UpdateProfileRequest
import com.faldo.hsk_quest.data.remote.ApiErrorParser
import com.faldo.hsk_quest.data.remote.ApiService
import com.faldo.hsk_quest.util.Resource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * Offline-first access to the player profile, stat allocations, and daily rewards.
 */
class PlayerRepository(
    private val api: ApiService,
    private val tokenManager: TokenManager,
    private val playerDao: PlayerDao,
) {

    @Volatile
    var cachedDailyStatus: DailyStatusResponse? = null

    @Volatile
    var cachedMemoryPlayer: Player? = null

    fun clearMemoryCache() {
        cachedDailyStatus = null
        cachedMemoryPlayer = null
    }

    fun observeLocalPlayer(): Flow<Player?> =
        playerDao.observeLatest().map { entity ->
            entity?.toModel()?.also { cachedMemoryPlayer = it }
        }

    sealed class ProfileResult {
        data class Fresh(val player: Player) : ProfileResult()
        data class Cached(val player: Player) : ProfileResult()
        data object Unauthorized : ProfileResult()
        data class Failure(val message: String) : ProfileResult()
    }

    suspend fun fetchProfile(): ProfileResult {
        return try {
            val response = api.getMyProfile()
            val body = response.body()
            when {
                response.isSuccessful && body != null -> {
                    cachedMemoryPlayer = body
                    playerDao.upsert(PlayerEntity.fromModel(body))
                    ProfileResult.Fresh(body)
                }
                response.code() == 401 -> ProfileResult.Unauthorized
                else -> cachedOr(ApiErrorParser.parse(response))
            }
        } catch (e: IOException) {
            cachedOr(AuthRepository.NETWORK_ERROR)
        }
    }

    suspend fun updateProfile(username: String, hskTarget: Int): Resource<PlayerProfileResponse> {
        return try {
            val response = api.updateProfile(
                UpdateProfileRequest(
                    username = username.trim(),
                    hskTarget = hskTarget,
                )
            )
            val body = response.body()
            if (response.isSuccessful && body != null) {
                playerDao.upsert(PlayerEntity.fromModel(body))
                tokenManager.updateUsername(body.username)
                Resource.Success(body)
            } else {
                Resource.Error(ApiErrorParser.parse(response))
            }
        } catch (e: IOException) {
            Resource.Error(AuthRepository.NETWORK_ERROR)
        }
    }

    suspend fun allocateStat(statName: String): Resource<PlayerStats> {
        return try {
            val response = api.allocateStat(AllocateStatRequest(statName))
            val body = response.body()
            if (response.isSuccessful && body != null) {
                // Update local Room cache with updated stats if profile exists
                val username = tokenManager.getUsername()
                if (username != null) {
                    val cached = playerDao.getByUsername(username)
                    if (cached != null) {
                        playerDao.upsert(
                            PlayerEntity.fromModel(cached.toModel().copy(stats = body))
                        )
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

    suspend fun getDailyStatus(): Resource<DailyStatusResponse> {
        return try {
            val response = api.getDailyStatus()
            val body = response.body()
            if (response.isSuccessful && body != null) {
                cachedDailyStatus = body
                Resource.Success(body)
            } else {
                Resource.Error(ApiErrorParser.parse(response))
            }
        } catch (e: IOException) {
            Resource.Error(AuthRepository.NETWORK_ERROR)
        }
    }

    suspend fun claimDaily(): Resource<DailyClaimResponse> {
        return try {
            val response = api.claimDaily()
            val body = response.body()
            if (response.isSuccessful && body != null) {
                cachedDailyStatus = cachedDailyStatus?.copy(
                    canClaim = false,
                    streak = body.streak,
                ) ?: DailyStatusResponse(
                    canClaim = false,
                    streak = body.streak,
                    coinsReward = body.coinsAwarded,
                )
                // Update coins in cached profile
                val username = tokenManager.getUsername()
                if (username != null) {
                    val cached = playerDao.getByUsername(username)
                    if (cached != null) {
                        val updated = cached.toModel()
                        val updatedStats = updated.stats.copy(coins = body.totalCoins)
                        playerDao.upsert(PlayerEntity.fromModel(updated.copy(stats = updatedStats)))
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

    private suspend fun cachedOr(message: String): ProfileResult {
        val username = tokenManager.getUsername() ?: return ProfileResult.Failure(message)
        val cached = playerDao.getByUsername(username)
        return if (cached != null) {
            ProfileResult.Cached(cached.toModel())
        } else {
            ProfileResult.Failure(message)
        }
    }
}
