"""
Gacha router — /api/gacha endpoints for Chinese mythological beast summoning.
"""
import random
from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.models.pet import Pet
from app.models.player import PlayerStats
from app.schemas.gacha import (
    GachaRatesResponse,
    GachaPullResponse,
    GachaMultiPullResponse,
)
from app.schemas.pet import PetResponse
from app.utils.security import get_current_user_id

router = APIRouter(prefix="/api/gacha", tags=["Gacha"])

COST_SINGLE = 100
COST_TEN = 900

# 5x harder rate thresholds:
# Legendary: 1% (0.01)
# Epic: 2% (0.02)
# Rare: 12% (0.12)
# Common: 85% (0.85)

COMMON_POOL = [
    {"name": "Wood Rat (木鼠)", "pet_type": "wood_rat", "rarity": "common", "buff_type": "none", "sprite_key": "pet_dummy_1"},
    {"name": "Stone Rabbit (石兔)", "pet_type": "stone_rabbit", "rarity": "common", "buff_type": "none", "sprite_key": "pet_dummy_1"},
]

RARE_POOL = [
    {"name": "Fu Dog (福狮 / Guardian Lion)", "pet_type": "fu_dog", "rarity": "rare", "buff_type": "shield", "sprite_key": "pet_dummy_2"},
]

EPIC_POOL = [
    {"name": "Fire Crane (火鹤)", "pet_type": "fire_crane", "rarity": "epic", "buff_type": "damage_boost", "sprite_key": "pet_dummy_1"},
]

LEGENDARY_POOL = [
    {"name": "Azure Dragon (青龙)", "pet_type": "azure_dragon", "rarity": "legendary", "buff_type": "time_extender", "sprite_key": "pet_dummy_2"},
    {"name": "Qilin (麒麟)", "pet_type": "qilin", "rarity": "legendary", "buff_type": "damage_boost", "sprite_key": "pet_dummy_2"},
]


def _roll_single_pet() -> dict:
    roll = random.random()
    if roll < 0.01:
        return random.choice(LEGENDARY_POOL)
    elif roll < 0.03:
        return random.choice(EPIC_POOL)
    elif roll < 0.15:
        return random.choice(RARE_POOL)
    else:
        return random.choice(COMMON_POOL)


@router.get("/rates", response_model=GachaRatesResponse)
async def get_gacha_rates():
    """Returns drop rates and pull costs."""
    return GachaRatesResponse()


@router.post("/pull", response_model=GachaPullResponse)
async def pull_single_gacha(
    user_id: int = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    """
    Summon 1 pet (Cost: 100 Diamonds).
    """
    stats_res = await db.execute(
        select(PlayerStats).where(PlayerStats.user_id == user_id)
    )
    stats = stats_res.scalar_one_or_none()
    if not stats:
        raise HTTPException(status_code=404, detail="Player stats not found")

    if stats.diamonds < COST_SINGLE:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Not enough diamonds. Required: {COST_SINGLE}, You have: {stats.diamonds}.",
        )

    # Deduct diamonds
    stats.diamonds -= COST_SINGLE

    # Check if user currently has an active pet
    active_res = await db.execute(
        select(Pet).where(Pet.user_id == user_id, Pet.is_active == True)
    )
    has_active_pet = active_res.scalar_one_or_none() is not None

    template = _roll_single_pet()
    new_pet = Pet(
        user_id=user_id,
        name=template["name"],
        pet_type=template["pet_type"],
        rarity=template["rarity"],
        buff_type=template["buff_type"],
        affection=50.0,
        is_active=not has_active_pet,  # Auto-equip if first pet!
        sprite_key=template["sprite_key"],
    )
    db.add(new_pet)
    await db.commit()
    await db.refresh(new_pet)
    await db.refresh(stats)

    return GachaPullResponse(
        success=True,
        pet=PetResponse.model_validate(new_pet),
        remaining_diamonds=stats.diamonds,
        is_new_companion=not has_active_pet,
        message=f"Summoned {new_pet.name} ({new_pet.rarity.upper()})!",
    )


@router.post("/pull-10", response_model=GachaMultiPullResponse)
async def pull_ten_gacha(
    user_id: int = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    """
    Summon 10 pets (Cost: 900 Diamonds, 10% discount).
    """
    stats_res = await db.execute(
        select(PlayerStats).where(PlayerStats.user_id == user_id)
    )
    stats = stats_res.scalar_one_or_none()
    if not stats:
        raise HTTPException(status_code=404, detail="Player stats not found")

    if stats.diamonds < COST_TEN:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Not enough diamonds. Required: {COST_TEN}, You have: {stats.diamonds}.",
        )

    # Deduct diamonds
    stats.diamonds -= COST_TEN

    active_res = await db.execute(
        select(Pet).where(Pet.user_id == user_id, Pet.is_active == True)
    )
    has_active_pet = active_res.scalar_one_or_none() is not None

    created_pets = []
    rarity_ranks = {"common": 1, "rare": 2, "epic": 3, "legendary": 4}
    highest_rank = 1

    for i in range(10):
        template = _roll_single_pet()
        rank = rarity_ranks.get(template["rarity"], 1)
        if rank > highest_rank:
            highest_rank = rank

        is_first = (not has_active_pet and i == 0)
        p = Pet(
            user_id=user_id,
            name=template["name"],
            pet_type=template["pet_type"],
            rarity=template["rarity"],
            buff_type=template["buff_type"],
            affection=50.0,
            is_active=is_first,
            sprite_key=template["sprite_key"],
        )
        db.add(p)
        created_pets.append(p)

    await db.commit()
    for p in created_pets:
        await db.refresh(p)
    await db.refresh(stats)

    inv_rarities = {1: "common", 2: "rare", 3: "epic", 4: "legendary"}
    highest_rarity_str = inv_rarities[highest_rank]

    return GachaMultiPullResponse(
        success=True,
        pets=[PetResponse.model_validate(p) for p in created_pets],
        remaining_diamonds=stats.diamonds,
        highest_rarity=highest_rarity_str,
        message=f"10x Summon complete! Highest rarity pulled: {highest_rarity_str.upper()}.",
    )
