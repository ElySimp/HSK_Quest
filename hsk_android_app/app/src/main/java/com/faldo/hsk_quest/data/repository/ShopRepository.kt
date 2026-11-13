package com.faldo.hsk_quest.data.repository

import com.faldo.hsk_quest.data.model.BuyBatchItem
import com.faldo.hsk_quest.data.model.BuyBatchRequest
import com.faldo.hsk_quest.data.model.BuyBatchResponse
import com.faldo.hsk_quest.data.model.BuyItemRequest
import com.faldo.hsk_quest.data.model.BuyItemResponse
import com.faldo.hsk_quest.data.model.InventoryItem
import com.faldo.hsk_quest.data.model.ShopItem
import com.faldo.hsk_quest.data.model.UseItemRequest
import com.faldo.hsk_quest.data.model.UseItemResponse
import com.faldo.hsk_quest.data.remote.ApiErrorParser
import com.faldo.hsk_quest.data.remote.ApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repository handling Shop catalog transactions and Player Inventory bag actions.
 */
class ShopRepository(
    private val apiService: ApiService,
) {

    suspend fun getCatalog(): Result<List<ShopItem>> = withContext(Dispatchers.IO) {
        try {
            val response = apiService.getShopCatalog()
            if (response.isSuccessful) {
                Result.success(response.body()?.items ?: emptyList())
            } else {
                Result.failure(Exception("Failed to fetch shop catalog: ${response.code()} ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun buyItem(itemId: String, quantity: Int = 1): Result<BuyItemResponse> = withContext(Dispatchers.IO) {
        try {
            val response = apiService.buyShopItem(BuyItemRequest(itemId = itemId, quantity = quantity))
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception(ApiErrorParser.parse(response)))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun buyItemsBatch(items: Map<String, Int>): Result<BuyBatchResponse> = withContext(Dispatchers.IO) {
        try {
            val payload = items.entries
                .filter { it.value > 0 }
                .map { BuyBatchItem(itemId = it.key, quantity = it.value) }
            val response = apiService.buyShopItemsBatch(BuyBatchRequest(items = payload))
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception(ApiErrorParser.parse(response)))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getInventory(): Result<List<InventoryItem>> = withContext(Dispatchers.IO) {
        try {
            val response = apiService.getInventory()
            if (response.isSuccessful) {
                Result.success(response.body()?.items ?: emptyList())
            } else {
                Result.failure(Exception("Failed to fetch inventory: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun useItem(itemId: String): Result<UseItemResponse> = withContext(Dispatchers.IO) {
        try {
            val response = apiService.useItem(UseItemRequest(itemId = itemId))
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception(ApiErrorParser.parse(response)))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
