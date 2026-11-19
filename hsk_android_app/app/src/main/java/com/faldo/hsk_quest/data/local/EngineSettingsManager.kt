package com.faldo.hsk_quest.data.local

import android.content.Context
import android.content.SharedPreferences
import com.faldo.hsk_quest.engine.QuestionEngineMode
import com.faldo.hsk_quest.engine.ResolvedQuestionEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists user-facing engine configuration such as the CAT question scheduler mode
 * ([QuestionEngineMode.RULE_BASED] vs [QuestionEngineMode.ML_CAT]) and checks whether
 * optional on-device TFLite assets are bundled.
 */
class EngineSettingsManager private constructor(
    private val prefs: SharedPreferences?,
    private val mlModelReadyProvider: () -> Boolean,
) {

    constructor(context: Context) : this(
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        mlModelReadyProvider = { hasBundledAsset(context.applicationContext, ML_CAT_ASSET_PATH) },
    )

    constructor(
        initialMode: QuestionEngineMode = QuestionEngineMode.RULE_BASED,
        mlModelReadyProvider: () -> Boolean = { false },
    ) : this(
        prefs = null,
        mlModelReadyProvider = mlModelReadyProvider,
    ) {
        inMemoryMode = initialMode
        _selectedMode.value = initialMode
    }

    private var inMemoryMode: QuestionEngineMode = QuestionEngineMode.fromId(
        prefs?.getString(KEY_QUESTION_ENGINE_MODE, QuestionEngineMode.RULE_BASED.id)
    )

    private val _selectedMode = MutableStateFlow(inMemoryMode)
    val selectedMode: StateFlow<QuestionEngineMode> = _selectedMode.asStateFlow()

    val isMlCatModelAvailable: Boolean
        get() = mlModelReadyProvider()

    fun setSelectedMode(mode: QuestionEngineMode) {
        inMemoryMode = mode
        prefs?.edit()?.putString(KEY_QUESTION_ENGINE_MODE, mode.id)?.apply()
        _selectedMode.value = mode
    }

    fun resolveActiveEngine(): ResolvedQuestionEngine {
        return ResolvedQuestionEngine.resolve(
            configuredMode = _selectedMode.value,
            isMlModelReady = isMlCatModelAvailable,
        )
    }

    companion object {
        private const val PREFS_NAME = "hsk_engine_settings_prefs"
        private const val KEY_QUESTION_ENGINE_MODE = "question_engine_mode"
        const val ML_CAT_ASSET_PATH = "ml/cat_scheduler.tflite"

        private fun hasBundledAsset(context: Context, assetPath: String): Boolean {
            return runCatching {
                context.assets.openFd(assetPath).close()
                true
            }.recoverCatching {
                context.assets.open(assetPath).close()
                true
            }.getOrDefault(false)
        }
    }
}
