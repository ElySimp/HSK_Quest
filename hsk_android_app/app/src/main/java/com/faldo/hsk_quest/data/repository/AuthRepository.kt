package com.faldo.hsk_quest.data.repository

import com.faldo.hsk_quest.data.local.TokenManager
import com.faldo.hsk_quest.data.local.dao.PlayerDao
import com.faldo.hsk_quest.data.model.LoginRequest
import com.faldo.hsk_quest.data.model.RegisterRequest
import com.faldo.hsk_quest.data.remote.ApiErrorParser
import com.faldo.hsk_quest.data.remote.ApiService
import com.faldo.hsk_quest.util.Resource
import java.io.IOException

/**
 * Handles registration, login, session restore and logout.
 */
class AuthRepository(
    private val api: ApiService,
    private val tokenManager: TokenManager,
    private val playerDao: PlayerDao,
) {

    /** Returns true if a stored token exists (session can be restored). */
    suspend fun restoreSession(): Boolean = !tokenManager.load().isNullOrBlank()

    suspend fun register(username: String, password: String, hskTarget: Int): Resource<Unit> =
        safeCall {
            val response = api.register(RegisterRequest(username, password, hskTarget))
            val body = response.body()
            if (response.isSuccessful && body != null) {
                tokenManager.save(body.accessToken, body.user.username)
                Resource.Success(Unit)
            } else {
                Resource.Error(ApiErrorParser.parse(response))
            }
        }

    suspend fun login(username: String, password: String): Resource<Unit> =
        safeCall {
            val response = api.login(LoginRequest(username, password))
            val body = response.body()
            if (response.isSuccessful && body != null) {
                tokenManager.save(body.accessToken, username)
                Resource.Success(Unit)
            } else {
                Resource.Error(ApiErrorParser.parse(response))
            }
        }

    suspend fun logout() {
        tokenManager.clear()
        playerDao.clear()
    }

    private inline fun <T> safeCall(block: () -> Resource<T>): Resource<T> =
        try {
            block()
        } catch (e: IOException) {
            Resource.Error(NETWORK_ERROR)
        }

    companion object {
        const val NETWORK_ERROR = "NETWORK_ERROR"
    }
}
