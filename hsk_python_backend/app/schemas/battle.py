"""
Battle schemas — request/response models for combat results.
"""
from pydantic import BaseModel, Field


class BattleResultRequest(BaseModel):
    """Submitted by the client after a battle ends."""
    # Encoded by the client as area_id * 100 + type_index * 10 + slot
    monster_id: int = Field(..., ge=0)
    area_id: int = Field(..., ge=1, le=9)
    monster_type: str = Field(default="normal", pattern=r"^(normal|elite|boss)$")
    # "flee" = player retreated voluntarily: no rewards, no penalty
    result: str = Field(..., pattern=r"^(win|lose|flee)$")
    damage_dealt: int = Field(..., ge=0)
    damage_taken: int = Field(..., ge=0)
    questions_answered: int = Field(..., ge=0)
    correct_answers: int = Field(..., ge=0)
    skill_type: str = Field(default="reading", pattern=r"^(reading|writing|listening|speaking|mixed)$")


class BattleResultResponse(BaseModel):
    """Server response with validated rewards."""
    result: str
    xp_earned: int
    coins_earned: int
    damage_dealt: int
    level_up: bool = False
    new_level: int | None = None
    stat_points_available: int = 0
    survival_hp: int = 0
    # item_id of the equipment lost on defeat, if any
    lost_item: str | None = None
    message: str = ""
