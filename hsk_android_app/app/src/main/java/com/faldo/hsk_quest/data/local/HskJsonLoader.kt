package com.faldo.hsk_quest.data.local

import android.content.Context
import com.faldo.hsk_quest.data.model.HskLevelFile
import com.faldo.hsk_quest.data.model.HskTerm
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Loads HSK vocabulary from assets/hsk_data and caches each level in memory.
 * Area 7, 8 and 9 all map to the combined hsk-7-9.json file.
 */
class HskJsonLoader(context: Context) {

    private val assets = context.applicationContext.assets
    private val gson = Gson()
    private val cache = ConcurrentHashMap<String, List<HskTerm>>()

    suspend fun loadLevel(level: Int): List<HskTerm> = withContext(Dispatchers.IO) {
        val fileName = fileNameFor(level)
        cache[fileName] ?: run {
            val terms = assets.open("$ASSET_DIR/$fileName").bufferedReader(Charsets.UTF_8).use {
                gson.fromJson(it, HskLevelFile::class.java).terms
            }
            cache[fileName] = terms
            terms
        }
    }

    private fun fileNameFor(level: Int): String = when (level.coerceIn(1, 9)) {
        in 7..9 -> "hsk-7-9.json"
        else -> "hsk-${level.coerceIn(1, 9)}.json"
    }

    private companion object {
        const val ASSET_DIR = "hsk_data"
    }
}
