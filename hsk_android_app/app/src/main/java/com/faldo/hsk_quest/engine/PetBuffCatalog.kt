package com.faldo.hsk_quest.engine

import com.faldo.hsk_quest.data.model.PetItem

/**
 * Canonical tiered buff registry for all 16 Spirit Beast companions.
 * Kept strictly synchronized with backend `app/services/pet_buffs.py`.
 */
data class PetBuffSpec(
    val petType: String,
    val rarity: String,
    val buffType: String,
    val title: String,
    val summary: String,
    val shortBadge: String,
    val damageBonusPct: Int = 0,
    val critDamageBonusPct: Int = 0,
    val critWindowBonusMs: Long = 0L,
    val timerBonusMs: Long = 0L,
    val shieldCharges: Int = 0,
    val monsterDamageReductionPct: Int = 0,
    val healOnCorrect: Int = 0,
    val healOnCrit: Int = 0,
    val firstStrikeBonusPct: Int = 0,
    val rebirthHpPct: Int = 0,
    val coinBonusPct: Int = 0,
    val xpBonusPct: Int = 0,
)

object PetBuffCatalog {

    private val specsByPetType: Map<String, PetBuffSpec> = listOf(
        // COMMON TIER (6 species) — Specialized starter perks
        PetBuffSpec(
            petType = "wood_rat",
            rarity = "common",
            buffType = "scavenger_nose",
            title = "Scavenger's Nose",
            summary = "-2% Monster DMG Taken",
            shortBadge = "-2% Enemy DMG",
            monsterDamageReductionPct = 2,
        ),
        PetBuffSpec(
            petType = "stone_rabbit",
            rarity = "common",
            buffType = "earthen_guard",
            title = "Earthen Guard",
            summary = "Heal +1 HP on Critical Hit",
            shortBadge = "+1 HP/Crit",
            healOnCrit = 1,
        ),
        PetBuffSpec(
            petType = "bamboo_panda",
            rarity = "common",
            buffType = "bamboo_vitality",
            title = "Bamboo Vitality",
            summary = "+4% Critical Hit DMG",
            shortBadge = "+4% Crit DMG",
            critDamageBonusPct = 4,
        ),
        PetBuffSpec(
            petType = "golden_koi",
            rarity = "common",
            buffType = "lucky_scales",
            title = "Lucky Scales",
            summary = "+3% Battle Coins",
            shortBadge = "+3% Coins",
            coinBonusPct = 3,
        ),
        PetBuffSpec(
            petType = "cloud_fox",
            rarity = "common",
            buffType = "swift_breeze",
            title = "Swift Breeze",
            summary = "+1s Question Timer",
            shortBadge = "+1s Timer",
            timerBonusMs = 1000L,
        ),
        PetBuffSpec(
            petType = "jade_frog",
            rarity = "common",
            buffType = "scholars_croak",
            title = "Scholar's Croak",
            summary = "+2% Battle XP",
            shortBadge = "+2% XP",
            xpBonusPct = 2,
        ),

        // RARE TIER (4 species) — Meaningful tactical edge
        PetBuffSpec(
            petType = "fu_dog",
            rarity = "rare",
            buffType = "temple_ward",
            title = "Temple Ward",
            summary = "1x Mistake Shield & -5% Monster DMG",
            shortBadge = "1x Shield • -5% DMG",
            shieldCharges = 1,
            monsterDamageReductionPct = 5,
        ),
        PetBuffSpec(
            petType = "plum_deer",
            rarity = "rare",
            buffType = "blossom_grace",
            title = "Blossom Grace",
            summary = "+2s Crit Window & Heal +5 HP on Crit",
            shortBadge = "+2s Crit • +5 HP/Crit",
            critWindowBonusMs = 2000L,
            healOnCrit = 5,
        ),
        PetBuffSpec(
            petType = "thunder_tiger",
            rarity = "rare",
            buffType = "thunder_claw",
            title = "Thunder Claw",
            summary = "+8% Attack DMG & +5% Crit DMG",
            shortBadge = "+8% ATK • +5% Crit",
            damageBonusPct = 8,
            critDamageBonusPct = 5,
        ),
        PetBuffSpec(
            petType = "jade_tortoise",
            rarity = "rare",
            buffType = "abyssal_shell",
            title = "Abyssal Shell",
            summary = "+5% Battle Coins & +5% Battle XP",
            shortBadge = "+5% Coins • +5% XP",
            coinBonusPct = 5,
            xpBonusPct = 5,
        ),

        // EPIC TIER (3 species) — Strong dual-mechanic synergies
        PetBuffSpec(
            petType = "fire_crane",
            rarity = "epic",
            buffType = "crimson_inferno",
            title = "Crimson Inferno",
            summary = "+12% DMG, +10% Crit DMG, +1s Crit & +10 HP/Crit",
            shortBadge = "+12% ATK • +10% Crit • +10 Heal",
            damageBonusPct = 12,
            critDamageBonusPct = 10,
            critWindowBonusMs = 1000L,
            healOnCrit = 10,
        ),
        PetBuffSpec(
            petType = "moon_kitsune",
            rarity = "epic",
            buffType = "lunar_illusion",
            title = "Lunar Illusion",
            summary = "+3s Timer, -10% Enemy DMG & +15% XP/Coins",
            shortBadge = "+3s • -10% DMG • +15% XP/Coins",
            timerBonusMs = 3000L,
            monsterDamageReductionPct = 10,
            coinBonusPct = 15,
            xpBonusPct = 15,
        ),
        PetBuffSpec(
            petType = "shadow_panther",
            rarity = "epic",
            buffType = "phantom_ambush",
            title = "Phantom Ambush",
            summary = "+18% DMG, +1s Timer, 1x Shield & +5 HP/Hit",
            shortBadge = "+18% ATK • 1x Shield • +5 HP",
            damageBonusPct = 18,
            timerBonusMs = 1000L,
            shieldCharges = 1,
            healOnCorrect = 5,
        ),

        // LEGENDARY TIER (3 species) — Dominant apex Spirit Beasts
        PetBuffSpec(
            petType = "azure_dragon",
            rarity = "legendary",
            buffType = "storm_sovereign",
            title = "Storm Dragon Sovereign",
            summary = "+25% DMG, +6s Crit, +5s Timer, -10% Enemy DMG & +25% XP",
            shortBadge = "+25% ATK • +6s Crit • -10% DMG",
            damageBonusPct = 25,
            critWindowBonusMs = 6000L,
            timerBonusMs = 5000L,
            monsterDamageReductionPct = 10,
            xpBonusPct = 25,
        ),
        PetBuffSpec(
            petType = "qilin",
            rarity = "legendary",
            buffType = "auspicious_mandate",
            title = "Auspicious Mandate",
            summary = "+15% DMG, +5 HP/Hit (+10/Crit) & +40% XP/Coins",
            shortBadge = "+15% ATK • Heal • +40% XP/Coins",
            damageBonusPct = 15,
            healOnCorrect = 5,
            healOnCrit = 10,
            coinBonusPct = 40,
            xpBonusPct = 40,
        ),
        PetBuffSpec(
            petType = "vermilion_phoenix",
            rarity = "legendary",
            buffType = "nirvana_rebirth",
            title = "Nirvana Rebirth",
            summary = "2x Shields, +20% DMG, +3s Timer, -5% Enemy DMG & 30% Revive",
            shortBadge = "2x Shield • +20% ATK • Revive",
            damageBonusPct = 20,
            timerBonusMs = 3000L,
            shieldCharges = 2,
            monsterDamageReductionPct = 5,
            rebirthHpPct = 30,
        ),
    ).associateBy { it.petType }

    private val legacyFallbackByBuffType: Map<String, PetBuffSpec> = mapOf(
        "shield" to specsByPetType.getValue("fu_dog"),
        "time_extender" to specsByPetType.getValue("plum_deer"),
        "damage_boost" to specsByPetType.getValue("thunder_tiger"),
    )

    fun resolveSpec(petType: String?, legacyBuffType: String? = null): PetBuffSpec {
        val cleanType = petType?.trim()?.lowercase()
        if (!cleanType.isNullOrEmpty()) {
            specsByPetType[cleanType]?.let { return it }
        }
        val cleanBuff = legacyBuffType?.trim()?.lowercase()
        if (!cleanBuff.isNullOrEmpty()) {
            legacyFallbackByBuffType[cleanBuff]?.let { return it }
        }
        return specsByPetType.getValue("wood_rat")
    }

    fun resolveSpec(pet: PetItem): PetBuffSpec = resolveSpec(pet.petType, pet.buffType)

    fun resolveActiveSpec(pet: PetItem?): PetBuffSpec? {
        if (pet == null || pet.isSleeping) return null
        return resolveSpec(pet)
    }

    fun formatBattleBanner(pet: PetItem?): String {
        if (pet == null) return ""
        if (pet.isSleeping) return "Resting: ${pet.name} (Feed >= 25 Affection to awaken buff)"
        val spec = resolveSpec(pet)
        return "${spec.title} • ${spec.shortBadge}"
    }
}
