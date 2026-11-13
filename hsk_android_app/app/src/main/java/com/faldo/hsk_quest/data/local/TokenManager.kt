package com.faldo.hsk_quest.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.authDataStore: DataStore<Preferences> by preferencesDataStore(name = "auth_prefs")

/**
 * Persists the JWT access token and the logged-in username via DataStore.
 * Keeps an in-memory copy so the OkHttp interceptor can read it without blocking.
 */
class TokenManager(context: Context) {

    private val dataStore = context.applicationContext.authDataStore

    @Volatile
    var cachedToken: String? = null
        private set

    /** Loads the persisted token into memory. Call once before the first API request. */
    suspend fun load(): String? {
        val token = dataStore.data.map { it[KEY_TOKEN] }.first()
        cachedToken = token
        return token
    }

    suspend fun getUsername(): String? = dataStore.data.map { it[KEY_USERNAME] }.first()

    suspend fun save(token: String, username: String) {
        dataStore.edit {
            it[KEY_TOKEN] = token
            it[KEY_USERNAME] = username
        }
        cachedToken = token
    }

    suspend fun updateUsername(username: String) {
        dataStore.edit {
            it[KEY_USERNAME] = username
        }
    }

    /**
     * Mutes speaking challenge questions for the specified user for [durationMs] (default 15 minutes).
     */
    suspend fun setSpeakingMutedUntil(
        username: String,
        durationMs: Long = 15L * 60L * 1000L,
    ) {
        val cleanUser = username.trim().lowercase().ifEmpty { "guest" }
        val muteUntil = System.currentTimeMillis() + durationMs
        val key = longPreferencesKey("speaking_mute_until_$cleanUser")
        dataStore.edit {
            it[key] = muteUntil
        }
    }

    /**
     * Checks if speaking challenge questions are currently muted for the specified user.
     */
    suspend fun isSpeakingMuted(username: String): Boolean {
        val cleanUser = username.trim().lowercase().ifEmpty { "guest" }
        val key = longPreferencesKey("speaking_mute_until_$cleanUser")
        val muteUntil = dataStore.data.map { it[key] ?: 0L }.first()
        return System.currentTimeMillis() < muteUntil
    }

    suspend fun clear() {
        dataStore.edit { it.clear() }
        cachedToken = null
    }

    private companion object {
        val KEY_TOKEN = stringPreferencesKey("access_token")
        val KEY_USERNAME = stringPreferencesKey("username")
    }
}
