package com.faldo.hsk_quest.engine

import com.faldo.hsk_quest.data.local.ClozeJsonEntry
import com.faldo.hsk_quest.data.local.ClozeLevelFile
import com.faldo.hsk_quest.data.model.HskLevelFile
import com.faldo.hsk_quest.data.model.HskTerm
import com.faldo.hsk_quest.data.model.MonsterType
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EngineAShuffleAndClozeTest {

    private val gson = Gson()

    private fun resolveHskAsset(fileName: String): File {
        val candidates = listOf(
            File("src/main/assets/hsk_data/$fileName"),
            File("app/src/main/assets/hsk_data/$fileName"),
            File("hsk_android_app/app/src/main/assets/hsk_data/$fileName"),
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("Could not locate HSK asset: $fileName from ${File(".").absolutePath}")
    }

    private fun loadHskLevelTerms(fileName: String): List<HskTerm> {
        val file = resolveHskAsset(fileName)
        return file.reader(Charsets.UTF_8).use { reader ->
            gson.fromJson(reader, HskLevelFile::class.java).terms
        }.filter { it.simplified.isNotBlank() && it.pinyin.isNotBlank() && it.english.isNotBlank() }
    }

    private fun loadClozeFile(fileName: String): ClozeLevelFile {
        val file = resolveHskAsset(fileName)
        return file.reader(Charsets.UTF_8).use { reader ->
            gson.fromJson(reader, ClozeLevelFile::class.java)
        }
    }

    @Test
    fun shuffleBag_coversEntireHsk1LevelWithoutRepeatsBeforeReshuffle() {
        val terms = loadHskLevelTerms("hsk-1.json")
        val clozeMap = loadClozeFile("cloze_hsk_1.json").entries.groupBy { it.target }
        val uniqueHanziCount = terms.map { it.simplified }.distinct().size
        assertTrue("Expected at least 250 unique HSK 1 terms, found $uniqueHanziCount", uniqueHanziCount >= 250)

        val deckManager = QuestionDeckManager()
        val seenInFirstCycle = LinkedHashSet<String>()

        repeat(uniqueHanziCount) { idx ->
            val q = QuestionGenerator.generateQuestion(
                terms = terms,
                monsterType = MonsterType.NORMAL,
                mistakeWeights = emptyMap(),
                deckManager = deckManager,
                username = "tester",
                areaId = 1,
                levelClozeMap = clozeMap,
            )
            assertNotNull("Expected generated question at index $idx", q)
            val hanzi = q!!.targetTerm.simplified
            assertFalse("Word '$hanzi' repeated at draw #$idx before full HSK 1 cycle ended", hanzi in seenInFirstCycle)
            seenInFirstCycle += hanzi
        }

        assertEquals(uniqueHanziCount, seenInFirstCycle.size)
    }

    @Test
    fun missedWordRequeue_waitsForCooldownInsteadOfImmediateRepeat() {
        val terms = loadHskLevelTerms("hsk-1.json")
        val deckManager = QuestionDeckManager()

        val firstQ = QuestionGenerator.generateQuestion(
            terms = terms,
            monsterType = MonsterType.NORMAL,
            deckManager = deckManager,
            username = "tester",
            areaId = 1,
        )!!
        val missedHanzi = firstQ.targetTerm.simplified
        deckManager.recordOutcome("tester", 1, missedHanzi, isCorrect = false)

        // Cooldown = 3 turns, so the immediate next two questions must NOT repeat missedHanzi
        val nextTwo = mutableListOf<String>()
        repeat(2) {
            val q = QuestionGenerator.generateQuestion(
                terms = terms,
                monsterType = MonsterType.NORMAL,
                deckManager = deckManager,
                username = "tester",
                areaId = 1,
            )!!
            nextTwo += q.targetTerm.simplified
        }
        assertFalse("Missed word must respect cooldown for first 2 turns", missedHanzi in nextTwo)

        // Within the subsequent turns, missedHanzi should be re-served once cooldown & spacing pass
        var requeuedFound = false
        repeat(5) {
            val q = QuestionGenerator.generateQuestion(
                terms = terms,
                monsterType = MonsterType.NORMAL,
                deckManager = deckManager,
                username = "tester",
                areaId = 1,
            )!!
            if (q.targetTerm.simplified == missedHanzi) {
                requeuedFound = true
                deckManager.recordOutcome("tester", 1, missedHanzi, isCorrect = true)
            }
        }
        assertTrue("Missed word '$missedHanzi' should re-queue after cooldown", requeuedFound)
    }

    @Test
    fun clozeJsonFiles_areLevelStrictAndFreeOfLeakedHighLevelWords() {
        val leakedWords = listOf(
            "词典", "价格", "风景", "图书馆", "流利", "交流", "按时到达", "日用品",
        )
        val clozeFiles = listOf(
            "cloze_hsk_1.json",
            "cloze_hsk_2.json",
            "cloze_hsk_3.json",
            "cloze_hsk_4.json",
            "cloze_hsk_5.json",
            "cloze_hsk_6.json",
            "cloze_hsk_7_9.json",
        )

        for (fileName in clozeFiles) {
            val clozeFile = loadClozeFile(fileName)
            assertTrue("$fileName must contain entries", clozeFile.entries.isNotEmpty())
            for (entry in clozeFile.entries) {
                assertTrue(
                    "Blank marker missing in sentence for '${entry.target}' in $fileName",
                    entry.sentence.contains("[ ___ ]"),
                )
                assertTrue(
                    "Blank marker missing in pinyin for '${entry.target}' in $fileName",
                    entry.pinyin.contains("[ ___ ]"),
                )
                val outsideBlank = entry.sentence.replace("[ ___ ]", "")
                assertFalse(
                    "Target '${entry.target}' leaked inside sentence '${entry.sentence}' in $fileName",
                    outsideBlank.contains(entry.target),
                )
                if (fileName == "cloze_hsk_1.json") {
                    for (leaked in leakedWords) {
                        assertFalse(
                            "High-level word '$leaked' leaked into HSK 1 Cloze: '${entry.sentence}'",
                            entry.sentence.contains(leaked),
                        )
                    }
                }
            }
        }

        // Also ensure fallback QuestionGenerator.buildClozePrompt for HSK 1 terms contains zero leaked high-level words
        val hsk1Terms = loadHskLevelTerms("hsk-1.json")
        val hsk1ClozeMap: Map<String, List<ClozeJsonEntry>> =
            loadClozeFile("cloze_hsk_1.json").entries.groupBy { it.target }

        for (term in hsk1Terms) {
            val (jsonPrompt, _) = QuestionGenerator.buildClozePrompt(term, hsk1ClozeMap)
            val (fallbackPrompt, _) = QuestionGenerator.buildClozePrompt(term, emptyMap())
            for (leaked in leakedWords) {
                assertFalse(
                    "High-level word '$leaked' found in HSK 1 JSON prompt for ${term.simplified}: $jsonPrompt",
                    jsonPrompt.contains(leaked),
                )
                assertFalse(
                    "High-level word '$leaked' found in HSK 1 fallback prompt for ${term.simplified}: $fallbackPrompt",
                    fallbackPrompt.contains(leaked),
                )
            }
        }
    }
}
