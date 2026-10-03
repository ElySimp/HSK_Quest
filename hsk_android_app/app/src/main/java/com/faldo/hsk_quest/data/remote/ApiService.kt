package com.faldo.hsk_quest.data.remote

import com.faldo.hsk_quest.data.model.LoginRequest
import com.faldo.hsk_quest.data.model.Player
import com.faldo.hsk_quest.data.model.RegisterRequest
import com.faldo.hsk_quest.data.model.RegisterResponse
import com.faldo.hsk_quest.data.model.TokenResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * Retrofit definition of the FastAPI "Brain Server" endpoints.
 * Endpoints for battle/shop/gacha/leaderboard are added in their respective phases.
 */
interface ApiService {

    @POST("api/auth/register")
    suspend fun register(@Body body: RegisterRequest): Response<RegisterResponse>

    @POST("api/auth/login")
    suspend fun login(@Body body: LoginRequest): Response<TokenResponse>

    @GET("api/player/me")
    suspend fun getMyProfile(): Response<Player>
}
