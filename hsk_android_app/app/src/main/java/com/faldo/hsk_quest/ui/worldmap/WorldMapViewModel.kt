package com.faldo.hsk_quest.ui.worldmap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.faldo.hsk_quest.data.model.AreaInfo
import com.faldo.hsk_quest.data.model.StageNode
import com.faldo.hsk_quest.data.repository.PlayerRepository
import com.faldo.hsk_quest.data.repository.StageRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class WorldMapViewModel(
    private val stageRepository: StageRepository,
    private val playerRepository: PlayerRepository,
) : ViewModel() {

    private val _selectedAreaId = MutableStateFlow(1)
    val selectedAreaId: StateFlow<Int> = _selectedAreaId.asStateFlow()

    private val _playerLevel = MutableStateFlow(1)
    val playerLevel: StateFlow<Int> = _playerLevel.asStateFlow()

    val areas: StateFlow<List<AreaInfo>> = stageRepository.getAllAreasInfoFlow()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val currentAreaInfo: StateFlow<AreaInfo?> = combine(areas, _selectedAreaId) { list, currentId ->
        list.firstOrNull { it.areaId == currentId }
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    val currentStages: StateFlow<List<StageNode>> = _selectedAreaId
        .flatMapLatest { areaId -> stageRepository.getStageNodesForAreaFlow(areaId) }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        loadPlayerLevel()
    }

    fun selectArea(areaId: Int) {
        _selectedAreaId.value = areaId.coerceIn(1, 9)
    }

    private fun loadPlayerLevel() {
        viewModelScope.launch {
            when (val res = playerRepository.fetchProfile()) {
                is PlayerRepository.ProfileResult.Fresh -> _playerLevel.value = res.player.stats.level
                is PlayerRepository.ProfileResult.Cached -> _playerLevel.value = res.player.stats.level
                else -> {}
            }
        }
    }

    /**
     * Determines if challenging an Organic Gatekeeper Boss poses extreme risk due to underlevel.
     */
    fun shouldWarnGatekeeperBoss(node: StageNode): Boolean {
        if (!node.isGatekeeper) return false
        val recLevel = currentAreaInfo.value?.recommendedLevel ?: (node.areaId * 2)
        return _playerLevel.value < recLevel
    }

    companion object {
        fun factory(
            stageRepository: StageRepository,
            playerRepository: PlayerRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { WorldMapViewModel(stageRepository, playerRepository) }
        }
    }
}
