"""
Player router — /api/player endpoints for stats, profile and daily rewards.
"""
from datetime import date, timedelta

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.models.user import User
from app.models.player import PlayerStats
from app.models.daily import DailyClaim
from app.schemas.player import (
    PlayerStatsResponse,
    AllocateStatRequest,
    PlayerProfileResponse,
    DailyStatusResponse,
    DailyClaimResponse,
)
from app.services.combat import xp_to_next_level, calculate_max_hp
from app.utils.security import get_current_user_id

router = APIRouter(prefix="/api/player", tags=["Player"])

# Daily reward: base coins + bonus per consecutive day (bonus capped at 7-day streak)
DAILY_BASE_COINS = 50
DAILY_STREAK_BONUS = 10
DAILY_MAX_STREAK_BONUS_DAYS = 7


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


# ---------------------------------------------------------------------------
# Daily reward
# ---------------------------------------------------------------------------
def _daily_coins(streak: int) -> int:
    bonus_days = min(streak, DAILY_MAX_STREAK_BONUS_DAYS) - 1
    return DAILY_BASE_COINS + DAILY_STREAK_BONUS * max(0, bonus_days)


async def _latest_claim(db: AsyncSession, user_id: int) -> DailyClaim | None:
    row = await db.execute(
        select(DailyClaim)
        .where(DailyClaim.user_id == user_id)
        .order_by(DailyClaim.claim_date.desc())
        .limit(1)
    )
    return row.scalar_one_or_none()


def _next_streak(latest: DailyClaim | None, today: date) -> int:
    if latest is not None and latest.claim_date == today - timedelta(days=1):
        return latest.streak + 1
    return 1


@router.get("/daily", response_model=DailyStatusResponse)
async def daily_status(
    user_id: int = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    """Whether today's reward is claimable, the current streak and the reward amount."""
    today = date.today()
    latest = await _latest_claim(db, user_id)
    claimed_today = latest is not None and latest.claim_date == today
    streak = latest.streak if claimed_today else _next_streak(latest, today)
    return DailyStatusResponse(
        can_claim=not claimed_today,
        streak=streak,
        coins_reward=_daily_coins(streak),
    )


@router.post("/daily/claim", response_model=DailyClaimResponse)
async def claim_daily(
    user_id: int = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    """Claim today's login reward. One claim per calendar day (server date)."""
    today = date.today()
    latest = await _latest_claim(db, user_id)
    if latest is not None and latest.claim_date == today:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="Daily reward already claimed today")

    row = await db.execute(select(PlayerStats).where(PlayerStats.user_id == user_id))
    stats = row.scalar_one_or_none()
    if not stats:
        raise HTTPException(status_code=404, detail="Player stats not found")

    streak = _next_streak(latest, today)
    coins = _daily_coins(streak)
    stats.coins += coins
    db.add(DailyClaim(user_id=user_id, claim_date=today, streak=streak, coins_awarded=coins))
    await db.flush()

    return DailyClaimResponse(coins_awarded=coins, streak=streak, total_coins=stats.coins)


class AdminGrantRequest(BaseModel):
    username: str
    diamonds: int = 10000
    coins: int = 5000


@router.post("/admin/grant", tags=["Admin / Testing"])
async def admin_grant_resources(
    body: AdminGrantRequest,
    db: AsyncSession = Depends(get_db),
):
    """
    Developer/Admin endpoint to grant diamonds and coins to any player.
    Can be used from Swagger UI: http://localhost:8000/docs
    """
    from app.models.user import User

    res = await db.execute(select(User).where(User.username.ilike(body.username.strip())))
    user = res.scalar_one_or_none()
    if not user:
        raise HTTPException(status_code=404, detail=f"User '{body.username}' not found")

    stats_res = await db.execute(select(PlayerStats).where(PlayerStats.user_id == user.id))
    stats = stats_res.scalar_one_or_none()
    if not stats:
        raise HTTPException(status_code=404, detail="Player stats not found")

    stats.diamonds += body.diamonds
    stats.coins += body.coins
    await db.commit()
    await db.refresh(stats)

    return {
        "success": True,
        "username": user.username,
        "diamonds_added": body.diamonds,
        "total_diamonds": stats.diamonds,
        "coins_added": body.coins,
        "total_coins": stats.coins,
    }
