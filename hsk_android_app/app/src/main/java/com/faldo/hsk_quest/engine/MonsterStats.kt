package com.faldo.hsk_quest.engine

import com.faldo.hsk_quest.data.model.Monster
import com.faldo.hsk_quest.data.model.MonsterType
import com.faldo.hsk_quest.util.Constants

/**
 * Monster stat templates per HSK area plus progressive sub-stage scaling and elite/boss multipliers.
 * Mirrors MONSTER_BASE_STATS and get_monster_stats() in backend combat.py.
 */
object MonsterStats {

    private data class Base(val hp: Int, val def: Int, val dmg: Int, val xp: Int, val coins: Int)

    private val BASE_STATS = mapOf(
        1 to Base(80, 1, 12, 20, 15),
        2 to Base(140, 3, 18, 35, 25),
        3 to Base(230, 5, 26, 55, 40),
        4 to Base(360, 8, 36, 80, 60),
        5 to Base(540, 11, 48, 115, 85),
        6 to Base(780, 15, 64, 160, 120),
        7 to Base(1080, 20, 85, 220, 165),
        8 to Base(1450, 26, 115, 300, 225),
        9 to Base(1950, 34, 155, 420, 320),
    )

    private const val ELITE_HP_MULT = 2.2
    private const val ELITE_DMG_MULT = 2.5
    private const val ELITE_REWARD_MULT = 2.5

    private const val BOSS_HP_MULT = 7.0
    private const val BOSS_DMG_MULT = 4.2
    private const val BOSS_REWARD_MULT = 6.0

    private val NORMAL_ROSTER = mapOf(
        1 to listOf(
            "Bamboo Slime (竹灵)",
            "Paper Lantern Ghost (红灯笼鬼)",
            "Baozi Imp (包子小妖)",
        ),
        2 to listOf(
            "Terracotta Pawn (兵马俑卒)",
            "Jiangshi Hopper (跳跳僵尸)",
            "Sandstone Crab (岩甲石蟹)",
        ),
        3 to listOf(
            "Man-Eating Lotus (噬人妖莲)",
            "Mist Viper (白雾蛇妖)",
            "Crimson Bat Fiend (赤蝠妖)",
        ),
        4 to listOf(
            "Magma Crag Golem (熔岩石怪)",
            "Ember Salamander (赤焰火蜥)",
            "Crypt Bone Spearman (枯骨刀兵)",
        ),
    )

    private val ELITE_ROSTER = mapOf(
        1 to listOf(
            "Bamboo King Slime (竹王巨怪)",
            "Soul-Fire Lantern Wraith (冥火灯妖)",
            "Iron-Steamer Baozi Ogre (铁笼包魔)",
        ),
        2 to listOf(
            "Terracotta General (兵马俑将军)",
            "Blood-Moon Jiangshi Lord (血月尸王)",
            "Gilded Ruin Crusher (金甲巨蟹)",
        ),
        3 to listOf(
            "Abyssal Blood Lotus (血煞魔莲)",
            "Tempest Naga Queen (雷泽蛇姬)",
            "Gold-Wing Bat King (金翼蝠王)",
        ),
        4 to listOf(
            "Infernal Core Colossus (业火魔像)",
            "Solar Drake Salamander (阳炎龙蜥)",
            "Netherworld Bone Warlord (幽冥骨将)",
        ),
    )

    private val BOSS_ROSTER = mapOf(
        1 to "Nian Beast (凶兽·年)",
        2 to "Taotie, Bronze Glutton (饕餮巨兽)",
        3 to "Kui, Thunder Ox (雷兽·夔牛)",
        4 to "Zhulong, Torch Dragon (烛龙·祝融)",
    )

    fun create(
        areaId: Int,
        type: MonsterType,
        index: Int = 1,
        variant: Int = (1..3).random(),
    ): Monster {
        val base = BASE_STATS[areaId] ?: BASE_STATS.getValue(1)
        val (hpMult, dmgMult, rewardMult) = when (type) {
            MonsterType.NORMAL -> Triple(1.0, 1.0, 1.0)
            MonsterType.ELITE -> Triple(ELITE_HP_MULT, ELITE_DMG_MULT, ELITE_REWARD_MULT)
            MonsterType.BOSS -> Triple(BOSS_HP_MULT, BOSS_DMG_MULT, BOSS_REWARD_MULT)
        }

        // Sub-stage progressive scaling (1.00x, 1.18x, 1.36x, 1.54x for stages 1..4)
        val stepMult = if (type == MonsterType.BOSS) 1.0 else (1.0 + Constants.STAGE_STEP_SCALING * (index.coerceIn(1, 4) - 1))

        val v = if (type == MonsterType.BOSS) 1 else variant.coerceIn(1, 3)
        val clampedArea = areaId.coerceIn(1, 9)

        val (monsterName, spriteKey) = if (clampedArea in 1..4) {
            when (type) {
                MonsterType.NORMAL -> {
                    val name = NORMAL_ROSTER.getValue(clampedArea)[v - 1]
                    name to "monster_h${clampedArea}_n$v"
                }
                MonsterType.ELITE -> {
                    val name = ELITE_ROSTER.getValue(clampedArea)[v - 1]
                    name to "monster_h${clampedArea}_e$v"
                }
                MonsterType.BOSS -> {
                    val name = BOSS_ROSTER.getValue(clampedArea)
                    name to "monster_h${clampedArea}_boss"
                }
            }
        } else {
            val label = type.name.lowercase().replaceFirstChar { it.uppercase() }
            val fallbackName = if (type == MonsterType.BOSS) {
                "HSK $areaId Gatekeeper Boss"
            } else {
                "HSK $areaId $label #$index"
            }
            val fallbackSprite = when (type) {
                MonsterType.BOSS -> "monster_boss_$clampedArea"
                MonsterType.ELITE -> "monster_elite_$clampedArea"
                MonsterType.NORMAL -> "monster_area_$clampedArea"
            }
            fallbackName to fallbackSprite
        }

        return Monster(
            id = "area${areaId}_${type.apiName}_$index",
            name = monsterName,
            areaId = areaId,
            type = type,
            maxHp = (base.hp * hpMult * stepMult).toInt(),
            def = base.def,
            dmg = (base.dmg * dmgMult * stepMult).toInt(),
            xpReward = (base.xp * rewardMult * stepMult).toInt(),
            coinReward = (base.coins * rewardMult * stepMult).toInt(),
            variant = v,
            spriteKey = spriteKey,
        )
    }

    /** Diminishing returns for farming the same monster: 100%, 90%, 75%, 55%, then 30%. */
    fun diminishingMultiplier(consecutiveKills: Int): Double {
        val tiers = doubleArrayOf(1.0, 0.9, 0.75, 0.55, 0.30)
        return tiers[consecutiveKills.coerceIn(0, tiers.lastIndex)]
    }
}
