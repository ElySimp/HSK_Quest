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

    fun refresh() {
        playerRepository.cachedDailyStatus?.let { _dailyStatus.value = it }
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
                is Resource.Success -> _dailyStatus.value = daily.data
                else -> {}
            }
        }

        // 3. Fetch equipped companion pet in parallel so starter pet appears immediately
        retryPetLoad()

        // 4. Fetch player inventory bag in parallel
        viewModelScope.launch {
            shopRepository.getInventory().onSuccess { items ->
                _inventoryItems.value = items
            }
        }
    }

    fun useInventoryItem(item: InventoryItem) {
        viewModelScope.launch {
            if (item.itemType == "pet_food" || (item.hpRestore <= 0 && item.affectionBoost > 0)) {
                _homeMessage.emit("🐾 Pet Treats are fed to your companion in the Pet Sanctuary!")
                return@launch
            }
            shopRepository.useItem(item.itemId).onSuccess { resp ->
                val current = (_state.value as? UiState.Loaded)?.player
                if (current != null) {
                    val updated = current.copy(
                        stats = current.stats.copy(hp = resp.currentHp, maxHp = resp.maxHp)
                    )
                    _state.value = UiState.Loaded(updated, isCached = false)
                }
                shopRepository.getInventory().onSuccess { items ->
                    _inventoryItems.value = items
                }
                playerRepository.fetchProfile()
                _homeMessage.emit("🍲 ${resp.message}")
            }.onFailure { err ->
                _homeMessage.emit("❌ ${err.message ?: "Failed to use item"}")
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
        _isClaimingDaily.value = true
        viewModelScope.launch {
            when (val r = playerRepository.claimDaily()) {
                is Resource.Success -> {
                    _dailyStatus.value = DailyStatusResponse(
                        canClaim = false,
                        streak = r.data.streak,
                        coinsReward = r.data.coinsAwarded,
                    )
                    // Update coins in current loaded state
                    val current = (_state.value as? UiState.Loaded)?.player
                    if (current != null) {
                        val updated = current.copy(
                            stats = current.stats.copy(coins = r.data.totalCoins)
                        )
                        _state.value = UiState.Loaded(updated, isCached = false)
                    }
                    _homeMessage.emit("🎁 Claimed Daily Reward! +${r.data.coinsAwarded} Coins")
                }
                is Resource.Error -> {
                    _homeMessage.emit(r.message)
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
