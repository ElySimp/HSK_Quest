"""
Leaderboard router — /api/leaderboard endpoints for weekly damage rankings and league standings.
"""
from fastapi import APIRouter, Depends, Query
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.schemas.leaderboard import WeeklyLeaderboardResponse
from app.services.leaderboard_service import get_weekly_board
from app.utils.security import get_current_user_id

router = APIRouter(prefix="/api/leaderboard", tags=["Leaderboard"])


@router.get("/weekly", response_model=WeeklyLeaderboardResponse)
async def get_weekly_leaderboard(
    league: str | None = Query(
        default=None,
        description="Optional league filter: bronze | silver | gold | radiant. Defaults to caller's league.",
    ),
    user_id: int = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    """
    Get the current ISO week's damage leaderboard rankings for a league tier,
    plus the authenticated player's personal rank and projected weekly reward.
    """
    return await get_weekly_board(db=db, user_id=user_id, requested_league=league)
