package com.faldo.hsk_quest.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.faldo.hsk_quest.data.model.DailyStatusResponse
import com.faldo.hsk_quest.data.model.InventoryItem
import com.faldo.hsk_quest.data.model.PetItem
import com.faldo.hsk_quest.data.model.Player
import com.faldo.hsk_quest.data.repository.AuthRepository
import com.faldo.hsk_quest.data.repository.PetRepository
import com.faldo.hsk_quest.data.repository.PlayerRepository
import com.faldo.hsk_quest.data.repository.ShopRepository
import com.faldo.hsk_quest.util.Resource
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HomeViewModel(
    private val playerRepository: PlayerRepository,
    private val authRepository: AuthRepository,
    private val petRepository: PetRepository,
    private val shopRepository: ShopRepository,
) : ViewModel() {

    sealed class UiState {
        data object Loading : UiState()
        data class Loaded(val player: Player, val isCached: Boolean) : UiState()
        data class Error(val message: String) : UiState()
        data object LoggedOut : UiState()
    }

    sealed class PetState {
        data object Loading : PetState()
        data class Loaded(val pet: PetItem?) : PetState()
        data class Error(val message: String) : PetState()
    }

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _dailyStatus = MutableStateFlow<DailyStatusResponse?>(playerRepository.cachedDailyStatus)
    val dailyStatus: StateFlow<DailyStatusResponse?> = _dailyStatus.asStateFlow()

    private val _isClaimingDaily = MutableStateFlow(false)
    val isClaimingDaily: StateFlow<Boolean> = _isClaimingDaily.asStateFlow()

    private val _petState = MutableStateFlow<PetState>(
        if (petRepository.hasLoadedActivePet) {
            PetState.Loaded(petRepository.cachedActivePet)
        } else {
            PetState.Loading
        }
    )
    val petState: StateFlow<PetState> = _petState.asStateFlow()

    private val _inventoryItems = MutableStateFlow<List<InventoryItem>>(emptyList())
    val inventoryItems: StateFlow<List<InventoryItem>> = _inventoryItems.asStateFlow()

    private val _homeMessage = MutableSharedFlow<String>()
    val homeMessage: SharedFlow<String> = _homeMessage.asSharedFlow()

    init {
        viewModelScope.launch {
            playerRepository.observeLocalPlayer().collect { localPlayer ->
                if (localPlayer != null) {
                    val wasCached = (_state.value as? UiState.Loaded)?.isCached ?: false
                    _state.value = UiState.Loaded(localPlayer, isCached = wasCached)
                }
            }
        }
    }

    private val useItemMutex = kotlinx.coroutines.sync.Mutex()
    private var pendingUseOps: Int = 0
    private var optimisticDailyClaimedToday: Boolean = false

    fun refresh() {
        if (!optimisticDailyClaimedToday) {
            playerRepository.cachedDailyStatus?.let { _dailyStatus.value = it }
        }
        if (petRepository.hasLoadedActivePet) {
            _petState.value = PetState.Loaded(petRepository.cachedActivePet)
        }

        // 1. Fetch player profile silently if already hydrated from Room
        viewModelScope.launch {
            if (_state.value !is UiState.Loaded) _state.value = UiState.Loading
            when (val r = playerRepository.fetchProfile()) {
                is PlayerRepository.ProfileResult.Fresh -> {
                    _state.value = UiState.Loaded(r.player, isCached = false)
                }
                is PlayerRepository.ProfileResult.Cached -> {
                    _state.value = UiState.Loaded(r.player, isCached = true)
                }
                is PlayerRepository.ProfileResult.Failure -> {
                    if (_state.value !is UiState.Loaded) {
                        _state.value = UiState.Error(r.message)
                    }
                }
                PlayerRepository.ProfileResult.Unauthorized -> {
                    playerRepository.clearMemoryCache()
                    petRepository.clearMemoryCache()
                    authRepository.logout()
                    _state.value = UiState.LoggedOut
                }
            }
        }

        // 2. Fetch daily reward status in parallel
        viewModelScope.launch {
            when (val daily = playerRepository.getDailyStatus()) {
                is Resource.Success -> {
                    if (!optimisticDailyClaimedToday || !daily.data.canClaim) {
                        _dailyStatus.value = daily.data
                    }
                }
                else -> {}
            }
        }

        // 3. Fetch equipped companion pet in parallel so starter pet appears immediately
        retryPetLoad()

        // 4. Fetch player inventory bag in parallel (skip overwriting mid-optimistic use)
        viewModelScope.launch {
            shopRepository.getInventory().onSuccess { items ->
                if (pendingUseOps == 0) {
                    _inventoryItems.value = items
                }
            }
        }
    }

    fun useInventoryItem(item: InventoryItem) {
        viewModelScope.launch {
            if (item.itemType == "pet_food" || (item.hpRestore <= 0 && item.affectionBoost > 0)) {
                _homeMessage.emit("🐾 Pet Treats are fed to your companion in the Pet Sanctuary!")
                return@launch
            }

            val currentLoaded = _state.value as? UiState.Loaded
            val currentPlayer = currentLoaded?.player
            val currentStats = currentPlayer?.stats
            val prevInventory = _inventoryItems.value

            val currentInBag = prevInventory.firstOrNull { it.itemId == item.itemId }
            if (currentInBag == null || currentInBag.quantity <= 0) return@launch

            val nextHp: Int
            val healedAmt: Int
            if (currentStats != null) {
                val targetHeal = if (item.hpRestorePct > 0) {
                    maxOf(1, kotlin.math.round(currentStats.maxHp * item.hpRestorePct / 100.0).toInt())
                } else {
                    item.hpRestore
                }
                nextHp = (currentStats.hp + targetHeal).coerceAtMost(currentStats.maxHp)
                healedAmt = (nextHp - currentStats.hp).coerceAtLeast(0)
            } else {
                nextHp = 0
                healedAmt = 0
            }

            // Optimistic UI & local Room update for 0ms perceived latency
            _inventoryItems.value = prevInventory.mapNotNull {
                when {
                    it.itemId != item.itemId -> it
                    it.quantity > 1 -> it.copy(quantity = it.quantity - 1)
                    else -> null
                }
            }
            if (currentPlayer != null && currentStats != null) {
                val optimisticPlayer = currentPlayer.copy(
                    stats = currentStats.copy(
                        hp = nextHp,
                        survivalHp = nextHp,
                    )
                )
                _state.value = UiState.Loaded(optimisticPlayer, isCached = false)
                playerRepository.applyLocalStatsPatch(hp = nextHp, maxHp = currentStats.maxHp)
            }
            val instantMsg = buildString {
                append("🍲 Used ${item.name}.")
                if (healedAmt > 0) append(" Restored $healedAmt HP!")
            }
            _homeMessage.emit(instantMsg)

            pendingUseOps++
            try {
                useItemMutex.lock()
                val res = shopRepository.useItem(item.itemId)
                res.onSuccess { resp ->
                    if (pendingUseOps <= 1) {
                        val latest = (_state.value as? UiState.Loaded)?.player
                        if (latest != null) {
                            _state.value = UiState.Loaded(
                                latest.copy(
                                    stats = latest.stats.copy(
                                        hp = resp.currentHp,
                                        maxHp = resp.maxHp,
                                        survivalHp = resp.currentHp,
                                    )
                                ),
                                isCached = false,
                            )
                        }
                        playerRepository.applyLocalStatsPatch(hp = resp.currentHp, maxHp = resp.maxHp)
                        _inventoryItems.value = if (resp.remainingQuantity <= 0) {
                            _inventoryItems.value.filterNot { it.itemId == item.itemId }
                        } else {
                            _inventoryItems.value.map {
                                if (it.itemId == item.itemId) it.copy(quantity = resp.remainingQuantity) else it
                            }
                        }
                    }
                }.onFailure { err ->
                    // Rollback local state on rejection/failure
                    _inventoryItems.value = prevInventory
                    if (currentPlayer != null && currentStats != null) {
                        _state.value = UiState.Loaded(currentPlayer, isCached = currentLoaded.isCached)
                        playerRepository.applyLocalStatsPatch(hp = currentStats.hp, maxHp = currentStats.maxHp)
                    }
                    _homeMessage.emit("❌ ${err.message ?: "Failed to use item"}")
                }
            } finally {
                pendingUseOps = (pendingUseOps - 1).coerceAtLeast(0)
                if (useItemMutex.isLocked) {
                    useItemMutex.unlock()
                }
            }
        }
    }

    fun retryPetLoad() {
        viewModelScope.launch {
            if (_petState.value !is PetState.Loaded) {
                _petState.value = PetState.Loading
            }
            when (val petRes = petRepository.getActivePet()) {
                is Resource.Success -> _petState.value = PetState.Loaded(petRes.data)
                is Resource.Error -> {
                    // Preserve already-loaded pet if available; otherwise surface retryable error
                    if (_petState.value !is PetState.Loaded) {
                        _petState.value = PetState.Error(petRes.message)
                    }
                }
                Resource.Loading -> {}
            }
        }
    }

    fun claimDaily() {
        if (_isClaimingDaily.value) return
        val prevDaily = _dailyStatus.value ?: return
        if (!prevDaily.canClaim) return

        val prevState = _state.value
        val prevPlayer = (prevState as? UiState.Loaded)?.player
        val rewardCoins = prevDaily.coinsReward
        val nextStreak = prevDaily.streak

        _isClaimingDaily.value = true
        optimisticDailyClaimedToday = true
        _dailyStatus.value = DailyStatusResponse(
            canClaim = false,
            streak = nextStreak,
            coinsReward = rewardCoins,
        )

        viewModelScope.launch {
            if (prevPlayer != null) {
                val optimisticCoins = prevPlayer.stats.coins + rewardCoins
                val optimisticPlayer = prevPlayer.copy(
                    stats = prevPlayer.stats.copy(coins = optimisticCoins)
                )
                _state.value = UiState.Loaded(optimisticPlayer, isCached = false)
                playerRepository.applyLocalStatsPatch(coins = optimisticCoins)
            }
            _homeMessage.emit("🎁 Claimed Daily Reward! +$rewardCoins Coins")

            when (val r = playerRepository.claimDaily()) {
                is Resource.Success -> {
                    _dailyStatus.value = DailyStatusResponse(
                        canClaim = false,
                        streak = r.data.streak,
                        coinsReward = r.data.coinsAwarded,
                    )
                    val latest = (_state.value as? UiState.Loaded)?.player
                    if (latest != null && latest.stats.coins != r.data.totalCoins) {
                        _state.value = UiState.Loaded(
                            latest.copy(stats = latest.stats.copy(coins = r.data.totalCoins)),
                            isCached = false,
                        )
                    }
                }
                is Resource.Error -> {
                    optimisticDailyClaimedToday = false
                    _dailyStatus.value = prevDaily
                    if (prevPlayer != null) {
                        _state.value = prevState
                        playerRepository.applyLocalStatsPatch(coins = prevPlayer.stats.coins)
                    }
                    _homeMessage.emit("❌ ${r.message}")
                }
                Resource.Loading -> {}
            }
            _isClaimingDaily.value = false
        }
    }

    fun logout() {
        viewModelScope.launch {
            playerRepository.clearMemoryCache()
            petRepository.clearMemoryCache()
            authRepository.logout()
            _state.value = UiState.LoggedOut
        }
    }

    companion object {
        fun factory(
            playerRepository: PlayerRepository,
            authRepository: AuthRepository,
            petRepository: PetRepository,
            shopRepository: ShopRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                HomeViewModel(
                    playerRepository = playerRepository,
                    authRepository = authRepository,
                    petRepository = petRepository,
                    shopRepository = shopRepository,
                )
            }
        }
    }
}
