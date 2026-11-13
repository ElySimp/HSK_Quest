package com.faldo.hsk_quest.ui.pet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.faldo.hsk_quest.data.model.BlackjackStateResponse
import com.faldo.hsk_quest.data.model.InventoryItem
import com.faldo.hsk_quest.data.model.PetItem
import com.faldo.hsk_quest.data.model.Player
import com.faldo.hsk_quest.data.model.RpsResponse
import com.faldo.hsk_quest.data.model.ShopItem
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

/**
 * ViewModel for the Companion Pet Sanctuary, handling Tamagotchi care,
 * food inventory bag feeding, Rock-Paper-Scissors & Blackjack mini-games,
 * and rarity-sorted companion equipping.
 */
class PetViewModel(
    private val petRepository: PetRepository,
    private val shopRepository: ShopRepository,
    private val playerRepository: PlayerRepository,
) : ViewModel() {

    private val _activePet = MutableStateFlow<PetItem?>(
        petRepository.cachedActivePet ?: petRepository.cachedPetList?.activePet
    )
    val activePet: StateFlow<PetItem?> = _activePet.asStateFlow()

    private val _petList = MutableStateFlow<List<PetItem>>(
        sortPetsByRarity(petRepository.cachedPetList?.pets.orEmpty())
    )
    val petList: StateFlow<List<PetItem>> = _petList.asStateFlow()

    private val _player = MutableStateFlow<Player?>(playerRepository.cachedMemoryPlayer)
    val player: StateFlow<Player?> = _player.asStateFlow()

    private val _isInitialHydrated = MutableStateFlow(
        petRepository.hasLoadedActivePet || petRepository.cachedPetList != null
    )
    val isInitialHydrated: StateFlow<Boolean> = _isInitialHydrated.asStateFlow()

    private val _foodBag = MutableStateFlow<List<InventoryItem>>(emptyList())
    val foodBag: StateFlow<List<InventoryItem>> = _foodBag.asStateFlow()

    private val _foodCatalog = MutableStateFlow<List<ShopItem>>(emptyList())
    val foodCatalog: StateFlow<List<ShopItem>> = _foodCatalog.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _blackjackState = MutableStateFlow<BlackjackStateResponse?>(null)
    val blackjackState: StateFlow<BlackjackStateResponse?> = _blackjackState.asStateFlow()

    private val _isBlackjackBusy = MutableStateFlow(false)
    val isBlackjackBusy: StateFlow<Boolean> = _isBlackjackBusy.asStateFlow()

    private val _actionFeedback = MutableSharedFlow<String>()
    val actionFeedback: SharedFlow<String> = _actionFeedback.asSharedFlow()

    private val _reactionEvent = MutableSharedFlow<String>()
    val reactionEvent: SharedFlow<String> = _reactionEvent.asSharedFlow()

    private val _rpsResult = MutableSharedFlow<RpsResponse>()
    val rpsResult: SharedFlow<RpsResponse> = _rpsResult.asSharedFlow()

    init {
        viewModelScope.launch {
            playerRepository.observeLocalPlayer().collect { localPlayer ->
                if (localPlayer != null) {
                    _player.value = localPlayer
                }
            }
        }
    }

    fun loadPetData() {
        viewModelScope.launch {
            _isLoading.value = true
            launch { refreshPlayerProfile() }
            launch { loadFoodBag() }
            when (val res = petRepository.getPets()) {
                is Resource.Success -> {
                    val list = sortPetsByRarity(res.data.pets)
                    _petList.value = list
                    _activePet.value = res.data.activePet ?: list.firstOrNull { it.isActive }
                    _isInitialHydrated.value = true
                }
                is Resource.Error -> {
                    _isInitialHydrated.value = true
                    _actionFeedback.emit(res.message)
                }
                Resource.Loading -> {}
            }
            _isLoading.value = false
        }
    }

    fun loadFoodBag() {
        viewModelScope.launch {
            shopRepository.getInventory().onSuccess { items ->
                _foodBag.value = items.filter {
                    it.quantity > 0 && (it.itemType == "pet_food" || it.affectionBoost > 0)
                }
            }
            if (_foodCatalog.value.isEmpty()) {
                shopRepository.getCatalog().onSuccess { catalog ->
                    _foodCatalog.value = catalog.filter {
                        it.category == "pet_food" || it.affectionBoost > 0
                    }
                }
            }
        }
    }

    private suspend fun refreshPlayerProfile() {
        when (val res = playerRepository.fetchProfile()) {
            is PlayerRepository.ProfileResult.Fresh -> _player.value = res.player
            is PlayerRepository.ProfileResult.Cached -> _player.value = res.player
            else -> {}
        }
    }

    fun equipPet(petId: Int) {
        viewModelScope.launch {
            _isLoading.value = true
            when (val res = petRepository.equipPet(petId)) {
                is Resource.Success -> {
                    _activePet.value = res.data
                    _petList.value = sortPetsByRarity(
                        _petList.value.map { it.copy(isActive = (it.id == petId)) }
                    )
                    _actionFeedback.emit("Equipped ${res.data.name} as active companion!")
                }
                is Resource.Error -> {
                    _actionFeedback.emit(res.message)
                }
                Resource.Loading -> {}
            }
            _isLoading.value = false
        }
    }

    fun playRps(choice: String) {
        viewModelScope.launch {
            when (val res = petRepository.playRps(choice)) {
                is Resource.Success -> {
                    val response = res.data
                    _rpsResult.emit(response)
                    updateActiveAffectionState(response.currentAffection)
                    val remaining = response.remainingCoins
                    if (remaining != null) {
                        playerRepository.applyLocalStatsPatch(coins = remaining)
                    } else if (response.coinsDropped > 0) {
                        val existingCoins = _player.value?.stats?.coins
                        if (existingCoins != null) {
                            playerRepository.applyLocalStatsPatch(coins = existingCoins + response.coinsDropped)
                        } else {
                            launch { refreshPlayerProfile() }
                        }
                    }
                }
                is Resource.Error -> {
                    _actionFeedback.emit(res.message)
                }
                Resource.Loading -> {}
            }
        }
    }

    fun startBlackjack() {
        if (_isBlackjackBusy.value) return
        val coins = _player.value?.stats?.coins ?: 0
        if (coins < 5) {
            viewModelScope.launch {
                _actionFeedback.emit("Need 5 Coins to play Blackjack!")
            }
            return
        }
        viewModelScope.launch {
            _isBlackjackBusy.value = true
            when (val res = petRepository.startBlackjack()) {
                is Resource.Success -> applyBlackjackResponse(res.data)
                is Resource.Error -> _actionFeedback.emit(res.message)
                Resource.Loading -> {}
            }
            _isBlackjackBusy.value = false
        }
    }

    fun hitBlackjack() {
        val current = _blackjackState.value ?: return
        val token = current.gameToken ?: return
        if (_isBlackjackBusy.value || current.status != "playing") return
        viewModelScope.launch {
            _isBlackjackBusy.value = true
            when (val res = petRepository.hitBlackjack(token)) {
                is Resource.Success -> applyBlackjackResponse(res.data)
                is Resource.Error -> _actionFeedback.emit(res.message)
                Resource.Loading -> {}
            }
            _isBlackjackBusy.value = false
        }
    }

    fun standBlackjack() {
        val current = _blackjackState.value ?: return
        val token = current.gameToken ?: return
        if (_isBlackjackBusy.value || current.status != "playing") return
        viewModelScope.launch {
            _isBlackjackBusy.value = true
            when (val res = petRepository.standBlackjack(token)) {
                is Resource.Success -> applyBlackjackResponse(res.data)
                is Resource.Error -> _actionFeedback.emit(res.message)
                Resource.Loading -> {}
            }
            _isBlackjackBusy.value = false
        }
    }

    fun resetBlackjack() {
        _blackjackState.value = null
    }

    private suspend fun applyBlackjackResponse(state: BlackjackStateResponse) {
        _blackjackState.value = state
        playerRepository.applyLocalStatsPatch(coins = state.remainingCoins)
        if (state.status != "playing") {
            updateActiveAffectionState(state.currentAffection)
            when (state.status) {
                "player_win" -> _reactionEvent.emit("🃏 21! You beat your companion! +5 🪙 & +${state.affectionGained.toInt()} ❤️!")
                "push" -> _reactionEvent.emit("🤝 Push! 5 🪙 refunded & +${state.affectionGained.toInt()} ❤️!")
                "pet_win", "player_bust" -> _reactionEvent.emit("🐾 Companion takes the round! +${state.affectionGained.toInt()} ❤️")
            }
        }
    }

    private fun updateActiveAffectionState(newAffection: Float) {
        val activeId = _activePet.value?.id
        _activePet.value = _activePet.value?.copy(affection = newAffection)
        _petList.value = sortPetsByRarity(
            _petList.value.map {
                if (it.id == activeId) it.copy(affection = newAffection) else it
            }
        )
    }

    fun feedPet(foodId: String? = null) {
        viewModelScope.launch {
            when (val res = petRepository.feedPet(foodId)) {
                is Resource.Success -> {
                    val response = res.data
                    _actionFeedback.emit(response.message)
                    val reactionMsg = if (response.hpHealed > 0) {
                        "😋 好吃! Delicious! +${response.affectionGained.toInt()} ❤️ & +${response.hpHealed} HP!"
                    } else {
                        "😋 好吃! Delicious! +${response.affectionGained.toInt()} ❤️ Affection!"
                    }
                    _reactionEvent.emit(reactionMsg)
                    updateActiveAffectionState(response.currentAffection)
                    when {
                        foodId != null && foodId != "quick_snack" -> {
                            _foodBag.value = _foodBag.value.mapNotNull { item ->
                                if (item.itemId != foodId) {
                                    item
                                } else if (item.quantity > 1) {
                                    item.copy(quantity = item.quantity - 1)
                                } else {
                                    null
                                }
                            }
                        }
                        foodId == "quick_snack" -> {
                            val currentCoins = _player.value?.stats?.coins
                            if (currentCoins != null) {
                                playerRepository.applyLocalStatsPatch(
                                    coins = (currentCoins - 15).coerceAtLeast(0),
                                    hp = response.currentPlayerHp,
                                )
                            }
                        }
                        else -> {
                            launch { loadFoodBag() }
                            launch { refreshPlayerProfile() }
                        }
                    }
                    if (foodId != "quick_snack") {
                        playerRepository.applyLocalStatsPatch(hp = response.currentPlayerHp)
                    }
                }
                is Resource.Error -> {
                    _actionFeedback.emit(res.message)
                }
                Resource.Loading -> {}
            }
        }
    }

    companion object {
        fun rarityRank(rarity: String): Int = when (rarity.lowercase()) {
            "secret" -> 5
            "legendary" -> 4
            "epic" -> 3
            "rare" -> 2
            else -> 1
        }

        fun sortPetsByRarity(pets: List<PetItem>): List<PetItem> {
            return pets.sortedWith(
                compareByDescending<PetItem> { rarityRank(it.rarity) }
                    .thenByDescending { it.isActive }
                    .thenByDescending { it.affection }
                    .thenBy { it.id }
            )
        }

        fun factory(
            petRepository: PetRepository,
            shopRepository: ShopRepository,
            playerRepository: PlayerRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { PetViewModel(petRepository, shopRepository, playerRepository) }
        }
    }
}
