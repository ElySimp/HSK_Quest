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

# Catalog of available alchemical elixirs, human meals, and companion pet treats
SHOP_ITEMS: dict[str, dict] = {
    # ------------------------------------------------------------------
    # ELIXIRS (4 Tiered Red Healing Potions — % Max HP Heal in Battle)
    # ------------------------------------------------------------------
    "minor_healing_potion": {
        "id": "minor_healing_potion",
        "name": "Minor Healing Potion",
        "name_zh": "小治疗药水",
        "category": "elixir",
        "description": "Small crimson vial that instantly restores 25% of your Max HP in battle.",
        "hp_restore": 0,
        "hp_restore_pct": 25,
        "affection_boost": 0,
        "price_coins": 60,
        "price_diamonds": 0,
        "icon_emoji": "🧪",
        "sprite_res": "potion_minor_red",
    },
    "healing_potion": {
        "id": "healing_potion",
        "name": "Healing Potion",
        "name_zh": "中治疗药水",
        "category": "elixir",
        "description": "Refined alchemical elixir that restores 50% of your Max HP in battle.",
        "hp_restore": 0,
        "hp_restore_pct": 50,
        "affection_boost": 0,
        "price_coins": 120,
        "price_diamonds": 0,
        "icon_emoji": "🧪",
        "sprite_res": "potion_regular_red",
    },
    "greater_healing_potion": {
        "id": "greater_healing_potion",
        "name": "Greater Healing Potion",
        "name_zh": "大治疗药水",
        "category": "elixir",
        "description": "Concentrated crimson bottle that restores 75% of your Max HP in battle.",
        "hp_restore": 0,
        "hp_restore_pct": 75,
        "affection_boost": 0,
        "price_coins": 200,
        "price_diamonds": 0,
        "icon_emoji": "🧪",
        "sprite_res": "potion_greater_red",
    },
    "supreme_healing_potion": {
        "id": "supreme_healing_potion",
        "name": "Supreme Healing Potion",
        "name_zh": "极品灵药",
        "category": "elixir",
        "description": "Imperial alchemical jar that fully restores 100% of your Max HP!",
        "hp_restore": 0,
        "hp_restore_pct": 100,
        "affection_boost": 0,
        "price_coins": 320,
        "price_diamonds": 0,
        "icon_emoji": "🏺",
        "sprite_res": "potion_supreme_red",
    },
    # ------------------------------------------------------------------
    # HUMAN FOOD (Escalating HP/Coin Efficiency for Out-of-Battle Healing)
    # ------------------------------------------------------------------
    "rice_ball_hsk1": {
        "id": "rice_ball_hsk1",
        "name": "Warm Rice Ball",
        "name_zh": "饭团",
        "category": "food",
        "description": "Steamed sticky rice ball wrapped in crisp nori. Restores +60 Hero HP (4.0 HP/Coin).",
        "hp_restore": 60,
        "hp_restore_pct": 0,
        "affection_boost": 0,
        "price_coins": 15,
        "price_diamonds": 0,
        "icon_emoji": "🍙",
        "sprite_res": None,
    },
    "dumpling_hsk2": {
        "id": "dumpling_hsk2",
        "name": "Jade Dumplings",
        "name_zh": "水饺",
        "category": "food",
        "description": "Steamed dumplings stuffed with aromatic herbs. Restores +175 Hero HP (5.0 HP/Coin).",
        "hp_restore": 175,
        "hp_restore_pct": 0,
        "affection_boost": 0,
        "price_coins": 35,
        "price_diamonds": 0,
        "icon_emoji": "🥟",
        "sprite_res": None,
    },
    "noodle_hsk3": {
        "id": "noodle_hsk3",
        "name": "Lanzhou Beef Noodles",
        "name_zh": "牛肉面",
        "category": "food",
        "description": "Rich spiced beef broth with hand-pulled noodles. Restores +455 Hero HP (6.5 HP/Coin).",
        "hp_restore": 455,
        "hp_restore_pct": 0,
        "affection_boost": 0,
        "price_coins": 70,
        "price_diamonds": 0,
        "icon_emoji": "🍜",
        "sprite_res": None,
    },
    "hotpot_hsk4": {
        "id": "hotpot_hsk4",
        "name": "Imperial Hotpot",
        "name_zh": "麻辣火锅",
        "category": "food",
        "description": "Fiery simmering cauldron of meats and mushrooms. Restores +1040 Hero HP (8.0 HP/Coin).",
        "hp_restore": 1040,
        "hp_restore_pct": 0,
        "affection_boost": 0,
        "price_coins": 130,
        "price_diamonds": 0,
        "icon_emoji": "🍲",
        "sprite_res": None,
    },
    "roast_duck_hsk5": {
        "id": "roast_duck_hsk5",
        "name": "Peking Roast Duck",
        "name_zh": "北京烤鸭",
        "category": "food",
        "description": "Crispy glazed imperial roast duck feast. Restores +2200 Hero HP (10.0 HP/Coin — Best Value!).",
        "hp_restore": 2200,
        "hp_restore_pct": 0,
        "affection_boost": 0,
        "price_coins": 220,
        "price_diamonds": 0,
        "icon_emoji": "🦆",
        "sprite_res": None,
    },
    # ------------------------------------------------------------------
    # PET FOOD (Escalating Affection/Coin Efficiency for Pet Sanctuary)
    # ------------------------------------------------------------------
    "pet_bone_snack": {
        "id": "pet_bone_snack",
        "name": "Crispy Spirit Bone",
        "name_zh": "灵骨棒",
        "category": "pet_food",
        "description": "Crunchy spirit bone treat for companion pets. Restores +12 Pet Affection (0.8 ❤️/Coin).",
        "hp_restore": 0,
        "hp_restore_pct": 0,
        "affection_boost": 12,
        "price_coins": 15,
        "price_diamonds": 0,
        "icon_emoji": "🦴",
        "sprite_res": None,
    },
    "pet_honey_cookie": {
        "id": "pet_honey_cookie",
        "name": "Honey Paw Biscuits",
        "name_zh": "蜜爪饼干",
        "category": "pet_food",
        "description": "Paw-shaped biscuits glazed with wildflower honey. Restores +30 Pet Affection (1.0 ❤️/Coin).",
        "hp_restore": 0,
        "hp_restore_pct": 0,
        "affection_boost": 30,
        "price_coins": 30,
        "price_diamonds": 0,
        "icon_emoji": "🍪",
        "sprite_res": None,
    },
    "pet_dried_koi": {
        "id": "pet_dried_koi",
        "name": "Golden Dried Koi",
        "name_zh": "黄金鱼干",
        "category": "pet_food",
        "description": "Sun-dried river koi treat packed with spirit energy. Restores +60 Pet Affection (1.2 ❤️/Coin).",
        "hp_restore": 0,
        "hp_restore_pct": 0,
        "affection_boost": 60,
        "price_coins": 50,
        "price_diamonds": 0,
        "icon_emoji": "🐟",
        "sprite_res": None,
    },
    "pet_jade_bamboo": {
        "id": "pet_jade_bamboo",
        "name": "Jade Bamboo Shoot",
        "name_zh": "翠竹笋",
        "category": "pet_food",
        "description": "Tender celestial bamboo shoot from the Misty Peaks. Fully restores +100 Pet Affection (1.4 ❤️/Coin — Best Value!).",
        "hp_restore": 0,
        "hp_restore_pct": 0,
        "affection_boost": 100,
        "price_coins": 70,
        "price_diamonds": 0,
        "icon_emoji": "🎋",
        "sprite_res": None,
    },
    "mooncake_hsk5": {
        "id": "mooncake_hsk5",
        "name": "Celestial Spirit Peach",
        "name_zh": "仙桃酥",
        "category": "pet_food",
        "description": "Mythical immortal peach pastry. Restores +100 Pet Affection & +500 Hero HP!",
        "hp_restore": 500,
        "hp_restore_pct": 0,
        "affection_boost": 100,
        "price_coins": 0,
        "price_diamonds": 20,
        "icon_emoji": "🍑",
        "sprite_res": None,
    },
}

# Backwards compatibility mapping for legacy inventory rows in existing databases
LEGACY_SHOP_ITEMS: dict[str, dict] = {
    "salve_elixir": {
        "id": "salve_elixir",
        "name": "Minor Healing Potion",
        "name_zh": "小治疗药水",
        "category": "elixir",
        "description": "Small crimson vial that instantly restores 25% of your Max HP in battle.",
        "hp_restore": 0,
        "hp_restore_pct": 25,
        "affection_boost": 0,
        "price_coins": 60,
        "price_diamonds": 0,
        "icon_emoji": "🧪",
        "sprite_res": "potion_minor_red",
    },
    "vitality_elixir": {
        "id": "vitality_elixir",
        "name": "Healing Potion",
        "name_zh": "中治疗药水",
        "category": "elixir",
        "description": "Refined alchemical elixir that restores 50% of your Max HP in battle.",
        "hp_restore": 0,
        "hp_restore_pct": 50,
        "affection_boost": 0,
        "price_coins": 120,
        "price_diamonds": 0,
        "icon_emoji": "🧪",
        "sprite_res": "potion_regular_red",
    },
}


def get_item_meta(item_id: str) -> dict | None:
    """Resolve item metadata from active catalog or legacy item dictionary."""
    return SHOP_ITEMS.get(item_id) or LEGACY_SHOP_ITEMS.get(item_id)


def compute_scaled_coin_price(item_meta: dict, player_level: int) -> int:
    """
    Compute coin price for an item.
    Elixirs (% Max HP potions) scale dynamically with the player's current level.
    """
    base_coins = item_meta.get("price_coins", 0)
    if item_meta.get("category") == "elixir" and base_coins > 0:
        return int(round(base_coins * (1.0 + max(0, player_level - 1) * 0.25)))
    return base_coins


@router.get("/catalog", response_model=ShopCatalogResponse)
@router.get("/items", response_model=ShopCatalogResponse)
async def get_shop_catalog(
    user_id: int = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    """
    Get full list of items available in the shop with level-scaled potion pricing and stat buffs.
    """
    stats_res = await db.execute(
        select(PlayerStats).where(PlayerStats.user_id == user_id)
    )
    stats = stats_res.scalar_one_or_none()
    player_level = stats.level if stats else 1

    items = []
    for item_data in SHOP_ITEMS.values():
        scaled_data = dict(item_data)
        scaled_data["price_coins"] = compute_scaled_coin_price(item_data, player_level)
        items.append(ShopItem(**scaled_data))
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
    item_meta = get_item_meta(request.item_id)
    if not item_meta:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Item '{request.item_id}' does not exist in the shop catalog.",
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

    quantity = request.quantity
    unit_coin_price = compute_scaled_coin_price(item_meta, stats.level)
    total_coins = unit_coin_price * quantity
    total_diamonds = item_meta["price_diamonds"] * quantity

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
            item_type=item_meta["category"],
            item_id=request.item_id,
            quantity=quantity,
        )
        db.add(new_inv)
        total_owned = quantity

    await db.commit()

    return BuyItemResponse(
        success=True,
        message=f"Successfully purchased {quantity}x {item_meta['name']}!",
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
        catalog_info = get_item_meta(row.item_id) or {}
        items.append(
            InventoryItemResponse(
                id=row.id,
                item_id=row.item_id,
                item_type=catalog_info.get("category", row.item_type),
                quantity=row.quantity,
                name=catalog_info.get("name", row.item_id.replace("_", " ").title()),
                name_zh=catalog_info.get("name_zh", ""),
                description=catalog_info.get("description", "A consumable item."),
                hp_restore=catalog_info.get("hp_restore", 0),
                hp_restore_pct=catalog_info.get("hp_restore_pct", 0),
                affection_boost=catalog_info.get("affection_boost", 0),
                icon_emoji=catalog_info.get("icon_emoji", "📦"),
                sprite_res=catalog_info.get("sprite_res"),
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

    item_meta = get_item_meta(request.item_id)
    if not item_meta:
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

    # Heal player HP (percentage-based for potions, flat for meals)
    old_hp = stats.hp
    if item_meta.get("hp_restore_pct", 0) > 0:
        target_hp_heal = max(1, int(round(stats.max_hp * item_meta["hp_restore_pct"] / 100.0)))
    else:
        target_hp_heal = item_meta.get("hp_restore", 0)

    stats.hp = min(stats.max_hp, stats.hp + target_hp_heal)
    stats.survival_hp = stats.hp
    hp_restored = stats.hp - old_hp

    # Boost pet affection if applicable
    affection_boost = item_meta.get("affection_boost", 0)
    affection_gained = 0
    current_affection = None

    if active_pet and affection_boost > 0:
        old_affection = active_pet.affection
        active_pet.affection = min(100, active_pet.affection + affection_boost)
        active_pet.last_interaction = datetime.utcnow()
        affection_gained = active_pet.affection - old_affection
        current_affection = active_pet.affection

    await db.commit()

    msg = f"Used {item_meta['name']}."
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
