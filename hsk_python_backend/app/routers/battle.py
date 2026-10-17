"""
Battle router — /api/battle endpoints for submitting battle results.
Handles anti-cheat plausibility, sub-stage monster scaling, persistent HP,
defeat penalties (Coins + Pet Affection), and adaptive weakness logging.
"""
from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.models.player import PlayerStats
from app.models.pet import Pet
from app.models.battle import BattleHistory, WeaknessLog
from app.schemas.battle import BattleResultRequest, BattleResultResponse
from app.services.combat import (
    xp_to_next_level,
    get_monster_stats,
    diminishing_returns_multiplier,
    calculate_base_damage,
    calculate_critical_multiplier,
)
from app.utils.security import get_current_user_id

router = APIRouter(prefix="/api/battle", tags=["Battle"])

# How many recent battles to inspect when computing the consecutive-kill streak
STREAK_LOOKBACK = 10


async def _consecutive_kills(db: AsyncSession, user_id: int, monster_id: int) -> int:
    """Count the current streak of wins against the same monster (most recent first)."""
    rows = await db.execute(
        select(BattleHistory.monster_id, BattleHistory.result)
        .where(BattleHistory.user_id == user_id)
        .order_by(BattleHistory.created_at.desc(), BattleHistory.id.desc())
        .limit(STREAK_LOOKBACK)
    )
    streak = 0
    for past_monster_id, past_result in rows.all():
        if past_monster_id == monster_id and past_result == "win":
            streak += 1
        else:
            break
    return streak


async def _get_active_pet(db: AsyncSession, user_id: int) -> Pet | None:
    """Fetch user's currently equipped companion pet."""
    row = await db.execute(
        select(Pet).where(Pet.user_id == user_id, Pet.is_active == True)
    )
    return row.scalar_one_or_none()


def _validate_plausibility(
    request: BattleResultRequest,
    stats: PlayerStats,
    monster_hp: int,
    has_damage_boost: bool = False,
) -> None:
    """Reject obviously impossible submissions (anti-cheat)."""
    if request.correct_answers > request.questions_answered:
        raise HTTPException(status_code=400, detail="correct_answers exceeds questions_answered")

    base_hit = calculate_base_damage(stats.stat_str)
    crit_mult = calculate_critical_multiplier(stats.stat_dex)
    pet_mult = 1.15 if has_damage_boost else 1.0

    max_single_hit = int(base_hit * crit_mult * pet_mult)
    if request.damage_dealt > max(1, max_single_hit) * request.correct_answers:
        raise HTTPException(status_code=400, detail="damage_dealt is not plausible")

    if request.result == "win" and request.damage_dealt < monster_hp:
        raise HTTPException(status_code=400, detail="Victory requires dealing the monster's full HP")


async def _update_weakness(db: AsyncSession, user_id: int, request: BattleResultRequest) -> None:
    """Accumulate per-skill correct/wrong counters for the adaptive boss AI."""
    if request.questions_answered == 0:
        return
    row = await db.execute(
        select(WeaknessLog).where(
            WeaknessLog.user_id == user_id, WeaknessLog.skill_type == request.skill_type
        )
    )
    log = row.scalar_one_or_none()
    if log is None:
        log = WeaknessLog(user_id=user_id, skill_type=request.skill_type, total_correct=0, total_wrong=0)
        db.add(log)
    log.total_correct = (log.total_correct or 0) + request.correct_answers
    log.total_wrong = (log.total_wrong or 0) + (request.questions_answered - request.correct_answers)


@router.post("/result", response_model=BattleResultResponse)
async def submit_battle_result(
    request: BattleResultRequest,
    user_id: int = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    """
    Submit battle result for server-side validation and reward distribution.

    Server enforces:
    1. Anti-cheat plausibility with active pet damage boost check
    2. Sub-stage progressive scaling (index 1..4)
    3. Persistent HP reduction across victory, retreat, and defeat
    4. Meaningful defeat penalty (Coins loss + Pet Affection drop)
    5. Diminishing returns on repeated monster kills
    6. Logging to battle_history and weakness_log
    """
    result = await db.execute(select(PlayerStats).where(PlayerStats.user_id == user_id))
    stats = result.scalar_one_or_none()
    if not stats:
        raise HTTPException(status_code=404, detail="Player stats not found")

    # Decode sub-stage index from monster_id (e.g. area 1, normal #3 -> 103 % 10 = 3)
    stage_index = max(1, request.monster_id % 10)
    monster_stats = get_monster_stats(request.area_id, request.monster_type, stage_index)

    # Active pet buff inspection
    active_pet = await _get_active_pet(db, user_id)
    has_dmg_boost = bool(
        active_pet
        and active_pet.affection >= 25.0
        and active_pet.buff_type == "damage_boost"
    )

    _validate_plausibility(request, stats, monster_stats["hp"], has_dmg_boost)

    consecutive_kills = await _consecutive_kills(db, user_id, request.monster_id)
    dr_multiplier = diminishing_returns_multiplier(consecutive_kills)

    xp_earned = 0
    coins_earned = 0
    level_up = False
    new_level = None
    lost_item = None

    if request.result == "win":
        # 1. Rewards
        xp_earned = int(monster_stats["xp_reward"] * dr_multiplier)
        coins_earned = int(monster_stats["coin_reward"] * dr_multiplier)
        stats.xp += xp_earned
        stats.coins += coins_earned

        # 2. HP Persistence: damage taken persists between battles!
        stats.hp = max(1, stats.hp - request.damage_taken)
        stats.survival_hp = stats.hp

        # 3. Pet Affection Decay: battle strain slightly fatigues companion
        if active_pet:
            active_pet.affection = max(0.0, active_pet.affection - 3.0)

        # 4. Level up calculation
        xp_needed = xp_to_next_level(stats.level)
        while stats.xp >= xp_needed:
            stats.xp -= xp_needed
            stats.level += 1
            stats.unspent_stat_points += 1
            level_up = True
            new_level = stats.level
            xp_needed = xp_to_next_level(stats.level)

        # 5. Gatekeeper area unlocking
        if request.monster_type == "boss" and request.area_id > stats.highest_area_cleared:
            stats.highest_area_cleared = request.area_id

    elif request.result == "lose":
        # Defeat penalty: HP drops to 10% MaxHP, lose 10% coins, companion loses 10 affection
        stats.hp = max(10, int(stats.max_hp * 0.10))
        stats.survival_hp = stats.hp

        coins_lost = min(500, int(stats.coins * 0.10))
        stats.coins = max(0, stats.coins - coins_lost)

        if active_pet:
            active_pet.affection = max(0.0, active_pet.affection - 10.0)

        lost_item = f"{coins_lost} Coins & -10 Pet Affection"

    else:
        # Fled battle safely: forfeit rewards, retain taken damage
        stats.hp = max(1, stats.hp - request.damage_taken)
        stats.survival_hp = stats.hp

    db.add(BattleHistory(
        user_id=user_id,
        monster_id=request.monster_id,
        area_id=request.area_id,
        monster_type=request.monster_type,
        result=request.result,
        damage_dealt=request.damage_dealt,
        damage_taken=request.damage_taken,
        questions_answered=request.questions_answered,
        correct_answers=request.correct_answers,
        skill_type=request.skill_type,
        xp_earned=xp_earned,
        coins_earned=coins_earned,
    ))
    await _update_weakness(db, user_id, request)
    await db.flush()

    if request.result == "win":
        msg = f"Victory! +{xp_earned} XP, +{coins_earned} coins."
        if dr_multiplier < 1.0:
            msg += f" (Farming Diminishing returns: {int(dr_multiplier * 100)}%)"
        if level_up:
            msg += f" Level Up! You are now Level {new_level}!"
    elif request.result == "lose":
        msg = "Defeated... The beast overwhelmed your vitality!"
        if lost_item:
            msg += f" Penalty: Lost {lost_item}."
    else:
        msg = "Tactical retreat. You preserved your coins and escaped safely."

    return BattleResultResponse(
        result=request.result,
        xp_earned=xp_earned,
        coins_earned=coins_earned,
        damage_dealt=request.damage_dealt,
        level_up=level_up,
        new_level=new_level,
        stat_points_available=stats.unspent_stat_points,
        survival_hp=stats.hp,
        lost_item=lost_item,
        message=msg,
    )
