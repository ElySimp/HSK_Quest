package com.faldo.hsk_quest.data.model

enum class PetRarity { COMMON, RARE, EPIC, LEGENDARY }

enum class PetBuff {
    NONE,

    /** Extends the critical-hit answer window from 2s to 5s. */
    TIME_EXTENDER,

    /** Blocks damage from one wrong answer per battle. */
    SHIELD,
}

/**
 * Pet companion (Tamagotchi side). Full gameplay arrives in Phase 3/4.
 */
data class Pet(
    val id: Int,
    val name: String,
    val rarity: PetRarity,
    val type: String,
    val affection: Int,
    val isActive: Boolean,
    val buff: PetBuff = PetBuff.NONE,
)
