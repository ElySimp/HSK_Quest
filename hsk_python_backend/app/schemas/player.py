"""
Player schemas — request/response models for player stats.
"""
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


class PlayerProfileResponse(BaseModel):
    user_id: int
    username: str
    hsk_target: int
    stats: PlayerStatsResponse

    model_config = {"from_attributes": True}
