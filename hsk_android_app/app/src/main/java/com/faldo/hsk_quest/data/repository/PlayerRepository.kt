package com.faldo.hsk_quest.data.repository

import com.faldo.hsk_quest.data.local.TokenManager
import com.faldo.hsk_quest.data.local.dao.PlayerDao
import com.faldo.hsk_quest.data.local.entity.PlayerEntity
import com.faldo.hsk_quest.data.model.Player
import com.faldo.hsk_quest.data.remote.ApiErrorParser
import com.faldo.hsk_quest.data.remote.ApiService
import java.io.IOException

/**
 * Offline-first access to the player profile: fetch from the backend,
 * fall back to the Room cache when the server is unreachable.
 */
class PlayerRepository(
    private val api: ApiService,
    private val tokenManager: TokenManager,
    private val playerDao: PlayerDao,
) {

    sealed class ProfileResult {
        data class Fresh(val player: Player) : ProfileResult()
        data class Cached(val player: Player) : ProfileResult()

        /** The token was rejected by the server; the user must log in again. */
        data object Unauthorized : ProfileResult()
        data class Failure(val message: String) : ProfileResult()
    }

    suspend fun fetchProfile(): ProfileResult {
        return try {
            val response = api.getMyProfile()
            val body = response.body()
            when {
                response.isSuccessful && body != null -> {
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
