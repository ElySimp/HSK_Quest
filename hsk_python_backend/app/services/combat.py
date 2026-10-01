"""
Combat service — game engine calculations.
XP, damage, leveling, and diminishing returns.
"""
import math


def xp_to_next_level(current_level: int) -> int:
    """Calculate XP needed for the next level: 100 × Level^1.2"""
    return int(100 * (current_level ** 1.2))


def calculate_base_damage(stat_str: int) -> int:
    """Base damage = 10 + (STR × 5)"""
    return 10 + (stat_str * 5)


def calculate_critical_multiplier(stat_dex: int) -> float:
    """Critical hit multiplier = 150% + (DEX × 2%)"""
    return 1.50 + (stat_dex * 0.02)


def calculate_defense_reduction(monster_def: int, stat_def: int) -> int:
    """Defense reduction = monster_def - (player_DEF × 2)"""
    return max(0, monster_def - (stat_def * 2))


def calculate_final_damage(
    stat_str: int,
    stat_dex: int,
    monster_def: int,
    is_critical: bool,
) -> int:
    """
    Full damage calculation:
    Final = max(1, BaseDmg - DefReduction) × CritMultiplier
    """
    base = calculate_base_damage(stat_str)
    def_reduction = calculate_defense_reduction(monster_def, 0)  # Player attacks monster
    raw = max(1, base - def_reduction)

    if is_critical:
        crit_mult = calculate_critical_multiplier(stat_dex)
        return int(raw * crit_mult)
    return raw


def calculate_max_hp(stat_vit: int) -> int:
    """Max HP = 100 + (VIT × 25)"""
    return 100 + (stat_vit * 25)


def calculate_monster_damage(
    monster_base_dmg: int,
    player_stat_def: int,
) -> int:
    """Damage monster deals to player: max(1, monster_dmg - player_DEF × 2)"""
    return max(1, monster_base_dmg - (player_stat_def * 2))


# ---------------------------------------------------------------------------
# Monster stat templates per area (HSK level)
# ---------------------------------------------------------------------------
MONSTER_BASE_STATS = {
    # area_id: {"hp": ..., "def": ..., "dmg": ..., "xp_reward": ..., "coin_reward": ...}
    1: {"hp": 50, "def": 0, "dmg": 5, "xp_reward": 15, "coin_reward": 10},
    2: {"hp": 80, "def": 2, "dmg": 8, "xp_reward": 25, "coin_reward": 18},
    3: {"hp": 120, "def": 5, "dmg": 12, "xp_reward": 40, "coin_reward": 28},
    4: {"hp": 180, "def": 8, "dmg": 18, "xp_reward": 60, "coin_reward": 42},
    5: {"hp": 260, "def": 12, "dmg": 25, "xp_reward": 85, "coin_reward": 60},
    6: {"hp": 360, "def": 16, "dmg": 35, "xp_reward": 120, "coin_reward": 85},
    7: {"hp": 500, "def": 22, "dmg": 48, "xp_reward": 170, "coin_reward": 120},
    8: {"hp": 680, "def": 28, "dmg": 65, "xp_reward": 240, "coin_reward": 170},
    9: {"hp": 900, "def": 35, "dmg": 85, "xp_reward": 350, "coin_reward": 250},
}

# Elite multipliers (from architecture doc)
ELITE_HP_MULT = 1.5       # +50% HP
ELITE_DMG_MULT = 3.5      # +250% Damage
ELITE_REWARD_MULT = 2.0   # +100% Rewards

# Boss multipliers
BOSS_HP_MULT = 3.0
BOSS_DMG_MULT = 2.0
BOSS_REWARD_MULT = 5.0


def get_monster_stats(area_id: int, monster_type: str) -> dict:
    """Get monster stats for a given area and type."""
    base = MONSTER_BASE_STATS.get(area_id, MONSTER_BASE_STATS[1]).copy()

    if monster_type == "elite":
        base["hp"] = int(base["hp"] * ELITE_HP_MULT)
        base["dmg"] = int(base["dmg"] * ELITE_DMG_MULT)
        base["xp_reward"] = int(base["xp_reward"] * ELITE_REWARD_MULT)
        base["coin_reward"] = int(base["coin_reward"] * ELITE_REWARD_MULT)
    elif monster_type == "boss":
        base["hp"] = int(base["hp"] * BOSS_HP_MULT)
        base["dmg"] = int(base["dmg"] * BOSS_DMG_MULT)
        base["xp_reward"] = int(base["xp_reward"] * BOSS_REWARD_MULT)
        base["coin_reward"] = int(base["coin_reward"] * BOSS_REWARD_MULT)

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
