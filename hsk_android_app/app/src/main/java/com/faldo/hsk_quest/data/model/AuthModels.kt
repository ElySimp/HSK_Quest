package com.faldo.hsk_quest.data.model

import com.google.gson.annotations.SerializedName

// ---------------------------------------------------------------------------
// Requests (mirror app/schemas/auth.py on the backend)
// ---------------------------------------------------------------------------
data class RegisterRequest(
    val username: String,
    val password: String,
    @SerializedName("hsk_target") val hskTarget: Int,
)

data class LoginRequest(
    val username: String,
    val password: String,
)

// ---------------------------------------------------------------------------
// Responses
// ---------------------------------------------------------------------------
data class TokenResponse(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("token_type") val tokenType: String,
)

data class UserResponse(
    val id: Int,
    val username: String,
    @SerializedName("hsk_target") val hskTarget: Int,
)

data class RegisterResponse(
    val user: UserResponse,
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("token_type") val tokenType: String,
    val message: String,
)
