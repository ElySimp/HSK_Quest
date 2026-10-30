"""
Admin Router — /api/admin endpoints and Web Dashboard for HSK Quest.

Provides full LiveOps telemetry, player progression tweaking, instant health restoration,
and automated/manual database maintenance for battle history pruning.
"""
from pathlib import Path

from fastapi import APIRouter, Depends, HTTPException, Query, status
from fastapi.responses import HTMLResponse
from pydantic import BaseModel
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.models.player import PlayerStats
from app.models.user import User
from app.services.maintenance_service import (
    get_database_table_stats,
    prune_battle_history,
)

# ---------------------------------------------------------------------------
# Path resolution for the Admin Web Dashboard UI
# ---------------------------------------------------------------------------
STATIC_DIR = Path(__file__).resolve().parent.parent / "static"
ADMIN_HTML_PATH = STATIC_DIR / "admin.html"


def get_admin_html_response() -> HTMLResponse:
    """Read and serve the admin dashboard HTML file."""
    if not ADMIN_HTML_PATH.is_file():
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Admin dashboard HTML file not found at {ADMIN_HTML_PATH}",
        )
    content = ADMIN_HTML_PATH.read_text(encoding="utf-8")
    return HTMLResponse(content=content, status_code=200)


# ---------------------------------------------------------------------------
# Main Router with prefix /api/admin
# ---------------------------------------------------------------------------
router = APIRouter(prefix="/api/admin", tags=["Admin / Testing"])

# Standalone UI router for mounting directly at GET /admin on the FastAPI app
admin_ui_router = APIRouter(tags=["Admin / Testing"])


@admin_ui_router.get("/admin", response_class=HTMLResponse, include_in_schema=False)
async def serve_admin_root_page() -> HTMLResponse:
    """Serve the Admin Web Dashboard at /admin."""
    return get_admin_html_response()


@router.get("", response_class=HTMLResponse, include_in_schema=False)
@router.get("/", response_class=HTMLResponse, include_in_schema=False)
@router.get("/ui", response_class=HTMLResponse, include_in_schema=False)
@router.get("/dashboard", response_class=HTMLResponse, include_in_schema=False)
async def serve_admin_page() -> HTMLResponse:
    """Serve the Admin Web Dashboard at /api/admin and /api/admin/ui."""
    return get_admin_html_response()


# ---------------------------------------------------------------------------
# Pydantic Schemas
# ---------------------------------------------------------------------------
class AdminPlayerItem(BaseModel):
    user_id: int
    username: str
    hsk_target: int
    level: int
    xp: int
    hp: int
    max_hp: int
    survival_hp: int
    coins: int
    diamonds: int
    stat_str: int
    stat_dex: int
    stat_def: int
    stat_vit: int
    unspent_stat_points: int
    league: str
    highest_area_cleared: int


class AdminUpdateStatsRequest(BaseModel):
    coins: int | None = None
    diamonds: int | None = None
    hp: int | None = None
    max_hp: int | None = None
    survival_hp: int | None = None
    level: int | None = None
    xp: int | None = None
    unspent_stat_points: int | None = None
    stat_str: int | None = None
    stat_dex: int | None = None
    stat_def: int | None = None
    stat_vit: int | None = None
    league: str | None = None
    highest_area_cleared: int | None = None


# ---------------------------------------------------------------------------
# Endpoints
# ---------------------------------------------------------------------------
@router.get("/players", response_model=list[AdminPlayerItem])
async def list_all_players(
    db: AsyncSession = Depends(get_db),
) -> list[AdminPlayerItem]:
    """
    Query all registered users joined with their PlayerStats.
    Returns a comprehensive list of player progression, combat stats, and currencies.
    """
    stmt = (
        select(User, PlayerStats)
        .outerjoin(PlayerStats, PlayerStats.user_id == User.id)
        .order_by(User.id.asc())
    )
    rows = (await db.execute(stmt)).all()

    players: list[AdminPlayerItem] = []
    for user, stats in rows:
        # Fallback values if PlayerStats record is not yet initialized
        level = stats.level if stats else 1
        xp = stats.xp if stats else 0
        hp = stats.hp if stats else 100
        max_hp = stats.max_hp if stats else 100
        survival_hp = stats.survival_hp if (stats and stats.survival_hp is not None) else hp
        coins = stats.coins if stats else 0
        diamonds = stats.diamonds if stats else 0
        stat_str = stats.stat_str if stats else 0
        stat_dex = stats.stat_dex if stats else 0
        stat_def = stats.stat_def if stats else 0
        stat_vit = stats.stat_vit if stats else 0
        unspent_stat_points = stats.unspent_stat_points if stats else 0
        league = stats.league if stats else "bronze"
        highest_area_cleared = stats.highest_area_cleared if stats else 0

        players.append(
            AdminPlayerItem(
                user_id=user.id,
                username=user.username,
                hsk_target=user.hsk_target,
                level=level,
                xp=xp,
                hp=hp,
                max_hp=max_hp,
                survival_hp=survival_hp,
                coins=coins,
                diamonds=diamonds,
                stat_str=stat_str,
                stat_dex=stat_dex,
                stat_def=stat_def,
                stat_vit=stat_vit,
                unspent_stat_points=unspent_stat_points,
                league=league,
                highest_area_cleared=highest_area_cleared,
            )
        )

    return players


@router.put("/players/{user_id}/stats", response_model=AdminPlayerItem)
async def update_player_stats(
    user_id: int,
    body: AdminUpdateStatsRequest,
    db: AsyncSession = Depends(get_db),
) -> AdminPlayerItem:
    """
    Update PlayerStats record for a specific user_id.
    Allows modifications to currencies, HP, RPG stats, league, and area progression.
    """
    user_res = await db.execute(select(User).where(User.id == user_id))
    user = user_res.scalar_one_or_none()
    if not user:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"User with id {user_id} not found",
        )

    stats_res = await db.execute(
        select(PlayerStats).where(PlayerStats.user_id == user_id)
    )
    stats = stats_res.scalar_one_or_none()

    if not stats:
        # Create a new stats record if missing
        stats = PlayerStats(user_id=user_id)
        db.add(stats)

    # Apply provided updates with sensible bounds
    if body.coins is not None:
        stats.coins = max(0, body.coins)

    if body.diamonds is not None:
        stats.diamonds = max(0, body.diamonds)

    if body.max_hp is not None:
        stats.max_hp = max(1, body.max_hp)

    if body.hp is not None:
        stats.hp = max(0, body.hp)
        if body.survival_hp is None:
            stats.survival_hp = stats.hp

    if body.survival_hp is not None:
        stats.survival_hp = max(0, body.survival_hp)

    if body.level is not None:
        stats.level = max(1, body.level)

    if body.xp is not None:
        stats.xp = max(0, body.xp)

    if body.unspent_stat_points is not None:
        stats.unspent_stat_points = max(0, body.unspent_stat_points)

    if body.stat_str is not None:
        stats.stat_str = max(0, body.stat_str)

    if body.stat_dex is not None:
        stats.stat_dex = max(0, body.stat_dex)

    if body.stat_def is not None:
        stats.stat_def = max(0, body.stat_def)

    if body.stat_vit is not None:
        stats.stat_vit = max(0, body.stat_vit)

    if body.league is not None:
        cleaned_league = body.league.strip().lower()
        if cleaned_league in ("bronze", "silver", "gold", "radiant"):
            stats.league = cleaned_league
        else:
            stats.league = cleaned_league[:20]

    if body.highest_area_cleared is not None:
        stats.highest_area_cleared = max(0, min(9, body.highest_area_cleared))

    await db.commit()
    await db.refresh(stats)

    return AdminPlayerItem(
        user_id=user.id,
        username=user.username,
        hsk_target=user.hsk_target,
        level=stats.level,
        xp=stats.xp,
        hp=stats.hp,
        max_hp=stats.max_hp,
        survival_hp=stats.survival_hp if stats.survival_hp is not None else stats.hp,
        coins=stats.coins,
        diamonds=stats.diamonds,
        stat_str=stats.stat_str,
        stat_dex=stats.stat_dex,
        stat_def=stats.stat_def,
        stat_vit=stats.stat_vit,
        unspent_stat_points=stats.unspent_stat_points,
        league=stats.league,
        highest_area_cleared=stats.highest_area_cleared,
    )


@router.post("/players/{user_id}/quick-heal")
async def quick_heal_player(
    user_id: int,
    db: AsyncSession = Depends(get_db),
) -> dict:
    """
    Instantly restore current HP and survival HP to max_hp for the target player.
    """
    user_res = await db.execute(select(User).where(User.id == user_id))
    user = user_res.scalar_one_or_none()
    if not user:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"User with id {user_id} not found",
        )

    stats_res = await db.execute(
        select(PlayerStats).where(PlayerStats.user_id == user_id)
    )
    stats = stats_res.scalar_one_or_none()
    if not stats:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"PlayerStats for user_id {user_id} not found",
        )

    stats.hp = stats.max_hp
    stats.survival_hp = stats.max_hp

    await db.commit()
    await db.refresh(stats)

    return {
        "success": True,
        "user_id": user.id,
        "username": user.username,
        "hp": stats.hp,
        "max_hp": stats.max_hp,
        "survival_hp": stats.survival_hp,
        "message": f"Player '{user.username}' successfully healed to {stats.hp}/{stats.max_hp} HP.",
    }


@router.post("/maintenance/prune-battles")
async def trigger_prune_battles(
    days: int = Query(
        default=30,
        ge=1,
        description="Retention window in days. Records older than this threshold will be pruned.",
    ),
    db: AsyncSession = Depends(get_db),
) -> dict:
    """
    Trigger automated maintenance to prune old battle_history records.
    Active weekly leaderboard records are safely preserved regardless of days requested.
    """
    return await prune_battle_history(db=db, retention_days=days)


@router.get("/maintenance/stats")
async def get_maintenance_telemetry(
    db: AsyncSession = Depends(get_db),
) -> dict:
    """
    Fetch comprehensive database telemetry and row counts across all primary tables.
    """
    return await get_database_table_stats(db=db)
