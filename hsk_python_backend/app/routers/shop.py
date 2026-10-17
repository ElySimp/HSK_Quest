"""
Shop router — /api/shop endpoints for the catalog, buying items, inventory bag, and item consumption.
"""
from datetime import datetime
from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.models.inventory import Inventory
from app.models.pet import Pet
from app.models.player import PlayerStats
from app.schemas.shop import (
    BuyItemRequest,
    BuyItemResponse,
    InventoryItemResponse,
    InventoryListResponse,
    ShopCatalogResponse,
    ShopItem,
    UseItemRequest,
    UseItemResponse,
)
from app.utils.security import get_current_user_id

router = APIRouter(prefix="/api/shop", tags=["Shop"])

# Catalog of available food and consumable items in the ancient Chinese market
SHOP_ITEMS: dict[str, dict] = {
    "rice_ball_hsk1": {
        "id": "rice_ball_hsk1",
        "name": "Rice Ball",
        "name_zh": "饭团 (Fàntuán)",
        "category": "food",
        "description": "A fragrant seasoned rice ball. Restores a small amount of HP and cheers up your companion pet.",
        "hp_restore": 20,
        "affection_boost": 10,
        "price_coins": 15,
        "price_diamonds": 0,
        "icon_emoji": "🍙",
    },
    "dumpling_hsk2": {
        "id": "dumpling_hsk2",
        "name": "Jade Dumplings",
        "name_zh": "水饺 (Shuǐjiǎo)",
        "category": "food",
        "description": "Steamed dumplings stuffed with savory herbs. Restores moderate HP and warms your pet's heart.",
        "hp_restore": 50,
        "affection_boost": 20,
        "price_coins": 40,
        "price_diamonds": 0,
        "icon_emoji": "🥟",
    },
    "noodle_hsk3": {
        "id": "noodle_hsk3",
        "name": "Beef Noodle Bowl",
        "name_zh": "牛肉面 (Niúròumiàn)",
        "category": "food",
        "description": "Hearty hand-pulled noodles in rich broth. High vitality recovery for arduous battles.",
        "hp_restore": 100,
        "affection_boost": 35,
        "price_coins": 80,
        "price_diamonds": 0,
        "icon_emoji": "🍜",
    },
    "hotpot_hsk4": {
        "id": "hotpot_hsk4",
        "name": "Imperial Hotpot",
        "name_zh": "火锅 (Huǒguō)",
        "category": "food",
        "description": "A bubbling cauldron of spicy delicacies. Greatly revitalizes both adventurer and companion.",
        "hp_restore": 200,
        "affection_boost": 50,
        "price_coins": 160,
        "price_diamonds": 0,
        "icon_emoji": "🍲",
    },
    "mooncake_hsk5": {
        "id": "mooncake_hsk5",
        "name": "Celestial Mooncake",
        "name_zh": "月饼 (Yuèbǐng)",
        "category": "food",
        "description": "Baked under mid-autumn moonlight with lotus paste. Fully restores HP and maximizes pet affection.",
        "hp_restore": 999,
        "affection_boost": 100,
        "price_coins": 0,
        "price_diamonds": 50,
        "icon_emoji": "🥮",
    },
    "salve_elixir": {
        "id": "salve_elixir",
        "name": "Healing Salve",
        "name_zh": "金创药 (Jīnchuāngyào)",
        "category": "elixir",
        "description": "Traditional herbal medicine for quick first aid on battle wounds.",
        "hp_restore": 60,
        "affection_boost": 0,
        "price_coins": 45,
        "price_diamonds": 0,
        "icon_emoji": "🧪",
    },
    "vitality_elixir": {
        "id": "vitality_elixir",
        "name": "Vitality Elixir",
        "name_zh": "大还丹 (Dàhuándān)",
        "category": "elixir",
        "description": "A legendary Taoist pill condensed from celestial herbs. Restores 180 HP.",
        "hp_restore": 180,
        "affection_boost": 0,
        "price_coins": 120,
        "price_diamonds": 0,
        "icon_emoji": "⚗️",
    },
}


@router.get("/catalog", response_model=ShopCatalogResponse)
@router.get("/items", response_model=ShopCatalogResponse)
async def get_shop_catalog():
    """
    Get full list of items available in the shop with pricing and stat buffs.
    """
    items = [ShopItem(**item_data) for item_data in SHOP_ITEMS.values()]
    return ShopCatalogResponse(items=items)


@router.post("/buy", response_model=BuyItemResponse)
async def buy_item(
    request: BuyItemRequest,
    user_id: int = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    """
    Purchase one or more items from the shop using Coins or Diamonds.
    """
    item_data = SHOP_ITEMS.get(request.item_id)
    if not item_data:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Item '{request.item_id}' does not exist in the shop catalog.",
        )

    quantity = request.quantity
    total_coins = item_data["price_coins"] * quantity
    total_diamonds = item_data["price_diamonds"] * quantity

    stats_res = await db.execute(
        select(PlayerStats).where(PlayerStats.user_id == user_id)
    )
    stats = stats_res.scalar_one_or_none()
    if not stats:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Player stats not found.",
        )

    # Balance validation
    if total_coins > 0 and stats.coins < total_coins:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Not enough coins. Required: {total_coins}, Current: {stats.coins}",
        )
    if total_diamonds > 0 and stats.diamonds < total_diamonds:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Not enough diamonds. Required: {total_diamonds}, Current: {stats.diamonds}",
        )

    # Deduct currency
    stats.coins -= total_coins
    stats.diamonds -= total_diamonds

    # Update or insert into inventory
    inv_res = await db.execute(
        select(Inventory).where(
            Inventory.user_id == user_id,
            Inventory.item_id == request.item_id,
        )
    )
    inventory_item = inv_res.scalar_one_or_none()

    if inventory_item:
        inventory_item.quantity += quantity
        total_owned = inventory_item.quantity
    else:
        new_inv = Inventory(
            user_id=user_id,
            item_type=item_data["category"],
            item_id=request.item_id,
            quantity=quantity,
        )
        db.add(new_inv)
        total_owned = quantity

    await db.commit()

    return BuyItemResponse(
        success=True,
        message=f"Successfully purchased {quantity}x {item_data['name']}!",
        item_id=request.item_id,
        quantity_bought=quantity,
        total_owned=total_owned,
        remaining_coins=stats.coins,
        remaining_diamonds=stats.diamonds,
    )


@router.get("/inventory", response_model=InventoryListResponse)
async def get_player_inventory(
    user_id: int = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    """
    Retrieve all consumable items currently held in the player's bag.
    """
    inv_res = await db.execute(
        select(Inventory).where(
            Inventory.user_id == user_id,
            Inventory.quantity > 0,
        )
    )
    rows = inv_res.scalars().all()

    items = []
    for row in rows:
        catalog_info = SHOP_ITEMS.get(row.item_id, {})
        items.append(
            InventoryItemResponse(
                id=row.id,
                item_id=row.item_id,
                item_type=row.item_type,
                quantity=row.quantity,
                name=catalog_info.get("name", row.item_id.replace("_", " ").title()),
                name_zh=catalog_info.get("name_zh", ""),
                description=catalog_info.get("description", "A consumable item."),
                hp_restore=catalog_info.get("hp_restore", 0),
                affection_boost=catalog_info.get("affection_boost", 0),
                icon_emoji=catalog_info.get("icon_emoji", "📦"),
            )
        )

    return InventoryListResponse(items=items)


@router.post("/use", response_model=UseItemResponse)
async def use_inventory_item(
    request: UseItemRequest,
    user_id: int = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    """
    Consume an item from inventory to heal player HP and/or increase equipped pet Affection.
    """
    inv_res = await db.execute(
        select(Inventory).where(
            Inventory.user_id == user_id,
            Inventory.item_id == request.item_id,
            Inventory.quantity > 0,
        )
    )
    inventory_item = inv_res.scalar_one_or_none()
    if not inventory_item:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Item '{request.item_id}' not found in your inventory.",
        )

    catalog_info = SHOP_ITEMS.get(request.item_id)
    if not catalog_info:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Item specifications not found in catalog.",
        )

    stats_res = await db.execute(
        select(PlayerStats).where(PlayerStats.user_id == user_id)
    )
    stats = stats_res.scalar_one_or_none()
    if not stats:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Player stats not found.",
        )

    # Fetch active companion pet (if any)
    pet_res = await db.execute(
        select(Pet).where(Pet.user_id == user_id, Pet.is_active == True)
    )
    active_pet = pet_res.scalar_one_or_none()

    # Decrement inventory quantity
    inventory_item.quantity -= 1
    remaining_qty = inventory_item.quantity
    if inventory_item.quantity <= 0:
        await db.delete(inventory_item)

    # Heal player HP
    old_hp = stats.hp
    hp_restore_amount = catalog_info.get("hp_restore", 0)
    stats.hp = min(stats.max_hp, stats.hp + hp_restore_amount)
    stats.survival_hp = stats.hp
    hp_restored = stats.hp - old_hp

    # Boost pet affection if applicable
    affection_boost = catalog_info.get("affection_boost", 0)
    affection_gained = 0
    current_affection = None

    if active_pet and affection_boost > 0:
        old_affection = active_pet.affection
        active_pet.affection = min(100, active_pet.affection + affection_boost)
        active_pet.last_interaction = datetime.utcnow()
        affection_gained = active_pet.affection - old_affection
        current_affection = active_pet.affection

    await db.commit()

    msg = f"Used {catalog_info['name']}."
    if hp_restored > 0:
        msg += f" Restored {hp_restored} HP!"
    if affection_gained > 0 and active_pet:
        msg += f" {active_pet.name} gained +{affection_gained} Affection!"

    return UseItemResponse(
        success=True,
        message=msg,
        hp_restored=hp_restored,
        current_hp=stats.hp,
        max_hp=stats.max_hp,
        affection_gained=affection_gained,
        current_affection=current_affection,
        remaining_quantity=remaining_qty,
    )
