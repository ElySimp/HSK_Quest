package com.faldo.hsk_quest.data.repository

import com.faldo.hsk_quest.data.model.BlackjackActionRequest
import com.faldo.hsk_quest.data.model.BlackjackSettleRequest
import com.faldo.hsk_quest.data.model.BlackjackStateResponse
import com.faldo.hsk_quest.data.model.FeedPetRequest
import com.faldo.hsk_quest.data.model.FeedPetResponse
import com.faldo.hsk_quest.data.model.PetItem
import com.faldo.hsk_quest.data.model.PetListResponse
import com.faldo.hsk_quest.data.model.RpsRequest
import com.faldo.hsk_quest.data.model.RpsResponse
import com.faldo.hsk_quest.data.model.RpsSettleRequest
import com.faldo.hsk_quest.data.remote.ApiErrorParser
import com.faldo.hsk_quest.data.remote.ApiService
import com.faldo.hsk_quest.util.Resource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repository handling companion pet data, Tamagotchi interactions, and mini-games.
 */
class PetRepository(
    private val api: ApiService,
) {

    @Volatile
    var cachedActivePet: PetItem? = null

    @Volatile
    var hasLoadedActivePet: Boolean = false

    @Volatile
    var cachedPetList: PetListResponse? = null

    fun clearMemoryCache() {
        cachedActivePet = null
        hasLoadedActivePet = false
        cachedPetList = null
    }

    fun updateActivePetAffectionLocal(affection: Float) {
        val clamped = affection.coerceIn(0f, 100f)
        val currentActive = cachedActivePet?.copy(affection = clamped)
        cachedActivePet = currentActive
        cachedPetList = cachedPetList?.let { listResp ->
            val updatedPets = listResp.pets.map { p ->
                if (p.isActive || p.id == currentActive?.id) p.copy(affection = clamped) else p
            }
            listResp.copy(
                pets = updatedPets,
                activePet = currentActive ?: updatedPets.firstOrNull { it.isActive },
            )
        }
    }

    suspend fun getActivePet(): Resource<PetItem?> = withContext(Dispatchers.IO) {
        try {
            val response = api.getActivePet()
            if (response.isSuccessful) {
                val body = response.body()
                cachedActivePet = body
                hasLoadedActivePet = true
                Resource.Success(body)
            } else {
                Resource.Error(ApiErrorParser.parse(response))
            }
        } catch (e: Exception) {
            Resource.Error(e.message ?: "Failed to load active pet")
        }
    }

    suspend fun getPets(): Resource<PetListResponse> = withContext(Dispatchers.IO) {
        try {
            val response = api.getPets()
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                cachedPetList = body
                cachedActivePet = body.activePet
                hasLoadedActivePet = true
                Resource.Success(body)
            } else {
                Resource.Error(ApiErrorParser.parse(response))
            }
        } catch (e: Exception) {
            Resource.Error(e.message ?: "Failed to load pets collection")
        }
    }

    suspend fun equipPet(petId: Int): Resource<PetItem> = withContext(Dispatchers.IO) {
        try {
            val response = api.equipPet(petId)
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                cachedActivePet = body
                hasLoadedActivePet = true
                cachedPetList = cachedPetList?.let { currentList ->
                    currentList.copy(
                        pets = currentList.pets.map { it.copy(isActive = it.id == body.id) },
                        activePet = body,
                    )
                }
                Resource.Success(body)
            } else {
                Resource.Error(ApiErrorParser.parse(response))
            }
        } catch (e: Exception) {
            Resource.Error(e.message ?: "Failed to equip pet")
        }
    }

    suspend fun playRps(choice: String): Resource<RpsResponse> = withContext(Dispatchers.IO) {
        try {
            val response = api.playRps(RpsRequest(choice = choice))
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                updateActivePetAffectionLocal(body.currentAffection)
                Resource.Success(body)
            } else {
                Resource.Error(ApiErrorParser.parse(response))
            }
        } catch (e: Exception) {
            Resource.Error(e.message ?: "Failed to play Rock-Paper-Scissors")
        }
    }

    suspend fun settleRps(request: RpsSettleRequest): Resource<RpsResponse> = withContext(Dispatchers.IO) {
        try {
            val response = api.settleRps(request)
            val body = response.body()
            if (response.isSuccessful && body != null) {
                updateActivePetAffectionLocal(body.currentAffection)
                Resource.Success(body)
            } else {
                Resource.Error(ApiErrorParser.parse(response))
            }
        } catch (e: Exception) {
            Resource.Error(e.message ?: "Failed to sync Rock-Paper-Scissors")
        }
    }

    suspend fun settleBlackjack(request: BlackjackSettleRequest): Resource<BlackjackStateResponse> = withContext(Dispatchers.IO) {
        try {
            val response = api.settleBlackjack(request)
            val body = response.body()
            if (response.isSuccessful && body != null) {
                updateActivePetAffectionLocal(body.currentAffection)
                Resource.Success(body)
            } else {
                Resource.Error(ApiErrorParser.parse(response))
            }
        } catch (e: Exception) {
            Resource.Error(e.message ?: "Failed to sync Blackjack")
        }
    }

    suspend fun startBlackjack(): Resource<BlackjackStateResponse> = withContext(Dispatchers.IO) {
        try {
            val response = api.startBlackjack()
            val body = response.body()
            if (response.isSuccessful && body != null) {
                if (body.status != "playing") {
                    updateActivePetAffectionLocal(body.currentAffection)
                }
                Resource.Success(body)
            } else {
                Resource.Error(ApiErrorParser.parse(response))
            }
        } catch (e: Exception) {
            Resource.Error(e.message ?: "Failed to start Blackjack")
        }
    }

    suspend fun hitBlackjack(gameToken: String): Resource<BlackjackStateResponse> = withContext(Dispatchers.IO) {
        try {
            val response = api.hitBlackjack(BlackjackActionRequest(gameToken = gameToken))
            val body = response.body()
            if (response.isSuccessful && body != null) {
                if (body.status != "playing") {
                    updateActivePetAffectionLocal(body.currentAffection)
                }
                Resource.Success(body)
            } else {
                Resource.Error(ApiErrorParser.parse(response))
            }
        } catch (e: Exception) {
            Resource.Error(e.message ?: "Failed to hit card")
        }
    }

    suspend fun standBlackjack(gameToken: String): Resource<BlackjackStateResponse> = withContext(Dispatchers.IO) {
        try {
            val response = api.standBlackjack(BlackjackActionRequest(gameToken = gameToken))
            val body = response.body()
            if (response.isSuccessful && body != null) {
                updateActivePetAffectionLocal(body.currentAffection)
                Resource.Success(body)
            } else {
                Resource.Error(ApiErrorParser.parse(response))
            }
        } catch (e: Exception) {
            Resource.Error(e.message ?: "Failed to stand")
        }
    }

    suspend fun feedPet(foodId: String? = null): Resource<FeedPetResponse> = withContext(Dispatchers.IO) {
        try {
            val response = api.feedPet(FeedPetRequest(foodId = foodId))
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                updateActivePetAffectionLocal(body.currentAffection)
                Resource.Success(body)
            } else {
                Resource.Error(ApiErrorParser.parse(response))
            }
        } catch (e: Exception) {
            Resource.Error(e.message ?: "Failed to feed pet")
        }
    }
}
