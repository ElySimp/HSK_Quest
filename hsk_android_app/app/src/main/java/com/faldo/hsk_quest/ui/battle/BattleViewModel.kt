package com.faldo.hsk_quest.ui.battle

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.faldo.hsk_quest.data.local.HskJsonLoader
import com.faldo.hsk_quest.data.local.TokenManager
import com.faldo.hsk_quest.data.local.dao.VocabMasteryDao
import com.faldo.hsk_quest.data.model.BattleResultRequest
import com.faldo.hsk_quest.data.model.BattleResultResponse
import com.faldo.hsk_quest.data.model.BattleState
import com.faldo.hsk_quest.data.model.HskTerm
import com.faldo.hsk_quest.data.model.MonsterType
import com.faldo.hsk_quest.data.model.PetItem
import com.faldo.hsk_quest.data.model.VocabDeltaDto
import com.faldo.hsk_quest.data.repository.BattleRepository
import com.faldo.hsk_quest.data.repository.PetRepository
import com.faldo.hsk_quest.data.repository.PlayerRepository
import com.faldo.hsk_quest.data.repository.ShopRepository
import com.faldo.hsk_quest.data.repository.StageRepository
import com.faldo.hsk_quest.engine.BattleEngine
import com.faldo.hsk_quest.engine.BattleQuestion
import com.faldo.hsk_quest.engine.DamageCalculator
import com.faldo.hsk_quest.engine.MonsterStats
import com.faldo.hsk_quest.engine.QuestionGenerator
import com.faldo.hsk_quest.engine.SpeechEvalResult
import com.faldo.hsk_quest.engine.SpeechGrade
import com.faldo.hsk_quest.util.Constants
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

data class OwnedPotion(
    val itemId: String,
    val name: String,
    val healPct: Int,
    val quantity: Int,
)

class BattleViewModel(
    private val hskJsonLoader: HskJsonLoader,
    private val battleRepository: BattleRepository,
    private val playerRepository: PlayerRepository,
    private val petRepository: PetRepository,
    private val stageRepository: StageRepository,
    private val shopRepository: ShopRepository,
    private val vocabMasteryDao: VocabMasteryDao,
    private val tokenManager: TokenManager,
) : ViewModel() {

    private var areaId: Int = 1
    private var monsterType: MonsterType = MonsterType.NORMAL
    private var monsterIndex: Int = 1
    private var terms: List<HskTerm> = emptyList()

    private var mistakeWeights: MutableMap<String, Int> = mutableMapOf()
    private val battleVocabDeltas: MutableMap<String, VocabDeltaDto> = mutableMapOf()
    private var allowSpeaking: Boolean = true

    private var statStr: Int = 0
    private var statDex: Int = 0
    private var statDef: Int = 0
    private var statVit: Int = 0
    private var hasTimeExtender: Boolean = false
    private var hasDamageBoost: Boolean = false
    private var isUnderleveledBoss: Boolean = false

    private val _battleState = MutableStateFlow<BattleState?>(null)
    val battleState: StateFlow<BattleState?> = _battleState.asStateFlow()

    private val _activePet = MutableStateFlow<PetItem?>(null)
    val activePet: StateFlow<PetItem?> = _activePet.asStateFlow()

    private val _currentQuestion = MutableStateFlow<BattleQuestion?>(null)
    val currentQuestion: StateFlow<BattleQuestion?> = _currentQuestion.asStateFlow()

    // Timer: 0 to limitMs remaining
    private val _timerProgressMs = MutableStateFlow<Long>(BattleEngine.DEFAULT_ANSWER_LIMIT_MS)
    val timerProgressMs: StateFlow<Long> = _timerProgressMs.asStateFlow()

    private val _currentQuestionLimitMs = MutableStateFlow<Long>(BattleEngine.DEFAULT_ANSWER_LIMIT_MS)
    val currentQuestionLimitMs: StateFlow<Long> = _currentQuestionLimitMs.asStateFlow()

    private val _ownedPotions = MutableStateFlow<List<OwnedPotion>>(emptyList())
    val ownedPotions: StateFlow<List<OwnedPotion>> = _ownedPotions.asStateFlow()

    private val _elixirCount = MutableStateFlow(0)
    val elixirCount: StateFlow<Int> = _elixirCount.asStateFlow()

    private val _battleToast = MutableSharedFlow<String>()
    val battleToast: SharedFlow<String> = _battleToast.asSharedFlow()

    private val _serverResult = MutableSharedFlow<BattleResultResponse>()
    val serverResult: SharedFlow<BattleResultResponse> = _serverResult.asSharedFlow()

    private val _isSubmittingResult = MutableStateFlow(false)
    val isSubmittingResult: StateFlow<Boolean> = _isSubmittingResult.asStateFlow()

    private var timerJob: Job? = null
    private var questionStartTime: Long = 0L
    private var pauseTimestamp: Long = 0L

    fun startBattle(areaId: Int, monsterTypeStr: String, monsterIndex: Int = 1) {
        this.areaId = areaId
        this.monsterType = MonsterType.fromApiName(monsterTypeStr)
        this.monsterIndex = monsterIndex
        battleVocabDeltas.clear()
        _activePet.value = petRepository.cachedActivePet

        viewModelScope.launch {
            // Load vocabulary
            terms = runCatching { hskJsonLoader.loadLevel(areaId) }.getOrDefault(emptyList())

            // Load adaptive mistake weights for this user and HSK level
            val username = tokenManager.getUsername() ?: "guest"
            val masteryEntries = runCatching {
                vocabMasteryDao.getForLevel(username, areaId)
            }.getOrDefault(emptyList())
            mistakeWeights = masteryEntries
                .filter { it.wrongCount > 0 && it.masteryScore < 85 }
                .associate { it.hanzi to it.wrongCount }
                .toMutableMap()

            // Fetch owned battle healing potions asynchronously so battle start is never blocked
            launch {
                shopRepository.getInventory().onSuccess { items ->
                    val potions = items
                        .filter { it.quantity > 0 }
                        .mapNotNull { inv ->
                            when (inv.itemId) {
                                "minor_healing_potion", "salve_elixir" -> OwnedPotion(
                                    itemId = inv.itemId,
                                    name = "Minor Healing Potion (+25% HP)",
                                    healPct = 25,
                                    quantity = inv.quantity,
                                )
                                "healing_potion", "vitality_elixir" -> OwnedPotion(
                                    itemId = inv.itemId,
                                    name = "Healing Potion (+50% HP)",
                                    healPct = 50,
                                    quantity = inv.quantity,
                                )
                                "greater_healing_potion" -> OwnedPotion(
                                    itemId = inv.itemId,
                                    name = "Greater Healing Potion (+75% HP)",
                                    healPct = 75,
                                    quantity = inv.quantity,
                                )
                                "supreme_healing_potion" -> OwnedPotion(
                                    itemId = inv.itemId,
                                    name = "Supreme Healing Potion (+100% HP)",
                                    healPct = 100,
                                    quantity = inv.quantity,
                                )
                                else -> null
                            }
                        }
                        .sortedBy { it.healPct }
                    _ownedPotions.value = potions
                    _elixirCount.value = potions.sumOf { it.quantity }
                }
            }

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

            val monster = MonsterStats.create(areaId, monsterType, monsterIndex)

            // Check equipped companion pet buffs (only active if awake: affection >= 25)
            val petRes = petRepository.getActivePet()
            val activePet = if (petRes is Resource.Success) petRes.data else petRepository.cachedActivePet
            _activePet.value = activePet
            val isPetAwake = activePet != null && activePet.affection >= Constants.PET_AFFECTION_AWAKE_THRESHOLD
            val hasShield = isPetAwake && (activePet?.buffType == "shield")
            hasTimeExtender = isPetAwake && (activePet?.buffType == "time_extender")
            hasDamageBoost = isPetAwake && (activePet?.buffType == "damage_boost")

            val buffLabel = when {
                activePet != null && !isPetAwake -> "💤 ${activePet.name} is Sleeping (Affection < 25%)"
                hasShield -> "🛡️ Pet Shield Active (Blocks 1 Hit)"
                hasTimeExtender -> "⏳ Pet Time Extender (+3s Crit)"
                hasDamageBoost -> "⚔️ Pet Damage Boost (+15% Dmg)"
                else -> null
            }

            val recLevel = stageRepository.getRecommendedLevel(areaId)
            isUnderleveledBoss = (monsterType == MonsterType.BOSS && (player?.stats?.level ?: 1) < recLevel)

            val initial = BattleState(
                monster = monster,
                monsterHp = monster.maxHp,
                playerHp = currentHp,
                playerMaxHp = maxHp,
                shieldAvailable = hasShield,
                activeBuffLabel = buffLabel,
                isGatekeeperPenaltyActive = isUnderleveledBoss,
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

        // Record vocabulary attempt for adaptive spaced repetition and Kamus HSK Weak Words
        val term = currentQ.targetTerm
        if (!isCorrect) {
            mistakeWeights[term.simplified] = (mistakeWeights[term.simplified] ?: 0) + 1
        }
        val existingDelta = battleVocabDeltas[term.simplified] ?: VocabDeltaDto(
            hanzi = term.simplified,
            hskLevel = areaId,
            pinyin = term.pinyin,
            english = term.english,
        )
        battleVocabDeltas[term.simplified] = existingDelta.copy(
            wrongDelta = existingDelta.wrongDelta + if (!isCorrect) 1 else 0,
            correctDelta = existingDelta.correctDelta + if (isCorrect) 1 else 0,
        )
        viewModelScope.launch {
            val username = tokenManager.getUsername() ?: "guest"
            runCatching {
                vocabMasteryDao.recordAttempt(
                    username = username,
                    hskLevel = areaId,
                    hanzi = term.simplified,
                    pinyin = term.pinyin,
                    english = term.english,
                    isCorrect = isCorrect,
                )
            }
        }

        val adjustedElapsedMs = if (
            currentQ.type.isListening || currentQ.type.isSpeaking || currentQ.type.isCloze
        ) {
            (elapsedMs - 3_000L).coerceAtLeast(0L)
        } else {
            elapsedMs
        }

        val updated = BattleEngine.applyAnswer(
            currentState = current,
            isCorrect = isCorrect,
            elapsedTimeMs = adjustedElapsedMs,
            statStr = statStr,
            statDex = statDex,
            statDef = statDef,
            hasTimeExtender = hasTimeExtender,
            hasDamageBoost = hasDamageBoost,
            isUnderleveledBoss = isUnderleveledBoss,
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

    fun submitSpeechResult(candidates: List<String>): SpeechEvalResult? {
        val currentQ = _currentQuestion.value ?: return null
        val current = _battleState.value ?: return null
        if (current.isFinished) return null

        val eval = QuestionGenerator.evaluateSpeechRecognition(
            candidates = candidates,
            target = currentQ.targetTerm,
            allLevelTerms = terms,
        )

        when (eval.grade) {
            SpeechGrade.EXACT_MATCH -> {
                // Reward exact Mandarin pronunciation with Critical Hit timing
                questionStartTime = System.currentTimeMillis() - 500L
                submitAnswer(currentQ.correctIndex)
            }
            SpeechGrade.TONE_LENIENT_MATCH -> {
                // Reward tone-lenient syllable match with standard hit damage
                questionStartTime = System.currentTimeMillis() - 4_000L
                submitAnswer(currentQ.correctIndex)
            }
            SpeechGrade.NO_MATCH -> {
                submitAnswer(-1)
            }
        }
        return eval
    }

    fun pauseTimerForSpeech() {
        if (pauseTimestamp == 0L) {
            pauseTimestamp = System.currentTimeMillis()
        }
        stopTimer()
    }

    fun resumeTimerAfterSpeech() {
        val current = _battleState.value ?: return
        if (current.isFinished) return
        if (pauseTimestamp > 0L) {
            val pausedDuration = (System.currentTimeMillis() - pauseTimestamp).coerceAtLeast(0L)
            questionStartTime += pausedDuration
            pauseTimestamp = 0L
        }
        val remaining = _timerProgressMs.value.coerceAtLeast(1_500L)
        startTimer(durationMs = remaining, resetStartTime = false)
    }

    fun disableSpeakingForBattle() {
        allowSpeaking = false
        if (_currentQuestion.value?.type?.isSpeaking == true) {
            stopTimer()
            nextQuestion()
        }
    }

    fun useQuickElixir() {
        val current = _battleState.value ?: return
        if (current.isFinished) return

        if (current.playerHp >= current.playerMaxHp) {
            viewModelScope.launch {
                _battleToast.emit("HP is already full!")
            }
            return
        }

        val potions = _ownedPotions.value.filter { it.quantity > 0 }
        if (potions.isEmpty()) {
            viewModelScope.launch {
                _battleToast.emit("No Potions in bag! Buy Red Potions in the Merchant Shop.")
            }
            return
        }

        val missingPct = ((current.playerMaxHp - current.playerHp) * 100) / current.playerMaxHp.coerceAtLeast(1)
        val bestPotion = potions.firstOrNull { it.healPct >= missingPct } ?: potions.last()
        usePotionById(bestPotion.itemId)
    }

    fun usePotionById(itemId: String) {
        val current = _battleState.value ?: return
        if (current.isFinished) return

        if (current.playerHp >= current.playerMaxHp) {
            viewModelScope.launch {
                _battleToast.emit("HP is already full!")
            }
            return
        }

        val potion = _ownedPotions.value.firstOrNull { it.itemId == itemId && it.quantity > 0 }
        if (potion == null) {
            viewModelScope.launch {
                _battleToast.emit("No Potions in bag! Buy Red Potions in the Merchant Shop.")
            }
            return
        }

        val healAmount = ((current.playerMaxHp * potion.healPct) / 100.0).toInt().coerceAtLeast(1)

        viewModelScope.launch {
            shopRepository.useItem(itemId)
            val updatedList = _ownedPotions.value
                .map {
                    if (it.itemId == itemId) it.copy(quantity = (it.quantity - 1).coerceAtLeast(0)) else it
                }
                .filter { it.quantity > 0 }
            _ownedPotions.value = updatedList
            _elixirCount.value = updatedList.sumOf { it.quantity }

            val latestState = _battleState.value ?: return@launch
            if (!latestState.isFinished) {
                _battleState.value = BattleEngine.applyElixirHeal(latestState, healAmount)
                _battleToast.emit("🧪 Used ${potion.name}! +$healAmount HP (${potion.healPct}%)")
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
        val q = QuestionGenerator.generateQuestion(
            terms = terms,
            monsterType = monsterType,
            mistakeWeights = mistakeWeights,
            allowSpeaking = allowSpeaking,
        ) ?: return
        _currentQuestion.value = q

        val limitMs = if (q.type.isListening || q.type.isSpeaking || q.type.isCloze) {
            if (hasTimeExtender) 13_000L else 10_000L
        } else {
            BattleEngine.DEFAULT_ANSWER_LIMIT_MS
        }
        _currentQuestionLimitMs.value = limitMs
        startTimer(limitMs)
    }

    private fun startTimer(
        durationMs: Long = BattleEngine.DEFAULT_ANSWER_LIMIT_MS,
        resetStartTime: Boolean = true,
    ) {
        stopTimer()
        if (resetStartTime) {
            questionStartTime = System.currentTimeMillis()
            pauseTimestamp = 0L
        }

        timerJob = viewModelScope.launch {
            var remaining = durationMs
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

        // Encoded monster identifier: area * 100 + typeIndex * 10 + monsterIndex
        val monsterIdCode = areaId * 100 + monsterType.ordinal * 10 + monsterIndex

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
            vocabDeltas = battleVocabDeltas.values.toList(),
        )

        viewModelScope.launch {
            // Save stage progression offline-first if player won
            if (resultStr == "win") {
                val stars = when {
                    state.playerHp >= (state.playerMaxHp * 0.8) -> 3
                    state.playerHp >= (state.playerMaxHp * 0.4) -> 2
                    else -> 1
                }
                stageRepository.recordStageCleared(
                    areaId = areaId,
                    monsterTypeStr = monsterType.apiName,
                    stageIndex = monsterIndex,
                    stars = stars,
                    highestDamage = state.totalDamageDealt,
                )
            }

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
            petRepository: PetRepository,
            stageRepository: StageRepository,
            shopRepository: ShopRepository,
            vocabMasteryDao: VocabMasteryDao,
            tokenManager: TokenManager,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                BattleViewModel(
                    hskJsonLoader,
                    battleRepository,
                    playerRepository,
                    petRepository,
                    stageRepository,
                    shopRepository,
                    vocabMasteryDao,
                    tokenManager,
                )
            }
        }
    }
}
