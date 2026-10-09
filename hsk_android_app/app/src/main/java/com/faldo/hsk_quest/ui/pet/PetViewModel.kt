package com.faldo.hsk_quest.ui.pet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.faldo.hsk_quest.data.model.PetItem
import com.faldo.hsk_quest.data.model.RpsResponse
import com.faldo.hsk_quest.data.repository.PetRepository
import com.faldo.hsk_quest.data.repository.PlayerRepository
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
 * Rock-Paper-Scissors minigames, and party equipment.
 */
class PetViewModel(
    private val petRepository: PetRepository,
    private val playerRepository: PlayerRepository,
) : ViewModel() {

    private val _activePet = MutableStateFlow<PetItem?>(null)
    val activePet: StateFlow<PetItem?> = _activePet.asStateFlow()

    private val _petList = MutableStateFlow<List<PetItem>>(emptyList())
    val petList: StateFlow<List<PetItem>> = _petList.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _actionFeedback = MutableSharedFlow<String>()
    val actionFeedback: SharedFlow<String> = _actionFeedback.asSharedFlow()

    private val _rpsResult = MutableSharedFlow<RpsResponse>()
    val rpsResult: SharedFlow<RpsResponse> = _rpsResult.asSharedFlow()

    fun loadPetData() {
        viewModelScope.launch {
            _isLoading.value = true
            when (val res = petRepository.getPets()) {
                is Resource.Success -> {
                    val list = res.data.pets
                    _petList.value = list
                    _activePet.value = res.data.activePet ?: list.firstOrNull { it.isActive }
                }
                is Resource.Error -> {
                    _actionFeedback.emit(res.message)
                }
                Resource.Loading -> {}
            }
            _isLoading.value = false
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
                        playerRepository.fetchProfile()
                    }
                }
                is Resource.Error -> {
                    _actionFeedback.emit(res.message)
                }
                Resource.Loading -> {}
            }
        }
    }

    fun feedPet() {
        viewModelScope.launch {
            when (val res = petRepository.feedPet()) {
                is Resource.Success -> {
                    val response = res.data
                    _actionFeedback.emit(response.message)
                    // Update affection
                    _activePet.value = _activePet.value?.copy(affection = response.currentAffection)
                    _petList.value = _petList.value.map {
                        if (it.id == _activePet.value?.id) it.copy(affection = response.currentAffection) else it
                    }
                    // Sync player HP / coin balances
                    playerRepository.fetchProfile()
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
            playerRepository: PlayerRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { PetViewModel(petRepository, playerRepository) }
        }
    }
}
