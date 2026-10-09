package com.faldo.hsk_quest.data.remote

import android.os.Build
import com.faldo.hsk_quest.BuildConfig
import com.faldo.hsk_quest.data.local.TokenManager
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Builds the configured [ApiService] instance.
 */
object RetrofitClient {

    private fun resolveBaseUrl(): String {
        val configuredUrl = BuildConfig.BASE_URL
        if (!configuredUrl.contains("10.0.2.2")) {
            return configuredUrl
        }

        val isEmulator = Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.MANUFACTURER.contains("Genymotion")
                || Build.HARDWARE.contains("goldfish")
                || Build.HARDWARE.contains("ranchu")
                || Build.PRODUCT.contains("sdk_gphone")
                || Build.PRODUCT.contains("sdk")
                || Build.PRODUCT.contains("emulator")

        return if (isEmulator) {
            configuredUrl
        } else {
            // For physical devices over USB debugging with `adb reverse tcp:8000 tcp:8000`
            configuredUrl.replace("10.0.2.2", "127.0.0.1")
        }
    }

    fun create(tokenManager: TokenManager): ApiService {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
            // Never log the bearer token.
            redactHeader("Authorization")
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(tokenManager))
            .addInterceptor(logging)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(resolveBaseUrl())
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}
