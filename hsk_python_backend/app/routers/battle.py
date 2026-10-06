"""
Battle router — /api/battle endpoints for submitting battle results.
"""
import random

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.models.player import PlayerStats
from app.models.battle import BattleHistory, WeaknessLog
from app.models.inventory import Inventory
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


def _validate_plausibility(request: BattleResultRequest, stats: PlayerStats, monster_hp: int) -> None:
    """Reject obviously impossible submissions (basic anti-cheat)."""
    if request.correct_answers > request.questions_answered:
        raise HTTPException(status_code=400, detail="correct_answers exceeds questions_answered")

    max_hit = int(calculate_base_damage(stats.stat_str) * calculate_critical_multiplier(stats.stat_dex))
    if request.damage_dealt > max(1, max_hit) * request.correct_answers:
        raise HTTPException(status_code=400, detail="damage_dealt is not plausible")

    if request.result == "win" and request.damage_dealt < monster_hp:
        raise HTTPException(status_code=400, detail="Victory requires dealing the monster's full HP")


async def _lose_random_equipment(db: AsyncSession, user_id: int) -> str | None:
    """
    Death penalty: remove one random equipment item.
    There is no equip slot system yet, so every owned equipment item counts as equipped.
    """
    rows = await db.execute(
        select(Inventory).where(Inventory.user_id == user_id, Inventory.item_type == "equipment")
    )
    items = list(rows.scalars().all())
    if not items:
        return None
    item = random.choice(items)
    lost_id = item.item_id
    if item.quantity > 1:
        item.quantity -= 1
    else:
        await db.delete(item)
    return lost_id


async def _update_weakness(db: AsyncSession, user_id: int, request: BattleResultRequest) -> None:
    """Accumulate per-skill correct/wrong counters for the adaptive boss AI (Phase 5/6)."""
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

    The server:
    1. Validates the result is plausible (anti-cheat)
    2. Applies diminishing returns for consecutive kills of the same monster
    3. Awards XP and Coins, handles level-up and stat point grants
    4. On defeat: survival HP damage + loses one random equipment item
    5. On flee: no rewards and no penalty
    6. Logs to battle_history (leaderboard) and weakness_log (adaptive AI)
    """
    result = await db.execute(select(PlayerStats).where(PlayerStats.user_id == user_id))
    stats = result.scalar_one_or_none()
    if not stats:
        raise HTTPException(status_code=404, detail="Player stats not found")

    monster_stats = get_monster_stats(request.area_id, request.monster_type)
    _validate_plausibility(request, stats, monster_stats["hp"])

    consecutive_kills = await _consecutive_kills(db, user_id, request.monster_id)
    dr_multiplier = diminishing_returns_multiplier(consecutive_kills)

    xp_earned = 0
    coins_earned = 0
    level_up = False
    new_level = None
    lost_item = None

    if request.result == "win":
        xp_earned = int(monster_stats["xp_reward"] * dr_multiplier)
        coins_earned = int(monster_stats["coin_reward"] * dr_multiplier)
        stats.xp += xp_earned
        stats.coins += coins_earned

        xp_needed = xp_to_next_level(stats.level)
        while stats.xp >= xp_needed:
            stats.xp -= xp_needed
            stats.level += 1
            stats.unspent_stat_points += 1
            level_up = True
            new_level = stats.level
            xp_needed = xp_to_next_level(stats.level)

        if request.monster_type == "boss" and request.area_id > stats.highest_area_cleared:
            stats.highest_area_cleared = request.area_id

    elif request.result == "lose":
        survival_damage = max(5, monster_stats["dmg"] // 2)
        stats.survival_hp = max(0, stats.survival_hp - survival_damage)
        lost_item = await _lose_random_equipment(db, user_id)

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
        msg = f"Victory! +{xp_earned} XP, +{coins_earned} coins"
        if dr_multiplier < 1.0:
            msg += f" (Diminishing returns: {int(dr_multiplier * 100)}%)"
        if level_up:
            msg += f" Level Up! You are now Level {new_level}!"
    elif request.result == "lose":
        msg = "Defeated... The monster was too strong this time."
        if lost_item:
            msg += f" You lost: {lost_item}."
    else:
        msg = "You fled the battle safely."

    return BattleResultResponse(
        result=request.result,
        xp_earned=xp_earned,
        coins_earned=coins_earned,
        damage_dealt=request.damage_dealt,
        level_up=level_up,
        new_level=new_level,
        stat_points_available=stats.unspent_stat_points,
        survival_hp=stats.survival_hp,
        lost_item=lost_item,
        message=msg,
    )
