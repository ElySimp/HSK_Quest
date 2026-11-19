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
    buff_title: str | None = None
    buff_description: str | None = None
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
    remaining_coins: Optional[int] = None
    message: str


class BlackjackActionRequest(BaseModel):
    game_token: str


class BlackjackStateResponse(BaseModel):
    game_token: Optional[str] = None
    status: str  # "playing", "player_win", "pet_win", "push", "player_bust"
    player_cards: list[str]
    dealer_cards: list[str]
    player_score: int
    dealer_score: int
    bet_coins: int
    coins_delta: int
    remaining_coins: int
    affection_gained: float
    current_affection: float
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


class RpsSettleRequest(BaseModel):
    round_id: str = Field(..., min_length=6, max_length=64)
    player_choice: str = Field(..., pattern=r"^(rock|paper|scissors)$")
    pet_choice: str = Field(..., pattern=r"^(rock|paper|scissors)$")
    outcome: str = Field(..., pattern=r"^(win|tie|lose)$")
    coins_dropped: int = Field(default=0, ge=0, le=5)


class BlackjackSettleRequest(BaseModel):
    round_id: str = Field(..., min_length=6, max_length=64)
    player_cards: list[str] = Field(..., min_length=2, max_length=11)
    dealer_cards: list[str] = Field(..., min_length=2, max_length=11)
    outcome: str = Field(..., pattern=r"^(player_win|pet_win|push|player_bust)$")
