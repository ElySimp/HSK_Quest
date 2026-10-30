"""
Pet router — /api/pet endpoints for Tamagotchi care, Rock-Paper-Scissors, and equipping.
"""
from datetime import datetime, timezone
import random
from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.models.pet import Pet
from app.models.player import PlayerStats
from app.models.inventory import Inventory
from app.routers.shop import SHOP_ITEMS, get_item_meta
from app.schemas.pet import (
    PetResponse,
    PetListResponse,
    RpsRequest,
    RpsResponse,
    FeedPetRequest,
    FeedPetResponse,
)
from app.utils.security import get_current_user_id

router = APIRouter(prefix="/api/pet", tags=["Pet"])

VALID_RPS_CHOICES = {"rock", "paper", "scissors"}
WINNING_CONDITIONS = {
    ("rock", "scissors"),
    ("scissors", "paper"),
    ("paper", "rock"),
}


@router.get("/active", response_model=PetResponse | None)
async def get_active_pet(
    user_id: int = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    """
    Get player's currently equipped pet.
    Returns null if player does not own or have any active pet equipped.
    """
    result = await db.execute(
        select(Pet).where(Pet.user_id == user_id, Pet.is_active == True)
    )
    return result.scalar_one_or_none()


@router.get("/list", response_model=PetListResponse)
async def list_pets(
    user_id: int = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    """
    List all pets owned by the player, alongside the currently active pet.
    """
    result = await db.execute(
        select(Pet).where(Pet.user_id == user_id).order_by(Pet.id.asc())
    )
    pets = result.scalars().all()
    active_pet = next((p for p in pets if p.is_active), None)

    return PetListResponse(
        pets=[PetResponse.model_validate(p) for p in pets],
        active_pet=PetResponse.model_validate(active_pet) if active_pet else None,
    )


@router.post("/equip/{pet_id}", response_model=PetResponse)
async def equip_pet(
    pet_id: int,
    user_id: int = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    """
    Equip a companion pet, setting all other pets to inactive.
    """
    # Verify ownership
    result = await db.execute(
        select(Pet).where(Pet.id == pet_id, Pet.user_id == user_id)
    )
    target_pet = result.scalar_one_or_none()
    if not target_pet:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Pet not found in your collection",
        )

    # Deactivate all pets for this user
    await db.execute(
        update(Pet).where(Pet.user_id == user_id).values(is_active=False)
    )

    # Activate selected pet
    target_pet.is_active = True
    await db.commit()
    await db.refresh(target_pet)

    return target_pet


@router.post("/interact/rps", response_model=RpsResponse)
async def play_rock_paper_scissors(
    request: RpsRequest,
    user_id: int = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    """
    Play Rock-Paper-Scissors against your companion pet.
    Free to play: awards affection and a 50% chance of coin drop on win.
    """
    player_choice = request.choice.lower().strip()
    if player_choice not in VALID_RPS_CHOICES:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Choice must be 'rock', 'paper', or 'scissors'",
        )

    # Fetch active pet
    pet_res = await db.execute(
        select(Pet).where(Pet.user_id == user_id, Pet.is_active == True)
    )
    pet = pet_res.scalar_one_or_none()
    if not pet:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="You must have an active pet equipped to play mini-games",
        )

    pet_choice = random.choice(list(VALID_RPS_CHOICES))

    coins_dropped = 0
    if player_choice == pet_choice:
        outcome = "tie"
        affection_gained = 3.0
        msg = f"It's a draw! Both chose {player_choice}. Your pet enjoyed playing!"
    elif (player_choice, pet_choice) in WINNING_CONDITIONS:
        outcome = "win"
        affection_gained = 10.0
        # 50% chance of coin drops (5 to 20 coins)
        if random.random() < 0.5:
            coins_dropped = random.randint(5, 20)
        msg = f"Victory! Your {player_choice} beats {pet_choice}! Your pet is super happy!"
    else:
        outcome = "lose"
        affection_gained = 1.0
        msg = f"Your pet's {pet_choice} beats {player_choice}! Your pet looks proud!"

    # Update pet affection (clamped to 100.0)
    pet.affection = min(100.0, pet.affection + affection_gained)

    # Award coins if dropped
    if coins_dropped > 0:
        stats_res = await db.execute(
            select(PlayerStats).where(PlayerStats.user_id == user_id)
        )
        stats = stats_res.scalar_one_or_none()
        if stats:
            stats.coins += coins_dropped
            msg += f" Found {coins_dropped} coins on the floor!"

    await db.commit()
    await db.refresh(pet)

    return RpsResponse(
        player_choice=player_choice,
        pet_choice=pet_choice,
        outcome=outcome,
        affection_gained=affection_gained,
        current_affection=round(pet.affection, 1),
        coins_dropped=coins_dropped,
        message=msg,
    )


@router.post("/feed", response_model=FeedPetResponse)
async def feed_pet(
    request: FeedPetRequest,
    user_id: int = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    """
    Feed active pet:
    - If a specific food_id is provided, consumes 1 unit of that dish from inventory.
    - If food_id == "quick_snack", buys and feeds a Warm Rice Ball for 15 coins.
    - If food_id is None, consumes the first available food item in inventory or falls back to a 15-coin Rice Ball.
    Applies true catalog affection_boost and hp_restore stats.
    """
    # Fetch active pet
    pet_res = await db.execute(
        select(Pet).where(Pet.user_id == user_id, Pet.is_active == True)
    )
    pet = pet_res.scalar_one_or_none()
    if not pet:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="You must have an active pet equipped to feed",
        )

    stats_res = await db.execute(
        select(PlayerStats).where(PlayerStats.user_id == user_id)
    )
    stats = stats_res.scalar_one_or_none()
    if not stats:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Player stats not found",
        )

    if request.food_id and request.food_id != "quick_snack":
        inv_res = await db.execute(
            select(Inventory).where(
                Inventory.user_id == user_id,
                Inventory.item_id == request.food_id,
                Inventory.quantity > 0,
            )
        )
        food_item = inv_res.scalars().first()
        if not food_item:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="You do not have that pet treat in your bag. Visit the Merchant Shop first!",
            )
        food_item.quantity -= 1
        if food_item.quantity <= 0:
            await db.delete(food_item)

        catalog = get_item_meta(request.food_id) or {}
        affection_gain = float(catalog.get("affection_boost", 15))
        hp_gain = int(catalog.get("hp_restore", 0))
        fallback_name = getattr(food_item, "item_name", food_item.item_id.replace("_", " ").title())
        food_name = catalog.get("name", fallback_name)
    elif request.food_id == "quick_snack":
        if stats.coins < 15:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="Need 15 Coins for a Crispy Spirit Bone!",
            )
        stats.coins -= 15
        affection_gain = 15.0
        hp_gain = 0
        food_name = "Crispy Spirit Bone (灵骨棒)"
    else:
        inv_res = await db.execute(
            select(Inventory).where(
                Inventory.user_id == user_id,
                Inventory.item_type.in_(["pet_food", "food"]),
                Inventory.quantity > 0,
            )
        )
        bag_items = list(inv_res.scalars().all())
        # Prioritize exclusive pet_food items first
        bag_items.sort(key=lambda row: 0 if row.item_type == "pet_food" else 1)
        food_item = bag_items[0] if bag_items else None
        if food_item:
            food_item.quantity -= 1
            if food_item.quantity <= 0:
                await db.delete(food_item)
            catalog = get_item_meta(food_item.item_id) or {}
            affection_gain = float(catalog.get("affection_boost", 15))
            hp_gain = int(catalog.get("hp_restore", 0))
            fallback_name = getattr(food_item, "item_name", food_item.item_id.replace("_", " ").title())
            food_name = catalog.get("name", fallback_name)
        else:
            if stats.coins >= 15:
                stats.coins -= 15
                affection_gain = 15.0
                hp_gain = 0
                food_name = "Crispy Spirit Bone (灵骨棒)"
            else:
                raise HTTPException(
                    status_code=status.HTTP_400_BAD_REQUEST,
                    detail="No pet treats in your bag and not enough coins (15) for a Crispy Spirit Bone!",
                )

    pet.affection = min(100.0, pet.affection + affection_gain)
    pet.last_fed = datetime.now(timezone.utc)

    # Heal player HP and survival_hp if item also restores HP
    if hp_gain > 0:
        stats.hp = min(stats.max_hp, stats.hp + hp_gain)
        stats.survival_hp = min(stats.max_hp, stats.survival_hp + hp_gain)

    await db.commit()
    await db.refresh(pet)
    await db.refresh(stats)

    msg = f"Fed {food_name} to {pet.name}! +{int(affection_gain)} Affection"
    if hp_gain > 0:
        msg += f", +{hp_gain} HP!"
    else:
        msg += "!"

    return FeedPetResponse(
        success=True,
        affection_gained=affection_gain,
        current_affection=round(pet.affection, 1),
        hp_healed=hp_gain,
        current_player_hp=stats.hp,
        message=msg,
    )
