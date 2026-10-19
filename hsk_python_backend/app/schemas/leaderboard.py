"""
Leaderboard schemas — Pydantic v2 request/response models for /api/leaderboard.
"""
from datetime import datetime
from pydantic import BaseModel, Field


class LeaderboardEntry(BaseModel):
    rank: int
    user_id: int
    username: str
    level: int
    league: str
    highest_area_cleared: int
    weekly_damage: int
    is_me: bool = False

    model_config = {"from_attributes": True}


class WeeklyLeaderboardResponse(BaseModel):
    league: str = Field(..., description="Currently viewed league: bronze | silver | gold | radiant")
    week_start: datetime
    week_end: datetime
    seconds_until_reset: int
    entries: list[LeaderboardEntry]
    me: LeaderboardEntry | None = None
    my_league: str
    projected_reward_diamonds: int = 0
    projected_reward_title: str | None = None
