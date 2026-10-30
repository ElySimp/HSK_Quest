package com.faldo.hsk_quest.engine

import com.faldo.hsk_quest.data.model.HskTerm
import com.faldo.hsk_quest.data.model.MonsterType
import java.text.Normalizer
import kotlin.random.Random

enum class QuestionType(val displayLabel: String) {
    ENGLISH_TO_HANZI("📖 Reading · Translate to Hanzi"),
    HANZI_TO_PINYIN("📖 Reading · Select Correct Pinyin"),
    HANZI_TO_ENGLISH("📖 Reading · Translate to English"),
    LISTENING_TO_ENGLISH("🔊 Listening · Listen & Select Meaning"),
    LISTENING_TO_HANZI("🔊 Listening · Listen & Select Hanzi"),
    SPEAKING_CHALLENGE("🎙️ Speaking · Pronounce in Mandarin"),
    CLOZE_SENTENCE("📜 Elite Story · Complete the Sentence");

    val isListening: Boolean
        get() = this == LISTENING_TO_ENGLISH || this == LISTENING_TO_HANZI

    val isSpeaking: Boolean
        get() = this == SPEAKING_CHALLENGE

    val isCloze: Boolean
        get() = this == CLOZE_SENTENCE
}

enum class SpeechGrade {
    EXACT_MATCH,
    TONE_LENIENT_MATCH,
    NO_MATCH,
}

data class SpeechEvalResult(
    val grade: SpeechGrade,
    val matchedText: String,
    val feedback: String,
)

data class BattleQuestion(
    val type: QuestionType,
    val prompt: String,
    val subPrompt: String? = null,
    val options: List<String>,
    val correctIndex: Int,
    val targetTerm: HskTerm,
) {
    fun isCorrect(selectedIndex: Int): Boolean = selectedIndex == correctIndex
}

/**
 * Multi-modal question generator supporting Reading, Listening (TTS), Speaking (STT),
 * Contextual Cloze Sentences for Elite/Boss encounters, and adaptive mistake weighting.
 */
object QuestionGenerator {

    private val DIACRITICS_REGEX = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val NON_ALPHANUM_REGEX = Regex("[^a-z0-9]")
    private val WHITESPACE_PUNCT_REGEX = Regex("[\\s\\p{Punct}。，！？、；：“”‘’（）《》【】「」]+")

    // Common Mandarin homophone characters grouped by base tone-stripped syllable for offline STT grading
    private val COMMON_HOMOPHONE_SYLLABLES: Map<Char, String> by lazy {
        val groups = mapOf(
            "ma" to "吗妈麻马骂嘛码蚂",
            "shi" to "是十时事市试式室视识师石食史使始世势示士氏似实失湿诗狮拾",
            "yi" to "一以已衣医依意议易义益艺亿忆译异翼椅姨疑移宜仪",
            "bu" to "不步部布簿补捕",
            "de" to "的得地德底第弟帝递蒂",
            "ni" to "你泥拟逆腻尼呢",
            "hao" to "好号毫豪耗浩喊",
            "wo" to "我握卧沃窝",
            "ta" to "他她它踏塔塌牠",
            "zai" to "在再载灾栽宰仔",
            "you" to "有又右友由游油邮优忧尤诱幼悠",
            "zhe" to "这着者哲折浙遮",
            "na" to "那哪拿呐纳娜捺",
            "le" to "了乐勒肋",
            "men" to "们门闷扪",
            "he" to "和河何合盒喝贺荷核禾",
            "shui" to "水谁睡税摔",
            "chi" to "吃尺池迟持齿赤翅匙痴",
            "kan" to "看砍刊堪侃槛",
            "ting" to "听停厅挺庭亭艇",
            "shuo" to "说烁硕朔",
            "du" to "读独毒度渡肚睹赌都堵",
            "xie" to "写谢些鞋歇斜血胁携蟹泄",
            "mai" to "买卖麦迈脉埋",
            "qu" to "去区取娶趣曲屈驱渠",
            "lai" to "来莱赖睐",
            "hui" to "会回灰挥辉恢悔汇绘惠慧毁",
            "xiang" to "想向像象项箱香乡相享响巷降",
            "yao" to "要药咬耀腰邀摇遥谣窑",
            "neng" to "能弄浓农脓",
            "ke" to "可课客刻渴克棵颗壳咳科",
            "ai" to "爱矮挨埃碍唉哀癌",
            "xiao" to "小笑校效消销晓孝肖削宵",
            "da" to "大打达答搭沓",
            "duo" to "多朵躲夺舵堕",
            "shao" to "少烧勺绍稍哨梢",
            "leng" to "冷棱愣",
            "re" to "热惹",
            "tian" to "天添甜填田舔",
            "nian" to "年念捻碾粘",
            "yue" to "月越约跃悦阅乐钥岳",
            "ri" to "日入",
            "xing" to "星行姓醒兴型形刑幸杏性省",
            "qi" to "期七妻凄欺漆齐其奇骑棋旗企岂起气汽弃泣契器",
            "dian" to "点电店垫典碘殿颠",
            "fen" to "分份粉奋愤纷芬坟",
            "kuai" to "块快筷会侩",
            "qian" to "钱前千签迁铅浅欠歉潜牵",
            "ren" to "人认任仁忍刃韧",
            "zhong" to "中种重钟终忠肿众仲",
            "guo" to "国果过锅裹郭",
            "jia" to "家加佳嘉假价架驾嫁稼夹甲",
            "xue" to "学雪血穴靴",
            "sheng" to "生声升胜省圣盛剩绳牲",
            "gong" to "工公功攻供宫弓恭巩共贡",
            "zuo" to "作做坐座左昨佐",
            "shang" to "上商伤尚赏裳",
            "xia" to "下夏吓瞎虾匣狭",
            "li" to "里理力历立丽利例礼离梨厘狸粒励",
            "wai" to "外歪",
            "hou" to "后候厚猴喉吼",
            "dong" to "东冬懂洞动冻栋",
            "xi" to "西习洗喜戏系细吸希昔息悉惜稀溪膝",
            "nan" to "南难男喃楠",
            "bei" to "北杯背倍备被悲碑贝辈",
            "bai" to "百白摆败拜柏",
            "wan" to "万完晚碗玩弯湾丸挽",
            "ling" to "零领另铃玲龄灵岭令",
            "liang" to "两亮辆凉量粮良谅梁",
            "san" to "三散伞叁",
            "si" to "四死丝司私思撕寺似",
            "wu" to "五无午舞屋物务误雾武伍侮污染",
            "liu" to "六流留刘柳溜陆",
            "ba" to "八吧把爸罢拔巴疤霸",
            "jiu" to "九久酒旧救就舅究纠",
        )
        buildMap {
            for ((syllable, chars) in groups) {
                for (ch in chars) {
                    put(ch, syllable)
                }
            }
        }
    }

    fun generateQuestion(
        terms: List<HskTerm>,
        monsterType: MonsterType = MonsterType.NORMAL,
        mistakeWeights: Map<String, Int> = emptyMap(),
        allowSpeaking: Boolean = true,
    ): BattleQuestion? {
        if (terms.size < 4) return null

        val type = selectQuestionType(monsterType, allowSpeaking)
        val initialTarget = selectWeightedTarget(terms, mistakeWeights)

        // For Fill-in-the-Blank (CLOZE_SENTENCE), prefer 2-character compound words or
        // content words (verbs, nouns, adjectives) ~70% of the time so sentences feel natural.
        val target = if (
            type == QuestionType.CLOZE_SENTENCE &&
            (initialTarget.simplified.length < 2 || !isContentWord(initialTarget)) &&
            Random.nextInt(100) < 70
        ) {
            val compoundContentPool = terms.filter { it.simplified.length >= 2 && isContentWord(it) }
            val fallbackPool = if (compoundContentPool.size >= 4) {
                compoundContentPool
            } else {
                terms.filter { it.simplified.length >= 2 || isContentWord(it) }
            }
            if (fallbackPool.isNotEmpty()) {
                selectWeightedTarget(fallbackPool, mistakeWeights)
            } else {
                initialTarget
            }
        } else {
            initialTarget
        }

        val (prompt, subPrompt, getOptionValue) = when (type) {
            QuestionType.ENGLISH_TO_HANZI -> Triple(
                target.english,
                "Select the matching Hanzi character / word",
                { t: HskTerm -> t.simplified },
            )
            QuestionType.HANZI_TO_PINYIN -> Triple(
                target.simplified,
                "Select the correct Pinyin pronunciation",
                { t: HskTerm -> t.pinyin },
            )
            QuestionType.HANZI_TO_ENGLISH -> Triple(
                target.simplified,
                "Pinyin: ${target.pinyin}",
                { t: HskTerm -> t.english },
            )
            QuestionType.LISTENING_TO_ENGLISH -> Triple(
                "🔊 Listen to the Mandarin Word",
                "Tap 🔊 to replay audio, then select the English meaning",
                { t: HskTerm -> t.english },
            )
            QuestionType.LISTENING_TO_HANZI -> Triple(
                "🔊 Listen to the Mandarin Word",
                "Tap 🔊 to replay audio, then select the matching Hanzi",
                { t: HskTerm -> t.simplified },
            )
            QuestionType.SPEAKING_CHALLENGE -> Triple(
                target.simplified,
                "Meaning: ${target.english.take(36)}",
                { t: HskTerm -> t.pinyin },
            )
            QuestionType.CLOZE_SENTENCE -> {
                val (clozeSentence, clozeContext) = buildClozePrompt(target)
                Triple(
                    clozeSentence,
                    clozeContext,
                    { t: HskTerm -> t.simplified },
                )
            }
        }

        val targetOption = getOptionValue(target)

        // Select 3 unique distractors with distinct values from target
        val distractors = terms
            .filter { getOptionValue(it) != targetOption }
            .shuffled()
            .distinctBy { getOptionValue(it) }
            .take(3)
            .map { getOptionValue(it) }

        if (distractors.size < 3) return null

        val allOptions = (distractors + targetOption).shuffled()
        val correctIndex = allOptions.indexOf(targetOption)

        return BattleQuestion(
            type = type,
            prompt = prompt,
            subPrompt = subPrompt,
            options = allOptions,
            correctIndex = correctIndex,
            targetTerm = target,
        )
    }

    private fun isContentWord(term: HskTerm): Boolean {
        val pos = term.partOfSpeech.orEmpty()
        return pos.contains("动") || pos.contains("名") || pos.contains("形")
    }

    /**
     * Selects a vocabulary term, weighting previously missed words ~3-4x higher.
     */
    private fun selectWeightedTarget(
        terms: List<HskTerm>,
        mistakeWeights: Map<String, Int>,
    ): HskTerm {
        if (mistakeWeights.isEmpty()) return terms.random()

        var totalWeight = 0
        val weights = IntArray(terms.size) { index ->
            val count = mistakeWeights[terms[index].simplified] ?: 0
            val w = if (count > 0) 1 + count.coerceIn(1, 6) * 3 else 1
            totalWeight += w
            w
        }

        if (totalWeight <= 0) return terms.random()

        var roll = Random.nextInt(totalWeight)
        for (i in terms.indices) {
            roll -= weights[i]
            if (roll < 0) return terms[i]
        }
        return terms.last()
    }

    /**
     * Chooses a question modality based on the monster rank and speaking setting.
     * - NORMAL monsters test 100% pure Flashwords (Reading ONLY).
     * - ELITE and BOSS monsters share an identical multi-modal mix of Fill-in-the-Blank
     *   (CLOZE_SENTENCE), Listening (Audio -> Hanzi / English), and Speaking 1 word/Hanzi.
     */
    private fun selectQuestionType(
        monsterType: MonsterType,
        allowSpeaking: Boolean,
    ): QuestionType {
        val roll = Random.nextInt(100)
        return when (monsterType) {
            MonsterType.NORMAL -> {
                when {
                    roll < 34 -> QuestionType.ENGLISH_TO_HANZI
                    roll < 67 -> QuestionType.HANZI_TO_PINYIN
                    else -> QuestionType.HANZI_TO_ENGLISH
                }
            }
            MonsterType.ELITE, MonsterType.BOSS -> {
                if (allowSpeaking) {
                    when {
                        // 40% Fill-in-the-blank 1 word / Hanzi
                        roll < 40 -> QuestionType.CLOZE_SENTENCE
                        // 20% Listening -> Hanzi
                        roll < 60 -> QuestionType.LISTENING_TO_HANZI
                        // 15% Listening -> English
                        roll < 75 -> QuestionType.LISTENING_TO_ENGLISH
                        // 25% Speaking 1 word / Hanzi
                        else -> QuestionType.SPEAKING_CHALLENGE
                    }
                } else {
                    when {
                        roll < 55 -> QuestionType.CLOZE_SENTENCE
                        roll < 80 -> QuestionType.LISTENING_TO_HANZI
                        else -> QuestionType.LISTENING_TO_ENGLISH
                    }
                }
            }
        }
    }

    /**
     * Builds a contextual Mandarin cloze sentence and English translation clue
     * based on the target word's part of speech.
     */
    fun buildClozePrompt(target: HskTerm): Pair<String, String> {
        val pos = target.partOfSpeech.orEmpty()
        val shortMeaning = target.english.take(26)

        val templates: List<Pair<String, String>> = when {
            pos.contains("动") -> listOf(
                "今天我想 [ ___ ]。" to "Today I want to [ ___ ] ($shortMeaning).",
                "他每天都会 [ ___ ]。" to "Every day he will [ ___ ] ($shortMeaning).",
                "请帮我 [ ___ ] 一下。" to "Please help me [ ___ ] ($shortMeaning) for a moment.",
                "你会不会 [ ___ ]？" to "Do you know how to [ ___ ] ($shortMeaning)?",
            )
            pos.contains("名") -> listOf(
                "我今天买了一个 [ ___ ]。" to "Today I bought a [ ___ ] ($shortMeaning).",
                "这个 [ ___ ] 非常重要。" to "This [ ___ ] ($shortMeaning) is very important.",
                "你在哪里看到 [ ___ ]？" to "Where did you see the [ ___ ] ($shortMeaning)?",
                "老师给我们介绍了 [ ___ ]。" to "The teacher introduced [ ___ ] ($shortMeaning) to us.",
            )
            pos.contains("形") -> listOf(
                "大家觉得这里很 [ ___ ]。" to "Everyone feels this place is very [ ___ ] ($shortMeaning).",
                "今天的天气真 [ ___ ]！" to "Today's atmosphere is truly [ ___ ] ($shortMeaning)!",
                "这件事看起来非常 [ ___ ]。" to "This matter looks very [ ___ ] ($shortMeaning).",
            )
            else -> listOf(
                "在汉语课上，我们学了「[ ___ ]」。" to "In Mandarin class, we learned '[ ___ ]' ($shortMeaning).",
                "请用「[ ___ ]」完成这句话。" to "Complete the sentence using '[ ___ ]' ($shortMeaning).",
            )
        }

        val index = (target.number.coerceAtLeast(0) + Random.nextInt(templates.size)) % templates.size
        return templates[index]
    }

    /**
     * Evaluates SpeechRecognizer candidates against the target HSK vocabulary term
     * using a 3-tier grading system:
     * 1. EXACT_MATCH: Exact Hanzi (simplified/traditional) or tone-marked Pinyin match.
     * 2. TONE_LENIENT_MATCH: Base Pinyin syllable matches when tone marks are stripped
     *    (including homophone Hanzi characters returned by Android zh-CN STT).
     * 3. NO_MATCH: Spoken audio did not match the target word.
     */
    fun evaluateSpeechRecognition(
        candidates: List<String>,
        target: HskTerm,
        allLevelTerms: List<HskTerm> = emptyList(),
    ): SpeechEvalResult {
        val cleanedCandidates = candidates
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (cleanedCandidates.isEmpty()) {
            return SpeechEvalResult(
                grade = SpeechGrade.NO_MATCH,
                matchedText = "",
                feedback = "Heard ''. Try saying ${target.simplified} (${target.pinyin})!",
            )
        }

        val targetExactPinyin = normalizeExactPinyin(target.pinyin)
        val targetStrippedPinyin = stripTones(target.pinyin)

        // Tier 1: Exact Hanzi (simplified or traditional) or exact tone-marked Pinyin
        for (candidate in cleanedCandidates) {
            val matchesSimplified = candidate.contains(target.simplified)
            val matchesTraditional = !target.traditional.isNullOrBlank() &&
                candidate.contains(target.traditional)
            val matchesExactPinyin = targetExactPinyin.isNotEmpty() &&
                normalizeExactPinyin(candidate) == targetExactPinyin

            if (matchesSimplified || matchesTraditional || matchesExactPinyin) {
                return SpeechEvalResult(
                    grade = SpeechGrade.EXACT_MATCH,
                    matchedText = candidate,
                    feedback = "🎯 Perfect Mandarin pronunciation! (${target.simplified} · ${target.pinyin})",
                )
            }
        }

        // Build character-to-stripped-pinyin and word-to-stripped-pinyin lookup from allLevelTerms
        val wordToStrippedPinyin = HashMap<String, String>(allLevelTerms.size)
        val charToStrippedPinyin = HashMap<Char, String>(COMMON_HOMOPHONE_SYLLABLES)
        for (term in allLevelTerms) {
            val stripped = stripTones(term.pinyin)
            if (stripped.isNotEmpty()) {
                wordToStrippedPinyin[term.simplified] = stripped
                if (!term.traditional.isNullOrBlank()) {
                    wordToStrippedPinyin[term.traditional] = stripped
                }
                if (term.simplified.length == 1) {
                    charToStrippedPinyin[term.simplified[0]] = stripped
                }
            }
        }

        // Tier 2: Tone-lenient syllable match (direct Latin letters or homophone Hanzi conversion)
        if (targetStrippedPinyin.isNotEmpty()) {
            for (candidate in cleanedCandidates) {
                val directStripped = stripTones(candidate)
                if (directStripped == targetStrippedPinyin ||
                    (directStripped.isNotEmpty() && directStripped.contains(targetStrippedPinyin))
                ) {
                    return SpeechEvalResult(
                        grade = SpeechGrade.TONE_LENIENT_MATCH,
                        matchedText = candidate,
                        feedback = "🎤 Good syllable! Watch the tone: ${target.simplified} (${target.pinyin})",
                    )
                }

                val candidateHanziPinyin = convertHanziCandidateToStrippedPinyin(
                    candidate = candidate,
                    wordMap = wordToStrippedPinyin,
                    charMap = charToStrippedPinyin,
                )
                if (candidateHanziPinyin.isNotEmpty() &&
                    (candidateHanziPinyin == targetStrippedPinyin ||
                        candidateHanziPinyin.contains(targetStrippedPinyin))
                ) {
                    return SpeechEvalResult(
                        grade = SpeechGrade.TONE_LENIENT_MATCH,
                        matchedText = candidate,
                        feedback = "🎤 Good syllable! Watch the tone: ${target.simplified} (${target.pinyin})",
                    )
                }
            }
        }

        // Tier 3: No match
        val firstHeard = cleanedCandidates.firstOrNull().orEmpty()
        return SpeechEvalResult(
            grade = SpeechGrade.NO_MATCH,
            matchedText = firstHeard,
            feedback = "Heard '$firstHeard'. Try saying ${target.simplified} (${target.pinyin})!",
        )
    }

    /**
     * Strips tone marks from Pinyin using NFD normalization and keeps only lowercase [a-z0-9].
     */
    fun stripTones(input: String): String {
        val decomposed = Normalizer.normalize(input, Normalizer.Form.NFD)
        val withoutMarks = DIACRITICS_REGEX.replace(decomposed, "")
        return NON_ALPHANUM_REGEX.replace(withoutMarks.lowercase(), "")
    }

    private fun normalizeExactPinyin(input: String): String {
        val nfc = Normalizer.normalize(input.trim().lowercase(), Normalizer.Form.NFC)
        return WHITESPACE_PUNCT_REGEX.replace(nfc, "")
    }

    private fun convertHanziCandidateToStrippedPinyin(
        candidate: String,
        wordMap: Map<String, String>,
        charMap: Map<Char, String>,
    ): String {
        val clean = WHITESPACE_PUNCT_REGEX.replace(candidate, "")
        if (clean.isEmpty()) return ""

        wordMap[clean]?.let { return it }

        val sb = StringBuilder()
        var i = 0
        while (i < clean.length) {
            // Try 2-character word match first if available
            if (i + 2 <= clean.length) {
                val twoChar = clean.substring(i, i + 2)
                val twoCharPinyin = wordMap[twoChar]
                if (twoCharPinyin != null) {
                    sb.append(twoCharPinyin)
                    i += 2
                    continue
                }
            }
            val ch = clean[i]
            val singlePinyin = charMap[ch] ?: wordMap[ch.toString()]
            if (singlePinyin != null) {
                sb.append(singlePinyin)
            } else if (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9') {
                sb.append(ch.lowercaseChar())
            }
            i++
        }
        return sb.toString()
    }
}
