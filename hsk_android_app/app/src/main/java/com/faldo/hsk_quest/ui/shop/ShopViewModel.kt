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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class ShopFilter {
    ALL, ELIXIR, FOOD, PET_FOOD, BAG
}

enum class ShopScreenMode {
    HUB, STORE_CATALOG, CHECKOUT
}

/**
 * ViewModel managing the Merchant Hub, specialty store catalogs, shopping cart,
 * checkout flow, inventory bag, and item consumption.
 */
class ShopViewModel(
    private val shopRepository: ShopRepository,
    private val playerRepository: PlayerRepository,
) : ViewModel() {

    private val _catalog = MutableStateFlow<List<ShopItem>>(emptyList())
    val catalogItems: StateFlow<List<ShopItem>> = _catalog.asStateFlow()

    private val _inventory = MutableStateFlow<List<InventoryItem>>(emptyList())
    val inventoryItems: StateFlow<List<InventoryItem>> = _inventory.asStateFlow()

    private val _screenMode = MutableStateFlow(ShopScreenMode.HUB)
    val screenMode: StateFlow<ShopScreenMode> = _screenMode.asStateFlow()

    private val _filter = MutableStateFlow(ShopFilter.ALL)
    val filter: StateFlow<ShopFilter> = _filter.asStateFlow()

    private val _cart = MutableStateFlow<Map<String, Int>>(emptyMap())
    val cart: StateFlow<Map<String, Int>> = _cart.asStateFlow()

    private val _uiItems = MutableStateFlow<List<ShopUiItem>>(emptyList())
    val uiItems: StateFlow<List<ShopUiItem>> = _uiItems.asStateFlow()

    private val cachedPlayer = playerRepository.cachedMemoryPlayer

    private val _coins = MutableStateFlow(cachedPlayer?.stats?.coins ?: 0)
    val coins: StateFlow<Int> = _coins.asStateFlow()

    private val _diamonds = MutableStateFlow(cachedPlayer?.stats?.diamonds ?: 0)
    val diamonds: StateFlow<Int> = _diamonds.asStateFlow()

    private val _hp = MutableStateFlow(cachedPlayer?.stats?.hp ?: 100)
    val hp: StateFlow<Int> = _hp.asStateFlow()

    private val _maxHp = MutableStateFlow(cachedPlayer?.stats?.maxHp ?: 100)
    val maxHp: StateFlow<Int> = _maxHp.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isCheckingOut = MutableStateFlow(false)
    val isCheckingOut: StateFlow<Boolean> = _isCheckingOut.asStateFlow()

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

    fun openStore(newFilter: ShopFilter) {
        _filter.value = newFilter
        updateUiItems()
        _screenMode.value = ShopScreenMode.STORE_CATALOG
    }

    fun returnToHub() {
        _uiItems.value = emptyList()
        _screenMode.value = ShopScreenMode.HUB
    }

    fun openCheckout() {
        val totalQty = _cart.value.values.sum()
        if (totalQty <= 0) {
            viewModelScope.launch {
                _message.emit("🛒 Your cart is empty! Add items from a store first.")
            }
        } else {
            _screenMode.value = ShopScreenMode.CHECKOUT
        }
    }

    fun setFilter(newFilter: ShopFilter) {
        _filter.value = newFilter
        updateUiItems()
    }

    fun updateCartQuantity(itemId: String, delta: Int) {
        val mutableCart = _cart.value.toMutableMap()
        val oldQty = mutableCart[itemId] ?: 0
        val newQty = (oldQty + delta).coerceIn(0, 99)
        if (newQty <= 0) {
            mutableCart.remove(itemId)
        } else {
            mutableCart[itemId] = newQty
        }
        _cart.value = mutableCart.toMap()
        updateUiItems()

        if (_cart.value.isEmpty() && _screenMode.value == ShopScreenMode.CHECKOUT) {
            _screenMode.value = ShopScreenMode.HUB
        }
    }

    fun clearCart() {
        _cart.value = emptyMap()
        updateUiItems()
        if (_screenMode.value == ShopScreenMode.CHECKOUT) {
            _screenMode.value = ShopScreenMode.HUB
        }
    }

    fun checkoutCart(onComplete: () -> Unit = {}) {
        val snapshotCart = _cart.value
        if (snapshotCart.isEmpty() || _isCheckingOut.value) return

        val catalogById = _catalog.value.associateBy { it.id }
        var requiredCoins = 0
        var requiredDiamonds = 0
        for ((itemId, qty) in snapshotCart) {
            val item = catalogById[itemId] ?: continue
            requiredCoins += item.priceCoins * qty
            requiredDiamonds += item.priceDiamonds * qty
        }

        if (requiredCoins > _coins.value) {
            viewModelScope.launch {
                _message.emit("❌ Not enough Coins! Required: $requiredCoins 🪙, Balance: ${_coins.value} 🪙")
            }
            return
        }
        if (requiredDiamonds > _diamonds.value) {
            viewModelScope.launch {
                _message.emit("❌ Not enough Gems! Required: $requiredDiamonds 💎, Balance: ${_diamonds.value} 💎")
            }
            return
        }

        viewModelScope.launch {
            _isCheckingOut.value = true
            delay(2000L)

            var totalPurchased = 0
            var failureMessage: String? = null

            for ((itemId, qty) in snapshotCart) {
                if (qty <= 0) continue
                val res = shopRepository.buyItem(itemId, qty)
                res.onSuccess { buyResp ->
                    totalPurchased += buyResp.quantityBought
                    _coins.value = buyResp.remainingCoins
                    _diamonds.value = buyResp.remainingDiamonds
                }.onFailure { err ->
                    failureMessage = err.message ?: "Checkout failed"
                }
            }

            fetchInventory()
            refreshPlayerStats()
            _cart.value = emptyMap()
            updateUiItems()
            _isCheckingOut.value = false
            _screenMode.value = ShopScreenMode.HUB
            onComplete()

            if (totalPurchased > 0) {
                _message.emit("✨ Order complete! Purchased $totalPurchased item(s)!")
            } else if (failureMessage != null) {
                _message.emit("❌ $failureMessage")
            }
        }
    }

    private fun updateUiItems() {
        val catalog = _catalog.value
        val inventory = _inventory.value
        val currentFilter = _filter.value
        val cartMap = _cart.value

        val ownedMap = inventory.associate { it.itemId to it.quantity }

        when (currentFilter) {
            ShopFilter.ALL -> {
                _uiItems.value = catalog.map { item ->
                    ShopUiItem.CatalogEntry(
                        item = item,
                        ownedQuantity = ownedMap[item.id] ?: 0,
                        cartQuantity = cartMap[item.id] ?: 0,
                    )
                }
            }
            ShopFilter.ELIXIR -> {
                _uiItems.value = catalog.filter { it.category == "elixir" }.map { item ->
                    ShopUiItem.CatalogEntry(
                        item = item,
                        ownedQuantity = ownedMap[item.id] ?: 0,
                        cartQuantity = cartMap[item.id] ?: 0,
                    )
                }
            }
            ShopFilter.FOOD -> {
                _uiItems.value = catalog.filter { it.category == "food" }.map { item ->
                    ShopUiItem.CatalogEntry(
                        item = item,
                        ownedQuantity = ownedMap[item.id] ?: 0,
                        cartQuantity = cartMap[item.id] ?: 0,
                    )
                }
            }
            ShopFilter.PET_FOOD -> {
                _uiItems.value = catalog.filter { it.category == "pet_food" }.map { item ->
                    ShopUiItem.CatalogEntry(
                        item = item,
                        ownedQuantity = ownedMap[item.id] ?: 0,
                        cartQuantity = cartMap[item.id] ?: 0,
                    )
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
                refreshPlayerStats()
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
                refreshPlayerStats()
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
