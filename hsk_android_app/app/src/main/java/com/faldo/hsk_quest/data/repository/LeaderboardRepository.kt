package com.faldo.hsk_quest.data.repository

import com.faldo.hsk_quest.data.model.WeeklyLeaderboardResponse
import com.faldo.hsk_quest.data.remote.ApiErrorParser
import com.faldo.hsk_quest.data.remote.ApiService
import com.faldo.hsk_quest.util.Resource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repository for fetching weekly damage leaderboard rankings and league standings.
 */
class LeaderboardRepository(
    private val api: ApiService,
) {

    suspend fun getWeeklyLeaderboard(league: String? = null): Resource<WeeklyLeaderboardResponse> =
        withContext(Dispatchers.IO) {
            try {
                val response = api.getWeeklyLeaderboard(league)
                val body = response.body()
                if (response.isSuccessful && body != null) {
                    Resource.Success(body)
                } else {
                    Resource.Error(ApiErrorParser.parse(response))
                }
            } catch (e: Exception) {
                Resource.Error(e.message ?: AuthRepository.NETWORK_ERROR)
            }
        }
}
