"""
Gacha router — /api/gacha endpoints.
Placeholder for Phase 4 full implementation.
"""
from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.utils.security import get_current_user_id

router = APIRouter(prefix="/api/gacha", tags=["Gacha"])


@router.post("/pull")
async def pull_gacha(
    user_id: int = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    """
    Pull the gacha (100 Diamonds).

    Rarity rates:
    - Common (60%): Tikus Kayu, Kelinci Batu
    - Rare (25%): Anjing Fu (Guardian Lion)
    - Epic (10%): Bangau Api
    - Legendary (5%): Naga Azure, Qilin

    Phase 4: Full implementation with diamond deduction and pet creation.
    """
    return {
        "message": "Gacha system coming in Phase 4",
        "cost": 100,
        "currency": "diamonds",
    }
