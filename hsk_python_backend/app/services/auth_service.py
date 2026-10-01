"""
Auth service — business logic for user registration and login.
"""
from fastapi import HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.user import User
from app.models.player import PlayerStats
from app.models.pet import Pet
from app.utils.security import hash_password, verify_password, create_access_token


async def register_user(
    db: AsyncSession,
    username: str,
    password: str,
    hsk_target: int,
) -> tuple[User, str]:
    """
    Register a new user with:
    - Hashed password
    - Initial PlayerStats (level 1, 100 HP, 0 coins/diamonds)
    - Starter pet (Egg — becomes a random common pet)

    Returns (user, access_token).
    """
    # Check if username already exists
    existing = await db.execute(select(User).where(User.username == username))
    if existing.scalar_one_or_none():
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=f"Username '{username}' is already taken.",
        )

    # Create user
    user = User(
        username=username,
        password_hash=hash_password(password),
        hsk_target=hsk_target,
    )
    db.add(user)
    await db.flush()  # Get user.id without committing

    # Create initial player stats
    stats = PlayerStats(
        user_id=user.id,
        level=1,
        xp=0,
        unspent_stat_points=0,
        stat_str=0,
        stat_dex=0,
        stat_def=0,
        stat_vit=0,
        hp=100,
        max_hp=100,
        survival_hp=100,
        coins=0,
        diamonds=100,  # Starting diamonds for first gacha pull
        league="bronze_5",
        highest_area_cleared=0,
    )
    db.add(stats)

    # Create starter pet (Egg that hatches into a common pet)
    starter_pet = Pet(
        user_id=user.id,
        name="Starter Egg",
        pet_type="egg_starter",
        rarity="common",
        buff_type="none",
        affection=50.0,
        is_active=True,
        sprite_key="pet_dummy_1",
    )
    db.add(starter_pet)

    await db.flush()

    # Generate JWT token
    token = create_access_token(user.id, user.username)

    return user, token


async def login_user(
    db: AsyncSession,
    username: str,
    password: str,
) -> tuple[User, str]:
    """
    Authenticate user credentials and return (user, access_token).
    """
    result = await db.execute(select(User).where(User.username == username))
    user = result.scalar_one_or_none()

    if not user or not verify_password(password, user.password_hash):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid username or password.",
        )

    token = create_access_token(user.id, user.username)
    return user, token
