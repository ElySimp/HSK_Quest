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

from app.database import engine, Base
from app.routers import auth, player, battle, leaderboard, shop, gacha


# ---------------------------------------------------------------------------
# Lifespan: create tables on startup
# ---------------------------------------------------------------------------
@asynccontextmanager
async def lifespan(app: FastAPI):
    """Create all database tables on startup, dispose engine on shutdown."""
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    print("[OK] Database tables created/verified successfully!")
    yield
    await engine.dispose()
    print("[CLOSED] Database connection closed.")


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
