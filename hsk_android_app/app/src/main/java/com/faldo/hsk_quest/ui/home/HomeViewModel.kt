package com.faldo.hsk_quest.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.faldo.hsk_quest.data.model.DailyStatusResponse
import com.faldo.hsk_quest.data.model.PetItem
import com.faldo.hsk_quest.data.model.Player
import com.faldo.hsk_quest.data.repository.AuthRepository
import com.faldo.hsk_quest.data.repository.PetRepository
import com.faldo.hsk_quest.data.repository.PlayerRepository
import com.faldo.hsk_quest.util.Resource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HomeViewModel(
    private val playerRepository: PlayerRepository,
    private val authRepository: AuthRepository,
    private val petRepository: PetRepository,
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

    private val _dailyStatus = MutableStateFlow<DailyStatusResponse?>(null)
    val dailyStatus: StateFlow<DailyStatusResponse?> = _dailyStatus.asStateFlow()

    private val _isClaimingDaily = MutableStateFlow(false)
    val isClaimingDaily: StateFlow<Boolean> = _isClaimingDaily.asStateFlow()

    private val _petState = MutableStateFlow<PetState>(PetState.Loading)
    val petState: StateFlow<PetState> = _petState.asStateFlow()

    fun refresh() {
        // 1. Fetch player profile
        viewModelScope.launch {
            if (_state.value !is UiState.Loaded) _state.value = UiState.Loading
            _state.value = when (val r = playerRepository.fetchProfile()) {
                is PlayerRepository.ProfileResult.Fresh -> UiState.Loaded(r.player, isCached = false)
                is PlayerRepository.ProfileResult.Cached -> UiState.Loaded(r.player, isCached = true)
                is PlayerRepository.ProfileResult.Failure -> UiState.Error(r.message)
                PlayerRepository.ProfileResult.Unauthorized -> {
                    authRepository.logout()
                    UiState.LoggedOut
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
                }
                is Resource.Error -> {}
                Resource.Loading -> {}
            }
            _isClaimingDaily.value = false
        }
    }

    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
            _state.value = UiState.LoggedOut
        }
    }

    companion object {
        fun factory(
            playerRepository: PlayerRepository,
            authRepository: AuthRepository,
            petRepository: PetRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { HomeViewModel(playerRepository, authRepository, petRepository) }
        }
    }
}
