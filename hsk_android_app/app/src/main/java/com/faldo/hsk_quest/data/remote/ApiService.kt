package com.faldo.hsk_quest.data.remote

import com.faldo.hsk_quest.data.model.AllocateStatRequest
import com.faldo.hsk_quest.data.model.BattleResultRequest
import com.faldo.hsk_quest.data.model.BattleResultResponse
import com.faldo.hsk_quest.data.model.DailyClaimResponse
import com.faldo.hsk_quest.data.model.DailyStatusResponse
import com.faldo.hsk_quest.data.model.LoginRequest
import com.faldo.hsk_quest.data.model.Player
import com.faldo.hsk_quest.data.model.PlayerProfileResponse
import com.faldo.hsk_quest.data.model.PlayerStats
import com.faldo.hsk_quest.data.model.RegisterRequest
import com.faldo.hsk_quest.data.model.RegisterResponse
import com.faldo.hsk_quest.data.model.TokenResponse
import com.faldo.hsk_quest.data.model.UpdateProfileRequest
import com.faldo.hsk_quest.data.model.WeeklyLeaderboardResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Query

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

    @PUT("api/player/profile")
    suspend fun updateProfile(@Body request: UpdateProfileRequest): Response<PlayerProfileResponse>

    @POST("api/player/allocate-stat")
    suspend fun allocateStat(@Body body: AllocateStatRequest): Response<PlayerStats>

    @GET("api/player/daily")
    suspend fun getDailyStatus(): Response<DailyStatusResponse>

    @POST("api/player/daily/claim")
    suspend fun claimDaily(): Response<DailyClaimResponse>

    @POST("api/battle/result")
    suspend fun submitBattleResult(@Body body: BattleResultRequest): Response<BattleResultResponse>

    @GET("api/pet/active")
    suspend fun getActivePet(): Response<com.faldo.hsk_quest.data.model.PetItem?>

    @GET("api/pet/list")
    suspend fun getPets(): Response<com.faldo.hsk_quest.data.model.PetListResponse>

    @POST("api/pet/equip/{pet_id}")
    suspend fun equipPet(@retrofit2.http.Path("pet_id") petId: Int): Response<com.faldo.hsk_quest.data.model.PetItem>

    @POST("api/pet/interact/rps")
    suspend fun playRps(@Body body: com.faldo.hsk_quest.data.model.RpsRequest): Response<com.faldo.hsk_quest.data.model.RpsResponse>

    @POST("api/pet/feed")
    suspend fun feedPet(@Body body: com.faldo.hsk_quest.data.model.FeedPetRequest): Response<com.faldo.hsk_quest.data.model.FeedPetResponse>

    @GET("api/gacha/rates")
    suspend fun getGachaRates(): Response<com.faldo.hsk_quest.data.model.GachaRatesResponse>

    @POST("api/gacha/pull")
    suspend fun pullGacha(): Response<com.faldo.hsk_quest.data.model.GachaPullResponse>

    @POST("api/gacha/pull-10")
    suspend fun pullTenGacha(): Response<com.faldo.hsk_quest.data.model.GachaMultiPullResponse>

    @GET("api/shop/catalog")
    suspend fun getShopCatalog(): Response<com.faldo.hsk_quest.data.model.ShopCatalogResponse>

    @POST("api/shop/buy")
    suspend fun buyShopItem(@Body body: com.faldo.hsk_quest.data.model.BuyItemRequest): Response<com.faldo.hsk_quest.data.model.BuyItemResponse>

    @GET("api/shop/inventory")
    suspend fun getInventory(): Response<com.faldo.hsk_quest.data.model.InventoryListResponse>

    @POST("api/shop/use")
    suspend fun useItem(@Body body: com.faldo.hsk_quest.data.model.UseItemRequest): Response<com.faldo.hsk_quest.data.model.UseItemResponse>

    @GET("api/leaderboard/weekly")
    suspend fun getWeeklyLeaderboard(
        @Query("league") league: String? = null,
    ): Response<WeeklyLeaderboardResponse>
}
