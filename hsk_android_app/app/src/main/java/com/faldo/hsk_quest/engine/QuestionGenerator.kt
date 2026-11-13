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
    WRITING_CHALLENGE("✍️ Writing · Draw the Hanzi"),
    CLOZE_SENTENCE("📜 Elite Story · Complete the Sentence");

    val isListening: Boolean
        get() = this == LISTENING_TO_ENGLISH || this == LISTENING_TO_HANZI

    val isSpeaking: Boolean
        get() = this == SPEAKING_CHALLENGE

    val isWriting: Boolean
        get() = this == WRITING_CHALLENGE

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
    val pinyinHint: String? = null,
    val options: List<String>,
    val correctIndex: Int,
    val targetTerm: HskTerm,
    val optionTerms: List<HskTerm> = emptyList(),
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
    private val ENGLISH_TOKEN_SPLIT_REGEX = Regex("[^a-z]+")

    private enum class SemanticSlot {
        DRINK,
        FOOD,
        VEHICLE,
        PLACE,
        PERSON,
        TIME,
        WEATHER,
        LANGUAGE,
        OBJECT_WEAR,
        OBJECT_READ,
        OBJECT_TECH,
        ANIMAL,
        MONEY,
        COLOR,
        MEASURE,
        PRONOUN,
        CONJUNCTION,
        PARTICLE,
        ADVERB,
        NUMBER,
        VERB_CONSUMPTION,
        VERB_MOTION,
        VERB_STUDY_COMM,
        VERB_MENTAL,
        VERB_PURCHASE,
        VERB_GENERAL,
        ADJ_WEATHER_TEMP,
        ADJ_EMOTION,
        ADJ_APPEARANCE,
        ADJ_GENERAL,
        NOUN_GENERAL,
        OTHER,
    }

    private data class ClozeSpec(
        val sentence: String,
        val pinyin: String,
        val excludedDistractorSlots: Set<SemanticSlot>,
    )

    private val CURATED_CLOZE_BY_HANZI: Map<String, ClozeSpec> by lazy {
        val slotsFn = setOf(SemanticSlot.PARTICLE, SemanticSlot.ADVERB, SemanticSlot.CONJUNCTION, SemanticSlot.PRONOUN, SemanticSlot.MEASURE, SemanticSlot.OTHER)
        mapOf(
            "的" to ClozeSpec("这是我新买 [ ___ ] 汉语词典。", "Zhè shì wǒ xīn mǎi [ ___ ] Hànyǔ cídiǎn.", slotsFn),
            "了" to ClozeSpec("我昨天去商店买 [ ___ ] 两个苹果。", "Wǒ zuótiān qù shāngdiàn mǎi [ ___ ] liǎng gè píngguǒ.", slotsFn),
            "吗" to ClozeSpec("请问，你明天上午有时间 [ ___ ]？", "Qǐngwèn, nǐ míngtiān shàngwǔ yǒu shíjiān [ ___ ]?", slotsFn),
            "呢" to ClozeSpec("我的手机在这里，你的手机 [ ___ ]？", "Wǒ de shǒujī zài zhèlǐ, nǐ de shǒujī [ ___ ]?", slotsFn),
            "吧" to ClozeSpec("外面太冷了，我们快进屋子里 [ ___ ]。", "Wàimiàn tài lěng le, wǒmen kuài jìn wūzi lǐ [ ___ ].", slotsFn),
            "在" to ClozeSpec("小猫正 [ ___ ] 椅子下面睡觉。", "Xiǎomāo zhèng [ ___ ] yǐzi xiàmiàn shuìjiào.", slotsFn + setOf(SemanticSlot.VERB_MOTION)),
            "从" to ClozeSpec("他早上八点 [ ___ ] 家里出发去学校。", "Tā zǎoshang bā diǎn [ ___ ] jiālǐ chūfā qù xuéxiào.", slotsFn),
            "给" to ClozeSpec("请帮我把这杯热茶递 [ ___ ] 爷爷。", "Qǐng bāng wǒ bǎ zhè bēi rè chá dì [ ___ ] yéye.", slotsFn),
            "比" to ClozeSpec("哥哥的个子 [ ___ ] 弟弟高很多。", "Gēge de gèzi [ ___ ] dìdi gāo hěn duō.", slotsFn),
            "因为" to ClozeSpec("[ ___ ] 今天下大雨，我们没有去爬山。", "[ ___ ] jīntiān xià dàyǔ, wǒmen méiyǒu qù páshān.", slotsFn),
            "所以" to ClozeSpec("昨天晚上睡得太晚，[ ___ ] 今天很困。", "Zuótiān wǎnshang shuì de tài wǎn, [ ___ ] jīntiān hěn kùn.", slotsFn),
            "但是" to ClozeSpec("这件衣服很漂亮，[ ___ ] 价格有点贵。", "Zhè jiàn yīfu hěn piàoliang, [ ___ ] jiàgé yǒudiǎn guì.", slotsFn),
            "虽然" to ClozeSpec("[ ___ ] 汉语很难学，但是我觉得很有意思。", "[ ___ ] Hànyǔ hěn nán xué, dànshì wǒ juéde hěn yǒu yìsi.", slotsFn),
            "如果" to ClozeSpec("[ ___ ] 明天天气好，我们就去公园。", "[ ___ ] míngtiān tiānqì hǎo, wǒmen jiù qù gōngyuán.", slotsFn),
            "个" to ClozeSpec("桌子上放着三 [ ___ ] 红苹果。", "Zhuōzi shàng fàngzhe sān [ ___ ] hóng píngguǒ.", slotsFn + setOf(SemanticSlot.NUMBER)),
            "本" to ClozeSpec("书包里装着两 [ ___ ] 汉语书。", "Shūbāo lǐ zhuāngzhe liǎng [ ___ ] Hànyǔ shū.", slotsFn + setOf(SemanticSlot.NUMBER)),
            "杯" to ClozeSpec("请给我倒一 [ ___ ] 温开水。", "Qǐng gěi wǒ dào yì [ ___ ] wēn kāishuǐ.", slotsFn + setOf(SemanticSlot.NUMBER, SemanticSlot.DRINK)),
            "只" to ClozeSpec("树枝上停着两 [ ___ ] 可爱的小鸟。", "Shùzhī shàng tíngzhe liǎng [ ___ ] kě'ài de xiǎoniǎo.", slotsFn + setOf(SemanticSlot.NUMBER)),
            "块" to ClozeSpec("这个面包只要五 [ ___ ] 钱。", "Zhège miànbāo zhǐyào wǔ [ ___ ] qián.", slotsFn + setOf(SemanticSlot.NUMBER, SemanticSlot.MONEY)),
            "岁" to ClozeSpec("我妹妹今年已经八 [ ___ ] 了。", "Wǒ mèimei jīnnián yǐjīng bā [ ___ ] le.", slotsFn + setOf(SemanticSlot.NUMBER, SemanticSlot.TIME)),
            "几" to ClozeSpec("请问现在是下午 [ ___ ] 点？", "Qǐngwèn xiànzài shì xiàwǔ [ ___ ] diǎn?", slotsFn + setOf(SemanticSlot.NUMBER)),
            "多少" to ClozeSpec("你们班一共有 [ ___ ] 个学生？", "Nǐmen bān yígòng yǒu [ ___ ] gè xuésheng?", slotsFn + setOf(SemanticSlot.NUMBER)),
            "怎么" to ClozeSpec("这道题太难了，我不知道 [ ___ ] 做。", "Zhè dào tí tài nán le, wǒ bù zhīdào [ ___ ] zuò.", slotsFn),
            "什么" to ClozeSpec("你周末通常喜欢做 [ ___ ] 运动？", "Nǐ zhōumò tōngcháng xǐhuan zuò [ ___ ] yùndòng?", slotsFn),
            "谁" to ClozeSpec("门口敲门的那位客人是 [ ___ ]？", "Ménkǒu qiāomén de nà wèi kèrén shì [ ___ ]?", slotsFn + setOf(SemanticSlot.PERSON)),
            "哪儿" to ClozeSpec("请问洗手间在 [ ___ ]？", "Qǐngwèn xǐshǒujiān zài [ ___ ]?", slotsFn + setOf(SemanticSlot.PLACE)),
            "哪里" to ClozeSpec("请问附近的地铁站在 [ ___ ]？", "Qǐngwèn fùjìn de dìtiězhàn zài [ ___ ]?", slotsFn + setOf(SemanticSlot.PLACE)),
            "很" to ClozeSpec("这里的风景 [ ___ ] 漂亮，大家都很喜欢。", "Zhèlǐ de fēngjǐng [ ___ ] piàoliang, dàjiā dōu hěn xǐhuan.", slotsFn),
            "太" to ClozeSpec("今天的汤 [ ___ ] 热了，等凉一点再喝。", "Jīntiān de tāng [ ___ ] rè le, děng liáng yìdiǎn zài hē.", slotsFn),
            "都" to ClozeSpec("我们全班同学 [ ___ ] 喜欢上汉语课。", "Wǒmen quánbān tóngxué [ ___ ] xǐhuan shàng Hànyǔ kè.", slotsFn),
            "也" to ClozeSpec("姐姐喜欢画画，我 [ ___ ] 喜欢画画。", "Jiějie xǐhuan huàhuà, wǒ [ ___ ] xǐhuan huàhuà.", slotsFn),
            "已经" to ClozeSpec("八点半了，电影 [ ___ ] 开始了。", "Bā diǎn bàn le, diànyǐng [ ___ ] kāishǐ le.", slotsFn),
            "正在" to ClozeSpec("老师 [ ___ ] 教室里给同学们讲故事。", "Lǎoshī [ ___ ] jiàoshì lǐ gěi tóngxuémen jiǎng gùshi.", slotsFn),
            "没有" to ClozeSpec("我今天早上 [ ___ ] 吃早饭就出门了。", "Wǒ jīntiān zǎoshang [ ___ ] chī zǎofàn jiù chūmén le.", slotsFn),
        )
    }

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
        val target = selectTargetForQuestionType(type, terms, mistakeWeights)

        if (type == QuestionType.WRITING_CHALLENGE) {
            val cleanMeaning = target.english.take(72)
            return BattleQuestion(
                type = QuestionType.WRITING_CHALLENGE,
                prompt = cleanMeaning,
                subPrompt = "Draw the Hanzi for: ${target.pinyin}",
                pinyinHint = "Trace Guide: ${target.simplified} (${target.pinyin})",
                options = listOf(target.simplified),
                correctIndex = 0,
                targetTerm = target,
            )
        }

        var pinyinHint: String? = null
        var activeClozeSpec: ClozeSpec? = null
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
            QuestionType.HANZI_TO_ENGLISH -> {
                pinyinHint = "Pinyin: ${target.pinyin}"
                Triple(
                    target.simplified,
                    "Select the correct English meaning",
                    { t: HskTerm -> t.english },
                )
            }
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
            QuestionType.SPEAKING_CHALLENGE -> {
                pinyinHint = "Pinyin: ${target.pinyin}"
                Triple(
                    target.simplified,
                    "Meaning: ${target.english.take(36)}",
                    { t: HskTerm -> t.pinyin },
                )
            }
            QuestionType.CLOZE_SENTENCE -> {
                val spec = buildClozeSpec(target)
                activeClozeSpec = spec
                pinyinHint = "Sentence Pinyin: ${spec.pinyin}"
                Triple(
                    spec.sentence,
                    "Complete the Mandarin sentence (Select the missing Hanzi)",
                    { t: HskTerm -> t.simplified },
                )
            }
            QuestionType.WRITING_CHALLENGE -> error("Handled above")
        }

        val targetOption = getOptionValue(target)
        val candidateDistractorTerms = if (type == QuestionType.CLOZE_SENTENCE && activeClozeSpec != null) {
            selectClozeDistractorPool(target, terms, activeClozeSpec)
        } else {
            terms.filter { getOptionValue(it) != targetOption }
        }

        val distractorTerms = candidateDistractorTerms
            .shuffled()
            .distinctBy { getOptionValue(it) }
            .take(3)

        if (distractorTerms.size < 3) return null

        val combinedTerms = (distractorTerms + target).shuffled()
        val allOptions = combinedTerms.map { getOptionValue(it) }
        val correctIndex = combinedTerms.indexOfFirst { getOptionValue(it) == targetOption }

        return BattleQuestion(
            type = type,
            prompt = prompt,
            subPrompt = subPrompt,
            pinyinHint = pinyinHint,
            options = allOptions,
            correctIndex = correctIndex,
            targetTerm = target,
            optionTerms = combinedTerms,
        )
    }

    private fun selectTargetForQuestionType(
        type: QuestionType,
        terms: List<HskTerm>,
        mistakeWeights: Map<String, Int>,
    ): HskTerm {
        if (type == QuestionType.WRITING_CHALLENGE) {
            val handwrittenSingle = terms.filter { it.handwritten && it.simplified.length == 1 }
            val handwrittenShort = terms.filter { it.handwritten && it.simplified.length <= 2 }
            val singleCharPool = terms.filter { it.simplified.length == 1 }
            val writingPool = when {
                handwrittenSingle.size >= 4 -> handwrittenSingle
                handwrittenShort.size >= 4 -> handwrittenShort
                singleCharPool.size >= 4 -> singleCharPool
                else -> terms.filter { it.simplified.length <= 2 }.ifEmpty { terms }
            }
            return selectWeightedTarget(writingPool, mistakeWeights)
        }

        if (type == QuestionType.CLOZE_SENTENCE) {
            val curatedOrSpecific = terms.filter { isSpecificClozeCandidate(it) }
            if (curatedOrSpecific.size >= 4) {
                return selectWeightedTarget(curatedOrSpecific, mistakeWeights)
            }
            val compoundContentPool = terms.filter { it.simplified.length >= 2 && isContentWord(it) }
            if (compoundContentPool.size >= 4) {
                return selectWeightedTarget(compoundContentPool, mistakeWeights)
            }
        }

        return selectWeightedTarget(terms, mistakeWeights)
    }

    private fun isSpecificClozeCandidate(term: HskTerm): Boolean {
        if (CURATED_CLOZE_BY_HANZI.containsKey(term.simplified)) return true
        return when (classifySemanticSlot(term)) {
            SemanticSlot.NOUN_GENERAL,
            SemanticSlot.VERB_GENERAL,
            SemanticSlot.ADJ_GENERAL,
            SemanticSlot.OTHER,
            -> false
            else -> true
        }
    }

    private fun selectClozeDistractorPool(
        target: HskTerm,
        terms: List<HskTerm>,
        spec: ClozeSpec,
    ): List<HskTerm> {
        val targetPos = primaryPosTag(target)
        val targetTokens = englishKeywordTokens(target.english)

        fun sharesEnglishConcept(other: HskTerm): Boolean {
            if (targetTokens.isEmpty()) return false
            val otherTokens = englishKeywordTokens(other.english)
            return targetTokens.any { it in otherTokens }
        }

        // Tier 1: Strictly exclude compatible semantic slots, same POS, and overlapping meanings
        val strictPool = terms.filter { candidate ->
            if (candidate.simplified == target.simplified) return@filter false
            val slot = classifySemanticSlot(candidate)
            if (slot in spec.excludedDistractorSlots) return@filter false
            if (targetPos != null && primaryPosTag(candidate) == targetPos) return@filter false
            !sharesEnglishConcept(candidate)
        }
        if (strictPool.size >= 3) return strictPool

        // Tier 2: Relax same-POS rule while preserving strict semantic exclusion
        val semanticPool = terms.filter { candidate ->
            candidate.simplified != target.simplified &&
                classifySemanticSlot(candidate) !in spec.excludedDistractorSlots &&
                !sharesEnglishConcept(candidate)
        }
        if (semanticPool.size >= 3) return semanticPool

        return terms.filter { it.simplified != target.simplified }
    }

    private fun englishKeywordTokens(english: String): Set<String> {
        val stopWords = setOf(
            "the", "and", "for", "with", "from", "that", "this", "one", "into",
            "to", "be", "of", "in", "on", "at", "or", "by", "as", "an", "used",
            "particle", "indicating", "classifier", "measure", "word",
        )
        return ENGLISH_TOKEN_SPLIT_REGEX
            .split(english.lowercase())
            .filter { it.length >= 3 && it !in stopWords }
            .toSet()
    }

    private fun primaryPosTag(term: HskTerm): String? {
        val pos = term.partOfSpeech.orEmpty()
        return when {
            pos.contains("动") -> "verb"
            pos.contains("名") -> "noun"
            pos.contains("形") -> "adj"
            else -> null
        }
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
     * - ELITE and BOSS monsters test Cloze, Writing, Listening, and Speaking challenges.
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
                        // 25% Cloze, 20% Writing, 20% Listening->Hanzi, 15% Listening->English, 20% Speaking
                        roll < 25 -> QuestionType.CLOZE_SENTENCE
                        roll < 45 -> QuestionType.WRITING_CHALLENGE
                        roll < 65 -> QuestionType.LISTENING_TO_HANZI
                        roll < 80 -> QuestionType.LISTENING_TO_ENGLISH
                        else -> QuestionType.SPEAKING_CHALLENGE
                    }
                } else {
                    when {
                        roll < 35 -> QuestionType.CLOZE_SENTENCE
                        roll < 60 -> QuestionType.WRITING_CHALLENGE
                        roll < 85 -> QuestionType.LISTENING_TO_HANZI
                        else -> QuestionType.LISTENING_TO_ENGLISH
                    }
                }
            }
        }
    }

    /**
     * Builds a contextual Mandarin cloze sentence and its corresponding sentence Pinyin
     * (without any parenthetical English answer spoilers).
     */
    fun buildClozePrompt(target: HskTerm): Pair<String, String> {
        val spec = buildClozeSpec(target)
        return spec.sentence to spec.pinyin
    }

    private fun buildClozeSpec(target: HskTerm): ClozeSpec {
        CURATED_CLOZE_BY_HANZI[target.simplified]?.let { return it }

        val slot = classifySemanticSlot(target)
        val nonNounSlots = setOf(SemanticSlot.NOUN_GENERAL, SemanticSlot.OTHER)
        val templates: List<ClozeSpec> = when (slot) {
            SemanticSlot.DRINK -> {
                val ex = setOf(SemanticSlot.DRINK, SemanticSlot.FOOD) + nonNounSlots
                listOf(
                    ClozeSpec("我口渴了，想喝一杯 [ ___ ]。", "Wǒ kǒukě le, xiǎng hē yì bēi [ ___ ].", ex),
                    ClozeSpec("服务员，请给我来一瓶 [ ___ ]。", "Fúwùyuán, qǐng gěi wǒ lái yì píng [ ___ ].", ex),
                )
            }
            SemanticSlot.FOOD -> {
                val ex = setOf(SemanticSlot.FOOD, SemanticSlot.DRINK) + nonNounSlots
                listOf(
                    ClozeSpec("中午肚子饿了，我想吃点 [ ___ ]。", "Zhōngwǔ dùzi è le, wǒ xiǎng chī diǎn [ ___ ].", ex),
                    ClozeSpec("妈妈在厨房准备了好吃的 [ ___ ]。", "Māma zài chúfáng zhǔnbèi le hǎochī de [ ___ ].", ex),
                )
            }
            SemanticSlot.VEHICLE -> {
                val ex = setOf(SemanticSlot.VEHICLE, SemanticSlot.PLACE) + nonNounSlots
                listOf(
                    ClozeSpec("他每天早上坐 [ ___ ] 去公司上班。", "Tā měitiān zǎoshang zuò [ ___ ] qù gōngsī shàngbān.", ex),
                    ClozeSpec("去那座城市坐 [ ___ ] 非常方便。", "Qù nà zuò chéngshì zuò [ ___ ] fēicháng fāngbiàn.", ex),
                )
            }
            SemanticSlot.PLACE -> {
                val ex = setOf(SemanticSlot.PLACE, SemanticSlot.TIME) + nonNounSlots
                listOf(
                    ClozeSpec("我现在就在 [ ___ ] 里面等你。", "Wǒ xiànzài jiù zài [ ___ ] lǐmiàn děng nǐ.", ex),
                    ClozeSpec("明天上午我要去 [ ___ ] 办一件事情。", "Míngtiān shàngwǔ wǒ yào qù [ ___ ] bàn yí jiàn shìqing.", ex),
                )
            }
            SemanticSlot.PERSON -> {
                val ex = setOf(SemanticSlot.PERSON, SemanticSlot.PRONOUN, SemanticSlot.ANIMAL) + nonNounSlots
                listOf(
                    ClozeSpec("那位正在说话的 [ ___ ] 对大家很客气。", "Nà wèi zhèngzài shuōhuà de [ ___ ] duì dàjiā hěn kèqi.", ex),
                    ClozeSpec("今天下午我们要去看望那位 [ ___ ]。", "Jīntiān xiàwǔ wǒmen yào qù kànwàng nà wèi [ ___ ].", ex),
                )
            }
            SemanticSlot.TIME -> {
                val ex = setOf(SemanticSlot.TIME, SemanticSlot.NUMBER) + nonNounSlots
                listOf(
                    ClozeSpec("[ ___ ] 下午三点，我们在门前集合吧。", "[ ___ ] xiàwǔ sāndiǎn, wǒmen zài ménqián jíhé ba.", ex),
                    ClozeSpec("我们约好了 [ ___ ] 一起去吃晚饭。", "Wǒmen yuēhǎo le [ ___ ] yìqǐ qù chī wǎnfàn.", ex),
                )
            }
            SemanticSlot.WEATHER -> {
                val ex = setOf(SemanticSlot.WEATHER, SemanticSlot.ADJ_WEATHER_TEMP, SemanticSlot.TIME) + nonNounSlots
                listOf(
                    ClozeSpec("你看窗外，最近的 [ ___ ] 变化真大！", "Nǐ kàn chuāngwài, zuìjìn de [ ___ ] biànhuà zhēn dà!", ex),
                    ClozeSpec("出门前先看一看外面的 [ ___ ] 吧。", "Chūmén qián xiān kàn yi kàn wàimiàn de [ ___ ] ba.", ex),
                )
            }
            SemanticSlot.LANGUAGE -> {
                val ex = setOf(SemanticSlot.LANGUAGE, SemanticSlot.OBJECT_READ) + nonNounSlots
                listOf(
                    ClozeSpec("越来越多的留学生喜欢学习 [ ___ ]。", "Yuèláiyuè duō de liúxuéshēng xǐhuan xuéxí [ ___ ].", ex),
                    ClozeSpec("她能流利地用 [ ___ ] 和朋友交流。", "Tā néng liúlì de yòng [ ___ ] hé péngyou jiāoliú.", ex),
                )
            }
            SemanticSlot.OBJECT_WEAR -> {
                val ex = setOf(SemanticSlot.OBJECT_WEAR, SemanticSlot.COLOR) + nonNounSlots
                listOf(
                    ClozeSpec("天气转凉了，出门记得穿好这件 [ ___ ]。", "Tiānqì zhuǎn liáng le, chūmén jìde chuānhǎo zhè jiàn [ ___ ].", ex),
                )
            }
            SemanticSlot.OBJECT_READ -> {
                val ex = setOf(SemanticSlot.OBJECT_READ, SemanticSlot.OBJECT_TECH, SemanticSlot.LANGUAGE) + nonNounSlots
                listOf(
                    ClozeSpec("晚上睡前我喜欢安静地读一会儿 [ ___ ]。", "Wǎnshang shuìqián wǒ xǐhuan ānjìng de dú yíhuìr [ ___ ].", ex),
                )
            }
            SemanticSlot.OBJECT_TECH -> {
                val ex = setOf(SemanticSlot.OBJECT_TECH, SemanticSlot.OBJECT_READ) + nonNounSlots
                listOf(
                    ClozeSpec("桌上放着一台刚买的新 [ ___ ]。", "Zhuōshàng fàngzhe yì tái gāng mǎi de xīn [ ___ ].", ex),
                )
            }
            SemanticSlot.ANIMAL -> {
                val ex = setOf(SemanticSlot.ANIMAL, SemanticSlot.PERSON) + nonNounSlots
                listOf(
                    ClozeSpec("草地上跑着一只非常可爱的 [ ___ ]。", "Cǎodì shàng pǎozhe yì zhī fēicháng kě'ài de [ ___ ].", ex),
                )
            }
            SemanticSlot.MONEY -> {
                val ex = setOf(SemanticSlot.MONEY, SemanticSlot.NUMBER, SemanticSlot.MEASURE) + nonNounSlots
                listOf(
                    ClozeSpec("去超市买东西前，记得带上足够的 [ ___ ]。", "Qù chāoshì mǎi dōngxi qián, jìde dài shàng zúgòu de [ ___ ].", ex),
                )
            }
            SemanticSlot.COLOR -> {
                val ex = setOf(SemanticSlot.COLOR, SemanticSlot.ADJ_APPEARANCE, SemanticSlot.ADJ_GENERAL) + nonNounSlots
                listOf(
                    ClozeSpec("她特别喜欢这朵花明亮的 [ ___ ]。", "Tā tèbié xǐhuan zhè duǒ huā míngliàng de [ ___ ].", ex),
                )
            }
            SemanticSlot.MEASURE -> {
                val ex = setOf(SemanticSlot.MEASURE, SemanticSlot.NUMBER, SemanticSlot.PARTICLE) + nonNounSlots
                listOf(
                    ClozeSpec("柜台上面整整齐齐地摆着三 [ ___ ] 物品。", "Guìtái shàngmiàn zhěngzhěngqíqí de bǎizhe sān [ ___ ] wùpǐn.", ex),
                )
            }
            SemanticSlot.PRONOUN -> {
                val ex = setOf(SemanticSlot.PRONOUN, SemanticSlot.PERSON, SemanticSlot.PLACE, SemanticSlot.PARTICLE)
                listOf(
                    ClozeSpec("刚才敲门进来的就是 [ ___ ]。", "Gāngcái qiāomén jìnlái de jiùshì [ ___ ].", ex),
                )
            }
            SemanticSlot.CONJUNCTION -> {
                val ex = setOf(SemanticSlot.CONJUNCTION, SemanticSlot.PARTICLE, SemanticSlot.ADVERB)
                listOf(
                    ClozeSpec("今天风很大，[ ___ ] 我们还是按时到达了。", "Jīntiān fēng hěn dà, [ ___ ] wǒmen háishì ànshí dàodá le.", ex),
                )
            }
            SemanticSlot.NUMBER -> {
                val ex = setOf(SemanticSlot.NUMBER, SemanticSlot.MEASURE, SemanticSlot.TIME)
                listOf(
                    ClozeSpec("这个班一共来了 [ ___ ] 位新同学。", "Zhège bān yígòng lái le [ ___ ] wèi xīn tóngxué.", ex),
                )
            }
            SemanticSlot.ADVERB -> {
                val ex = setOf(SemanticSlot.ADVERB, SemanticSlot.CONJUNCTION, SemanticSlot.PARTICLE, SemanticSlot.ADJ_GENERAL)
                listOf(
                    ClozeSpec("这周的作业他 [ ___ ] 认真地完成了。", "Zhè zhōu de zuòyè tā [ ___ ] rènzhēn de wánchéng le.", ex),
                )
            }
            SemanticSlot.VERB_CONSUMPTION -> {
                val ex = setOf(SemanticSlot.VERB_CONSUMPTION, SemanticSlot.VERB_GENERAL, SemanticSlot.VERB_PURCHASE)
                listOf(
                    ClozeSpec("客人来了，请赶快先 [ ___ ] 一点吧。", "Kèrén lái le, qǐng gǎnkuài xiān [ ___ ] yìdiǎn ba.", ex),
                )
            }
            SemanticSlot.VERB_MOTION -> {
                val ex = setOf(SemanticSlot.VERB_MOTION, SemanticSlot.VERB_GENERAL)
                listOf(
                    ClozeSpec("时间不早了，我们现在就 [ ___ ] 图书馆吧。", "Shíjiān bù zǎo le, wǒmen xiànzài jiù [ ___ ] túshūguǎn ba.", ex),
                )
            }
            SemanticSlot.VERB_STUDY_COMM -> {
                val ex = setOf(SemanticSlot.VERB_STUDY_COMM, SemanticSlot.VERB_MENTAL, SemanticSlot.VERB_GENERAL)
                listOf(
                    ClozeSpec("每天早上我都会认真 [ ___ ] 新的生词。", "Měitiān zǎoshang wǒ dōu huì rènzhēn [ ___ ] xīn de shēngcí.", ex),
                )
            }
            SemanticSlot.VERB_MENTAL -> {
                val ex = setOf(SemanticSlot.VERB_MENTAL, SemanticSlot.VERB_STUDY_COMM, SemanticSlot.VERB_GENERAL)
                listOf(
                    ClozeSpec("我非常 [ ___ ] 今天在课堂上学到的知识。", "Wǒ fēicháng [ ___ ] jīntiān zài kètáng shàng xuédào de zhīshi.", ex),
                )
            }
            SemanticSlot.VERB_PURCHASE -> {
                val ex = setOf(SemanticSlot.VERB_PURCHASE, SemanticSlot.VERB_CONSUMPTION, SemanticSlot.VERB_GENERAL)
                listOf(
                    ClozeSpec("周末去市场的时候我想 [ ___ ] 几件日用品。", "Zhōumò qù shìchǎng de shíhou wǒ xiǎng [ ___ ] jǐ jiàn rìyòngpǐn.", ex),
                )
            }
            SemanticSlot.ADJ_WEATHER_TEMP -> {
                val ex = setOf(SemanticSlot.ADJ_WEATHER_TEMP, SemanticSlot.ADJ_GENERAL, SemanticSlot.ADJ_APPEARANCE, SemanticSlot.ADJ_EMOTION)
                listOf(
                    ClozeSpec("今天的风吹得人觉得天气真 [ ___ ]！", "Jīntiān de fēng chuī de rén juéde tiānqì zhēn [ ___ ]!", ex),
                )
            }
            SemanticSlot.ADJ_EMOTION -> {
                val ex = setOf(SemanticSlot.ADJ_EMOTION, SemanticSlot.ADJ_GENERAL, SemanticSlot.ADJ_APPEARANCE, SemanticSlot.ADJ_WEATHER_TEMP, SemanticSlot.ADVERB)
                listOf(
                    ClozeSpec("听到这个好消息后，大家心里都非常 [ ___ ]。", "Tīngdào zhège hǎo xiāoxi hòu, dàjiā xīnlǐ dōu fēicháng [ ___ ].", ex),
                )
            }
            SemanticSlot.ADJ_APPEARANCE, SemanticSlot.ADJ_GENERAL -> {
                val ex = setOf(SemanticSlot.ADJ_APPEARANCE, SemanticSlot.ADJ_GENERAL, SemanticSlot.ADJ_EMOTION, SemanticSlot.ADJ_WEATHER_TEMP, SemanticSlot.ADVERB)
                listOf(
                    ClozeSpec("大家都觉得这个地方看起来非常 [ ___ ]。", "Dàjiā dōu juéde zhège dìfang kàn qǐlái fēicháng [ ___ ].", ex),
                    ClozeSpec("这件东西做得很用心，真的特别 [ ___ ]。", "Zhè jiàn dōngxi zuò de hěn yòngxīn, zhēnde tèbié [ ___ ].", ex),
                )
            }
            SemanticSlot.VERB_GENERAL -> {
                val ex = setOf(
                    SemanticSlot.VERB_GENERAL, SemanticSlot.VERB_CONSUMPTION, SemanticSlot.VERB_MOTION,
                    SemanticSlot.VERB_STUDY_COMM, SemanticSlot.VERB_MENTAL, SemanticSlot.VERB_PURCHASE,
                )
                listOf(
                    ClozeSpec("每天早上我们都可以试着 [ ___ ] 一下。", "Měitiān zǎoshang wǒmen dōu kěyǐ shìzhe [ ___ ] yíxià.", ex),
                )
            }
            SemanticSlot.NOUN_GENERAL, SemanticSlot.PARTICLE, SemanticSlot.OTHER -> {
                val ex = setOf(
                    SemanticSlot.NOUN_GENERAL, SemanticSlot.OTHER, SemanticSlot.PLACE, SemanticSlot.PERSON,
                    SemanticSlot.DRINK, SemanticSlot.FOOD, SemanticSlot.OBJECT_READ, SemanticSlot.OBJECT_TECH, SemanticSlot.OBJECT_WEAR,
                )
                listOf(
                    ClozeSpec("老师刚才指着桌上的那个 [ ___ ] 给大家看。", "Lǎoshī gāngcái zhǐzhe zhuōshàng de nàge [ ___ ] gěi dàjiā kàn.", ex),
                )
            }
        }

        val index = (target.number.coerceAtLeast(0) + Random.nextInt(templates.size)) % templates.size
        return templates[index]
    }

    private fun classifySemanticSlot(term: HskTerm): SemanticSlot {
        val hanzi = term.simplified
        val pos = term.partOfSpeech.orEmpty()
        val tokens = ENGLISH_TOKEN_SPLIT_REGEX.split(term.english.lowercase()).toSet()
        val engLower = term.english.lowercase()

        fun hasAny(vararg words: String): Boolean = words.any { it in tokens }

        if (pos.contains("连") || hanzi in setOf("因为", "所以", "但是", "虽然", "而且", "如果", "或者", "还是")) {
            return SemanticSlot.CONJUNCTION
        }
        if (pos.contains("助") || hanzi in setOf("的", "了", "吗", "呢", "吧", "过", "着", "得", "地")) {
            return SemanticSlot.PARTICLE
        }
        if (pos.contains("量") && !pos.contains("名")) {
            return SemanticSlot.MEASURE
        }
        if (pos.contains("代")) {
            return SemanticSlot.PRONOUN
        }
        if (pos.contains("数") && !pos.contains("名") && !pos.contains("动")) {
            return SemanticSlot.NUMBER
        }
        if (pos.contains("副") && !pos.contains("形") && !pos.contains("动")) {
            return SemanticSlot.ADVERB
        }

        // Verbs
        if (pos.contains("动")) {
            return when {
                hanzi in setOf("吃", "喝") || hasAny("eat", "drink") -> SemanticSlot.VERB_CONSUMPTION
                hanzi in setOf("去", "来", "回", "到", "走", "跑", "进", "出") ||
                    hasAny("go", "come", "return", "arrive", "walk", "run", "enter") -> SemanticSlot.VERB_MOTION
                hanzi in setOf("学", "学习", "读", "写", "说", "听", "看", "问", "回答", "教", "介绍") ||
                    hasAny("study", "learn", "read", "write", "speak", "listen", "watch", "ask", "answer", "teach") -> SemanticSlot.VERB_STUDY_COMM
                hanzi in setOf("喜欢", "爱", "想", "要", "希望", "觉得", "知道", "认识", "忘记") ||
                    hasAny("like", "love", "want", "hope", "think", "feel", "know", "remember", "forget") -> SemanticSlot.VERB_MENTAL
                hanzi in setOf("买", "卖", "花") || hasAny("buy", "purchase", "sell", "spend") -> SemanticSlot.VERB_PURCHASE
                else -> SemanticSlot.VERB_GENERAL
            }
        }

        // Adjectives
        if (pos.contains("形")) {
            return when {
                hanzi in setOf("冷", "热", "暖和", "凉快", "晴") ||
                    hasAny("cold", "hot", "warm", "cool", "sunny") -> SemanticSlot.ADJ_WEATHER_TEMP
                hanzi in setOf("高兴", "开心", "快乐", "难过", "生气", "紧张", "累", "忙") ||
                    hasAny("happy", "glad", "joyful", "sad", "angry", "nervous", "tired", "busy") -> SemanticSlot.ADJ_EMOTION
                hanzi in setOf("漂亮", "好看", "干净", "帅", "可爱", "大", "小", "高", "矮", "新", "旧", "好", "坏", "贵", "便宜", "快", "慢") ||
                    hasAny("beautiful", "pretty", "clean", "handsome", "cute", "big", "small", "tall", "new", "old", "good", "bad", "expensive", "cheap", "fast", "slow") -> SemanticSlot.ADJ_APPEARANCE
                else -> SemanticSlot.ADJ_GENERAL
            }
        }

        // Nouns (and mixed semantic nouns)
        return when {
            hanzi in setOf("水", "茶", "咖啡", "牛奶", "酒", "果汁", "饮料") ||
                hasAny("water", "tea", "coffee", "milk", "juice", "wine", "beer", "beverage", "drink") -> SemanticSlot.DRINK
            hanzi in setOf("饭", "米饭", "苹果", "水果", "面包", "菜", "肉", "鸡蛋", "包子", "饺子", "面条", "蛋糕", "早饭", "午饭", "晚饭") ||
                hasAny("food", "rice", "apple", "fruit", "bread", "meat", "egg", "noodle", "soup", "cake", "dish", "meal", "breakfast", "lunch", "dinner", "banana") -> SemanticSlot.FOOD
            hanzi in setOf("车", "出租车", "火车", "飞机", "公共汽车", "地铁", "船", "自行车") ||
                hasAny("car", "taxi", "train", "airplane", "plane", "bus", "subway", "bicycle", "bike", "boat", "ship", "vehicle") -> SemanticSlot.VEHICLE
            hanzi in setOf("家", "学校", "医院", "商店", "饭店", "银行", "机场", "火车站", "房间", "教室", "办公室", "公司", "公园", "超市", "图书馆", "中国", "北京") ||
                hasAny("school", "hospital", "shop", "store", "restaurant", "hotel", "bank", "airport", "station", "room", "classroom", "office", "company", "park", "library", "china", "beijing", "home", "city", "country") -> SemanticSlot.PLACE
            hanzi in setOf("爸爸", "妈妈", "哥哥", "姐姐", "弟弟", "妹妹", "老师", "学生", "朋友", "同学", "医生", "先生", "小姐", "儿子", "女儿", "孩子", "人", "病人") ||
                hasAny("father", "dad", "mother", "mom", "brother", "sister", "teacher", "student", "friend", "classmate", "doctor", "mister", "miss", "son", "daughter", "child", "person", "people", "worker", "boss") -> SemanticSlot.PERSON
            pos.contains("时间") || hanzi in setOf("今天", "明天", "昨天", "现在", "早上", "晚上", "中午", "上午", "下午", "年", "月", "日", "星期", "小时", "分钟", "时候", "周末") ||
                hasAny("today", "tomorrow", "yesterday", "now", "morning", "evening", "night", "noon", "afternoon", "year", "month", "week", "hour", "minute", "weekend", "monday", "sunday") -> SemanticSlot.TIME
            hanzi in setOf("天气", "风", "雨", "雪", "太阳", "云") ||
                hasAny("weather", "wind", "rain", "snow", "sun", "cloud") -> SemanticSlot.WEATHER
            hanzi in setOf("汉语", "中文", "英语", "外语", "日语") ||
                (engLower.contains("language") || hasAny("chinese", "english", "japanese")) -> SemanticSlot.LANGUAGE
            hanzi in setOf("衣服", "裤子", "鞋", "帽子", "裙子", "衬衫") ||
                hasAny("clothes", "clothing", "shirt", "pants", "trousers", "shoes", "hat", "skirt", "coat", "dress") -> SemanticSlot.OBJECT_WEAR
            hanzi in setOf("书", "报纸", "杂志", "信", "本子", "词典") ||
                hasAny("book", "newspaper", "magazine", "letter", "notebook", "dictionary") -> SemanticSlot.OBJECT_READ
            hanzi in setOf("手机", "电脑", "电视", "电话", "照相机") ||
                hasAny("phone", "smartphone", "mobile", "computer", "laptop", "television", "telephone", "camera") -> SemanticSlot.OBJECT_TECH
            hanzi in setOf("猫", "狗", "鸟", "马", "牛", "羊", "鱼") ||
                hasAny("cat", "dog", "bird", "horse", "cow", "sheep", "fish", "animal") -> SemanticSlot.ANIMAL
            hanzi in setOf("钱", "元", "人民币") || hasAny("money", "dollar", "yuan", "cash") -> SemanticSlot.MONEY
            hanzi in setOf("颜色", "红", "黑", "白", "绿", "蓝", "黄") || hasAny("color", "colour", "red", "blue", "green", "yellow", "black", "white") -> SemanticSlot.COLOR
            pos.contains("名") -> SemanticSlot.NOUN_GENERAL
            else -> SemanticSlot.OTHER
        }
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

        // Build character-to-pinyin lookup maps from allLevelTerms
        val wordToStrippedPinyin = HashMap<String, String>(allLevelTerms.size)
        val charToStrippedPinyin = HashMap<Char, String>(COMMON_HOMOPHONE_SYLLABLES)
        val charToTonePinyin = HashMap<Char, String>()
        for (term in allLevelTerms) {
            if (term.pinyin.isNotBlank() && term.simplified.length == 1) {
                charToTonePinyin.putIfAbsent(term.simplified[0], term.pinyin.trim())
            }
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
        val heardPinyin = firstHeard.mapNotNull { ch ->
            charToTonePinyin[ch] ?: charToStrippedPinyin[ch]
        }.joinToString(" ")
        val heardDisplay = if (heardPinyin.isNotEmpty()) {
            "'$firstHeard' ($heardPinyin)"
        } else {
            "'$firstHeard'"
        }
        return SpeechEvalResult(
            grade = SpeechGrade.NO_MATCH,
            matchedText = firstHeard,
            feedback = "❌ Heard $heardDisplay — Expected: ${target.simplified} (${target.pinyin} · ${target.english})",
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
