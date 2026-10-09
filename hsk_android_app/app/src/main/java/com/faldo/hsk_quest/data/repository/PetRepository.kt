package com.faldo.hsk_quest.data.repository

import com.faldo.hsk_quest.data.model.FeedPetRequest
import com.faldo.hsk_quest.data.model.FeedPetResponse
import com.faldo.hsk_quest.data.model.PetItem
import com.faldo.hsk_quest.data.model.PetListResponse
import com.faldo.hsk_quest.data.model.RpsRequest
import com.faldo.hsk_quest.data.model.RpsResponse
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

    suspend fun getActivePet(): Resource<PetItem?> = withContext(Dispatchers.IO) {
        try {
            val response = api.getActivePet()
            if (response.isSuccessful) {
                Resource.Success(response.body())
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
                Resource.Success(response.body()!!)
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
                Resource.Success(response.body()!!)
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
                Resource.Success(response.body()!!)
            } else {
                Resource.Error(ApiErrorParser.parse(response))
            }
        } catch (e: Exception) {
            Resource.Error(e.message ?: "Failed to play Rock-Paper-Scissors")
        }
    }

    suspend fun feedPet(foodId: String? = null): Resource<FeedPetResponse> = withContext(Dispatchers.IO) {
        try {
            val response = api.feedPet(FeedPetRequest(foodId = foodId))
            if (response.isSuccessful && response.body() != null) {
                Resource.Success(response.body()!!)
            } else {
                Resource.Error(ApiErrorParser.parse(response))
            }
        } catch (e: Exception) {
            Resource.Error(e.message ?: "Failed to feed pet")
        }
    }
}
