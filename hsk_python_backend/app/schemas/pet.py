"""
Pydantic schemas for Pet and Tamagotchi mechanics.
"""
from typing import Optional
from pydantic import BaseModel, Field


class PetResponse(BaseModel):
    id: int
    name: str
    pet_type: str
    rarity: str
    buff_type: str
    affection: float
    is_active: bool
    sprite_key: str

    class Config:
        from_attributes = True


class PetListResponse(BaseModel):
    pets: list[PetResponse]
    active_pet: Optional[PetResponse] = None


class RpsRequest(BaseModel):
    choice: str = Field(..., description="rock, paper, or scissors")


class RpsResponse(BaseModel):
    player_choice: str
    pet_choice: str
    outcome: str  # "win", "tie", "lose"
    affection_gained: float
    current_affection: float
    coins_dropped: int
    message: str


class FeedPetRequest(BaseModel):
    food_id: Optional[str] = None


class FeedPetResponse(BaseModel):
    success: bool
    affection_gained: float
    current_affection: float
    hp_healed: int
    current_player_hp: int
    message: str
