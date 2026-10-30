package com.faldo.hsk_quest.data.model

import com.google.gson.annotations.SerializedName

/**
 * A single vocabulary entry from assets/hsk_data/hsk-*.json.
 * Note: alsoLevels can contain string ranges such as "7-9" alongside integer levels.
 */
data class HskTerm(
    val number: Int,
    val simplified: String,
    val traditional: String?,
    val pinyin: String,
    val english: String,
    val partOfSpeech: String?,
    val sense: Int? = null,
    val alsoLevels: List<String>? = null,
)

/**
 * Root object of an HSK JSON file. Only the fields the app needs are mapped.
 */
data class HskLevelFile(
    val level: Int,
    val label: String,
    @SerializedName("totalTerms") val totalTerms: Int,
    val terms: List<HskTerm>,
)
