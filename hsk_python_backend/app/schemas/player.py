"""
Player schemas — request/response models for player stats.
"""
from typing import Optional
from pydantic import BaseModel, Field


class PlayerStatsResponse(BaseModel):
    level: int
    xp: int
    xp_to_next_level: int
    unspent_stat_points: int
    stat_str: int
    stat_dex: int
    stat_def: int
    stat_vit: int
    hp: int
    max_hp: int
    survival_hp: int
    coins: int
    diamonds: int
    league: str
    highest_area_cleared: int

    model_config = {"from_attributes": True}


class AllocateStatRequest(BaseModel):
    """Allocate a single stat point."""
    stat_name: str = Field(..., pattern=r"^(str|dex|def|vit)$")


class UpdateProfileRequest(BaseModel):
    """Update player username and/or target HSK level."""
    username: Optional[str] = Field(None, min_length=2, max_length=50)
    hsk_target: Optional[int] = Field(None, ge=1, le=9)


class PlayerProfileResponse(BaseModel):
    user_id: int
    username: str
    hsk_target: int
    stats: PlayerStatsResponse

    model_config = {"from_attributes": True}


class DailyStatusResponse(BaseModel):
    can_claim: bool
    streak: int
    coins_reward: int


class DailyClaimResponse(BaseModel):
    coins_awarded: int
    streak: int
    total_coins: int
