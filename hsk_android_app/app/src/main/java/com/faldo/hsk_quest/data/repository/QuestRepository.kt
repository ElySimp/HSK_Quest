package com.faldo.hsk_quest.data.repository

import com.faldo.hsk_quest.data.model.ClaimQuestResponse
import com.faldo.hsk_quest.data.model.DailyQuestListResponse
import com.faldo.hsk_quest.data.remote.ApiErrorParser
import com.faldo.hsk_quest.data.remote.ApiService
import com.faldo.hsk_quest.util.Resource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repository for server-authoritative Daily Quests and Diamond claims.
 */
class QuestRepository(
    private val api: ApiService,
    private val playerRepository: PlayerRepository,
) {

    @Volatile
    var cachedDailyQuests: DailyQuestListResponse? = null

    fun clearMemoryCache() {
        cachedDailyQuests = null
    }

    suspend fun getDailyQuests(): Resource<DailyQuestListResponse> = withContext(Dispatchers.IO) {
        try {
            val response = api.getDailyQuests()
            val body = response.body()
            if (response.isSuccessful && body != null) {
                cachedDailyQuests = body
                playerRepository.applyLocalStatsPatch(
                    coins = body.totalCoins,
                    diamonds = body.totalDiamonds,
                )
                Resource.Success(body)
            } else {
                Resource.Error(ApiErrorParser.parse(response))
            }
        } catch (e: Exception) {
            Resource.Error(e.message ?: "Failed to load Daily Quests")
        }
    }

    suspend fun claimQuest(questKey: String): Resource<ClaimQuestResponse> = withContext(Dispatchers.IO) {
        try {
            val response = api.claimDailyQuest(questKey)
            val body = response.body()
            if (response.isSuccessful && body != null) {
                cachedDailyQuests = body.daily
                playerRepository.applyLocalStatsPatch(
                    coins = body.totalCoins,
                    diamonds = body.totalDiamonds,
                )
                Resource.Success(body)
            } else {
                Resource.Error(ApiErrorParser.parse(response))
            }
        } catch (e: Exception) {
            Resource.Error(e.message ?: "Failed to claim quest reward")
        }
    }

    suspend fun claimAllQuests(): Resource<ClaimQuestResponse> = withContext(Dispatchers.IO) {
        try {
            val response = api.claimAllDailyQuests()
            val body = response.body()
            if (response.isSuccessful && body != null) {
                cachedDailyQuests = body.daily
                playerRepository.applyLocalStatsPatch(
                    coins = body.totalCoins,
                    diamonds = body.totalDiamonds,
                )
                Resource.Success(body)
            } else {
                Resource.Error(ApiErrorParser.parse(response))
            }
        } catch (e: Exception) {
            Resource.Error(e.message ?: "Failed to claim quest rewards")
        }
    }
}
