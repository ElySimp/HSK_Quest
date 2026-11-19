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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

    private val _catalog = MutableStateFlow<List<ShopItem>>(shopRepository.cachedCatalog.orEmpty())
    val catalogItems: StateFlow<List<ShopItem>> = _catalog.asStateFlow()

    private val _inventory = MutableStateFlow<List<InventoryItem>>(shopRepository.cachedInventory.orEmpty())
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

    private var lastLoadTimestampMs: Long = 0L
    private var isLoadInFlight: Boolean = false

    init {
        if (_catalog.value.isNotEmpty() || _inventory.value.isNotEmpty()) {
            updateUiItems()
        }
        loadData()
    }

    fun loadData(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && isLoadInFlight) return
        if (!force && _catalog.value.isNotEmpty() && (now - lastLoadTimestampMs) < 3_000L) {
            return
        }
        lastLoadTimestampMs = now
        isLoadInFlight = true

        viewModelScope.launch {
            try {
                if (_catalog.value.isEmpty()) {
                    _isLoading.value = true
                }
                coroutineScope {
                    awaitAll(
                        async { refreshPlayerStats() },
                        async { fetchCatalog() },
                        async { fetchInventory() },
                    )
                }
                updateUiItems()
            } finally {
                isLoadInFlight = false
                _isLoading.value = false
            }
        }
    }

    private suspend fun refreshPlayerStats() {
        when (val res = playerRepository.fetchProfile()) {
            is PlayerRepository.ProfileResult.Fresh -> {
                _coins.value = res.player.stats.coins
                _diamonds.value = res.player.stats.diamonds
                if (pendingUseOps == 0) {
                    _hp.value = res.player.stats.hp
                    _maxHp.value = res.player.stats.maxHp
                }
            }
            is PlayerRepository.ProfileResult.Cached -> {
                _coins.value = res.player.stats.coins
                _diamonds.value = res.player.stats.diamonds
                if (pendingUseOps == 0) {
                    _hp.value = res.player.stats.hp
                    _maxHp.value = res.player.stats.maxHp
                }
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
            if (pendingUseOps == 0) {
                _inventory.value = items
            }
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
            val res = shopRepository.buyItemsBatch(snapshotCart)
            res.onSuccess { batchResp ->
                _coins.value = batchResp.remainingCoins
                _diamonds.value = batchResp.remainingDiamonds
                mergePurchasedQuantities(batchResp.purchasedQuantities)
                playerRepository.applyLocalStatsPatch(
                    coins = batchResp.remainingCoins,
                    diamonds = batchResp.remainingDiamonds,
                )
                _cart.value = emptyMap()
                updateUiItems()
                _isCheckingOut.value = false
                _screenMode.value = ShopScreenMode.HUB
                onComplete()
                _message.emit("✨ Order complete! Purchased ${batchResp.totalItemsBought} item(s)!")
            }.onFailure { err ->
                _isCheckingOut.value = false
                onComplete()
                _message.emit("❌ ${err.message ?: "Checkout failed"}")
            }
        }
    }

    private fun mergePurchasedQuantities(addedQuantities: Map<String, Int>) {
        if (addedQuantities.isEmpty()) return
        val catalogById = _catalog.value.associateBy { it.id }
        val updated = _inventory.value.associateBy { it.itemId }.toMutableMap()
        for ((itemId, addedQty) in addedQuantities) {
            if (addedQty <= 0) continue
            val existing = updated[itemId]
            if (existing != null) {
                updated[itemId] = existing.copy(quantity = existing.quantity + addedQty)
            } else {
                val meta = catalogById[itemId] ?: continue
                updated[itemId] = InventoryItem(
                    id = itemId.hashCode(),
                    itemId = meta.id,
                    itemType = meta.category,
                    quantity = addedQty,
                    name = meta.name,
                    nameZh = meta.nameZh,
                    description = meta.description,
                    hpRestore = meta.hpRestore,
                    hpRestorePct = meta.hpRestorePct,
                    affectionBoost = meta.affectionBoost,
                    iconEmoji = meta.iconEmoji,
                    spriteRes = meta.spriteRes,
                )
            }
        }
        _inventory.value = updated.values.toList()
        shopRepository.updateInventoryCache(_inventory.value)
    }

    private fun updateInventoryExactQuantity(itemId: String, exactQty: Int, fallbackCatalogItem: ShopItem? = null) {
        val mutable = _inventory.value.toMutableList()
        val idx = mutable.indexOfFirst { it.itemId == itemId }
        if (exactQty <= 0) {
            if (idx >= 0) mutable.removeAt(idx)
        } else if (idx >= 0) {
            mutable[idx] = mutable[idx].copy(quantity = exactQty)
        } else if (fallbackCatalogItem != null) {
            mutable.add(
                InventoryItem(
                    id = itemId.hashCode(),
                    itemId = fallbackCatalogItem.id,
                    itemType = fallbackCatalogItem.category,
                    quantity = exactQty,
                    name = fallbackCatalogItem.name,
                    nameZh = fallbackCatalogItem.nameZh,
                    description = fallbackCatalogItem.description,
                    hpRestore = fallbackCatalogItem.hpRestore,
                    hpRestorePct = fallbackCatalogItem.hpRestorePct,
                    affectionBoost = fallbackCatalogItem.affectionBoost,
                    iconEmoji = fallbackCatalogItem.iconEmoji,
                    spriteRes = fallbackCatalogItem.spriteRes,
                )
            )
        }
        _inventory.value = mutable
        shopRepository.updateInventoryCache(mutable)
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
                updateInventoryExactQuantity(item.id, buyResp.totalOwned, item)
                updateUiItems()
                playerRepository.applyLocalStatsPatch(
                    coins = buyResp.remainingCoins,
                    diamonds = buyResp.remainingDiamonds,
                )
                _message.emit("✨ ${buyResp.message}")
            }.onFailure { err ->
                _message.emit("❌ ${err.message ?: "Purchase failed"}")
            }
            _isLoading.value = false
        }
    }

    private val useItemMutex = kotlinx.coroutines.sync.Mutex()
    private var pendingUseOps: Int = 0

    fun useItem(item: InventoryItem) {
        viewModelScope.launch {
            val prevInventory = _inventory.value
            val currentItem = prevInventory.firstOrNull { it.itemId == item.itemId }
            if (currentItem == null || currentItem.quantity <= 0) return@launch

            val prevHp = _hp.value
            val maxHp = _maxHp.value
            val targetHeal = if (item.hpRestorePct > 0) {
                maxOf(1, kotlin.math.round(maxHp * item.hpRestorePct / 100.0).toInt())
            } else {
                item.hpRestore
            }
            val nextHp = (prevHp + targetHeal).coerceAtMost(maxHp)
            val restoredAmt = (nextHp - prevHp).coerceAtLeast(0)

            // Optimistic UI & local Room patch for instant response without blocking spinner
            _hp.value = nextHp
            updateInventoryExactQuantity(item.itemId, currentItem.quantity - 1)
            updateUiItems()
            playerRepository.applyLocalStatsPatch(hp = nextHp, maxHp = maxHp)

            val instantMsg = buildString {
                append("🍲 Used ${item.name}.")
                if (restoredAmt > 0) append(" Restored $restoredAmt HP!")
            }
            _message.emit(instantMsg)

            pendingUseOps++
            try {
                useItemMutex.lock()
                val res = shopRepository.useItem(item.itemId)
                res.onSuccess { useResp ->
                    if (pendingUseOps <= 1) {
                        _hp.value = useResp.currentHp
                        _maxHp.value = useResp.maxHp
                        updateInventoryExactQuantity(item.itemId, useResp.remainingQuantity)
                        updateUiItems()
                        playerRepository.applyLocalStatsPatch(
                            hp = useResp.currentHp,
                            maxHp = useResp.maxHp,
                        )
                    }
                }.onFailure { err ->
                    _hp.value = prevHp
                    _inventory.value = prevInventory
                    updateUiItems()
                    playerRepository.applyLocalStatsPatch(hp = prevHp, maxHp = maxHp)
                    _message.emit("❌ ${err.message ?: "Failed to use item"}")
                }
            } finally {
                pendingUseOps = (pendingUseOps - 1).coerceAtLeast(0)
                if (useItemMutex.isLocked) {
                    useItemMutex.unlock()
                }
            }
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
