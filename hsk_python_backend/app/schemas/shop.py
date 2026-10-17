"""
Pydantic schemas for the Shop and Player Inventory systems.
"""
from typing import List, Optional
from pydantic import BaseModel, Field


class ShopItem(BaseModel):
    id: str
    name: str
    name_zh: str
    category: str  # "food" | "elixir"
    description: str
    hp_restore: int
    affection_boost: int
    price_coins: int = 0
    price_diamonds: int = 0
    icon_emoji: str


class ShopCatalogResponse(BaseModel):
    items: List[ShopItem]


class BuyItemRequest(BaseModel):
    item_id: str
    quantity: int = Field(default=1, ge=1, le=99)


class BuyItemResponse(BaseModel):
    success: bool
    message: str
    item_id: str
    quantity_bought: int
    total_owned: int
    remaining_coins: int
    remaining_diamonds: int


class InventoryItemResponse(BaseModel):
    id: int
    item_id: str
    item_type: str
    quantity: int
    name: str
    name_zh: str
    description: str
    hp_restore: int
    affection_boost: int
    icon_emoji: str


class InventoryListResponse(BaseModel):
    items: List[InventoryItemResponse]


class UseItemRequest(BaseModel):
    item_id: str


class UseItemResponse(BaseModel):
    success: bool
    message: str
    hp_restored: int
    current_hp: int
    max_hp: int
    affection_gained: int
    current_affection: Optional[int] = None
    remaining_quantity: int
