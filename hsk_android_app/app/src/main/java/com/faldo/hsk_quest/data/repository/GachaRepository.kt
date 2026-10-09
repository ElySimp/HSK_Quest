package com.faldo.hsk_quest.data.repository

import com.faldo.hsk_quest.data.model.GachaMultiPullResponse
import com.faldo.hsk_quest.data.model.GachaPullResponse
import com.faldo.hsk_quest.data.model.GachaRatesResponse
import com.faldo.hsk_quest.data.remote.ApiErrorParser
import com.faldo.hsk_quest.data.remote.ApiService
import com.faldo.hsk_quest.util.Resource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repository for Gacha Altar summons and probability rates.
 */
class GachaRepository(
    private val api: ApiService,
) {

    suspend fun getRates(): Resource<GachaRatesResponse> = withContext(Dispatchers.IO) {
        try {
            val response = api.getGachaRates()
            if (response.isSuccessful && response.body() != null) {
                Resource.Success(response.body()!!)
            } else {
                Resource.Error(ApiErrorParser.parse(response))
            }
        } catch (e: Exception) {
            Resource.Error(e.message ?: "Failed to load summoning rates")
        }
    }

    suspend fun pullSingle(): Resource<GachaPullResponse> = withContext(Dispatchers.IO) {
        try {
            val response = api.pullGacha()
            if (response.isSuccessful && response.body() != null) {
                Resource.Success(response.body()!!)
            } else {
                Resource.Error(ApiErrorParser.parse(response))
            }
        } catch (e: Exception) {
            Resource.Error(e.message ?: "Failed to perform summon")
        }
    }

    suspend fun pullTen(): Resource<GachaMultiPullResponse> = withContext(Dispatchers.IO) {
        try {
            val response = api.pullTenGacha()
            if (response.isSuccessful && response.body() != null) {
                Resource.Success(response.body()!!)
            } else {
                Resource.Error(ApiErrorParser.parse(response))
            }
        } catch (e: Exception) {
            Resource.Error(e.message ?: "Failed to perform 10x summon")
        }
    }
}
