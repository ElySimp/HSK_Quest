package com.faldo.hsk_quest.ui.battle

import android.graphics.PointF
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.faldo.hsk_quest.data.local.DictionaryEntry
import com.faldo.hsk_quest.data.local.EngineSettingsManager
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
import com.faldo.hsk_quest.engine.HandwritingEngineRegistry
import com.faldo.hsk_quest.engine.MandarinAudioFeatureExtractor
import com.faldo.hsk_quest.engine.MonsterStats
import com.faldo.hsk_quest.engine.PetBuffCatalog
import com.faldo.hsk_quest.engine.PetBuffSpec
import com.faldo.hsk_quest.engine.QuestionEngineMode
import com.faldo.hsk_quest.engine.QuestionGenerator
import com.faldo.hsk_quest.engine.ResolvedQuestionEngine
import com.faldo.hsk_quest.engine.SpeechEvalResult
import com.faldo.hsk_quest.engine.SpeechGrade
import com.faldo.hsk_quest.engine.TfliteMandarinToneEvaluator
import com.faldo.hsk_quest.engine.ToneEvaluationResult
import com.faldo.hsk_quest.util.Resource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class OwnedPotion(
    val itemId: String,
    val name: String,
    val healPct: Int,
    val quantity: Int,
)

data class NextQuestionDelayState(
    val totalMs: Long,
    val remainingMs: Long,
    val isCorrect: Boolean,
    val isBattleEnding: Boolean = false,
)

data class AnswerFeedbackEvent(
    val question: BattleQuestion,
    val selectedIndex: Int,
    val isCorrect: Boolean,
    val isTimeout: Boolean,
    val petHeal: Double = 0.0,
    val rebirthTriggered: Boolean = false,
    val customFeedback: String? = null,
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
    private val toneEvaluator: TfliteMandarinToneEvaluator? = null,
    private val questionDeckManager: com.faldo.hsk_quest.engine.QuestionDeckManager? = null,
    private val engineSettingsManager: EngineSettingsManager? = null,
) : ViewModel() {

    private var areaId: Int = 1
    private var monsterType: MonsterType = MonsterType.NORMAL
    private var monsterIndex: Int = 1
    private var terms: List<HskTerm> = emptyList()
    private var levelClozeMap: Map<String, List<com.faldo.hsk_quest.data.local.ClozeJsonEntry>> = emptyMap()
    private var allDictionaryEntries: List<DictionaryEntry> = emptyList()
    private var activeUsername: String = "guest"

    private var mistakeWeights: MutableMap<String, Int> = mutableMapOf()
    private val battleVocabDeltas: MutableMap<String, VocabDeltaDto> = mutableMapOf()
    private var allowSpeaking: Boolean = true

    private var statStr: Int = 0
    private var statDex: Int = 0
    private var statDef: Int = 0
    private var statVit: Int = 0
    private var hasTimeExtender: Boolean = false
    private var hasDamageBoost: Boolean = false
    private var activePetBuff: PetBuffSpec? = null
    private var isUnderleveledBoss: Boolean = false

    private val _battleState = MutableStateFlow<BattleState?>(null)
    val battleState: StateFlow<BattleState?> = _battleState.asStateFlow()

    private val _activePet = MutableStateFlow<PetItem?>(null)
    val activePet: StateFlow<PetItem?> = _activePet.asStateFlow()

    private val _currentQuestion = MutableStateFlow<BattleQuestion?>(null)
    val currentQuestion: StateFlow<BattleQuestion?> = _currentQuestion.asStateFlow()

    private val _isPinyinHintActive = MutableStateFlow(false)
    val isPinyinHintActive: StateFlow<Boolean> = _isPinyinHintActive.asStateFlow()

    private val _writingCharIndex = MutableStateFlow(0)
    val writingCharIndex: StateFlow<Int> = _writingCharIndex.asStateFlow()

    // Timer: 0 to limitMs remaining
    private val _timerProgressMs = MutableStateFlow<Long>(BattleEngine.DEFAULT_ANSWER_LIMIT_MS)
    val timerProgressMs: StateFlow<Long> = _timerProgressMs.asStateFlow()

    private val _currentQuestionLimitMs = MutableStateFlow<Long>(BattleEngine.DEFAULT_ANSWER_LIMIT_MS)
    val currentQuestionLimitMs: StateFlow<Long> = _currentQuestionLimitMs.asStateFlow()

    private val _nextQuestionDelay = MutableStateFlow<NextQuestionDelayState?>(null)
    val nextQuestionDelay: StateFlow<NextQuestionDelayState?> = _nextQuestionDelay.asStateFlow()

    private val _answerFeedback = MutableSharedFlow<AnswerFeedbackEvent>(extraBufferCapacity = 4)
    val answerFeedback: SharedFlow<AnswerFeedbackEvent> = _answerFeedback.asSharedFlow()

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

    private val _activeQuestionEngine = MutableStateFlow(
        engineSettingsManager?.resolveActiveEngine()
            ?: ResolvedQuestionEngine.resolve(QuestionEngineMode.RULE_BASED, isMlModelReady = false)
    )
    val activeQuestionEngine: StateFlow<ResolvedQuestionEngine> = _activeQuestionEngine.asStateFlow()

    private var timerJob: Job? = null
    private var interQuestionJob: Job? = null
    private var questionStartTime: Long = 0L
    private var pauseTimestamp: Long = 0L

    fun startBattle(areaId: Int, monsterTypeStr: String, monsterIndex: Int = 1) {
        this.areaId = areaId
        this.monsterType = MonsterType.fromApiName(monsterTypeStr)
        this.monsterIndex = monsterIndex
        battleVocabDeltas.clear()
        _activePet.value = petRepository.cachedActivePet
        engineSettingsManager?.resolveActiveEngine()?.let { resolved ->
            _activeQuestionEngine.value = resolved
        }

        viewModelScope.launch {
            // Load vocabulary and level-strict cloze sentences for the selected stage first
            terms = runCatching { hskJsonLoader.loadLevel(areaId) }.getOrDefault(emptyList())
            levelClozeMap = runCatching { hskJsonLoader.loadClozeMap(areaId) }.getOrDefault(emptyMap())
            launch {
                allDictionaryEntries = runCatching {
                    hskJsonLoader.loadDictionaryEntries(null)
                }.getOrDefault(emptyList())
            }

            // Load adaptive mistake weights for this user and HSK level
            val username = tokenManager.getUsername() ?: "guest"
            activeUsername = username
            allowSpeaking = !runCatching { tokenManager.isSpeakingMuted(username) }.getOrDefault(false)
            val masteryEntries = runCatching {
                vocabMasteryDao.getForLevel(username, areaId)
            }.getOrDefault(emptyList())
            mistakeWeights = masteryEntries
                .filter { it.wrongCount > 0 && it.masteryScore < 85 }
                .associate { it.hanzi to it.wrongCount }
                .toMutableMap()

            // Fetch owned battle healing potions asynchronously so battle start is never blocked
            launch {
                refreshBattleInventory()
            }

            // Start immediately from RAM / Room cache if available instead of waiting on WAN DB RTT
            val cachedPlayer = playerRepository.getLocalPlayerSnapshot()
            val player = cachedPlayer ?: when (val playerResult = playerRepository.fetchProfile()) {
                is PlayerRepository.ProfileResult.Fresh -> playerResult.player
                is PlayerRepository.ProfileResult.Cached -> playerResult.player
                else -> null
            }

            val recLevel = stageRepository.getRecommendedLevel(areaId)
            val monster = MonsterStats.create(areaId, monsterType, monsterIndex)
            initializeBattleFromPlayerAndPet(
                player = player,
                activePet = petRepository.cachedActivePet,
                recLevel = recLevel,
                monster = monster,
            )

            // Refresh player stats and equipped pet in the background without blocking question 1
            if (cachedPlayer != null) {
                launch {
                    val freshRes = playerRepository.fetchProfile()
                    val freshPlayer = (freshRes as? PlayerRepository.ProfileResult.Fresh)?.player
                    if (freshPlayer != null) {
                        syncFreshPlayerIntoBattle(freshPlayer, recLevel)
                    }
                }
            }
            launch {
                val petRes = petRepository.getActivePet()
                val syncedPet = if (petRes is Resource.Success) petRes.data else petRepository.cachedActivePet
                syncPetIntoBattle(syncedPet)
            }
        }
    }

    private suspend fun refreshBattleInventory() {
        shopRepository.cachedInventory?.let { applyBattlePotionsFromItems(it) }
        shopRepository.getInventory().onSuccess { items ->
            if (pendingPotionOps == 0) {
                applyBattlePotionsFromItems(items)
            }
        }
    }

    private fun applyBattlePotionsFromItems(items: List<com.faldo.hsk_quest.data.model.InventoryItem>) {
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

    private fun initializeBattleFromPlayerAndPet(
        player: com.faldo.hsk_quest.data.model.Player?,
        activePet: PetItem?,
        recLevel: Int,
        monster: com.faldo.hsk_quest.data.model.Monster,
    ) {
        statStr = player?.stats?.statStr ?: 0
        statDex = player?.stats?.statDex ?: 0
        statDef = player?.stats?.statDef ?: 0
        statVit = player?.stats?.statVit ?: 0
        val playerLevel = player?.stats?.level ?: 1
        val maxHp = player?.stats?.maxHp ?: DamageCalculator.maxHp(statVit, playerLevel)
        val currentHp = player?.stats?.hp ?: maxHp

        _activePet.value = activePet
        activePetBuff = PetBuffCatalog.resolveActiveSpec(activePet)
        hasTimeExtender = (activePetBuff?.critWindowBonusMs ?: 0L) > 0L
        hasDamageBoost = (activePetBuff?.damageBonusPct ?: 0) > 0
        val initialShields = activePetBuff?.shieldCharges ?: 0
        val canRebirth = (activePetBuff?.rebirthHpPct ?: 0) > 0
        val buffLabel = if (activePet != null) {
            PetBuffCatalog.formatBattleBanner(activePet)
        } else {
            null
        }

        isUnderleveledBoss = (monsterType == MonsterType.BOSS && playerLevel < recLevel)

        val initial = BattleState(
            monster = monster,
            monsterHp = monster.maxHp.toDouble(),
            playerHp = currentHp.toDouble(),
            playerMaxHp = maxHp,
            shieldAvailable = initialShields > 0,
            shieldsRemaining = initialShields,
            rebirthAvailable = canRebirth,
            activeBuffLabel = buffLabel,
            isGatekeeperPenaltyActive = isUnderleveledBoss,
        )
        _nextQuestionDelay.value = null
        _battleState.value = initial

        nextQuestion()
    }

    private fun syncFreshPlayerIntoBattle(
        freshPlayer: com.faldo.hsk_quest.data.model.Player,
        recLevel: Int,
    ) {
        val current = _battleState.value ?: return
        if (current.isFinished) return

        statStr = freshPlayer.stats.statStr
        statDex = freshPlayer.stats.statDex
        statDef = freshPlayer.stats.statDef
        statVit = freshPlayer.stats.statVit
        isUnderleveledBoss = (monsterType == MonsterType.BOSS && freshPlayer.stats.level < recLevel)

        if (current.totalQuestionsAnswered == 0 && current.totalPetHealing == 0.0) {
            _battleState.value = current.copy(
                playerHp = freshPlayer.stats.hp.toDouble(),
                playerMaxHp = freshPlayer.stats.maxHp,
                isGatekeeperPenaltyActive = isUnderleveledBoss,
            )
        } else {
            _battleState.value = current.copy(
                playerMaxHp = freshPlayer.stats.maxHp,
                isGatekeeperPenaltyActive = isUnderleveledBoss,
            )
        }
    }

    private fun syncPetIntoBattle(syncedPet: PetItem?) {
        _activePet.value = syncedPet
        activePetBuff = PetBuffCatalog.resolveActiveSpec(syncedPet)
        hasTimeExtender = (activePetBuff?.critWindowBonusMs ?: 0L) > 0L
        hasDamageBoost = (activePetBuff?.damageBonusPct ?: 0) > 0
        val buffLabel = if (syncedPet != null) {
            PetBuffCatalog.formatBattleBanner(syncedPet)
        } else {
            null
        }
        val current = _battleState.value ?: return
        if (current.isFinished) return

        if (current.totalQuestionsAnswered == 0) {
            val shields = activePetBuff?.shieldCharges ?: 0
            val canRebirth = (activePetBuff?.rebirthHpPct ?: 0) > 0
            _battleState.value = current.copy(
                shieldAvailable = shields > 0,
                shieldsRemaining = shields,
                rebirthAvailable = canRebirth,
                activeBuffLabel = buffLabel,
            )
        } else {
            _battleState.value = current.copy(activeBuffLabel = buffLabel)
        }
    }

    fun submitAnswer(
        selectedIndex: Int,
        customFeedback: String? = null,
        forceCritical: Boolean? = null,
        damageMultiplierOverride: Double? = null,
    ) {
        val currentQ = _currentQuestion.value ?: return
        val current = _battleState.value ?: return
        if (current.isFinished) return

        stopTimer()
        interQuestionJob?.cancel()
        interQuestionJob = null

        val elapsedMs = System.currentTimeMillis() - questionStartTime
        val isTimeout = selectedIndex < 0 && customFeedback == null
        val isCorrect = selectedIndex >= 0 && currentQ.isCorrect(selectedIndex)

        // Record vocabulary attempt for adaptive spaced repetition and Kamus HSK Weak Words
        val term = currentQ.targetTerm
        if (!isCorrect) {
            mistakeWeights[term.simplified] = (mistakeWeights[term.simplified] ?: 0) + 1
        }
        questionDeckManager?.recordOutcome(
            username = activeUsername,
            areaId = areaId,
            hanzi = term.simplified,
            isCorrect = isCorrect,
        )
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
            currentQ.type.isListening || currentQ.type.isSpeaking || currentQ.type.isCloze || currentQ.type.isWriting
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
            usedPinyinHint = _isPinyinHintActive.value,
            petBuff = activePetBuff,
            forceCritical = forceCritical,
            damageMultiplierOverride = damageMultiplierOverride,
        )
        _battleState.value = updated
        _answerFeedback.tryEmit(
            AnswerFeedbackEvent(
                question = currentQ,
                selectedIndex = selectedIndex,
                isCorrect = isCorrect,
                isTimeout = isTimeout,
                petHeal = updated.lastPetHeal,
                rebirthTriggered = updated.lastRebirthTriggered,
                customFeedback = customFeedback,
            )
        )

        val isBattleEnding = updated.isFinished
        val delayDurationMs = if (isCorrect) CORRECT_ANSWER_DELAY_MS else WRONG_ANSWER_DELAY_MS
        interQuestionJob = viewModelScope.launch {
            var remaining = delayDurationMs
            while (remaining > 0L) {
                val latest = _battleState.value
                if (latest == null ||
                    latest.phase == BattleState.Phase.FLED ||
                    (!isBattleEnding && latest.isFinished)
                ) {
                    _nextQuestionDelay.value = null
                    return@launch
                }
                _nextQuestionDelay.value = NextQuestionDelayState(
                    totalMs = delayDurationMs,
                    remainingMs = remaining,
                    isCorrect = isCorrect,
                    isBattleEnding = isBattleEnding,
                )
                delay(50L)
                remaining = (remaining - 50L).coerceAtLeast(0L)
            }
            _nextQuestionDelay.value = null
            val latest = _battleState.value ?: return@launch
            if (isBattleEnding) {
                if (latest.phase != BattleState.Phase.FLED) {
                    finalizeBattle(updated)
                }
            } else if (!latest.isFinished) {
                nextQuestion()
            }
        }
    }

    fun shouldUseToneNetForCurrentQuestion(): Boolean {
        val evaluator = toneEvaluator ?: return false
        val q = _currentQuestion.value ?: return false
        return q.type.isSpeaking && evaluator.isTermSupported(q.targetTerm.pinyin)
    }

    fun submitToneAudio(
        wav16k: FloatArray,
        onNoSpeechDetected: () -> Unit = {},
        onFallbackRequired: () -> Unit = {},
        onEvaluated: (ToneEvaluationResult) -> Unit = {},
    ) {
        val evaluator = toneEvaluator
        val currentQ = _currentQuestion.value
        val current = _battleState.value
        if (evaluator == null || currentQ == null || current == null || current.isFinished) {
            onFallbackRequired()
            return
        }

        if (!MandarinAudioFeatureExtractor.hasAudibleSpeech(wav16k)) {
            resumeTimerAfterSpeech()
            onNoSpeechDetected()
            return
        }

        viewModelScope.launch {
            val eval = withContext(Dispatchers.Default) {
                evaluator.evaluateAudio(
                    wav16k = wav16k,
                    targetPinyin = currentQ.targetTerm.pinyin,
                    hanzi = currentQ.targetTerm.simplified,
                )
            }

            val latestState = _battleState.value
            if (latestState == null || latestState.isFinished || _currentQuestion.value !== currentQ) {
                return@launch
            }

            if (eval == null) {
                onFallbackRequired()
                return@launch
            }

            onEvaluated(eval)
            when (eval.grade) {
                SpeechGrade.EXACT_MATCH -> {
                    submitAnswer(
                        selectedIndex = currentQ.correctIndex,
                        customFeedback = eval.feedback,
                        forceCritical = false,
                        damageMultiplierOverride = evaluator.meta.exactDamagePct / 100.0,
                    )
                }
                SpeechGrade.TONE_LENIENT_MATCH -> {
                    submitAnswer(
                        selectedIndex = currentQ.correctIndex,
                        customFeedback = eval.feedback,
                        forceCritical = false,
                        damageMultiplierOverride = evaluator.meta.lenientDamagePct / 100.0,
                    )
                }
                SpeechGrade.NO_MATCH -> {
                    submitAnswer(
                        selectedIndex = -1,
                        customFeedback = eval.feedback,
                    )
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
                submitAnswer(
                    selectedIndex = currentQ.correctIndex,
                    customFeedback = eval.feedback,
                    forceCritical = false,
                    damageMultiplierOverride = 1.2,
                )
            }
            SpeechGrade.TONE_LENIENT_MATCH -> {
                submitAnswer(
                    selectedIndex = currentQ.correctIndex,
                    customFeedback = eval.feedback,
                    forceCritical = false,
                    damageMultiplierOverride = 0.5,
                )
            }
            SpeechGrade.NO_MATCH -> {
                submitAnswer(-1, customFeedback = eval.feedback)
            }
        }
        return eval
    }

    fun submitWritingStrokes(
        strokes: List<List<PointF>>,
        onEvaluated: (
            matched: Boolean,
            hasMoreChars: Boolean,
            nextCharIndex: Int,
            totalChars: Int,
            recognizedHanzi: String,
            engineId: String,
            latencyMs: Long,
        ) -> Unit,
    ) {
        val currentQ = _currentQuestion.value ?: return
        val current = _battleState.value ?: return
        if (current.isFinished) return

        val targetWord = currentQ.targetTerm.simplified.trim()
        val totalChars = targetWord.length.coerceAtLeast(1)
        val charIdx = _writingCharIndex.value.coerceIn(0, totalChars - 1)

        viewModelScope.launch {
            val entries = allDictionaryEntries.ifEmpty {
                runCatching { hskJsonLoader.loadDictionaryEntries(null) }.getOrDefault(emptyList())
                    .also { allDictionaryEntries = it }
            }
            val result = HandwritingEngineRegistry.activeRecognizer.recognize(
                strokes = strokes,
                allEntries = entries,
                limit = 5,
            )

            val expectedSimp = targetWord.getOrNull(charIdx)?.toString()
            val expectedTrad = currentQ.targetTerm.traditional?.trim()?.getOrNull(charIdx)?.toString()
            val expectedChars = setOfNotNull(expectedSimp, expectedTrad)

            val topCandidates = result.candidates.take(3).map { it.hanzi }
            val isMatch = topCandidates.any { it in expectedChars || (totalChars == 1 && it == targetWord) }
            val topRecognized = result.candidates.firstOrNull()?.hanzi ?: "?"

            if (isMatch && charIdx + 1 < totalChars) {
                val nextIdx = charIdx + 1
                _writingCharIndex.value = nextIdx
                onEvaluated(true, true, nextIdx, totalChars, topRecognized, result.engineId, result.inferenceLatencyMs)
                return@launch
            }

            stopTimer()
            onEvaluated(isMatch, false, charIdx, totalChars, topRecognized, result.engineId, result.inferenceLatencyMs)
            val term = currentQ.targetTerm
            val writeFeedback = if (isMatch) {
                "✅ Matched $topRecognized — ${term.simplified} (${term.pinyin} · ${term.english})"
            } else {
                "❌ Detected '$topRecognized' — Expected: ${term.simplified} (${term.pinyin} · ${term.english})"
            }
            submitAnswer(if (isMatch) currentQ.correctIndex else -1, customFeedback = writeFeedback)
        }
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

    fun activatePinyinHintForCurrentQuestion() {
        if (_currentQuestion.value?.pinyinHint != null && !_isPinyinHintActive.value) {
            _isPinyinHintActive.value = true
        }
    }

    fun skipSpeakingFor15Minutes() {
        allowSpeaking = false
        viewModelScope.launch {
            val username = tokenManager.getUsername() ?: "guest"
            runCatching {
                tokenManager.setSpeakingMutedUntil(username, durationMs = 15L * 60L * 1000L)
            }
            _battleToast.emit("🔇 Speaking muted for 15 minutes. Skipping question...")
        }
        if (_currentQuestion.value?.type?.isSpeaking == true) {
            stopTimer()
            nextQuestion()
        }
    }

    fun disableSpeakingForBattle() {
        skipSpeakingFor15Minutes()
    }

    fun useQuickElixir() {
        val current = _battleState.value ?: return
        if (current.isFinished) return

        if (current.playerHp >= current.playerMaxHp.toDouble()) {
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

        val missingPct = (((current.playerMaxHp.toDouble() - current.playerHp) * 100.0) / current.playerMaxHp.coerceAtLeast(1)).toInt()
        val bestPotion = potions.firstOrNull { it.healPct >= missingPct } ?: potions.last()
        usePotionById(bestPotion.itemId)
    }

    private val usePotionMutex = kotlinx.coroutines.sync.Mutex()
    private var pendingPotionOps: Int = 0

    fun usePotionById(itemId: String) {
        val current = _battleState.value ?: return
        if (current.isFinished) return

        if (current.playerHp >= current.playerMaxHp.toDouble()) {
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
        val healedState = BattleEngine.applyElixirHeal(current, healAmount)
        val actualAppliedHeal = DamageCalculator.roundTenth(
            (healedState.playerHp - current.playerHp).coerceAtLeast(0.0)
        )

        // Optimistic update: deduct potion and heal HP immediately without blocking on network RTT
        val updatedList = _ownedPotions.value
            .map {
                if (it.itemId == itemId) it.copy(quantity = (it.quantity - 1).coerceAtLeast(0)) else it
            }
            .filter { it.quantity > 0 }
        _ownedPotions.value = updatedList
        _elixirCount.value = updatedList.sumOf { it.quantity }
        _battleState.value = healedState
        shopRepository.decrementItemQuantityLocal(itemId)

        pendingPotionOps++
        viewModelScope.launch {
            playerRepository.applyLocalStatsPatch(
                hp = healedState.roundedUpPlayerHp,
                maxHp = healedState.playerMaxHp,
            )
            _battleToast.emit(
                "🧪 Used ${potion.name}! +${DamageCalculator.formatDecimal(actualAppliedHeal)} HP (${potion.healPct}%)"
            )

            var lockAcquired = false
            try {
                usePotionMutex.lock()
                lockAcquired = true
                val res = shopRepository.useItem(itemId)
                res.onSuccess { useResp ->
                    if (pendingPotionOps <= 1) {
                        val currentList = _ownedPotions.value.toMutableList()
                        val idx = currentList.indexOfFirst { it.itemId == itemId }
                        if (useResp.remainingQuantity <= 0) {
                            if (idx >= 0) currentList.removeAt(idx)
                        } else if (idx >= 0) {
                            currentList[idx] = currentList[idx].copy(quantity = useResp.remainingQuantity)
                        }
                        _ownedPotions.value = currentList
                        _elixirCount.value = currentList.sumOf { it.quantity }
                    }
                }.onFailure { err ->
                    val restoredList = _ownedPotions.value.toMutableList()
                    val existingIdx = restoredList.indexOfFirst { it.itemId == itemId }
                    if (existingIdx >= 0) {
                        restoredList[existingIdx] = restoredList[existingIdx].copy(
                            quantity = restoredList[existingIdx].quantity + 1
                        )
                    } else {
                        restoredList.add(potion.copy(quantity = 1))
                        restoredList.sortBy { it.healPct }
                    }
                    _ownedPotions.value = restoredList
                    _elixirCount.value = restoredList.sumOf { it.quantity }

                    val latestState = _battleState.value
                    if (latestState != null && !latestState.isFinished && actualAppliedHeal > 0.0) {
                        val rolledBackHp = DamageCalculator.roundTenth(
                            (latestState.playerHp - actualAppliedHeal).coerceAtLeast(1.0)
                        )
                        val rolledBackState = latestState.copy(
                            playerHp = rolledBackHp,
                            totalDamageTaken = DamageCalculator.roundTenth(latestState.totalDamageTaken + actualAppliedHeal),
                        )
                        _battleState.value = rolledBackState
                        playerRepository.applyLocalStatsPatch(
                            hp = rolledBackState.roundedUpPlayerHp,
                            maxHp = latestState.playerMaxHp,
                        )
                    }
                    _battleToast.emit("❌ ${err.message ?: "Failed to use potion"}")
                }
            } finally {
                pendingPotionOps = (pendingPotionOps - 1).coerceAtLeast(0)
                if (lockAcquired) {
                    usePotionMutex.unlock()
                }
            }
        }
    }

    fun flee() {
        val current = _battleState.value ?: return
        if (current.isFinished) return
        stopTimer()
        interQuestionJob?.cancel()
        interQuestionJob = null
        _nextQuestionDelay.value = null

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
        interQuestionJob?.cancel()
        interQuestionJob = null
        _nextQuestionDelay.value = null
        _isPinyinHintActive.value = false
        _writingCharIndex.value = 0

        val q = QuestionGenerator.generateQuestion(
            terms = terms,
            monsterType = monsterType,
            mistakeWeights = mistakeWeights,
            allowSpeaking = allowSpeaking,
            toneEvaluator = toneEvaluator,
            deckManager = questionDeckManager,
            username = activeUsername,
            areaId = areaId,
            levelClozeMap = levelClozeMap,
            engineMode = _activeQuestionEngine.value.effectiveMode,
        ) ?: return
        _currentQuestion.value = q

        val baseLimitMs = when {
            q.type.isWriting -> BattleEngine.ELITE_WRITING_LIMIT_MS
            q.type.isCloze -> BattleEngine.ELITE_READING_LIMIT_MS
            q.type.isListening || q.type.isSpeaking -> BattleEngine.LISTENING_SPEAKING_LIMIT_MS
            else -> BattleEngine.DEFAULT_ANSWER_LIMIT_MS
        }
        val extraTimerMs = activePetBuff?.timerBonusMs
            ?: if (hasTimeExtender) 3_000L else 0L
        val limitMs = baseLimitMs + extraTimerMs
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
        interQuestionJob?.cancel()
        interQuestionJob = null
        _nextQuestionDelay.value = null
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
            hpHealedByPet = state.totalPetHealing,
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
                    highestDamage = state.totalDamageDealt.toInt(),
                )
            }

            when (val r = battleRepository.submitBattleResult(request)) {
                is Resource.Success -> {
                    r.data.petAffection?.let { nextAff ->
                        petRepository.updateActivePetAffectionLocal(nextAff.toFloat())
                    }
                    _serverResult.emit(r.data)
                }
                is Resource.Error -> {
                    val xpMult = 1.0 + ((activePetBuff?.xpBonusPct ?: 0) / 100.0)
                    val coinMult = 1.0 + ((activePetBuff?.coinBonusPct ?: 0) / 100.0)
                    // Fallback local response if network error
                    _serverResult.emit(
                        BattleResultResponse(
                            result = resultStr,
                            xpEarned = if (resultStr == "win") (state.monster.xpReward * xpMult).toInt() else 0,
                            coinsEarned = if (resultStr == "win") (state.monster.coinReward * coinMult).toInt() else 0,
                            damageDealt = state.totalDamageDealt,
                            levelUp = false,
                            newLevel = null,
                            statPointsAvailable = 0,
                            survivalHp = state.roundedUpPlayerHp,
                            lostItem = null,
                            message = when (resultStr) {
                                "win" -> "Victory!"
                                "flee" -> "Tactical retreat offline."
                                else -> "Defeated offline."
                            },
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
        interQuestionJob?.cancel()
    }

    companion object {
        const val CORRECT_ANSWER_DELAY_MS = 2_000L
        const val WRONG_ANSWER_DELAY_MS = 6_000L

        fun factory(
            hskJsonLoader: HskJsonLoader,
            battleRepository: BattleRepository,
            playerRepository: PlayerRepository,
            petRepository: PetRepository,
            stageRepository: StageRepository,
            shopRepository: ShopRepository,
            vocabMasteryDao: VocabMasteryDao,
            tokenManager: TokenManager,
            toneEvaluator: TfliteMandarinToneEvaluator? = null,
            questionDeckManager: com.faldo.hsk_quest.engine.QuestionDeckManager? = null,
            engineSettingsManager: EngineSettingsManager? = null,
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
                    toneEvaluator,
                    questionDeckManager,
                    engineSettingsManager,
                )
            }
        }
    }
}
