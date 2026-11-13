"""
Combat service — game engine calculations.
XP, damage, leveling, monster scaling, and diminishing returns.
"""
import math


def xp_to_next_level(current_level: int) -> int:
    """Calculate XP needed for the next level: 100 × Level^1.2"""
    return int(100 * (current_level ** 1.2))


def calculate_base_damage(stat_str: int) -> int:
    """Base damage = 15 + (STR × 2)"""
    return 15 + (stat_str * 2)


def calculate_critical_multiplier(stat_dex: int) -> float:
    """Critical hit multiplier = 150% + (DEX × 0.25%)"""
    return 1.50 + (stat_dex * 0.0025)


def calculate_defense_reduction(monster_def: int, stat_def: int) -> int:
    """Defense reduction = monster_def - (player_DEF × 0.5)"""
    return max(0, int(round(monster_def - (stat_def * 0.5))))


def calculate_final_damage(
    stat_str: int,
    stat_dex: int,
    monster_def: int,
    is_critical: bool,
    has_damage_boost: bool = False,
    damage_bonus_pct: int = 0,
    crit_damage_bonus_pct: int = 0,
    first_strike_bonus_pct: int = 0,
) -> int:
    """
    Full damage calculation:
    Final = max(1, BaseDmg - DefReduction) * CritMultiplier * PetDamageMultiplier * OpenerMultiplier
    """
    base = calculate_base_damage(stat_str)
    def_reduction = calculate_defense_reduction(monster_def, 0)  # Player attacks monster
    raw = max(1, base - def_reduction)

    crit_mult = calculate_critical_multiplier(stat_dex) if is_critical else 1.0
    if is_critical and crit_damage_bonus_pct > 0:
        crit_mult += crit_damage_bonus_pct / 100.0

    effective_dmg_pct = damage_bonus_pct if damage_bonus_pct > 0 else (15 if has_damage_boost else 0)
    pet_mult = 1.0 + (effective_dmg_pct / 100.0)
    if first_strike_bonus_pct > 0:
        pet_mult *= 1.0 + (first_strike_bonus_pct / 100.0)

    return int(raw * crit_mult * pet_mult)


def calculate_max_hp(stat_vit: int, level: int = 1) -> int:
    """Max HP = 100 + ((Level - 1) × 5) + (VIT × 5)"""
    return 100 + (max(0, level - 1) * 5) + (stat_vit * 5)


def calculate_monster_damage(
    monster_base_dmg: int,
    player_stat_def: int,
) -> int:
    """Damage monster deals to player: max(1, monster_dmg - player_DEF × 0.5)"""
    return max(1, int(round(monster_base_dmg - (player_stat_def * 0.5))))


# ---------------------------------------------------------------------------
# Monster stat templates per area (HSK level)
# ---------------------------------------------------------------------------
MONSTER_BASE_STATS = {
    # area_id: {"hp": ..., "def": ..., "dmg": ..., "xp_reward": ..., "coin_reward": ...}
    1: {"hp": 80, "def": 1, "dmg": 12, "xp_reward": 20, "coin_reward": 15},
    2: {"hp": 140, "def": 3, "dmg": 18, "xp_reward": 35, "coin_reward": 25},
    3: {"hp": 230, "def": 5, "dmg": 26, "xp_reward": 55, "coin_reward": 40},
    4: {"hp": 360, "def": 8, "dmg": 36, "xp_reward": 80, "coin_reward": 60},
    5: {"hp": 540, "def": 11, "dmg": 48, "xp_reward": 115, "coin_reward": 85},
    6: {"hp": 780, "def": 15, "dmg": 64, "xp_reward": 160, "coin_reward": 120},
    7: {"hp": 1080, "def": 20, "dmg": 85, "xp_reward": 220, "coin_reward": 165},
    8: {"hp": 1450, "def": 26, "dmg": 115, "xp_reward": 300, "coin_reward": 225},
    9: {"hp": 1950, "def": 34, "dmg": 155, "xp_reward": 420, "coin_reward": 320},
}

# Elite multipliers
ELITE_HP_MULT = 2.2
ELITE_DMG_MULT = 2.5
ELITE_REWARD_MULT = 2.5

# Boss multipliers
BOSS_HP_MULT = 4.5
BOSS_DMG_MULT = 4.2
BOSS_REWARD_MULT = 6.0

# Global 60% monster damage nerf (monsters deal 40% of template damage)
MONSTER_DAMAGE_NERF_MULTIPLIER = 0.40


def stage_step_multiplier(stage_index: int) -> float:
    """
    Progressive scaling for stages 1..4 within an area:
    Stage 1 = 1.00x, Stage 2 = 1.18x, Stage 3 = 1.36x, Stage 4 = 1.54x.
    """
    clamped = max(1, min(4, stage_index))
    return 1.0 + 0.18 * (clamped - 1)


def get_monster_stats(area_id: int, monster_type: str, stage_index: int = 1) -> dict:
    """Get monster stats for a given area, type, and sub-stage index (1..4)."""
    base = MONSTER_BASE_STATS.get(area_id, MONSTER_BASE_STATS[1]).copy()
    step_mult = 1.0 if monster_type == "boss" else stage_step_multiplier(stage_index)

    if monster_type == "elite":
        base["hp"] = int(base["hp"] * ELITE_HP_MULT * step_mult)
        base["dmg"] = max(1, int(base["dmg"] * ELITE_DMG_MULT * step_mult * MONSTER_DAMAGE_NERF_MULTIPLIER))
        base["xp_reward"] = int(base["xp_reward"] * ELITE_REWARD_MULT * step_mult)
        base["coin_reward"] = int(base["coin_reward"] * ELITE_REWARD_MULT * step_mult)
    elif monster_type == "boss":
        base["hp"] = int(base["hp"] * BOSS_HP_MULT)
        base["dmg"] = max(1, int(base["dmg"] * BOSS_DMG_MULT * MONSTER_DAMAGE_NERF_MULTIPLIER))
        base["xp_reward"] = int(base["xp_reward"] * BOSS_REWARD_MULT)
        base["coin_reward"] = int(base["coin_reward"] * BOSS_REWARD_MULT)
    else:
        base["hp"] = int(base["hp"] * step_mult)
        base["dmg"] = max(1, int(base["dmg"] * step_mult * MONSTER_DAMAGE_NERF_MULTIPLIER))
        base["xp_reward"] = int(base["xp_reward"] * step_mult)
        base["coin_reward"] = int(base["coin_reward"] * step_mult)

    return base


# ---------------------------------------------------------------------------
# Diminishing returns for farming same monster
# ---------------------------------------------------------------------------
def diminishing_returns_multiplier(consecutive_kills: int) -> float:
    """
    Reduce XP/coins for farming the same monster repeatedly.
    1st kill = 100%, 2nd = 90%, 3rd = 75%, 4th = 55%, 5th+ = 30%
    """
    tiers = [1.0, 0.9, 0.75, 0.55, 0.30]
    idx = min(consecutive_kills, len(tiers) - 1)
    return tiers[idx]
