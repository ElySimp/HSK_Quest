package com.faldo.hsk_quest.data.remote

import com.faldo.hsk_quest.data.model.AllocateStatRequest
import com.faldo.hsk_quest.data.model.BattleResultRequest
import com.faldo.hsk_quest.data.model.BattleResultResponse
import com.faldo.hsk_quest.data.model.DailyClaimResponse
import com.faldo.hsk_quest.data.model.DailyStatusResponse
import com.faldo.hsk_quest.data.model.LoginRequest
import com.faldo.hsk_quest.data.model.Player
import com.faldo.hsk_quest.data.model.PlayerStats
import com.faldo.hsk_quest.data.model.RegisterRequest
import com.faldo.hsk_quest.data.model.RegisterResponse
import com.faldo.hsk_quest.data.model.TokenResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * Retrofit definition of the FastAPI "Brain Server" endpoints.
 */
interface ApiService {

    @POST("api/auth/register")
    suspend fun register(@Body body: RegisterRequest): Response<RegisterResponse>

    @POST("api/auth/login")
    suspend fun login(@Body body: LoginRequest): Response<TokenResponse>

    @GET("api/player/me")
    suspend fun getMyProfile(): Response<Player>

    @POST("api/player/allocate-stat")
    suspend fun allocateStat(@Body body: AllocateStatRequest): Response<PlayerStats>

    @GET("api/player/daily")
    suspend fun getDailyStatus(): Response<DailyStatusResponse>

    @POST("api/player/daily/claim")
    suspend fun claimDaily(): Response<DailyClaimResponse>

    @POST("api/battle/result")
    suspend fun submitBattleResult(@Body body: BattleResultRequest): Response<BattleResultResponse>
}
