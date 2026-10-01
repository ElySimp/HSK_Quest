"""
Shop router — /api/shop endpoints.
Placeholder for Phase 4 full implementation.
"""
from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.utils.security import get_current_user_id

router = APIRouter(prefix="/api/shop", tags=["Shop"])


@router.get("/items")
async def get_shop_items():
    """
    Get available shop items.
    Phase 4: Full implementation with HSK-tier price scaling.
    """
    return {
        "items": [
            {"id": "bread_hsk1", "name": "Rice Ball", "type": "food", "hp_restore": 20, "price_coins": 15},
            {"id": "noodle_hsk2", "name": "Noodle Bowl", "type": "food", "hp_restore": 50, "price_coins": 40},
            {"id": "dumpling_hsk3", "name": "Jade Dumplings", "type": "food", "hp_restore": 100, "price_coins": 85},
            {"id": "feast_hsk4", "name": "Imperial Feast", "type": "food", "hp_restore": 200, "price_coins": 180},
        ]
    }
