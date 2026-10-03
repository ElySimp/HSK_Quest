package com.faldo.hsk_quest.data.remote

import com.google.gson.JsonParser
import retrofit2.Response

/**
 * Extracts a human-readable message from a FastAPI error body.
 * FastAPI returns either {"detail": "message"} or {"detail": [{"msg": "...", ...}]}.
 */
object ApiErrorParser {

    fun parse(response: Response<*>): String {
        val raw = runCatching { response.errorBody()?.string() }.getOrNull()
        if (raw.isNullOrBlank()) return "HTTP ${response.code()}"

        return runCatching {
            val detail = JsonParser.parseString(raw).asJsonObject.get("detail")
            when {
                detail == null -> "HTTP ${response.code()}"
                detail.isJsonPrimitive -> detail.asString
                detail.isJsonArray && detail.asJsonArray.size() > 0 ->
                    detail.asJsonArray[0].asJsonObject.get("msg")?.asString
                        ?: "HTTP ${response.code()}"
                else -> "HTTP ${response.code()}"
            }
        }.getOrDefault("HTTP ${response.code()}")
    }
}
