package com.faldo.hsk_quest

import android.app.Application
import android.content.Context
import com.faldo.hsk_quest.data.local.AppDatabase
import com.faldo.hsk_quest.data.local.HskJsonLoader
import com.faldo.hsk_quest.data.local.TokenManager
import com.faldo.hsk_quest.data.remote.ApiService
import com.faldo.hsk_quest.data.remote.RetrofitClient
import com.faldo.hsk_quest.data.repository.AuthRepository
import com.faldo.hsk_quest.data.repository.BattleRepository
import com.faldo.hsk_quest.data.repository.LeaderboardRepository
import com.faldo.hsk_quest.data.repository.PlayerRepository
import com.faldo.hsk_quest.util.SoundPlayer

/**
 * Application entry point. Owns the manual dependency container.
 */
class HskQuestApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/**
 * Lightweight manual DI: lazily creates singletons shared across the app.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val tokenManager: TokenManager by lazy { TokenManager(appContext) }
    val database: AppDatabase by lazy { AppDatabase.build(appContext) }
    val vocabMasteryDao by lazy { database.vocabMasteryDao() }
    val apiService: ApiService by lazy { RetrofitClient.create(tokenManager) }
    val hskJsonLoader: HskJsonLoader by lazy { HskJsonLoader(appContext) }
    val soundPlayer: SoundPlayer by lazy { SoundPlayer(appContext) }

    val authRepository: AuthRepository by lazy {
        AuthRepository(apiService, tokenManager, database.playerDao())
    }
    val playerRepository: PlayerRepository by lazy {
        PlayerRepository(apiService, tokenManager, database.playerDao())
    }
    val battleRepository: BattleRepository by lazy {
        BattleRepository(apiService, tokenManager, database.playerDao())
    }
    val petRepository: com.faldo.hsk_quest.data.repository.PetRepository by lazy {
        com.faldo.hsk_quest.data.repository.PetRepository(apiService)
    }
    val gachaRepository: com.faldo.hsk_quest.data.repository.GachaRepository by lazy {
        com.faldo.hsk_quest.data.repository.GachaRepository(apiService)
    }
    val shopRepository: com.faldo.hsk_quest.data.repository.ShopRepository by lazy {
        com.faldo.hsk_quest.data.repository.ShopRepository(apiService)
    }
    val stageRepository: com.faldo.hsk_quest.data.repository.StageRepository by lazy {
        com.faldo.hsk_quest.data.repository.StageRepository(
            database.stageProgressDao(),
            tokenManager,
            database.playerDao()
        )
    }
    val leaderboardRepository: LeaderboardRepository by lazy {
        LeaderboardRepository(apiService)
    }
}
