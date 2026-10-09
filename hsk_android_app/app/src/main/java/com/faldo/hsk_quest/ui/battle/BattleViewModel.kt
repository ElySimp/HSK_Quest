package com.faldo.hsk_quest.ui.battle

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.faldo.hsk_quest.data.local.HskJsonLoader
import com.faldo.hsk_quest.data.model.BattleResultRequest
import com.faldo.hsk_quest.data.model.BattleResultResponse
import com.faldo.hsk_quest.data.model.BattleState
import com.faldo.hsk_quest.data.model.HskTerm
import com.faldo.hsk_quest.data.model.MonsterType
import com.faldo.hsk_quest.data.repository.BattleRepository
import com.faldo.hsk_quest.data.repository.PlayerRepository
import com.faldo.hsk_quest.engine.BattleEngine
import com.faldo.hsk_quest.engine.BattleQuestion
import com.faldo.hsk_quest.engine.DamageCalculator
import com.faldo.hsk_quest.engine.MonsterStats
import com.faldo.hsk_quest.engine.QuestionGenerator
import com.faldo.hsk_quest.util.Resource
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class BattleViewModel(
    private val hskJsonLoader: HskJsonLoader,
    private val battleRepository: BattleRepository,
    private val playerRepository: PlayerRepository,
    private val petRepository: com.faldo.hsk_quest.data.repository.PetRepository,
) : ViewModel() {

    private var areaId: Int = 1
    private var monsterType: MonsterType = MonsterType.NORMAL
    private var terms: List<HskTerm> = emptyList()

    private var statStr: Int = 0
    private var statDex: Int = 0
    private var statDef: Int = 0
    private var statVit: Int = 0
    private var hasTimeExtender: Boolean = false

    private val _battleState = MutableStateFlow<BattleState?>(null)
    val battleState: StateFlow<BattleState?> = _battleState.asStateFlow()

    private val _currentQuestion = MutableStateFlow<BattleQuestion?>(null)
    val currentQuestion: StateFlow<BattleQuestion?> = _currentQuestion.asStateFlow()

    // Timer: 0 to 5000 ms remaining
    private val _timerProgressMs = MutableStateFlow<Long>(BattleEngine.DEFAULT_ANSWER_LIMIT_MS)
    val timerProgressMs: StateFlow<Long> = _timerProgressMs.asStateFlow()

    private val _serverResult = MutableSharedFlow<BattleResultResponse>()
    val serverResult: SharedFlow<BattleResultResponse> = _serverResult.asSharedFlow()

    private val _isSubmittingResult = MutableStateFlow(false)
    val isSubmittingResult: StateFlow<Boolean> = _isSubmittingResult.asStateFlow()

    private var timerJob: Job? = null
    private var questionStartTime: Long = 0L

    fun startBattle(areaId: Int, monsterTypeStr: String) {
        this.areaId = areaId
        this.monsterType = MonsterType.fromApiName(monsterTypeStr)

        viewModelScope.launch {
            // Load vocabulary
            terms = runCatching { hskJsonLoader.loadLevel(areaId) }.getOrDefault(emptyList())

            // Load player profile
            val playerResult = playerRepository.fetchProfile()
            val player = when (playerResult) {
                is PlayerRepository.ProfileResult.Fresh -> playerResult.player
                is PlayerRepository.ProfileResult.Cached -> playerResult.player
                else -> null
            }

            statStr = player?.stats?.statStr ?: 0
            statDex = player?.stats?.statDex ?: 0
            statDef = player?.stats?.statDef ?: 0
            statVit = player?.stats?.statVit ?: 0
            val maxHp = player?.stats?.maxHp ?: DamageCalculator.maxHp(statVit)
            val currentHp = player?.stats?.hp ?: maxHp

            val monster = MonsterStats.create(areaId, monsterType)

            // Check equipped companion pet buffs
            val petRes = petRepository.getActivePet()
            val activePet = if (petRes is Resource.Success) petRes.data else null
            val hasShield = (activePet?.buffType == "shield")
            hasTimeExtender = (activePet?.buffType == "time_extender")

            val initial = BattleState(
                monster = monster,
                monsterHp = monster.maxHp,
                playerHp = currentHp,
                playerMaxHp = maxHp,
                shieldAvailable = hasShield,
            )
            _battleState.value = initial

            nextQuestion()
        }
    }

    fun submitAnswer(selectedIndex: Int) {
        val currentQ = _currentQuestion.value ?: return
        val current = _battleState.value ?: return
        if (current.isFinished) return

        stopTimer()

        val elapsedMs = System.currentTimeMillis() - questionStartTime
        val isCorrect = selectedIndex >= 0 && currentQ.isCorrect(selectedIndex)

        val updated = BattleEngine.applyAnswer(
            currentState = current,
            isCorrect = isCorrect,
            elapsedTimeMs = elapsedMs,
            statStr = statStr,
            statDex = statDex,
            statDef = statDef,
            hasTimeExtender = hasTimeExtender,
        )
        _battleState.value = updated

        if (updated.isFinished) {
            finalizeBattle(updated)
        } else {
            // Delay slightly for damage animations before next question
            viewModelScope.launch {
                delay(750)
                if (!(_battleState.value?.isFinished ?: true)) {
                    nextQuestion()
                }
            }
        }
    }

    fun flee() {
        val current = _battleState.value ?: return
        if (current.isFinished) return
        stopTimer()

        val fledState = BattleEngine.flee(current)
        _battleState.value = fledState
        finalizeBattle(fledState)
    }

    fun dismissLowHpWarning() {
        val current = _battleState.value ?: return
        _battleState.value = BattleEngine.dismissLowHpWarning(current)
    }

    private fun nextQuestion() {
        if (terms.isEmpty()) return
        val q = QuestionGenerator.generateQuestion(terms) ?: return
        _currentQuestion.value = q
        startTimer()
    }

    private fun startTimer() {
        stopTimer()
        questionStartTime = System.currentTimeMillis()
        val totalMs = BattleEngine.DEFAULT_ANSWER_LIMIT_MS

        timerJob = viewModelScope.launch {
            var remaining = totalMs
            while (remaining > 0) {
                _timerProgressMs.value = remaining
                delay(50)
                remaining -= 50
            }
            _timerProgressMs.value = 0
            // Timeout equals wrong answer
            submitAnswer(selectedIndex = -1)
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    private fun finalizeBattle(state: BattleState) {
        stopTimer()
        _isSubmittingResult.value = true

        val resultStr = when (state.phase) {
            BattleState.Phase.VICTORY -> "win"
            BattleState.Phase.DEFEAT -> "lose"
            BattleState.Phase.FLED -> "flee"
            BattleState.Phase.IN_PROGRESS -> "flee"
        }

        // Encoded monster identifier: area * 100 + typeIndex * 10 + 1
        val monsterIdCode = areaId * 100 + monsterType.ordinal * 10 + 1

        val request = BattleResultRequest(
            monsterId = monsterIdCode,
            areaId = areaId,
            monsterType = monsterType.apiName,
            result = resultStr,
            damageDealt = state.totalDamageDealt,
            damageTaken = state.totalDamageTaken,
            questionsAnswered = state.totalQuestionsAnswered,
            correctAnswers = state.correctAnswers,
            skillType = "reading",
        )

        viewModelScope.launch {
            when (val r = battleRepository.submitBattleResult(request)) {
                is Resource.Success -> {
                    _serverResult.emit(r.data)
                }
                is Resource.Error -> {
                    // Fallback local response if network error
                    _serverResult.emit(
                        BattleResultResponse(
                            result = resultStr,
                            xpEarned = if (resultStr == "win") state.monster.xpReward else 0,
                            coinsEarned = if (resultStr == "win") state.monster.coinReward else 0,
                            damageDealt = state.totalDamageDealt,
                            levelUp = false,
                            newLevel = null,
                            statPointsAvailable = 0,
                            survivalHp = state.playerHp,
                            lostItem = null,
                            message = if (resultStr == "win") "Victory!" else "Defeated offline.",
                        )
                    )
                }
                Resource.Loading -> {}
            }
            _isSubmittingResult.value = false
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopTimer()
    }

    companion object {
        fun factory(
            hskJsonLoader: HskJsonLoader,
            battleRepository: BattleRepository,
            playerRepository: PlayerRepository,
            petRepository: com.faldo.hsk_quest.data.repository.PetRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { BattleViewModel(hskJsonLoader, battleRepository, playerRepository, petRepository) }
        }
    }
}
