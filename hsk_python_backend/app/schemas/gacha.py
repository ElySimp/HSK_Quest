"""
Pydantic schemas for Gacha Summoning Altar.
"""
from pydantic import BaseModel
from app.schemas.pet import PetResponse


class GachaRatesResponse(BaseModel):
    common_rate: float = 0.85
    rare_rate: float = 0.12
    epic_rate: float = 0.02
    legendary_rate: float = 0.01
    cost_single: int = 100
    cost_ten: int = 900


class GachaPullResponse(BaseModel):
    success: bool
    pet: PetResponse
    remaining_diamonds: int
    is_new_companion: bool
    message: str


class GachaMultiPullResponse(BaseModel):
    success: bool
    pets: list[PetResponse]
    remaining_diamonds: int
    highest_rarity: str
    message: str
