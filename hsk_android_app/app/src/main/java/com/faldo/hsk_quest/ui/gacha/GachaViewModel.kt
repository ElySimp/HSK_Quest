package com.faldo.hsk_quest.ui.gacha

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.faldo.hsk_quest.data.model.GachaMultiPullResponse
import com.faldo.hsk_quest.data.model.GachaPullResponse
import com.faldo.hsk_quest.data.repository.GachaRepository
import com.faldo.hsk_quest.data.repository.PetRepository
import com.faldo.hsk_quest.data.repository.PlayerRepository
import com.faldo.hsk_quest.util.Resource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the Gacha Summoning Altar, handling Diamond balance verification,
 * single & 10x summons, and summoning animation events.
 */
class GachaViewModel(
    private val gachaRepository: GachaRepository,
    private val playerRepository: PlayerRepository,
    private val petRepository: PetRepository,
) : ViewModel() {

    private val _diamonds = MutableStateFlow(0)
    val diamonds: StateFlow<Int> = _diamonds.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /** 0 = idle, 1 = 1x summon ritual in progress, 10 = 10x summon ritual in progress */
    private val _summoningMode = MutableStateFlow(0)
    val summoningMode: StateFlow<Int> = _summoningMode.asStateFlow()

    private val _singleSummonResult = MutableSharedFlow<GachaPullResponse>()
    val singleSummonResult: SharedFlow<GachaPullResponse> = _singleSummonResult.asSharedFlow()

    private val _multiSummonResult = MutableSharedFlow<GachaMultiPullResponse>()
    val multiSummonResult: SharedFlow<GachaMultiPullResponse> = _multiSummonResult.asSharedFlow()

    private val _feedbackMessage = MutableSharedFlow<String>()
    val feedbackMessage: SharedFlow<String> = _feedbackMessage.asSharedFlow()

    init {
        viewModelScope.launch {
            playerRepository.observeLocalPlayer().collect { localPlayer ->
                if (localPlayer != null) {
                    _diamonds.value = localPlayer.stats.diamonds
                }
            }
        }
    }

    fun loadBalance() {
        viewModelScope.launch {
            when (val r = playerRepository.fetchProfile()) {
                is PlayerRepository.ProfileResult.Fresh -> {
                    _diamonds.value = r.player.stats.diamonds
                }
                is PlayerRepository.ProfileResult.Cached -> {
                    _diamonds.value = r.player.stats.diamonds
                }
                else -> {}
            }
        }
    }

    fun pullSingle() {
        if (_isLoading.value) return
        if (_diamonds.value < 100) {
            viewModelScope.launch {
                _feedbackMessage.emit("Not enough diamonds! 100 💎 required.")
            }
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            _summoningMode.value = 1
            val startTime = System.currentTimeMillis()
            when (val res = gachaRepository.pullSingle()) {
                is Resource.Success -> {
                    val elapsed = System.currentTimeMillis() - startTime
                    if (elapsed < 950L) {
                        delay(950L - elapsed)
                    }
                    _diamonds.value = res.data.remainingDiamonds
                    _summoningMode.value = 0
                    _isLoading.value = false
                    _singleSummonResult.emit(res.data)
                    playerRepository.fetchProfile()
                }
                is Resource.Error -> {
                    _summoningMode.value = 0
                    _isLoading.value = false
                    _feedbackMessage.emit(res.message)
                }
                Resource.Loading -> {
                    _summoningMode.value = 0
                    _isLoading.value = false
                }
            }
        }
    }

    fun pullTen() {
        if (_isLoading.value) return
        if (_diamonds.value < 900) {
            viewModelScope.launch {
                _feedbackMessage.emit("Not enough diamonds! 900 💎 required.")
            }
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            _summoningMode.value = 10
            val startTime = System.currentTimeMillis()
            when (val res = gachaRepository.pullTen()) {
                is Resource.Success -> {
                    val elapsed = System.currentTimeMillis() - startTime
                    if (elapsed < 950L) {
                        delay(950L - elapsed)
                    }
                    _diamonds.value = res.data.remainingDiamonds
                    _summoningMode.value = 0
                    _isLoading.value = false
                    _multiSummonResult.emit(res.data)
                    playerRepository.fetchProfile()
                }
                is Resource.Error -> {
                    _summoningMode.value = 0
                    _isLoading.value = false
                    _feedbackMessage.emit(res.message)
                }
                Resource.Loading -> {
                    _summoningMode.value = 0
                    _isLoading.value = false
                }
            }
        }
    }

    companion object {
        fun factory(
            gachaRepository: GachaRepository,
            playerRepository: PlayerRepository,
            petRepository: PetRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { GachaViewModel(gachaRepository, playerRepository, petRepository) }
        }
    }
}
