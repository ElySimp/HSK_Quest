"""
Leaderboard router — /api/leaderboard endpoints.
Placeholder for Phase 5 full implementation.
"""
from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.utils.security import get_current_user_id

router = APIRouter(prefix="/api/leaderboard", tags=["Leaderboard"])


@router.get("/weekly")
async def get_weekly_leaderboard(
    db: AsyncSession = Depends(get_db),
):
    """
    Get the current week's leaderboard rankings.
    Phase 5: Full implementation with league tiers and pagination.
    """
    return {
        "message": "Leaderboard coming in Phase 5",
        "rankings": [],
    }
