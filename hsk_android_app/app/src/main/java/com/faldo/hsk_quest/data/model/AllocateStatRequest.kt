package com.faldo.hsk_quest.data.model

import com.google.gson.annotations.SerializedName

data class AllocateStatRequest(
    @SerializedName("stat_name") val statName: String,
)
