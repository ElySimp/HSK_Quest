package com.faldo.hsk_quest.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
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

    suspend fun clear() {
        dataStore.edit { it.clear() }
        cachedToken = null
    }

    private companion object {
        val KEY_TOKEN = stringPreferencesKey("access_token")
        val KEY_USERNAME = stringPreferencesKey("username")
    }
}
