"""
Pydantic schemas for the Daily Quest & Diamond Reward system.
"""
from pydantic import BaseModel


class DailyQuestItemResponse(BaseModel):
    quest_key: str
    title: str
    description: str
    icon: str
    progress: int
    target: int
    reward_diamonds: int
    reward_coins: int
    is_completed: bool
    is_claimed: bool
    can_claim: bool


class DailyQuestListResponse(BaseModel):
    quest_date: str
    completed_count: int
    total_base_quests: int
    claimable_count: int
    claimable_diamonds: int
    total_diamonds: int
    total_coins: int
    quests: list[DailyQuestItemResponse]


class ClaimQuestResponse(BaseModel):
    success: bool
    claimed_keys: list[str]
    diamonds_awarded: int
    coins_awarded: int
    total_diamonds: int
    total_coins: int
    message: str
    daily: DailyQuestListResponse
