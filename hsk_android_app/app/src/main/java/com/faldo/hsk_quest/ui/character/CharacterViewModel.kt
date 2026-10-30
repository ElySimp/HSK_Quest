package com.faldo.hsk_quest.ui.character

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.faldo.hsk_quest.data.model.Player
import com.faldo.hsk_quest.data.repository.AuthRepository
import com.faldo.hsk_quest.data.repository.PlayerRepository
import com.faldo.hsk_quest.util.Resource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CharacterViewModel(
    private val playerRepository: PlayerRepository,
    val authRepository: AuthRepository? = null,
) : ViewModel() {

    sealed class UiState {
        data object Loading : UiState()
        data class Loaded(val player: Player) : UiState()
        data class Error(val message: String) : UiState()
    }

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _isAllocating = MutableStateFlow(false)
    val isAllocating: StateFlow<Boolean> = _isAllocating.asStateFlow()

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    init {
        viewModelScope.launch {
            playerRepository.observeLocalPlayer().collect { localPlayer ->
                if (localPlayer != null) {
                    _state.value = UiState.Loaded(localPlayer)
                }
            }
        }
    }

    fun clearToastMessage() {
        _toastMessage.value = null
    }

    fun loadProfile() {
        viewModelScope.launch {
            if (_state.value !is UiState.Loaded) {
                _state.value = UiState.Loading
            }
            when (val r = playerRepository.fetchProfile()) {
                is PlayerRepository.ProfileResult.Fresh -> {
                    _state.value = UiState.Loaded(r.player)
                }
                is PlayerRepository.ProfileResult.Cached -> {
                    _state.value = UiState.Loaded(r.player)
                }
                is PlayerRepository.ProfileResult.Failure -> {
                    if (_state.value !is UiState.Loaded) {
                        _state.value = UiState.Error(r.message)
                    }
                }
                PlayerRepository.ProfileResult.Unauthorized -> {
                    _state.value = UiState.Error("Session expired")
                }
            }
        }
    }

    fun allocateStat(statName: String) {
        if (_isAllocating.value) return
        val current = (_state.value as? UiState.Loaded)?.player ?: return
        if (current.stats.unspentStatPoints <= 0) return

        _isAllocating.value = true
        viewModelScope.launch {
            when (val r = playerRepository.allocateStat(statName)) {
                is Resource.Success -> {
                    _state.value = UiState.Loaded(current.copy(stats = r.data))
                }
                is Resource.Error -> {
                    _toastMessage.value = r.message
                }
                Resource.Loading -> {}
            }
            _isAllocating.value = false
        }
    }

    fun updateProfile(username: String, hskTarget: Int) {
        val trimmed = username.trim()
        if (trimmed.length < 2 || trimmed.length > 50) {
            _toastMessage.value = "Username must be between 2 and 50 characters."
            return
        }
        viewModelScope.launch {
            when (val r = playerRepository.updateProfile(trimmed, hskTarget)) {
                is Resource.Success -> {
                    _state.value = UiState.Loaded(r.data)
                    _toastMessage.value = "Profile updated! Target: HSK ${r.data.hskTarget}"
                }
                is Resource.Error -> {
                    _toastMessage.value = r.message
                }
                Resource.Loading -> {}
            }
        }
    }

    companion object {
        fun factory(
            playerRepository: PlayerRepository,
            authRepository: AuthRepository? = null,
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { CharacterViewModel(playerRepository, authRepository) }
            }
    }
}
