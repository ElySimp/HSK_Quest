"""
Leaderboard service — computes live weekly damage rankings from battle_history,
enforces anti-smurf league locking by highest_area_cleared, and calculates reward projections.
"""
from datetime import datetime, timedelta, timezone
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.battle import BattleHistory
from app.models.player import PlayerStats
from app.models.user import User
from app.schemas.leaderboard import LeaderboardEntry, WeeklyLeaderboardResponse

VALID_LEAGUES = ("bronze", "silver", "gold", "radiant")


def league_for_area(highest_area_cleared: int) -> str:
    """
    Deterministic anti-smurf league tier based on highest_area_cleared:
    - Area 0..3 -> bronze
    - Area 4..5 -> silver
    - Area 6    -> gold
    - Area 7..9 -> radiant
    """
    area = max(0, int(highest_area_cleared or 0))
    if area >= 7:
        return "radiant"
    if area == 6:
        return "gold"
    if area >= 4:
        return "silver"
    return "bronze"


def current_week_bounds(now: datetime | None = None) -> tuple[datetime, datetime, int]:
    """
    Return (week_start_utc, week_end_utc, seconds_until_reset) for the current ISO week.
    Week starts Monday 00:00:00 UTC and ends next Monday 00:00:00 UTC.
    """
    if now is None:
        now = datetime.now(timezone.utc)
    else:
        now = now.astimezone(timezone.utc)

    days_since_monday = now.weekday()
    monday = datetime(now.year, now.month, now.day, tzinfo=timezone.utc) - timedelta(days=days_since_monday)
    next_monday = monday + timedelta(days=7)
    seconds_remaining = max(0, int((next_monday - now).total_seconds()))
    return monday, next_monday, seconds_remaining


def projected_reward(league: str, rank: int | None, weekly_damage: int) -> tuple[int, str | None]:
    """
    Return (projected_diamonds, projected_title) for a player's rank within their league.
    Requires at least 1 damage dealt during the week to qualify for rewards.
    """
    if not rank or rank <= 0 or weekly_damage <= 0:
        return 0, None

    tier = league.lower()
    if tier == "radiant":
        if rank <= 10:
            return 1000, "Dragon Slayer"
        if rank <= 25:
            return 600, None
        if rank <= 50:
            return 350, None
    elif tier == "gold":
        if rank <= 25:
            return 500, None
        if rank <= 50:
            return 250, None
    elif tier == "silver":
        if rank <= 50:
            return 250, None
    else:  # bronze
        if rank <= 50:
            return 100, None

    return 0, None


async def get_weekly_board(
    db: AsyncSession,
    user_id: int,
    requested_league: str | None = None,
    limit: int = 50,
) -> WeeklyLeaderboardResponse:
    """
    Aggregate weekly victorious battle damage from battle_history for all players,
    partitioned by their league tier (derived from highest_area_cleared).
    """
    week_start, week_end, seconds_until_reset = current_week_bounds()

    # Subquery: sum of victorious damage_dealt in the current ISO week per user_id
    dmg_subq = (
        select(
            BattleHistory.user_id.label("uid"),
            func.coalesce(func.sum(BattleHistory.damage_dealt), 0).label("weekly_damage"),
        )
        .where(
            BattleHistory.result == "win",
            BattleHistory.created_at >= week_start,
            BattleHistory.created_at < week_end,
        )
        .group_by(BattleHistory.user_id)
        .subquery()
    )

    # Query all users + player_stats + left-joined weekly damage
    stmt = (
        select(
            User.id,
            User.username,
            PlayerStats.level,
            PlayerStats.highest_area_cleared,
            func.coalesce(dmg_subq.c.weekly_damage, 0).label("weekly_damage"),
        )
        .join(PlayerStats, PlayerStats.user_id == User.id)
        .outerjoin(dmg_subq, dmg_subq.c.uid == User.id)
    )
    rows = (await db.execute(stmt)).all()

    # Partition players by canonical league tier and identify caller's league
    by_league: dict[str, list[dict]] = {t: [] for t in VALID_LEAGUES}
    my_league = "bronze"

    for r in rows:
        uid = int(r.id)
        area = int(r.highest_area_cleared or 0)
        tier = league_for_area(area)
        dmg = int(r.weekly_damage or 0)
        lvl = int(r.level or 1)

        if uid == user_id:
            my_league = tier

        by_league[tier].append(
            {
                "user_id": uid,
                "username": r.username,
                "level": lvl,
                "league": tier,
                "highest_area_cleared": area,
                "weekly_damage": dmg,
                "is_me": uid == user_id,
            }
        )

    # Sort each league by weekly_damage DESC, level DESC, user_id ASC
    for tier in VALID_LEAGUES:
        by_league[tier].sort(
            key=lambda item: (-item["weekly_damage"], -item["level"], item["user_id"])
        )

    # Determine which league tab to return
    target_league = (requested_league or "").strip().lower()
    if target_league not in VALID_LEAGUES:
        target_league = my_league

    # Build caller's personal entry & rank inside their own league
    my_entry: LeaderboardEntry | None = None
    for idx, item in enumerate(by_league[my_league], start=1):
        if item["user_id"] == user_id:
            my_entry = LeaderboardEntry(rank=idx, **item)
            break

    # Build top N entries for the requested league
    entries: list[LeaderboardEntry] = []
    for idx, item in enumerate(by_league[target_league][:limit], start=1):
        entries.append(LeaderboardEntry(rank=idx, **item))

    proj_diamonds, proj_title = projected_reward(
        my_league,
        my_entry.rank if my_entry else None,
        my_entry.weekly_damage if my_entry else 0,
    )

    return WeeklyLeaderboardResponse(
        league=target_league,
        week_start=week_start,
        week_end=week_end,
        seconds_until_reset=seconds_until_reset,
        entries=entries,
        me=my_entry,
        my_league=my_league,
        projected_reward_diamonds=proj_diamonds,
        projected_reward_title=proj_title,
    )
