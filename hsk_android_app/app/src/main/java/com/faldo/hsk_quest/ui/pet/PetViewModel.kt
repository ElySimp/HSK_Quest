package com.faldo.hsk_quest.ui.pet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
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
 * food inventory bag feeding, Rock-Paper-Scissors minigames, and party equipment.
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
        petRepository.cachedPetList?.pets.orEmpty()
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
                    val list = res.data.pets
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
                    // Update isActive flag across collection list
                    _petList.value = _petList.value.map {
                        it.copy(isActive = (it.id == petId))
                    }
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
                    // Update active pet affection locally
                    _activePet.value = _activePet.value?.copy(affection = response.currentAffection)
                    // Update in list
                    _petList.value = _petList.value.map {
                        if (it.id == _activePet.value?.id) it.copy(affection = response.currentAffection) else it
                    }
                    // Refresh player profile if coins dropped
                    if (response.coinsDropped > 0) {
                        refreshPlayerProfile()
                    }
                }
                is Resource.Error -> {
                    _actionFeedback.emit(res.message)
                }
                Resource.Loading -> {}
            }
        }
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
                    // Update affection
                    _activePet.value = _activePet.value?.copy(affection = response.currentAffection)
                    _petList.value = _petList.value.map {
                        if (it.id == _activePet.value?.id) it.copy(affection = response.currentAffection) else it
                    }
                    // Sync food bag and player HP / coin balances immediately
                    loadFoodBag()
                    refreshPlayerProfile()
                }
                is Resource.Error -> {
                    _actionFeedback.emit(res.message)
                }
                Resource.Loading -> {}
            }
        }
    }

    companion object {
        fun factory(
            petRepository: PetRepository,
            shopRepository: ShopRepository,
            playerRepository: PlayerRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { PetViewModel(petRepository, shopRepository, playerRepository) }
        }
    }
}
