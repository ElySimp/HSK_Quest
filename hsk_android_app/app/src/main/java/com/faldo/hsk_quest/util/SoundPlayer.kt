package com.faldo.hsk_quest.util

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import androidx.annotation.RawRes
import com.faldo.hsk_quest.R

/** Short retro sound effects bundled in res/raw. */
enum class Sfx(@RawRes val resId: Int) {
    CORRECT(R.raw.sfx_correct),
    WRONG(R.raw.sfx_wrong),
    HIT(R.raw.sfx_hit),
    CRIT(R.raw.sfx_crit),
    MONSTER_DEFEAT(R.raw.sfx_monster_defeat),
    COIN(R.raw.sfx_coin),
    POTION(R.raw.sfx_potion),
    GACHA_SUMMON(R.raw.sfx_gacha_summon),
    LEVEL_UP(R.raw.sfx_level_up),
}

/**
 * Thin SoundPool wrapper that preloads every [Sfx] once and plays them on demand.
 */
class SoundPlayer(context: Context) {

    private val soundPool: SoundPool = SoundPool.Builder()
        .setMaxStreams(MAX_STREAMS)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    private val soundIds = HashMap<Sfx, Int>()
    private val loadedIds = HashSet<Int>()

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) loadedIds.add(sampleId)
        }
        val appContext = context.applicationContext
        Sfx.values().forEach { sfx ->
            soundIds[sfx] = soundPool.load(appContext, sfx.resId, 1)
        }
    }

    fun play(sfx: Sfx) {
        val soundId = soundIds[sfx] ?: return
        if (soundId !in loadedIds) return
        soundPool.play(soundId, VOLUME, VOLUME, 1, 0, 1f)
    }

    fun release() {
        soundPool.release()
        soundIds.clear()
        loadedIds.clear()
    }

    private companion object {
        const val MAX_STREAMS = 4
        const val VOLUME = 0.8f
    }
}
