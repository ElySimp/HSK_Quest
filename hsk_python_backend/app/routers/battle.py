"""
Battle router — /api/battle endpoints for submitting battle results.
"""
from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select, func
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.models.player import PlayerStats
from app.models.battle import BattleHistory
from app.schemas.battle import BattleResultRequest, BattleResultResponse
from app.services.combat import (
    xp_to_next_level,
    get_monster_stats,
    diminishing_returns_multiplier,
    calculate_max_hp,
)
from app.utils.security import get_current_user_id

router = APIRouter(prefix="/api/battle", tags=["Battle"])


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
    2. Applies diminishing returns if farming same monster
    3. Awards XP and Coins
    4. Handles level-up and stat point grants
    5. Logs to battle_history for leaderboard
    """
    # Get player stats
    result = await db.execute(
        select(PlayerStats).where(PlayerStats.user_id == user_id)
    )
    stats = result.scalar_one_or_none()
    if not stats:
        raise HTTPException(status_code=404, detail="Player stats not found")

    # Get monster base rewards
    monster_stats = get_monster_stats(request.area_id, request.monster_type)

    # --- Diminishing returns check ---
    # Count consecutive kills of the same monster in recent history
    recent_battles = await db.execute(
        select(func.count())
        .select_from(BattleHistory)
        .where(
            BattleHistory.user_id == user_id,
            BattleHistory.monster_id == request.monster_id,
            BattleHistory.area_id == request.area_id,
            BattleHistory.result == "win",
        )
    )
    consecutive_kills = recent_battles.scalar() or 0
    dr_multiplier = diminishing_returns_multiplier(consecutive_kills)

    # --- Calculate rewards ---
    xp_earned = 0
    coins_earned = 0
    level_up = False
    new_level = None

    if request.result == "win":
        xp_earned = int(monster_stats["xp_reward"] * dr_multiplier)
        coins_earned = int(monster_stats["coin_reward"] * dr_multiplier)

        # Apply rewards
        stats.xp += xp_earned
        stats.coins += coins_earned

        # --- Level up check ---
        xp_needed = xp_to_next_level(stats.level)
        while stats.xp >= xp_needed:
            stats.xp -= xp_needed
            stats.level += 1
            stats.unspent_stat_points += 1
            level_up = True
            new_level = stats.level
            xp_needed = xp_to_next_level(stats.level)

        # Update highest area cleared
        if request.monster_type == "boss" and request.area_id > stats.highest_area_cleared:
            stats.highest_area_cleared = request.area_id

    elif request.result == "lose":
        # Player takes survival HP damage on loss
        survival_damage = max(5, monster_stats["dmg"] // 2)
        stats.survival_hp = max(0, stats.survival_hp - survival_damage)

    # --- Log battle history ---
    battle_log = BattleHistory(
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
    )
    db.add(battle_log)
    await db.flush()

    # Build response message
    if request.result == "win":
        msg = f"Victory! +{xp_earned} XP, +{coins_earned} 🪙"
        if dr_multiplier < 1.0:
            msg += f" (Diminishing returns: {int(dr_multiplier * 100)}%)"
        if level_up:
            msg += f" 🎉 Level Up! You are now Level {new_level}!"
    else:
        msg = "Defeated... The monster was too strong this time."

    return BattleResultResponse(
        result=request.result,
        xp_earned=xp_earned,
        coins_earned=coins_earned,
        damage_dealt=request.damage_dealt,
        level_up=level_up,
        new_level=new_level,
        stat_points_available=stats.unspent_stat_points,
        message=msg,
    )
