package com.faldo.hsk_quest.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.faldo.hsk_quest.data.local.entity.VocabMasteryEntity

@Dao
interface VocabMasteryDao {

    @Query("SELECT * FROM vocab_mastery WHERE username = :username AND hskLevel = :hskLevel")
    suspend fun getForLevel(username: String, hskLevel: Int): List<VocabMasteryEntity>

    @Query("SELECT * FROM vocab_mastery WHERE username = :username AND wrongCount > 0 AND masteryScore < 85 ORDER BY masteryScore ASC, wrongCount DESC")
    suspend fun getWeakWords(username: String): List<VocabMasteryEntity>

    @Query("SELECT * FROM vocab_mastery WHERE username = :username AND hskLevel = :hskLevel AND hanzi = :hanzi LIMIT 1")
    suspend fun getEntry(username: String, hskLevel: Int, hanzi: String): VocabMasteryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: VocabMasteryEntity)

    /**
     * Records a single question attempt for [hanzi].
     * To keep the database compact, words answered right on their first encounter are not stored.
     */
    suspend fun recordAttempt(
        username: String,
        hskLevel: Int,
        hanzi: String,
        pinyin: String,
        english: String,
        isCorrect: Boolean,
    ) {
        if (username.isBlank() || hanzi.isBlank()) return
        val existing = getEntry(username, hskLevel, hanzi)
        if (existing == null && isCorrect) {
            return
        }

        val now = System.currentTimeMillis()
        val updated = if (existing == null) {
            VocabMasteryEntity(
                username = username,
                hskLevel = hskLevel,
                hanzi = hanzi,
                pinyin = pinyin,
                english = english,
                wrongCount = 1,
                correctCount = 0,
                correctStreak = 0,
                masteryScore = 30,
                updatedAt = now,
            )
        } else if (isCorrect) {
            existing.copy(
                pinyin = pinyin.ifBlank { existing.pinyin },
                english = english.ifBlank { existing.english },
                correctCount = existing.correctCount + 1,
                correctStreak = existing.correctStreak + 1,
                masteryScore = (existing.masteryScore + 15).coerceIn(0, 100),
                updatedAt = now,
            )
        } else {
            existing.copy(
                pinyin = pinyin.ifBlank { existing.pinyin },
                english = english.ifBlank { existing.english },
                wrongCount = existing.wrongCount + 1,
                correctStreak = 0,
                masteryScore = (existing.masteryScore - 20).coerceIn(0, 100),
                updatedAt = now,
            )
        }
        upsert(updated)
    }
}
