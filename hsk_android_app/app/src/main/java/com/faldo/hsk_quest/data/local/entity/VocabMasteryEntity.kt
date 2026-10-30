package com.faldo.hsk_quest.data.local.entity

import androidx.room.Entity

/**
 * Compact per-user vocabulary mastery and mistake tracking entity.
 * Rows are only created when a player struggles with a word (or already has a tracked row),
 * keeping local Room storage minimal while powering spaced repetition and the Kamus HSK Weak Words filter.
 */
@Entity(
    tableName = "vocab_mastery",
    primaryKeys = ["username", "hskLevel", "hanzi"],
)
data class VocabMasteryEntity(
    val username: String,
    val hskLevel: Int,
    val hanzi: String,
    val pinyin: String,
    val english: String,
    val wrongCount: Int = 0,
    val correctCount: Int = 0,
    val correctStreak: Int = 0,
    val masteryScore: Int = 50,
    val updatedAt: Long = System.currentTimeMillis(),
)
