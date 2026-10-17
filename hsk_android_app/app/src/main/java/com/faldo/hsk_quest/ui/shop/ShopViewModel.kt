package com.faldo.hsk_quest.ui.shop

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.faldo.hsk_quest.data.model.InventoryItem
import com.faldo.hsk_quest.data.model.ShopItem
import com.faldo.hsk_quest.data.repository.PlayerRepository
import com.faldo.hsk_quest.data.repository.ShopRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class ShopFilter {
    ALL, FOOD, ELIXIR, BAG
}

/**
 * ViewModel managing the shop catalog, buying items, inventory bag, and consumption.
 */
class ShopViewModel(
    private val shopRepository: ShopRepository,
    private val playerRepository: PlayerRepository,
) : ViewModel() {

    private val _catalog = MutableStateFlow<List<ShopItem>>(emptyList())
    private val _inventory = MutableStateFlow<List<InventoryItem>>(emptyList())

    private val _filter = MutableStateFlow(ShopFilter.ALL)
    val filter: StateFlow<ShopFilter> = _filter.asStateFlow()

    private val _uiItems = MutableStateFlow<List<ShopUiItem>>(emptyList())
    val uiItems: StateFlow<List<ShopUiItem>> = _uiItems.asStateFlow()

    private val _coins = MutableStateFlow(0)
    val coins: StateFlow<Int> = _coins.asStateFlow()

    private val _diamonds = MutableStateFlow(0)
    val diamonds: StateFlow<Int> = _diamonds.asStateFlow()

    private val _hp = MutableStateFlow(100)
    val hp: StateFlow<Int> = _hp.asStateFlow()

    private val _maxHp = MutableStateFlow(100)
    val maxHp: StateFlow<Int> = _maxHp.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _message = MutableSharedFlow<String>()
    val message: SharedFlow<String> = _message.asSharedFlow()

    init {
        loadData()
    }

    fun loadData() {
        viewModelScope.launch {
            _isLoading.value = true
            refreshPlayerStats()
            fetchCatalog()
            fetchInventory()
            updateUiItems()
            _isLoading.value = false
        }
    }

    private suspend fun refreshPlayerStats() {
        when (val res = playerRepository.fetchProfile()) {
            is PlayerRepository.ProfileResult.Fresh -> {
                _coins.value = res.player.stats.coins
                _diamonds.value = res.player.stats.diamonds
                _hp.value = res.player.stats.hp
                _maxHp.value = res.player.stats.maxHp
            }
            is PlayerRepository.ProfileResult.Cached -> {
                _coins.value = res.player.stats.coins
                _diamonds.value = res.player.stats.diamonds
                _hp.value = res.player.stats.hp
                _maxHp.value = res.player.stats.maxHp
            }
            else -> {}
        }
    }

    private suspend fun fetchCatalog() {
        val result = shopRepository.getCatalog()
        result.onSuccess { items ->
            _catalog.value = items
        }
    }

    private suspend fun fetchInventory() {
        val result = shopRepository.getInventory()
        result.onSuccess { items ->
            _inventory.value = items
        }
    }

    fun setFilter(newFilter: ShopFilter) {
        _filter.value = newFilter
        updateUiItems()
    }

    private fun updateUiItems() {
        val catalog = _catalog.value
        val inventory = _inventory.value
        val currentFilter = _filter.value

        val ownedMap = inventory.associate { it.itemId to it.quantity }

        when (currentFilter) {
            ShopFilter.ALL -> {
                _uiItems.value = catalog.map { item ->
                    ShopUiItem.CatalogEntry(item = item, ownedQuantity = ownedMap[item.id] ?: 0)
                }
            }
            ShopFilter.FOOD -> {
                _uiItems.value = catalog.filter { it.category == "food" }.map { item ->
                    ShopUiItem.CatalogEntry(item = item, ownedQuantity = ownedMap[item.id] ?: 0)
                }
            }
            ShopFilter.ELIXIR -> {
                _uiItems.value = catalog.filter { it.category == "elixir" }.map { item ->
                    ShopUiItem.CatalogEntry(item = item, ownedQuantity = ownedMap[item.id] ?: 0)
                }
            }
            ShopFilter.BAG -> {
                _uiItems.value = inventory.map { inv ->
                    ShopUiItem.BagEntry(item = inv)
                }
            }
        }
    }

    fun buyItem(item: ShopItem, quantity: Int = 1) {
        viewModelScope.launch {
            _isLoading.value = true
            val res = shopRepository.buyItem(item.id, quantity)
            res.onSuccess { buyResp ->
                _coins.value = buyResp.remainingCoins
                _diamonds.value = buyResp.remainingDiamonds
                fetchInventory()
                updateUiItems()
                _message.emit("✨ ${buyResp.message}")
            }.onFailure { err ->
                _message.emit("❌ ${err.message ?: "Purchase failed"}")
            }
            _isLoading.value = false
        }
    }

    fun useItem(item: InventoryItem) {
        viewModelScope.launch {
            _isLoading.value = true
            val res = shopRepository.useItem(item.itemId)
            res.onSuccess { useResp ->
                _hp.value = useResp.currentHp
                _maxHp.value = useResp.maxHp
                fetchInventory()
                updateUiItems()
                _message.emit("🍲 ${useResp.message}")
            }.onFailure { err ->
                _message.emit("❌ ${err.message ?: "Failed to use item"}")
            }
            _isLoading.value = false
        }
    }

    companion object {
        fun factory(
            shopRepository: ShopRepository,
            playerRepository: PlayerRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ShopViewModel(
                    shopRepository = shopRepository,
                    playerRepository = playerRepository,
                )
            }
        }
    }
}
