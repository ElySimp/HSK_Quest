"""
Auth router — /api/auth/register and /api/auth/login endpoints.
"""
from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.schemas.auth import (
    RegisterRequest,
    LoginRequest,
    TokenResponse,
    UserResponse,
    RegisterResponse,
)
from app.services.auth_service import register_user, login_user

router = APIRouter(prefix="/api/auth", tags=["Authentication"])


@router.post("/register", response_model=RegisterResponse, status_code=201)
async def register(request: RegisterRequest, db: AsyncSession = Depends(get_db)):
    """
    Register a new player account.

    Creates:
    - User record with hashed password
    - Initial PlayerStats (Level 1, 100 HP, 100 💎 starter diamonds)
    - Starter pet egg (common rarity)

    Returns JWT access token for immediate use.
    """
    user, token = await register_user(
        db=db,
        username=request.username,
        password=request.password,
        hsk_target=request.hsk_target,
    )
    return RegisterResponse(
        user=UserResponse.model_validate(user),
        access_token=token,
    )


@router.post("/login", response_model=TokenResponse)
async def login(request: LoginRequest, db: AsyncSession = Depends(get_db)):
    """
    Authenticate with username and password.
    Returns JWT access token (valid for 24 hours).
    """
    user, token = await login_user(
        db=db,
        username=request.username,
        password=request.password,
    )
    return TokenResponse(access_token=token)
