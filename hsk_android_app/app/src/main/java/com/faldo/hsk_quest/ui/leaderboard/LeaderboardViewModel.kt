package com.faldo.hsk_quest.ui.leaderboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.faldo.hsk_quest.data.model.WeeklyLeaderboardResponse
import com.faldo.hsk_quest.data.repository.LeaderboardRepository
import com.faldo.hsk_quest.util.Resource
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class LeaderboardViewModel(
    private val leaderboardRepository: LeaderboardRepository,
) : ViewModel() {

    sealed class UiState {
        data object Loading : UiState()
        data class Loaded(val data: WeeklyLeaderboardResponse) : UiState()
        data class Error(val message: String) : UiState()
    }

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _selectedLeague = MutableStateFlow<String?>(null)
    val selectedLeague: StateFlow<String?> = _selectedLeague.asStateFlow()

    private val _secondsRemaining = MutableStateFlow(0)
    val secondsRemaining: StateFlow<Int> = _secondsRemaining.asStateFlow()

    private var countdownJob: Job? = null

    init {
        leaderboardRepository.cachedLeaderboard?.let { cached ->
            _selectedLeague.value = cached.league
            _state.value = UiState.Loaded(cached)
            startCountdown(cached.secondsUntilReset)
        }
    }

    fun loadLeaderboard(league: String? = _selectedLeague.value) {
        _selectedLeague.value = league
        viewModelScope.launch {
            val cached = leaderboardRepository.cachedLeaderboard
            if (cached != null && (league == null || cached.league.equals(league, ignoreCase = true))) {
                _selectedLeague.value = cached.league
                _state.value = UiState.Loaded(cached)
                startCountdown(cached.secondsUntilReset)
            } else if (_state.value !is UiState.Loaded) {
                _state.value = UiState.Loading
            }
            when (val res = leaderboardRepository.getWeeklyLeaderboard(league)) {
                is Resource.Success -> {
                    val data = res.data
                    _selectedLeague.value = data.league
                    _state.value = UiState.Loaded(data)
                    startCountdown(data.secondsUntilReset)
                }
                is Resource.Error -> {
                    if (_state.value !is UiState.Loaded) {
                        _state.value = UiState.Error(res.message)
                    }
                }
                Resource.Loading -> {}
            }
        }
    }

    private fun startCountdown(initialSeconds: Int) {
        countdownJob?.cancel()
        _secondsRemaining.value = initialSeconds.coerceAtLeast(0)
        countdownJob = viewModelScope.launch {
            while (isActive && _secondsRemaining.value > 0) {
                delay(1_000L)
                _secondsRemaining.value = (_secondsRemaining.value - 1).coerceAtLeast(0)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        countdownJob?.cancel()
    }

    companion object {
        fun factory(leaderboardRepository: LeaderboardRepository): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { LeaderboardViewModel(leaderboardRepository) }
            }
    }
}
