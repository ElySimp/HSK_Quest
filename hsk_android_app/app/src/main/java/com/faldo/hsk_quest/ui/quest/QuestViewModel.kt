package com.faldo.hsk_quest.ui.quest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.faldo.hsk_quest.data.model.ClaimQuestResponse
import com.faldo.hsk_quest.data.model.DailyQuestListResponse
import com.faldo.hsk_quest.data.model.Player
import com.faldo.hsk_quest.data.repository.PlayerRepository
import com.faldo.hsk_quest.data.repository.QuestRepository
import com.faldo.hsk_quest.util.Resource
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for Daily Quests, syncing live backend quest progress,
 * Diamond & Coin wallet totals, and single/batch reward claims.
 */
class QuestViewModel(
    private val questRepository: QuestRepository,
    private val playerRepository: PlayerRepository,
) : ViewModel() {

    private val _dailyQuests = MutableStateFlow(questRepository.cachedDailyQuests)
    val dailyQuests: StateFlow<DailyQuestListResponse?> = _dailyQuests.asStateFlow()

    private val _player = MutableStateFlow<Player?>(playerRepository.cachedMemoryPlayer)
    val player: StateFlow<Player?> = _player.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _claimingKey = MutableStateFlow<String?>(null)
    val claimingKey: StateFlow<String?> = _claimingKey.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _feedbackMessage = MutableSharedFlow<String>()
    val feedbackMessage: SharedFlow<String> = _feedbackMessage.asSharedFlow()

    private val _claimSuccessEvent = MutableSharedFlow<ClaimQuestResponse>()
    val claimSuccessEvent: SharedFlow<ClaimQuestResponse> = _claimSuccessEvent.asSharedFlow()

    init {
        viewModelScope.launch {
            playerRepository.observeLocalPlayer().collect { localPlayer ->
                if (localPlayer != null) {
                    _player.value = localPlayer
                }
            }
        }
    }

    fun loadQuests() {
        viewModelScope.launch {
            _isLoading.value = _dailyQuests.value == null
            _errorMessage.value = null
            when (val res = questRepository.getDailyQuests()) {
                is Resource.Success -> {
                    _dailyQuests.value = res.data
                }
                is Resource.Error -> {
                    if (_dailyQuests.value == null) {
                        _errorMessage.value = res.message
                    } else {
                        _feedbackMessage.emit(res.message)
                    }
                }
                Resource.Loading -> {}
            }
            _isLoading.value = false
        }
    }

    fun claimQuest(questKey: String) {
        if (_claimingKey.value != null) return
        viewModelScope.launch {
            _claimingKey.value = questKey
            when (val res = questRepository.claimQuest(questKey)) {
                is Resource.Success -> {
                    _dailyQuests.value = res.data.daily
                    _claimSuccessEvent.emit(res.data)
                    _feedbackMessage.emit(res.data.message)
                }
                is Resource.Error -> {
                    _feedbackMessage.emit(res.message)
                }
                Resource.Loading -> {}
            }
            _claimingKey.value = null
        }
    }

    fun claimAll() {
        if (_claimingKey.value != null) return
        val current = _dailyQuests.value ?: return
        if (current.claimableCount <= 0) return

        viewModelScope.launch {
            _claimingKey.value = "__ALL__"
            when (val res = questRepository.claimAllQuests()) {
                is Resource.Success -> {
                    _dailyQuests.value = res.data.daily
                    _claimSuccessEvent.emit(res.data)
                    _feedbackMessage.emit(res.data.message)
                }
                is Resource.Error -> {
                    _feedbackMessage.emit(res.message)
                }
                Resource.Loading -> {}
            }
            _claimingKey.value = null
        }
    }

    companion object {
        fun factory(
            questRepository: QuestRepository,
            playerRepository: PlayerRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { QuestViewModel(questRepository, playerRepository) }
        }
    }
}
