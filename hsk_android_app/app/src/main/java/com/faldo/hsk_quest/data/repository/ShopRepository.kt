package com.faldo.hsk_quest.data.repository

import android.content.Context
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
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Repository handling Shop catalog transactions and Player Inventory bag actions.
 * Maintains an in-memory + SharedPreferences local cache and reactive [inventoryFlow]
 * so the Home Inventory Bag, Shop, Pet Sanctuary, and Battle Bag stay synced at 0ms.
 */
class ShopRepository(
    private val apiService: ApiService,
    context: Context? = null,
) {

    private val prefs = context?.applicationContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    @Volatile
    var cachedCatalog: List<ShopItem>? = loadPersistedCatalog()

    @Volatile
    var cachedInventory: List<InventoryItem>? = loadPersistedInventory()

    private val _inventoryFlow = MutableStateFlow<List<InventoryItem>>(cachedInventory.orEmpty())
    val inventoryFlow: StateFlow<List<InventoryItem>> = _inventoryFlow.asStateFlow()

    fun updateInventoryCache(items: List<InventoryItem>) {
        val cleaned = items.filter { it.quantity > 0 }
        cachedInventory = cleaned
        _inventoryFlow.value = cleaned
        persistInventory(cleaned)
    }

    fun decrementItemQuantityLocal(itemId: String) {
        val current = cachedInventory.orEmpty()
        val updated = current.mapNotNull { item ->
            when {
                item.itemId != itemId -> item
                item.quantity > 1 -> item.copy(quantity = item.quantity - 1)
                else -> null
            }
        }
        updateInventoryCache(updated)
    }

    fun clearMemoryCache() {
        cachedCatalog = null
        cachedInventory = null
        _inventoryFlow.value = emptyList()
        prefs?.edit()?.clear()?.apply()
    }

    suspend fun getCatalog(): Result<List<ShopItem>> = withContext(Dispatchers.IO) {
        try {
            val response = apiService.getShopCatalog()
            if (response.isSuccessful) {
                val items = response.body()?.items ?: emptyList()
                cachedCatalog = items
                persistCatalog(items)
                Result.success(items)
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
                val items = response.body()?.items ?: emptyList()
                updateInventoryCache(items)
                Result.success(items)
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

    private fun loadPersistedInventory(): List<InventoryItem>? {
        val raw = prefs?.getString(KEY_INVENTORY_JSON, null) ?: return null
        return runCatching {
            val type = object : TypeToken<List<InventoryItem>>() {}.type
            gson.fromJson<List<InventoryItem>>(raw, type)
        }.getOrNull()
    }

    private fun persistInventory(items: List<InventoryItem>) {
        val p = prefs ?: return
        runCatching {
            p.edit().putString(KEY_INVENTORY_JSON, gson.toJson(items)).apply()
        }
    }

    private fun loadPersistedCatalog(): List<ShopItem>? {
        val raw = prefs?.getString(KEY_CATALOG_JSON, null) ?: return null
        return runCatching {
            val type = object : TypeToken<List<ShopItem>>() {}.type
            gson.fromJson<List<ShopItem>>(raw, type)
        }.getOrNull()
    }

    private fun persistCatalog(items: List<ShopItem>) {
        val p = prefs ?: return
        runCatching {
            p.edit().putString(KEY_CATALOG_JSON, gson.toJson(items)).apply()
        }
    }

    companion object {
        private const val PREFS_NAME = "hsk_shop_inventory_cache"
        private const val KEY_INVENTORY_JSON = "cached_inventory_items"
        private const val KEY_CATALOG_JSON = "cached_catalog_items"
    }
}
