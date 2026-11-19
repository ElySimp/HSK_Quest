package com.faldo.hsk_quest.engine

import com.faldo.hsk_quest.data.local.EngineSettingsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineSettingsTest {

    @Test
    fun defaultMode_isRuleBased() {
        val manager = EngineSettingsManager(mlModelReadyProvider = { false })
        assertEquals(QuestionEngineMode.RULE_BASED, manager.selectedMode.value)

        val resolved = manager.resolveActiveEngine()
        assertEquals(QuestionEngineMode.RULE_BASED, resolved.configuredMode)
        assertEquals(QuestionEngineMode.RULE_BASED, resolved.effectiveMode)
        assertFalse(resolved.isFallbackToRuleBased)
    }

    @Test
    fun switchingToMlCat_withoutBundledModel_fallsBackSafelyToRuleBased() {
        val manager = EngineSettingsManager(mlModelReadyProvider = { false })
        manager.setSelectedMode(QuestionEngineMode.ML_CAT)

        assertEquals(QuestionEngineMode.ML_CAT, manager.selectedMode.value)
        val resolved = manager.resolveActiveEngine()
        assertEquals(QuestionEngineMode.ML_CAT, resolved.configuredMode)
        assertEquals(QuestionEngineMode.RULE_BASED, resolved.effectiveMode)
        assertTrue(resolved.isFallbackToRuleBased)
    }

    @Test
    fun switchingToMlCat_whenModelAvailable_activatesMlEngine() {
        var modelPresent = false
        val manager = EngineSettingsManager(mlModelReadyProvider = { modelPresent })
        manager.setSelectedMode(QuestionEngineMode.ML_CAT)
        assertEquals(QuestionEngineMode.RULE_BASED, manager.resolveActiveEngine().effectiveMode)

        modelPresent = true
        val resolved = manager.resolveActiveEngine()
        assertEquals(QuestionEngineMode.ML_CAT, resolved.configuredMode)
        assertEquals(QuestionEngineMode.ML_CAT, resolved.effectiveMode)
        assertFalse(resolved.isFallbackToRuleBased)
    }

    @Test
    fun questionEngineModeFromId_defaultsSafelyOnUnknownIds() {
        assertEquals(QuestionEngineMode.RULE_BASED, QuestionEngineMode.fromId(null))
        assertEquals(QuestionEngineMode.RULE_BASED, QuestionEngineMode.fromId("unknown_mode"))
        assertEquals(QuestionEngineMode.ML_CAT, QuestionEngineMode.fromId("ml_cat"))
    }
}
