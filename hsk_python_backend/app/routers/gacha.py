"""
Gacha router — /api/gacha endpoints for Chinese mythological beast summoning.
Enforces duplicate fusion/coin awards so the sanctuary collection stays clean.
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
    If player already owns the beast, boosts affection (+15) and awards +40 bonus coins.
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

    template = _roll_single_pet()

    # Check if user already owns this species
    existing_pet_res = await db.execute(
        select(Pet).where(Pet.user_id == user_id, Pet.pet_type == template["pet_type"])
    )
    existing_pet = existing_pet_res.scalar_one_or_none()

    if existing_pet:
        # Duplicate handling: boost affection and award bonus coins
        existing_pet.affection = min(100.0, existing_pet.affection + 15.0)
        stats.coins += 40
        await db.commit()
        await db.refresh(existing_pet)
        await db.refresh(stats)

        return GachaPullResponse(
            success=True,
            pet=PetResponse.model_validate(existing_pet),
            remaining_diamonds=stats.diamonds,
            is_new_companion=False,
            message=f"Duplicate {existing_pet.name}! Affection +15, +40 Bonus Coins awarded!",
        )

    # Check if user currently has an active pet
    active_res = await db.execute(
        select(Pet).where(Pet.user_id == user_id, Pet.is_active == True)
    )
    has_active_pet = active_res.scalar_one_or_none() is not None

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
    Duplicates boost affection (+15) and award +40 bonus coins each.
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

    # Fetch all existing pets owned by user
    owned_res = await db.execute(
        select(Pet).where(Pet.user_id == user_id)
    )
    owned_pets_map = {p.pet_type: p for p in owned_res.scalars().all()}
    has_active_pet = any(p.is_active for p in owned_pets_map.values())

    result_pets = []
    rarity_ranks = {"common": 1, "rare": 2, "epic": 3, "legendary": 4}
    highest_rank = 1
    duplicate_count = 0

    for i in range(10):
        template = _roll_single_pet()
        rank = rarity_ranks.get(template["rarity"], 1)
        if rank > highest_rank:
            highest_rank = rank

        pet_type = template["pet_type"]
        if pet_type in owned_pets_map:
            # Duplicate
            p = owned_pets_map[pet_type]
            p.affection = min(100.0, p.affection + 15.0)
            stats.coins += 40
            duplicate_count += 1
            result_pets.append(p)
        else:
            is_first = (not has_active_pet and len(result_pets) == 0)
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
            owned_pets_map[pet_type] = p
            if is_first:
                has_active_pet = True
            result_pets.append(p)

    await db.commit()
    for p in result_pets:
        await db.refresh(p)
    await db.refresh(stats)

    inv_rarities = {1: "common", 2: "rare", 3: "epic", 4: "legendary"}
    highest_rarity_str = inv_rarities[highest_rank]

    msg = f"10x Summon complete! Highest rarity pulled: {highest_rarity_str.upper()}."
    if duplicate_count > 0:
        msg += f" ({duplicate_count} duplicate pulls converted to Affection & Bonus Coins!)"

    return GachaMultiPullResponse(
        success=True,
        pets=[PetResponse.model_validate(p) for p in result_pets],
        remaining_diamonds=stats.diamonds,
        highest_rarity=highest_rarity_str,
        message=msg,
    )
