"""
Player router — /api/player endpoints for stats and profile.
"""
import math

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.models.user import User
from app.models.player import PlayerStats
from app.schemas.player import (
    PlayerStatsResponse,
    AllocateStatRequest,
    PlayerProfileResponse,
)
from app.services.combat import xp_to_next_level, calculate_max_hp
from app.utils.security import get_current_user_id

router = APIRouter(prefix="/api/player", tags=["Player"])


@router.get("/me", response_model=PlayerProfileResponse)
async def get_profile(
    user_id: int = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    """Get current player profile with stats."""
    result = await db.execute(
        select(User).where(User.id == user_id)
    )
    user = result.scalar_one_or_none()
    if not user:
        raise HTTPException(status_code=404, detail="User not found")

    result = await db.execute(
        select(PlayerStats).where(PlayerStats.user_id == user_id)
    )
    stats = result.scalar_one_or_none()
    if not stats:
        raise HTTPException(status_code=404, detail="Player stats not found")

    return PlayerProfileResponse(
        user_id=user.id,
        username=user.username,
        hsk_target=user.hsk_target,
        stats=PlayerStatsResponse(
            level=stats.level,
            xp=stats.xp,
            xp_to_next_level=xp_to_next_level(stats.level),
            unspent_stat_points=stats.unspent_stat_points,
            stat_str=stats.stat_str,
            stat_dex=stats.stat_dex,
            stat_def=stats.stat_def,
            stat_vit=stats.stat_vit,
            hp=stats.hp,
            max_hp=stats.max_hp,
            survival_hp=stats.survival_hp,
            coins=stats.coins,
            diamonds=stats.diamonds,
            league=stats.league,
            highest_area_cleared=stats.highest_area_cleared,
        ),
    )


@router.post("/allocate-stat", response_model=PlayerStatsResponse)
async def allocate_stat(
    request: AllocateStatRequest,
    user_id: int = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    """
    Allocate 1 unspent stat point to STR, DEX, DEF, or VIT.

    Effects:
    - STR: +5 Raw Damage per point
    - DEX: +2% Critical Damage per point (base 150%)
    - DEF: -2 Raw Damage received per point
    - VIT: +25 Max HP per point (base 100)
    """
    result = await db.execute(
        select(PlayerStats).where(PlayerStats.user_id == user_id)
    )
    stats = result.scalar_one_or_none()
    if not stats:
        raise HTTPException(status_code=404, detail="Player stats not found")

    if stats.unspent_stat_points <= 0:
        raise HTTPException(
            status_code=400, detail="No unspent stat points available"
        )

    # Allocate the point
    stat_field = f"stat_{request.stat_name}"
    current_value = getattr(stats, stat_field)
    setattr(stats, stat_field, current_value + 1)
    stats.unspent_stat_points -= 1

    # Recalculate max HP if VIT changed
    if request.stat_name == "vit":
        stats.max_hp = calculate_max_hp(stats.stat_vit)
        # Also heal to new max if current HP was at old max
        if stats.hp == stats.max_hp - 25:  # Was at previous max
            stats.hp = stats.max_hp

    await db.flush()

    return PlayerStatsResponse(
        level=stats.level,
        xp=stats.xp,
        xp_to_next_level=xp_to_next_level(stats.level),
        unspent_stat_points=stats.unspent_stat_points,
        stat_str=stats.stat_str,
        stat_dex=stats.stat_dex,
        stat_def=stats.stat_def,
        stat_vit=stats.stat_vit,
        hp=stats.hp,
        max_hp=stats.max_hp,
        survival_hp=stats.survival_hp,
        coins=stats.coins,
        diamonds=stats.diamonds,
        league=stats.league,
        highest_area_cleared=stats.highest_area_cleared,
    )
