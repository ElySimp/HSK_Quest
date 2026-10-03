package com.faldo.hsk_quest.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.faldo.hsk_quest.data.model.Player
import com.faldo.hsk_quest.data.repository.AuthRepository
import com.faldo.hsk_quest.data.repository.PlayerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HomeViewModel(
    private val playerRepository: PlayerRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    sealed class UiState {
        data object Loading : UiState()
        data class Loaded(val player: Player, val isCached: Boolean) : UiState()
        data class Error(val message: String) : UiState()

        /** Session expired or logged out: go back to Login. */
        data object LoggedOut : UiState()
    }

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            // Keep showing current data while refreshing.
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
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { HomeViewModel(playerRepository, authRepository) }
        }
    }
}
