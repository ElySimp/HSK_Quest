package com.faldo.hsk_quest.ui.character

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.faldo.hsk_quest.data.model.Player
import com.faldo.hsk_quest.data.repository.PlayerRepository
import com.faldo.hsk_quest.util.Resource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CharacterViewModel(
    private val playerRepository: PlayerRepository,
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

    fun loadProfile() {
        viewModelScope.launch {
            _state.value = when (val r = playerRepository.fetchProfile()) {
                is PlayerRepository.ProfileResult.Fresh -> UiState.Loaded(r.player)
                is PlayerRepository.ProfileResult.Cached -> UiState.Loaded(r.player)
                is PlayerRepository.ProfileResult.Failure -> UiState.Error(r.message)
                PlayerRepository.ProfileResult.Unauthorized -> UiState.Error("Session expired")
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
                    // Silently ignore or reload
                }
                Resource.Loading -> {}
            }
            _isAllocating.value = false
        }
    }

    companion object {
        fun factory(playerRepository: PlayerRepository): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { CharacterViewModel(playerRepository) }
            }
    }
}
