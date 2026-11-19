package com.faldo.hsk_quest.ui.pet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.faldo.hsk_quest.data.model.BlackjackSettleRequest
import com.faldo.hsk_quest.data.model.BlackjackStateResponse
import com.faldo.hsk_quest.data.model.InventoryItem
import com.faldo.hsk_quest.data.model.PetItem
import com.faldo.hsk_quest.data.model.Player
import com.faldo.hsk_quest.data.model.RpsResponse
import com.faldo.hsk_quest.data.model.RpsSettleRequest
import com.faldo.hsk_quest.data.model.ShopItem
import com.faldo.hsk_quest.data.repository.PetRepository
import com.faldo.hsk_quest.data.repository.PlayerRepository
import com.faldo.hsk_quest.data.repository.ShopRepository
import com.faldo.hsk_quest.util.Resource
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import kotlin.random.Random

/**
 * ViewModel for the Companion Pet Sanctuary, handling Tamagotchi care,
 * food inventory bag feeding, local-first Rock-Paper-Scissors & Blackjack mini-games
 * with background verification, and rarity-sorted companion equipping.
 */
class PetViewModel(
    private val petRepository: PetRepository,
    private val shopRepository: ShopRepository,
    private val playerRepository: PlayerRepository,
) : ViewModel() {

    private val _activePet = MutableStateFlow<PetItem?>(
        petRepository.cachedActivePet ?: petRepository.cachedPetList?.activePet
    )
    val activePet: StateFlow<PetItem?> = _activePet.asStateFlow()

    private val _petList = MutableStateFlow<List<PetItem>>(
        sortPetsByRarity(petRepository.cachedPetList?.pets.orEmpty())
    )
    val petList: StateFlow<List<PetItem>> = _petList.asStateFlow()

    private val _player = MutableStateFlow<Player?>(playerRepository.cachedMemoryPlayer)
    val player: StateFlow<Player?> = _player.asStateFlow()

    private val _isInitialHydrated = MutableStateFlow(
        petRepository.hasLoadedActivePet || petRepository.cachedPetList != null
    )
    val isInitialHydrated: StateFlow<Boolean> = _isInitialHydrated.asStateFlow()

    private val _foodBag = MutableStateFlow(
        shopRepository.cachedInventory.orEmpty().filter {
            it.quantity > 0 && (it.itemType == "pet_food" || it.affectionBoost > 0)
        }
    )
    val foodBag: StateFlow<List<InventoryItem>> = _foodBag.asStateFlow()

    private val _foodCatalog = MutableStateFlow(
        shopRepository.cachedCatalog.orEmpty().filter {
            it.category == "pet_food" || it.affectionBoost > 0
        }
    )
    val foodCatalog: StateFlow<List<ShopItem>> = _foodCatalog.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _blackjackState = MutableStateFlow<BlackjackStateResponse?>(null)
    val blackjackState: StateFlow<BlackjackStateResponse?> = _blackjackState.asStateFlow()

    private val _isBlackjackBusy = MutableStateFlow(false)
    val isBlackjackBusy: StateFlow<Boolean> = _isBlackjackBusy.asStateFlow()

    private val _actionFeedback = MutableSharedFlow<String>()
    val actionFeedback: SharedFlow<String> = _actionFeedback.asSharedFlow()

    private val _reactionEvent = MutableSharedFlow<String>()
    val reactionEvent: SharedFlow<String> = _reactionEvent.asSharedFlow()

    private val _rpsResult = MutableSharedFlow<RpsResponse>()
    val rpsResult: SharedFlow<RpsResponse> = _rpsResult.asSharedFlow()

    private val miniGameSettleMutex = Mutex()
    private var pendingMiniGameSettles: Int = 0
    private var activeBjRoundId: String? = null
    private var localBjDeck: MutableList<String> = mutableListOf()
    private var localBjPlayerCards: MutableList<String> = mutableListOf()
    private var localBjDealerCards: MutableList<String> = mutableListOf()

    init {
        viewModelScope.launch {
            playerRepository.observeLocalPlayer().collect { localPlayer ->
                if (localPlayer != null) {
                    _player.value = localPlayer
                }
            }
        }
    }

    fun loadPetData() {
        viewModelScope.launch {
            _isLoading.value = !_isInitialHydrated.value
            launch { refreshPlayerProfile() }
            launch { loadFoodBag() }
            when (val res = petRepository.getPets()) {
                is Resource.Success -> {
                    val list = sortPetsByRarity(res.data.pets)
                    _petList.value = list
                    _activePet.value = res.data.activePet ?: list.firstOrNull { it.isActive }
                    _isInitialHydrated.value = true
                }
                is Resource.Error -> {
                    _isInitialHydrated.value = true
                    _actionFeedback.emit(res.message)
                }
                Resource.Loading -> {}
            }
            _isLoading.value = false
        }
    }

    fun loadFoodBag() {
        viewModelScope.launch {
            shopRepository.getInventory().onSuccess { items ->
                _foodBag.value = items.filter {
                    it.quantity > 0 && (it.itemType == "pet_food" || it.affectionBoost > 0)
                }
            }
            if (_foodCatalog.value.isEmpty()) {
                shopRepository.getCatalog().onSuccess { catalog ->
                    _foodCatalog.value = catalog.filter {
                        it.category == "pet_food" || it.affectionBoost > 0
                    }
                }
            }
        }
    }

    private suspend fun refreshPlayerProfile() {
        when (val res = playerRepository.fetchProfile()) {
            is PlayerRepository.ProfileResult.Fresh -> _player.value = res.player
            is PlayerRepository.ProfileResult.Cached -> _player.value = res.player
            else -> {}
        }
    }

    fun equipPet(petId: Int) {
        viewModelScope.launch {
            _isLoading.value = true
            when (val res = petRepository.equipPet(petId)) {
                is Resource.Success -> {
                    _activePet.value = res.data
                    _petList.value = sortPetsByRarity(
                        _petList.value.map { it.copy(isActive = (it.id == petId)) }
                    )
                    _actionFeedback.emit("Equipped ${res.data.name} as active companion!")
                }
                is Resource.Error -> {
                    _actionFeedback.emit(res.message)
                }
                Resource.Loading -> {}
            }
            _isLoading.value = false
        }
    }

    fun playRps(choice: String) {
        val pet = _activePet.value ?: run {
            viewModelScope.launch {
                _actionFeedback.emit("You must have an active pet equipped to play mini-games")
            }
            return
        }
        val playerChoice = choice.lowercase().trim()
        val choices = listOf("rock", "paper", "scissors")
        val petChoice = choices[Random.nextInt(choices.size)]
        val winPairs = setOf("rock" to "scissors", "scissors" to "paper", "paper" to "rock")

        val outcome: String
        val affectionGained: Float
        var coinsDropped = 0
        var msg: String
        when {
            playerChoice == petChoice -> {
                outcome = "tie"
                affectionGained = 3f
                msg = "It's a draw! Both chose $playerChoice. Your pet enjoyed playing!"
            }
            (playerChoice to petChoice) in winPairs -> {
                outcome = "win"
                affectionGained = 10f
                if (Random.nextFloat() < 0.5f) {
                    coinsDropped = Random.nextInt(1, 6)
                }
                msg = "Victory! Your $playerChoice beats $petChoice! Your pet is super happy!"
            }
            else -> {
                outcome = "lose"
                affectionGained = 1f
                msg = "Your pet's $petChoice beats $playerChoice! Your pet looks proud!"
            }
        }
        if (coinsDropped > 0) {
            msg += " Found $coinsDropped coin${if (coinsDropped != 1) "s" else ""} on the floor!"
        }

        val newAffection = ((pet.affection + affectionGained).coerceIn(0f, 100f) * 10f).toInt() / 10f
        val currentCoins = _player.value?.stats?.coins ?: playerRepository.cachedMemoryPlayer?.stats?.coins
        val newCoins = currentCoins?.let { it + coinsDropped }
        val roundId = UUID.randomUUID().toString().replace("-", "").take(24)

        updateActiveAffectionState(newAffection)
        petRepository.updateActivePetAffectionLocal(newAffection)

        viewModelScope.launch {
            if (newCoins != null) {
                playerRepository.applyLocalStatsPatch(coins = newCoins)
                playerRepository.cachedMemoryPlayer?.let { _player.value = it }
            }
            _rpsResult.emit(
                RpsResponse(
                    playerChoice = playerChoice,
                    petChoice = petChoice,
                    outcome = outcome,
                    affectionGained = affectionGained,
                    currentAffection = newAffection,
                    coinsDropped = coinsDropped,
                    remainingCoins = newCoins,
                    message = msg,
                )
            )

            // Verify and settle the completed round once in a sequential background queue
            pendingMiniGameSettles++
            try {
                miniGameSettleMutex.withLock {
                    val res = petRepository.settleRps(
                        RpsSettleRequest(
                            roundId = roundId,
                            playerChoice = playerChoice,
                            petChoice = petChoice,
                            outcome = outcome,
                            coinsDropped = coinsDropped,
                        )
                    )
                    if (res is Resource.Success && pendingMiniGameSettles <= 1) {
                        updateActiveAffectionState(res.data.currentAffection)
                        res.data.remainingCoins?.let {
                            playerRepository.applyLocalStatsPatch(coins = it)
                            playerRepository.cachedMemoryPlayer?.let { p -> _player.value = p }
                        }
                    } else if (res is Resource.Error) {
                        _actionFeedback.emit(res.message)
                        refreshPlayerProfile()
                    }
                }
            } finally {
                pendingMiniGameSettles = (pendingMiniGameSettles - 1).coerceAtLeast(0)
            }
        }
    }

    fun startBlackjack() {
        val pet = _activePet.value ?: run {
            viewModelScope.launch {
                _actionFeedback.emit("You must have an active pet equipped to play mini-games!")
            }
            return
        }
        val coins = _player.value?.stats?.coins ?: playerRepository.cachedMemoryPlayer?.stats?.coins ?: 0
        if (coins < 5) {
            viewModelScope.launch {
                _actionFeedback.emit("Need 5 Coins to play Blackjack!")
            }
            return
        }

        val deck = buildShuffledDeck()
        val pCards = mutableListOf(deck.removeAt(deck.lastIndex), deck.removeAt(deck.lastIndex))
        val dCards = mutableListOf(deck.removeAt(deck.lastIndex), deck.removeAt(deck.lastIndex))
        val roundId = UUID.randomUUID().toString().replace("-", "").take(24)

        localBjDeck = deck
        localBjPlayerCards = pCards
        localBjDealerCards = dCards
        activeBjRoundId = roundId

        val pScore = handScore(pCards)
        if (pScore == 21) {
            while (handScore(localBjDealerCards) < 17 && localBjDeck.isNotEmpty()) {
                localBjDealerCards.add(localBjDeck.removeAt(localBjDeck.lastIndex))
            }
            viewModelScope.launch {
                finishLocalBlackjack(roundId, pet, localBjPlayerCards.toList(), localBjDealerCards.toList())
            }
            return
        }

        _blackjackState.value = BlackjackStateResponse(
            gameToken = roundId,
            status = "playing",
            playerCards = pCards.toList(),
            dealerCards = listOf(dCards[0], "??"),
            playerScore = pScore,
            dealerScore = handScore(listOf(dCards[0])),
            betCoins = 5,
            coinsDelta = -5,
            remainingCoins = coins - 5,
            affectionGained = 0f,
            currentAffection = pet.affection,
            message = "Your turn! Hit or Stand against ${pet.name}.",
        )
    }

    fun hitBlackjack() {
        val current = _blackjackState.value ?: return
        val roundId = activeBjRoundId ?: return
        val pet = _activePet.value ?: return
        if (current.status != "playing" || localBjDeck.isEmpty()) return

        localBjPlayerCards.add(localBjDeck.removeAt(localBjDeck.lastIndex))
        val pScore = handScore(localBjPlayerCards)
        if (pScore > 21) {
            viewModelScope.launch {
                finishLocalBlackjack(roundId, pet, localBjPlayerCards.toList(), localBjDealerCards.toList())
            }
            return
        }
        if (pScore == 21) {
            while (handScore(localBjDealerCards) < 17 && localBjDeck.isNotEmpty()) {
                localBjDealerCards.add(localBjDeck.removeAt(localBjDeck.lastIndex))
            }
            viewModelScope.launch {
                finishLocalBlackjack(roundId, pet, localBjPlayerCards.toList(), localBjDealerCards.toList())
            }
            return
        }

        val currentCoins = _player.value?.stats?.coins ?: playerRepository.cachedMemoryPlayer?.stats?.coins ?: 5
        _blackjackState.value = BlackjackStateResponse(
            gameToken = roundId,
            status = "playing",
            playerCards = localBjPlayerCards.toList(),
            dealerCards = listOf(localBjDealerCards[0], "??"),
            playerScore = pScore,
            dealerScore = handScore(listOf(localBjDealerCards[0])),
            betCoins = 5,
            coinsDelta = -5,
            remainingCoins = (currentCoins - 5).coerceAtLeast(0),
            affectionGained = 0f,
            currentAffection = pet.affection,
            message = "Drew a card! Hit again or Stand?",
        )
    }

    fun standBlackjack() {
        val current = _blackjackState.value ?: return
        val roundId = activeBjRoundId ?: return
        val pet = _activePet.value ?: return
        if (current.status != "playing") return

        while (handScore(localBjDealerCards) < 17 && localBjDeck.isNotEmpty()) {
            localBjDealerCards.add(localBjDeck.removeAt(localBjDeck.lastIndex))
        }
        viewModelScope.launch {
            finishLocalBlackjack(roundId, pet, localBjPlayerCards.toList(), localBjDealerCards.toList())
        }
    }

    fun resetBlackjack() {
        activeBjRoundId = null
        localBjDeck.clear()
        localBjPlayerCards.clear()
        localBjDealerCards.clear()
        _blackjackState.value = null
    }

    private suspend fun finishLocalBlackjack(
        roundId: String,
        pet: PetItem,
        playerCards: List<String>,
        dealerCards: List<String>,
    ) {
        activeBjRoundId = null
        localBjDeck.clear()

        val pScore = handScore(playerCards)
        val dScore = handScore(dealerCards)
        val roundStatus: String
        val coinsDelta: Int
        val affectionGained: Float
        val msg: String

        when {
            pScore > 21 -> {
                roundStatus = "player_bust"
                coinsDelta = -5
                affectionGained = 1f
                msg = "Bust ($pScore)! Lost 5 Coins. ${pet.name} cheered you on (+1 Affection)."
            }
            dScore > 21 || pScore > dScore -> {
                roundStatus = "player_win"
                coinsDelta = 5
                affectionGained = 8f
                msg = if (dScore <= 21) {
                    "You win ($pScore vs $dScore)! +5 Coins & +8 Affection!"
                } else {
                    "${pet.name} busted ($dScore)! +5 Coins & +8 Affection!"
                }
            }
            pScore == dScore -> {
                roundStatus = "push"
                coinsDelta = 0
                affectionGained = 3f
                msg = "Push ($pScore vs $dScore)! Your 5 Coins were returned (+3 Affection)."
            }
            else -> {
                roundStatus = "pet_win"
                coinsDelta = -5
                affectionGained = 1f
                msg = "${pet.name} wins ($dScore vs $pScore)! Lost 5 Coins (+1 Affection)."
            }
        }

        val preBetCoins = _player.value?.stats?.coins ?: playerRepository.cachedMemoryPlayer?.stats?.coins ?: 5
        val finalCoins = (preBetCoins + coinsDelta).coerceAtLeast(0)
        val newAffection = ((pet.affection + affectionGained).coerceIn(0f, 100f) * 10f).toInt() / 10f

        updateActiveAffectionState(newAffection)
        petRepository.updateActivePetAffectionLocal(newAffection)
        playerRepository.applyLocalStatsPatch(coins = finalCoins)
        playerRepository.cachedMemoryPlayer?.let { _player.value = it }

        _blackjackState.value = BlackjackStateResponse(
            gameToken = null,
            status = roundStatus,
            playerCards = playerCards,
            dealerCards = dealerCards,
            playerScore = pScore,
            dealerScore = dScore,
            betCoins = 5,
            coinsDelta = coinsDelta,
            remainingCoins = finalCoins,
            affectionGained = affectionGained,
            currentAffection = newAffection,
            message = msg,
        )

        when (roundStatus) {
            "player_win" -> _reactionEvent.emit("🃏 21! You beat your companion! +5 🪙 & +${affectionGained.toInt()} ❤️!")
            "push" -> _reactionEvent.emit("🤝 Push! 5 🪙 refunded & +${affectionGained.toInt()} ❤️!")
            "pet_win", "player_bust" -> _reactionEvent.emit("🐾 Companion takes the round! +${affectionGained.toInt()} ❤️")
        }

        // Verify and persist completed hand once on backend
        pendingMiniGameSettles++
        try {
            miniGameSettleMutex.withLock {
                val res = petRepository.settleBlackjack(
                    BlackjackSettleRequest(
                        roundId = roundId,
                        playerCards = playerCards,
                        dealerCards = dealerCards,
                        outcome = roundStatus,
                    )
                )
                if (res is Resource.Success && pendingMiniGameSettles <= 1) {
                    val settled = res.data
                    if (activeBjRoundId == null && _blackjackState.value != null) {
                        _blackjackState.value = settled
                    }
                    updateActiveAffectionState(settled.currentAffection)
                    playerRepository.applyLocalStatsPatch(coins = settled.remainingCoins)
                    playerRepository.cachedMemoryPlayer?.let { _player.value = it }
                } else if (res is Resource.Error) {
                    _actionFeedback.emit(res.message)
                    refreshPlayerProfile()
                }
            }
        } finally {
            pendingMiniGameSettles = (pendingMiniGameSettles - 1).coerceAtLeast(0)
        }
    }

    private fun buildShuffledDeck(): MutableList<String> {
        val ranks = listOf("A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K")
        val suits = listOf("♠", "♥", "♦", "♣")
        val deck = mutableListOf<String>()
        for (rank in ranks) {
            for (suit in suits) {
                deck.add("$rank$suit")
            }
        }
        deck.shuffle(Random)
        return deck
    }

    private fun handScore(cards: List<String>): Int {
        var total = 0
        var aces = 0
        for (card in cards) {
            val rank = card.dropLast(1)
            when (rank) {
                "A" -> {
                    aces++
                    total += 11
                }
                "K", "Q", "J" -> total += 10
                else -> total += rank.toIntOrNull() ?: 0
            }
        }
        while (total > 21 && aces > 0) {
            total -= 10
            aces--
        }
        return total
    }

    private fun updateActiveAffectionState(newAffection: Float) {
        val activeId = _activePet.value?.id
        _activePet.value = _activePet.value?.copy(affection = newAffection)
        _petList.value = sortPetsByRarity(
            _petList.value.map {
                if (it.id == activeId) it.copy(affection = newAffection) else it
            }
        )
    }

    fun feedPet(foodId: String? = null) {
        viewModelScope.launch {
            when (val res = petRepository.feedPet(foodId)) {
                is Resource.Success -> {
                    val response = res.data
                    _actionFeedback.emit(response.message)
                    val reactionMsg = if (response.hpHealed > 0) {
                        "😋 好吃! Delicious! +${response.affectionGained.toInt()} ❤️ & +${response.hpHealed} HP!"
                    } else {
                        "😋 好吃! Delicious! +${response.affectionGained.toInt()} ❤️ Affection!"
                    }
                    _reactionEvent.emit(reactionMsg)
                    updateActiveAffectionState(response.currentAffection)
                    when {
                        foodId != null && foodId != "quick_snack" -> {
                            _foodBag.value = _foodBag.value.mapNotNull { item ->
                                if (item.itemId != foodId) {
                                    item
                                } else if (item.quantity > 1) {
                                    item.copy(quantity = item.quantity - 1)
                                } else {
                                    null
                                }
                            }
                            shopRepository.decrementItemQuantityLocal(foodId)
                        }
                        foodId == "quick_snack" -> {
                            val currentCoins = _player.value?.stats?.coins
                            if (currentCoins != null) {
                                playerRepository.applyLocalStatsPatch(
                                    coins = (currentCoins - 15).coerceAtLeast(0),
                                    hp = response.currentPlayerHp,
                                )
                            }
                        }
                        else -> {
                            launch { loadFoodBag() }
                            launch { refreshPlayerProfile() }
                        }
                    }
                    if (foodId != "quick_snack") {
                        playerRepository.applyLocalStatsPatch(hp = response.currentPlayerHp)
                    }
                }
                is Resource.Error -> {
                    _actionFeedback.emit(res.message)
                }
                Resource.Loading -> {}
            }
        }
    }

    companion object {
        fun rarityRank(rarity: String): Int = when (rarity.lowercase()) {
            "secret" -> 5
            "legendary" -> 4
            "epic" -> 3
            "rare" -> 2
            else -> 1
        }

        fun sortPetsByRarity(pets: List<PetItem>): List<PetItem> {
            return pets.sortedWith(
                compareByDescending<PetItem> { rarityRank(it.rarity) }
                    .thenByDescending { it.isActive }
                    .thenByDescending { it.affection }
                    .thenBy { it.id }
            )
        }

        fun factory(
            petRepository: PetRepository,
            shopRepository: ShopRepository,
            playerRepository: PlayerRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { PetViewModel(petRepository, shopRepository, playerRepository) }
        }
    }
}
