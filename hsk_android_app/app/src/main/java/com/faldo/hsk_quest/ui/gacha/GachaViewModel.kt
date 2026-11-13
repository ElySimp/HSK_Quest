package com.faldo.hsk_quest.ui.gacha

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.faldo.hsk_quest.data.model.GachaMultiPullResponse
import com.faldo.hsk_quest.data.model.GachaPullResponse
import com.faldo.hsk_quest.data.model.GachaRatesResponse
import com.faldo.hsk_quest.data.repository.GachaRepository
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

data class FeaturedSpiritPreview(
    val petType: String,
    val displayName: String,
    val chineseName: String,
    val spriteKey: String,
)

/**
 * ViewModel for the Celestial Summoning Shrine, managing live drop rates,
 * featured companion cycling, Diamond balance verification, and 1x/10x summons.
 */
class GachaViewModel(
    private val gachaRepository: GachaRepository,
    private val playerRepository: PlayerRepository,
    private val petRepository: PetRepository,
) : ViewModel() {

    val featuredSpirits: List<FeaturedSpiritPreview> = listOf(
        FeaturedSpiritPreview("azure_dragon", "Azure Dragon", "青龙", "pet_azure_dragon"),
        FeaturedSpiritPreview("qilin", "Sacred Qilin", "麒麟", "pet_qilin"),
        FeaturedSpiritPreview("vermilion_phoenix", "Vermilion Bird", "朱雀", "pet_vermilion_phoenix"),
        FeaturedSpiritPreview("fire_crane", "Immortal Fire Crane", "火鹤", "pet_fire_crane"),
        FeaturedSpiritPreview("moon_kitsune", "Nine-Tail Sky Fox", "九尾天狐", "pet_moon_kitsune"),
        FeaturedSpiritPreview("fu_dog", "Guardian Fu Dog", "福狮", "pet_fu_dog"),
    )

    private val _diamonds = MutableStateFlow(0)
    val diamonds: StateFlow<Int> = _diamonds.asStateFlow()

    private val _rates = MutableStateFlow(
        GachaRatesResponse(
            commonRate = 0.75f,
            rareRate = 0.15f,
            epicRate = 0.07f,
            legendaryRate = 0.025f,
            secretRate = 0.005f,
            activeTotalRate = 0.995f,
            costSingle = 100,
            costTen = 900,
        )
    )
    val rates: StateFlow<GachaRatesResponse> = _rates.asStateFlow()

    private val _featuredIndex = MutableStateFlow(0)
    val featuredIndex: StateFlow<Int> = _featuredIndex.asStateFlow()

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

    fun prevFeatured() {
        val count = featuredSpirits.size
        if (count == 0) return
        _featuredIndex.value = (_featuredIndex.value - 1 + count) % count
    }

    fun nextFeatured() {
        val count = featuredSpirits.size
        if (count == 0) return
        _featuredIndex.value = (_featuredIndex.value + 1) % count
    }

    fun selectFeatured(index: Int) {
        if (index in featuredSpirits.indices) {
            _featuredIndex.value = index
        }
    }

    fun loadBalance() {
        viewModelScope.launch {
            launch {
                when (val r = gachaRepository.getRates()) {
                    is Resource.Success -> _rates.value = r.data
                    else -> {}
                }
            }
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
        val cost = _rates.value.costSingle.coerceAtLeast(1)
        if (_diamonds.value < cost) {
            viewModelScope.launch {
                _feedbackMessage.emit("Not enough diamonds! $cost 💎 required.")
            }
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            _summoningMode.value = 1
            when (val res = gachaRepository.pullSingle()) {
                is Resource.Success -> {
                    _diamonds.value = res.data.remainingDiamonds
                    _summoningMode.value = 0
                    _isLoading.value = false
                    _singleSummonResult.emit(res.data)
                    playerRepository.applyLocalStatsPatch(diamonds = res.data.remainingDiamonds)
                    launch { petRepository.getPets() }
                    if (!res.data.isNewCompanion) {
                        launch { playerRepository.fetchProfile() }
                    }
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
        val cost = _rates.value.costTen.coerceAtLeast(1)
        if (_diamonds.value < cost) {
            viewModelScope.launch {
                _feedbackMessage.emit("Not enough diamonds! $cost 💎 required.")
            }
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            _summoningMode.value = 10
            when (val res = gachaRepository.pullTen()) {
                is Resource.Success -> {
                    _diamonds.value = res.data.remainingDiamonds
                    _summoningMode.value = 0
                    _isLoading.value = false
                    _multiSummonResult.emit(res.data)
                    playerRepository.applyLocalStatsPatch(diamonds = res.data.remainingDiamonds)
                    launch { petRepository.getPets() }
                    launch { playerRepository.fetchProfile() }
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
