package com.faldo.hsk_quest.util

/**
 * Game-wide constants shared by the engine and UI.
 * Values mirror hsk_python_backend/app/services/combat.py.
 */
object Constants {
    const val HSK_MIN_LEVEL = 1
    const val HSK_MAX_LEVEL = 9

    // Splash
    const val SPLASH_MIN_DURATION_MS = 1200L

    // Combat
    const val BASE_DAMAGE = 10
    const val DAMAGE_PER_STR = 5
    const val BASE_CRIT_MULTIPLIER = 1.50
    const val CRIT_PER_DEX = 0.02
    const val DEF_REDUCTION_PER_POINT = 2
    const val BASE_MAX_HP = 100
    const val HP_PER_VIT = 25

    // Critical-hit answer window
    const val CRIT_WINDOW_MS = 2_000L
    const val CRIT_WINDOW_TIME_EXTENDER_MS = 5_000L

    // XP curve: 100 x Level^1.2
    const val XP_BASE = 100.0
    const val XP_EXPONENT = 1.2

    // Area layout
    const val NORMAL_MONSTERS_PER_AREA = 4
    const val ELITE_MONSTERS_PER_AREA = 4

    // Economy
    const val GACHA_PULL_COST_DIAMONDS = 100

    // Username rule (mirrors backend RegisterRequest)
    val USERNAME_REGEX = Regex("^[a-zA-Z0-9_]{3,50}$")
    const val PASSWORD_MIN_LENGTH = 6
}
