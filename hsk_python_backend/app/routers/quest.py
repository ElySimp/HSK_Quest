"""
Quest router — /api/quest endpoints for Daily Quests & Diamond rewards.
"""
from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.schemas.quest import ClaimQuestResponse, DailyQuestListResponse
from app.services.quest_service import (
    build_daily_quest_list,
    claim_single_or_all_quests,
)
from app.utils.security import get_current_user_id

router = APIRouter(prefix="/api/quest", tags=["Quest"])


@router.get("/daily", response_model=DailyQuestListResponse)
async def get_daily_quests(
    user_id: int = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    """Fetch today's Daily Quests with live progress and claimable Diamond rewards."""
    return await build_daily_quest_list(db, user_id)


@router.post("/claim/{quest_key}", response_model=ClaimQuestResponse)
async def claim_daily_quest(
    quest_key: str,
    user_id: int = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    """Claim a single completed Daily Quest reward."""
    return await claim_single_or_all_quests(db, user_id, quest_key=quest_key)


@router.post("/claim-all", response_model=ClaimQuestResponse)
async def claim_all_daily_quests(
    user_id: int = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    """Claim all completed, unclaimed Daily Quests at once."""
    return await claim_single_or_all_quests(db, user_id, quest_key=None)
