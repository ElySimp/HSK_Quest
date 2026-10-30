"""
HSK Quest — FastAPI Backend Entry Point

Brain Server (Python FastAPI) - AI Academic Core
Handles: Auth, Player Stats, Battle Validation, Leaderboard, Shop, Gacha, AI Engines.

Run: uvicorn main:app --reload --host 0.0.0.0 --port 8000
"""
from contextlib import asynccontextmanager

# pyrefly: ignore [missing-import]
from fastapi import FastAPI
# pyrefly: ignore [missing-import]
from fastapi.middleware.cors import CORSMiddleware
from sqlalchemy import text

from app.database import engine, Base, async_session
from app.routers import auth, player, battle, leaderboard, shop, gacha, pet, admin
from app.services.maintenance_service import prune_battle_history


# ---------------------------------------------------------------------------
# Lifespan: create tables & ensure indexes/league normalization on startup
# ---------------------------------------------------------------------------
@asynccontextmanager
async def lifespan(app: FastAPI):
    """Create all database tables on startup, run idempotent index/league migrations, dispose on shutdown."""
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
        await conn.execute(
            text(
                "CREATE INDEX IF NOT EXISTS ix_battle_history_created_at "
                "ON battle_history (created_at)"
            )
        )
        await conn.execute(
            text(
                "CREATE INDEX IF NOT EXISTS ix_battle_history_user_created "
                "ON battle_history (user_id, created_at)"
            )
        )
        await conn.execute(
            text(
                "UPDATE player_stats SET league = CASE "
                "WHEN highest_area_cleared >= 7 THEN 'radiant' "
                "WHEN highest_area_cleared = 6 THEN 'gold' "
                "WHEN highest_area_cleared >= 4 THEN 'silver' "
                "ELSE 'bronze' END "
                "WHERE league NOT IN ('bronze', 'silver', 'gold', 'radiant') OR league IS NULL"
            )
        )

    # Automated data lifecycle management: prune battle logs older than 30 days
    try:
        async with async_session() as db:
            await prune_battle_history(db, retention_days=30)
    except Exception:
        # Prevent server launch failure if background pruning encounters temporary issues
        pass

    yield
    await engine.dispose()


# ---------------------------------------------------------------------------
# FastAPI App
# ---------------------------------------------------------------------------
app = FastAPI(
    title="HSK Quest API",
    description=(
        "🐉 HSK Quest Brain Server — Gamified Adaptive Chinese Learning\n\n"
        "RPG × Tamagotchi backend for combat validation, player progression, "
        "leaderboards, gacha, and AI-powered writing/speaking evaluation."
    ),
    version="0.1.0",
    lifespan=lifespan,
)

# ---------------------------------------------------------------------------
# CORS — Allow Android client connections
# ---------------------------------------------------------------------------
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],  # In production, restrict to specific origins
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# ---------------------------------------------------------------------------
# Include routers
# ---------------------------------------------------------------------------
app.include_router(auth.router)
app.include_router(player.router)
app.include_router(battle.router)
app.include_router(leaderboard.router)
app.include_router(shop.router)
app.include_router(gacha.router)
app.include_router(pet.router)
app.include_router(admin.admin_ui_router)
app.include_router(admin.router)


# ---------------------------------------------------------------------------
# Root health check
# ---------------------------------------------------------------------------
@app.get("/", tags=["Health"])
async def root():
    return {
        "status": "online",
        "app": "HSK Quest Brain Server",
        "version": "0.1.0",
        "message": "The Dragon awaits your challenge!",
    }


@app.get("/health", tags=["Health"])
async def health_check():
    return {"status": "healthy"}
