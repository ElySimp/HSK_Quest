"""
Personalized tiered Spirit Beast combat & progression buffs.
Kept strictly synchronized with Android PetBuffCatalog.kt.
"""
from dataclasses import dataclass
from typing import Optional


@dataclass(frozen=True)
class PetBuffSpec:
    pet_type: str
    rarity: str
    buff_type: str
    buff_title: str
    buff_summary: str
    damage_bonus_pct: int = 0
    crit_damage_bonus_pct: int = 0
    crit_window_bonus_ms: int = 0
    timer_bonus_ms: int = 0
    shield_charges: int = 0
    monster_damage_reduction_pct: int = 0
    heal_on_correct: int = 0
    heal_on_crit: int = 0
    first_strike_bonus_pct: int = 0
    rebirth_hp_pct: int = 0
    coin_bonus_pct: int = 0
    xp_bonus_pct: int = 0


PET_BUFF_BY_TYPE: dict[str, PetBuffSpec] = {
    # ------------------------------------------------------------------
    # COMMON TIER (6 species) — Specialized starter perks
    # ------------------------------------------------------------------
    "wood_rat": PetBuffSpec(
        pet_type="wood_rat",
        rarity="common",
        buff_type="scavenger_nose",
        buff_title="Scavenger's Nose",
        buff_summary="-2% Monster DMG Taken",
        monster_damage_reduction_pct=2,
    ),
    "stone_rabbit": PetBuffSpec(
        pet_type="stone_rabbit",
        rarity="common",
        buff_type="earthen_guard",
        buff_title="Earthen Guard",
        buff_summary="Heal +1 HP on Critical Hit",
        heal_on_crit=1,
    ),
    "bamboo_panda": PetBuffSpec(
        pet_type="bamboo_panda",
        rarity="common",
        buff_type="bamboo_vitality",
        buff_title="Bamboo Vitality",
        buff_summary="+4% Critical Hit DMG",
        crit_damage_bonus_pct=4,
    ),
    "golden_koi": PetBuffSpec(
        pet_type="golden_koi",
        rarity="common",
        buff_type="lucky_scales",
        buff_title="Lucky Scales",
        buff_summary="+3% Battle Coins",
        coin_bonus_pct=3,
    ),
    "cloud_fox": PetBuffSpec(
        pet_type="cloud_fox",
        rarity="common",
        buff_type="swift_breeze",
        buff_title="Swift Breeze",
        buff_summary="+1s Question Timer",
        timer_bonus_ms=1000,
    ),
    "jade_frog": PetBuffSpec(
        pet_type="jade_frog",
        rarity="common",
        buff_type="scholars_croak",
        buff_title="Scholar's Croak",
        buff_summary="+2% Battle XP",
        xp_bonus_pct=2,
    ),
    # ------------------------------------------------------------------
    # RARE TIER (4 species) — Meaningful tactical edge
    # ------------------------------------------------------------------
    "fu_dog": PetBuffSpec(
        pet_type="fu_dog",
        rarity="rare",
        buff_type="temple_ward",
        buff_title="Temple Ward",
        buff_summary="1x Mistake Shield & -5% Monster DMG",
        shield_charges=1,
        monster_damage_reduction_pct=5,
    ),
    "plum_deer": PetBuffSpec(
        pet_type="plum_deer",
        rarity="rare",
        buff_type="blossom_grace",
        buff_title="Blossom Grace",
        buff_summary="+2s Crit Window & Heal +5 HP on Crit",
        crit_window_bonus_ms=2000,
        heal_on_crit=5,
    ),
    "thunder_tiger": PetBuffSpec(
        pet_type="thunder_tiger",
        rarity="rare",
        buff_type="thunder_claw",
        buff_title="Thunder Claw",
        buff_summary="+8% Attack DMG & +5% Crit DMG",
        damage_bonus_pct=8,
        crit_damage_bonus_pct=5,
    ),
    "jade_tortoise": PetBuffSpec(
        pet_type="jade_tortoise",
        rarity="rare",
        buff_type="abyssal_shell",
        buff_title="Abyssal Shell",
        buff_summary="+5% Battle Coins & +5% Battle XP",
        coin_bonus_pct=5,
        xp_bonus_pct=5,
    ),
    # ------------------------------------------------------------------
    # EPIC TIER (3 species) — Strong dual-mechanic synergies
    # ------------------------------------------------------------------
    "fire_crane": PetBuffSpec(
        pet_type="fire_crane",
        rarity="epic",
        buff_type="crimson_inferno",
        buff_title="Crimson Inferno",
        buff_summary="+12% DMG, +10% Crit DMG, +1s Crit & +10 HP/Crit",
        damage_bonus_pct=12,
        crit_damage_bonus_pct=10,
        crit_window_bonus_ms=1000,
        heal_on_crit=10,
    ),
    "moon_kitsune": PetBuffSpec(
        pet_type="moon_kitsune",
        rarity="epic",
        buff_type="lunar_illusion",
        buff_title="Lunar Illusion",
        buff_summary="+3s Timer, -10% Enemy DMG & +15% XP/Coins",
        timer_bonus_ms=3000,
        monster_damage_reduction_pct=10,
        coin_bonus_pct=15,
        xp_bonus_pct=15,
    ),
    "shadow_panther": PetBuffSpec(
        pet_type="shadow_panther",
        rarity="epic",
        buff_type="phantom_ambush",
        buff_title="Phantom Ambush",
        buff_summary="+18% DMG, +1s Timer, 1x Shield & +5 HP/Hit",
        damage_bonus_pct=18,
        timer_bonus_ms=1000,
        shield_charges=1,
        heal_on_correct=5,
    ),
    # ------------------------------------------------------------------
    # LEGENDARY TIER (3 species) — Dominant apex Spirit Beasts
    # ------------------------------------------------------------------
    "azure_dragon": PetBuffSpec(
        pet_type="azure_dragon",
        rarity="legendary",
        buff_type="storm_sovereign",
        buff_title="Storm Dragon Sovereign",
        buff_summary="+25% DMG, +6s Crit, +5s Timer, -10% Enemy DMG & +25% XP",
        damage_bonus_pct=25,
        crit_window_bonus_ms=6000,
        timer_bonus_ms=5000,
        monster_damage_reduction_pct=10,
        xp_bonus_pct=25,
    ),
    "qilin": PetBuffSpec(
        pet_type="qilin",
        rarity="legendary",
        buff_type="auspicious_mandate",
        buff_title="Auspicious Mandate",
        buff_summary="+15% DMG, +5 HP/Hit (+10/Crit) & +40% XP/Coins",
        damage_bonus_pct=15,
        heal_on_correct=5,
        heal_on_crit=10,
        coin_bonus_pct=40,
        xp_bonus_pct=40,
    ),
    "vermilion_phoenix": PetBuffSpec(
        pet_type="vermilion_phoenix",
        rarity="legendary",
        buff_type="nirvana_rebirth",
        buff_title="Nirvana Rebirth",
        buff_summary="2x Shields, +20% DMG, +3s Timer, -5% Enemy DMG & 30% Revive",
        damage_bonus_pct=20,
        timer_bonus_ms=3000,
        shield_charges=2,
        monster_damage_reduction_pct=5,
        rebirth_hp_pct=30,
    ),
}

DEFAULT_BUFF_BY_LEGACY_KEY: dict[str, PetBuffSpec] = {
    "shield": PET_BUFF_BY_TYPE["fu_dog"],
    "time_extender": PET_BUFF_BY_TYPE["plum_deer"],
    "damage_boost": PET_BUFF_BY_TYPE["thunder_tiger"],
}

PET_AWAKE_THRESHOLD = 25.0


def get_pet_buff_spec(pet_type: Optional[str], legacy_buff_type: Optional[str] = None) -> PetBuffSpec:
    """Resolve canonical tiered buff spec by pet_type, falling back cleanly."""
    if pet_type:
        spec = PET_BUFF_BY_TYPE.get(pet_type.strip().lower())
        if spec:
            return spec
    if legacy_buff_type:
        spec = DEFAULT_BUFF_BY_LEGACY_KEY.get(legacy_buff_type.strip().lower())
        if spec:
            return spec
    return PET_BUFF_BY_TYPE["wood_rat"]


def get_active_pet_buff(pet) -> Optional[PetBuffSpec]:
    """Return active PetBuffSpec only if pet is equipped and awake (affection >= 25)."""
    if pet is None or (pet.affection or 0.0) < PET_AWAKE_THRESHOLD:
        return None
    return get_pet_buff_spec(getattr(pet, "pet_type", None), getattr(pet, "buff_type", None))


def sync_pet_record(pet) -> None:
    """Normalize legacy sprite_key and buff_type on ORM Pet instances in memory."""
    if pet is None:
        return
    if pet.pet_type:
        if not pet.sprite_key or pet.sprite_key.startswith("pet_dummy"):
            pet.sprite_key = f"pet_{pet.pet_type}"
        spec = get_pet_buff_spec(pet.pet_type, pet.buff_type)
        if pet.buff_type != spec.buff_type:
            pet.buff_type = spec.buff_type
        if pet.rarity != spec.rarity:
            pet.rarity = spec.rarity


def build_pet_response(pet):
    """Synchronize canonical pet fields and return an enriched PetResponse."""
    from app.schemas.pet import PetResponse

    if pet is None:
        return None
    sync_pet_record(pet)
    spec = get_pet_buff_spec(pet.pet_type, pet.buff_type)
    base = PetResponse.model_validate(pet)
    return base.model_copy(update={
        "buff_title": spec.buff_title,
        "buff_description": spec.buff_summary,
    })
