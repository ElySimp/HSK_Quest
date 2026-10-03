package com.faldo.hsk_quest.data.remote

import com.faldo.hsk_quest.data.local.TokenManager
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Attaches "Authorization: Bearer <token>" to every request when a token is stored.
 * Auth endpoints are skipped so stale tokens never interfere with login/register.
 */
class AuthInterceptor(private val tokenManager: TokenManager) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val token = tokenManager.cachedToken
        if (token.isNullOrBlank() || request.url.encodedPath.contains("/api/auth/")) {
            return chain.proceed(request)
        }
        val authed = request.newBuilder()
            .header("Authorization", "Bearer $token")
            .build()
        return chain.proceed(authed)
    }
}
