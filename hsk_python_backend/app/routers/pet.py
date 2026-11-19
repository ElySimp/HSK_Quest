"""
Pet router — /api/pet endpoints for Tamagotchi care, Rock-Paper-Scissors, and equipping.
"""
from datetime import datetime, timedelta, timezone
import random
import secrets
import time
from fastapi import APIRouter, Depends, HTTPException, status
from jose import JWTError, jwt
from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import get_settings
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
    RpsSettleRequest,
    FeedPetRequest,
    FeedPetResponse,
    BlackjackActionRequest,
    BlackjackSettleRequest,
    BlackjackStateResponse,
)
from app.services.pet_buffs import get_pet_buff_spec, sync_pet_record
from app.services.quest_service import record_quest_progress
from app.utils.security import get_current_user_id

settings = get_settings()

router = APIRouter(prefix="/api/pet", tags=["Pet"])

VALID_RPS_CHOICES = {"rock", "paper", "scissors"}
WINNING_CONDITIONS = {
    ("rock", "scissors"),
    ("scissors", "paper"),
    ("paper", "rock"),
}


def _to_pet_response(pet: Pet | None) -> PetResponse | None:
    """Self-heal canonical sprite/buff/rarity fields and enrich response with buff metadata."""
    if pet is None:
        return None
    sync_pet_record(pet)
    spec = get_pet_buff_spec(pet.pet_type, pet.buff_type)
    base = PetResponse.model_validate(pet)
    return base.model_copy(update={
        "buff_title": spec.buff_title,
        "buff_description": spec.buff_summary,
    })


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
    pet = result.scalars().first()
    return _to_pet_response(pet)


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
    pet_responses = [_to_pet_response(p) for p in pets]
    active_pet = next((p for p in pet_responses if p and p.is_active), None)

    return PetListResponse(
        pets=[p for p in pet_responses if p is not None],
        active_pet=active_pet,
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

    # Deactivate other pets and activate the selected pet in one transaction
    await db.execute(
        update(Pet)
        .where(Pet.user_id == user_id, Pet.id != pet_id)
        .values(is_active=False)
    )
    target_pet.is_active = True
    response = _to_pet_response(target_pet)
    await db.flush()
    return response


def _compute_rps_outcome(player_choice: str, pet_choice: str) -> str:
    if player_choice == pet_choice:
        return "tie"
    if (player_choice, pet_choice) in WINNING_CONDITIONS:
        return "win"
    return "lose"


async def _apply_rps_outcome(
    db: AsyncSession,
    user_id: int,
    pet: Pet,
    stats: PlayerStats | None,
    player_choice: str,
    pet_choice: str,
    outcome: str,
    coins_dropped: int,
) -> RpsResponse:
    if outcome == "tie":
        affection_gained = 3.0
        msg = f"It's a draw! Both chose {player_choice}. Your pet enjoyed playing!"
    elif outcome == "win":
        affection_gained = 10.0
        msg = f"Victory! Your {player_choice} beats {pet_choice}! Your pet is super happy!"
    else:
        affection_gained = 1.0
        msg = f"Your pet's {pet_choice} beats {player_choice}! Your pet looks proud!"

    pet.affection = min(100.0, (pet.affection or 0.0) + affection_gained)

    if coins_dropped > 0 and stats:
        stats.coins += coins_dropped
        msg += f" Found {coins_dropped} coin{'s' if coins_dropped != 1 else ''} on the floor!"

    quest_increments: dict[str, int] = {"pet_care": 1, "pet_rps": 1}
    if coins_dropped > 0:
        quest_increments["coins_earned"] = coins_dropped
    await record_quest_progress(db, user_id, quest_increments)

    return RpsResponse(
        player_choice=player_choice,
        pet_choice=pet_choice,
        outcome=outcome,
        affection_gained=affection_gained,
        current_affection=round(pet.affection, 1),
        coins_dropped=coins_dropped,
        remaining_coins=stats.coins if stats else None,
        message=msg,
    )


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
    outcome = _compute_rps_outcome(player_choice, pet_choice)
    coins_dropped = 0
    if outcome == "win" and random.random() < 0.5:
        coins_dropped = random.randint(1, 5)

    stats_res = await db.execute(
        select(PlayerStats).where(PlayerStats.user_id == user_id)
    )
    stats = stats_res.scalar_one_or_none()
    return await _apply_rps_outcome(
        db=db,
        user_id=user_id,
        pet=pet,
        stats=stats,
        player_choice=player_choice,
        pet_choice=pet_choice,
        outcome=outcome,
        coins_dropped=coins_dropped,
    )


# ---------------------------------------------------------------------------
# Blackjack vs Companion Pet (5-coin stake, stateless signed JWT token)
# ---------------------------------------------------------------------------
BLACKJACK_STAKE = 5
_BLACKJACK_RANKS = ("A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K")
_BLACKJACK_SUITS = ("♠", "♥", "♦", "♣")
_CONSUMED_BLACKJACK_STEPS: dict[tuple[str, int], float] = {}


def _hand_score(cards: list[str]) -> int:
    total = 0
    aces = 0
    for card in cards:
        rank = card[:-1]
        if rank == "A":
            aces += 1
            total += 11
        elif rank in {"K", "Q", "J"}:
            total += 10
        else:
            total += int(rank)
    while total > 21 and aces > 0:
        total -= 10
        aces -= 1
    return total


def _sign_blackjack_token(
    user_id: int,
    game_id: str,
    step: int,
    deck: list[str],
    player_cards: list[str],
    dealer_cards: list[str],
) -> str:
    payload = {
        "kind": "pet_blackjack",
        "sub": str(user_id),
        "jti": game_id,
        "step": step,
        "deck": deck,
        "player_cards": player_cards,
        "dealer_cards": dealer_cards,
        "exp": datetime.now(timezone.utc) + timedelta(minutes=15),
    }
    return jwt.encode(payload, settings.JWT_SECRET_KEY, algorithm=settings.JWT_ALGORITHM)


def _consume_blackjack_token(token: str, user_id: int) -> dict:
    try:
        payload = jwt.decode(
            token, settings.JWT_SECRET_KEY, algorithms=[settings.JWT_ALGORITHM]
        )
    except JWTError:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Blackjack session expired or invalid. Please start a new round.",
        )

    if payload.get("kind") != "pet_blackjack" or str(payload.get("sub")) != str(user_id):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Invalid Blackjack session owner.",
        )

    game_id = str(payload.get("jti", ""))
    step = int(payload.get("step", 0))
    now_ts = time.time()
    if len(_CONSUMED_BLACKJACK_STEPS) > 500:
        expired = [k for k, ts in _CONSUMED_BLACKJACK_STEPS.items() if now_ts - ts > 900.0]
        for k in expired:
            _CONSUMED_BLACKJACK_STEPS.pop(k, None)

    step_key = (game_id, step)
    if step_key in _CONSUMED_BLACKJACK_STEPS:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="This Blackjack turn was already played.",
        )
    _CONSUMED_BLACKJACK_STEPS[step_key] = now_ts
    return payload


async def _settle_blackjack_round(
    db: AsyncSession,
    user_id: int,
    pet: Pet,
    stats: PlayerStats,
    player_cards: list[str],
    dealer_cards: list[str],
    forced_bust: bool = False,
) -> BlackjackStateResponse:
    player_score = _hand_score(player_cards)
    dealer_score = _hand_score(dealer_cards)

    if forced_bust or player_score > 21:
        round_status = "player_bust"
        coins_delta = -BLACKJACK_STAKE
        affection_gained = 1.0
        msg = f"Bust ({player_score})! Lost {BLACKJACK_STAKE} Coins. {pet.name} cheered you on (+1 Affection)."
    elif dealer_score > 21 or player_score > dealer_score:
        round_status = "player_win"
        stats.coins += BLACKJACK_STAKE * 2
        coins_delta = BLACKJACK_STAKE
        affection_gained = 8.0
        msg = (
            f"You win ({player_score} vs {dealer_score})! +{BLACKJACK_STAKE} Coins & +8 Affection!"
            if dealer_score <= 21
            else f"{pet.name} busted ({dealer_score})! +{BLACKJACK_STAKE} Coins & +8 Affection!"
        )
    elif player_score == dealer_score:
        round_status = "push"
        stats.coins += BLACKJACK_STAKE
        coins_delta = 0
        affection_gained = 3.0
        msg = f"Push ({player_score} vs {dealer_score})! Your {BLACKJACK_STAKE} Coins were returned (+3 Affection)."
    else:
        round_status = "pet_win"
        coins_delta = -BLACKJACK_STAKE
        affection_gained = 1.0
        msg = f"{pet.name} wins ({dealer_score} vs {player_score})! Lost {BLACKJACK_STAKE} Coins (+1 Affection)."

    pet.affection = min(100.0, (pet.affection or 0.0) + affection_gained)
    quest_increments: dict[str, int] = {"pet_care": 1, "pet_blackjack": 1}
    if coins_delta > 0:
        quest_increments["coins_earned"] = coins_delta
    await record_quest_progress(db, user_id, quest_increments)

    return BlackjackStateResponse(
        game_token=None,
        status=round_status,
        player_cards=player_cards,
        dealer_cards=dealer_cards,
        player_score=player_score,
        dealer_score=dealer_score,
        bet_coins=BLACKJACK_STAKE,
        coins_delta=coins_delta,
        remaining_coins=stats.coins,
        affection_gained=affection_gained,
        current_affection=round(pet.affection, 1),
        message=msg,
    )


async def _require_active_pet_and_stats(
    db: AsyncSession,
    user_id: int,
) -> tuple[Pet, PlayerStats]:
    pet = (
        await db.execute(select(Pet).where(Pet.user_id == user_id, Pet.is_active == True))
    ).scalar_one_or_none()
    if not pet:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="You must have an active pet equipped to play mini-games.",
        )
    stats = (
        await db.execute(select(PlayerStats).where(PlayerStats.user_id == user_id))
    ).scalar_one_or_none()
    if not stats:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Player stats not found")
    return pet, stats


@router.post("/interact/blackjack/start", response_model=BlackjackStateResponse)
async def start_blackjack(
    user_id: int = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    """Start a 5-Coin Blackjack hand against the active companion pet."""
    pet, stats = await _require_active_pet_and_stats(db, user_id)
    if stats.coins < BLACKJACK_STAKE:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Need at least {BLACKJACK_STAKE} Coins to play Blackjack!",
        )

    stats.coins -= BLACKJACK_STAKE

    deck = [f"{r}{s}" for r in _BLACKJACK_RANKS for s in _BLACKJACK_SUITS]
    random.shuffle(deck)
    player_cards = [deck.pop(), deck.pop()]
    dealer_cards = [deck.pop(), deck.pop()]

    player_score = _hand_score(player_cards)
    dealer_score = _hand_score(dealer_cards)
    if player_score == 21 or dealer_score == 21:
        return await _settle_blackjack_round(db, user_id, pet, stats, player_cards, dealer_cards)

    token = _sign_blackjack_token(
        user_id=user_id,
        game_id=secrets.token_hex(12),
        step=0,
        deck=deck,
        player_cards=player_cards,
        dealer_cards=dealer_cards,
    )
    visible_dealer_score = _hand_score([dealer_cards[0]])
    return BlackjackStateResponse(
        game_token=token,
        status="playing",
        player_cards=player_cards,
        dealer_cards=[dealer_cards[0], "??"],
        player_score=player_score,
        dealer_score=visible_dealer_score,
        bet_coins=BLACKJACK_STAKE,
        coins_delta=-BLACKJACK_STAKE,
        remaining_coins=stats.coins,
        affection_gained=0.0,
        current_affection=round(pet.affection, 1),
        message=f"Stake: {BLACKJACK_STAKE} Coins. Hit to draw or Stand!",
    )


@router.post("/interact/blackjack/hit", response_model=BlackjackStateResponse)
async def hit_blackjack(
    request: BlackjackActionRequest,
    user_id: int = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    """Draw one more card in the active Blackjack hand."""
    payload = _consume_blackjack_token(request.game_token, user_id)
    pet, stats = await _require_active_pet_and_stats(db, user_id)

    deck = list(payload["deck"])
    player_cards = list(payload["player_cards"])
    dealer_cards = list(payload["dealer_cards"])
    if not deck:
        raise HTTPException(status_code=400, detail="Deck exhausted")

    player_cards.append(deck.pop())
    player_score = _hand_score(player_cards)
    if player_score > 21:
        return await _settle_blackjack_round(
            db, user_id, pet, stats, player_cards, dealer_cards, forced_bust=True
        )
    if player_score == 21:
        while _hand_score(dealer_cards) < 17 and deck:
            dealer_cards.append(deck.pop())
        return await _settle_blackjack_round(db, user_id, pet, stats, player_cards, dealer_cards)

    next_token = _sign_blackjack_token(
        user_id=user_id,
        game_id=str(payload["jti"]),
        step=int(payload["step"]) + 1,
        deck=deck,
        player_cards=player_cards,
        dealer_cards=dealer_cards,
    )
    return BlackjackStateResponse(
        game_token=next_token,
        status="playing",
        player_cards=player_cards,
        dealer_cards=[dealer_cards[0], "??"],
        player_score=player_score,
        dealer_score=_hand_score([dealer_cards[0]]),
        bet_coins=BLACKJACK_STAKE,
        coins_delta=-BLACKJACK_STAKE,
        remaining_coins=stats.coins,
        affection_gained=0.0,
        current_affection=round(pet.affection, 1),
        message="Drew a card! Hit again or Stand?",
    )


@router.post("/interact/blackjack/stand", response_model=BlackjackStateResponse)
async def stand_blackjack(
    request: BlackjackActionRequest,
    user_id: int = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    """Stand on the current hand and let the companion pet draw to 17."""
    payload = _consume_blackjack_token(request.game_token, user_id)
    pet, stats = await _require_active_pet_and_stats(db, user_id)

    deck = list(payload["deck"])
    player_cards = list(payload["player_cards"])
    dealer_cards = list(payload["dealer_cards"])

    while _hand_score(dealer_cards) < 17 and deck:
        dealer_cards.append(deck.pop())

    return await _settle_blackjack_round(db, user_id, pet, stats, player_cards, dealer_cards)


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
    await record_quest_progress(db, user_id, {"pet_care": 1, "pet_feed": 1})

    # Heal player HP and survival_hp if item also restores HP
    if hp_gain > 0:
        stats.hp = min(stats.max_hp, stats.hp + hp_gain)
        stats.survival_hp = min(stats.max_hp, stats.survival_hp + hp_gain)

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


# ---------------------------------------------------------------------------
# Local-first mini-game settlement endpoints (single request per round)
# ---------------------------------------------------------------------------
_VALID_BLACKJACK_CARDS = {
    f"{rank}{suit}" for rank in _BLACKJACK_RANKS for suit in _BLACKJACK_SUITS
}
_SETTLED_LOCAL_ROUNDS: dict[tuple[str, int, str], float] = {}


def _claim_local_round(kind: str, user_id: int, round_id: str) -> None:
    now_ts = time.time()
    expired = [k for k, ts in _SETTLED_LOCAL_ROUNDS.items() if now_ts - ts > 900.0]
    for key in expired:
        _SETTLED_LOCAL_ROUNDS.pop(key, None)
    if len(_SETTLED_LOCAL_ROUNDS) > 1000:
        _SETTLED_LOCAL_ROUNDS.clear()

    round_key = (kind, user_id, round_id.strip())
    if round_key in _SETTLED_LOCAL_ROUNDS:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="This mini-game round was already settled.",
        )
    _SETTLED_LOCAL_ROUNDS[round_key] = now_ts


@router.post("/interact/rps/settle", response_model=RpsResponse)
async def settle_local_rps(
    request: RpsSettleRequest,
    user_id: int = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    """Verify and settle a locally played Rock-Paper-Scissors round."""
    expected_outcome = _compute_rps_outcome(request.player_choice, request.pet_choice)
    if request.outcome != expected_outcome:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="RPS outcome does not match player and pet choices.",
        )
    if request.outcome != "win" and request.coins_dropped != 0:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Coin drops are only valid on an RPS victory.",
        )

    pet, stats = await _require_active_pet_and_stats(db, user_id)
    _claim_local_round("rps", user_id, request.round_id)

    return await _apply_rps_outcome(
        db=db,
        user_id=user_id,
        pet=pet,
        stats=stats,
        player_choice=request.player_choice,
        pet_choice=request.pet_choice,
        outcome=request.outcome,
        coins_dropped=request.coins_dropped,
    )


@router.post("/interact/blackjack/settle", response_model=BlackjackStateResponse)
async def settle_local_blackjack(
    request: BlackjackSettleRequest,
    user_id: int = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    """Verify and settle a locally played 5-coin Blackjack hand."""
    all_cards = request.player_cards + request.dealer_cards
    if any(card not in _VALID_BLACKJACK_CARDS for card in all_cards):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Invalid playing card in Blackjack hand.",
        )
    if len(all_cards) != len(set(all_cards)):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Duplicate cards detected in Blackjack hand.",
        )

    player_score = _hand_score(request.player_cards)
    dealer_score = _hand_score(request.dealer_cards)
    if player_score > 21:
        expected_outcome = "player_bust"
    else:
        if dealer_score < 17:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="Dealer must stand on 17 or higher before settling.",
            )
        if dealer_score > 21 or player_score > dealer_score:
            expected_outcome = "player_win"
        elif player_score == dealer_score:
            expected_outcome = "push"
        else:
            expected_outcome = "pet_win"

    if request.outcome != expected_outcome:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Blackjack outcome does not match dealt hands.",
        )

    pet, stats = await _require_active_pet_and_stats(db, user_id)
    if stats.coins < BLACKJACK_STAKE:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Need at least {BLACKJACK_STAKE} Coins to play Blackjack!",
        )

    _claim_local_round("blackjack", user_id, request.round_id)
    stats.coins -= BLACKJACK_STAKE
    return await _settle_blackjack_round(
        db=db,
        user_id=user_id,
        pet=pet,
        stats=stats,
        player_cards=request.player_cards,
        dealer_cards=request.dealer_cards,
        forced_bust=(request.outcome == "player_bust"),
    )
