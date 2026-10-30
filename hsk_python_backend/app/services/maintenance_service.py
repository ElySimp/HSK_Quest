"""
Database Maintenance Service — safe auto-prune and table telemetry for HSK Quest.

Provides automated pruning of old battle history records while strictly protecting
active weekly leaderboard rankings, plus database metrics for admin dashboard oversight.
"""
from datetime import datetime, timedelta, timezone

from sqlalchemy import delete, func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.battle import BattleHistory, UserVocabMastery, WeaknessLog
from app.models.pet import Pet
from app.models.player import PlayerStats
from app.models.user import User
from app.services.leaderboard_service import current_week_bounds


def get_effective_prune_cutoff(retention_days: int = 30, now: datetime | None = None) -> datetime:
    """
    Calculate the effective retention cutoff timestamp in UTC.

    Applies a safety guard: the effective cutoff is capped at current_week_bounds()[0]
    (week_start) so that battle records belonging to the active ISO weekly leaderboard
    are never pruned, even if a retention_days value smaller than current week age is provided.
    """
    if now is None:
        now = datetime.now(timezone.utc)
    else:
        now = now.astimezone(timezone.utc)

    retention_cutoff = now - timedelta(days=max(1, int(retention_days)))
    week_start, _, _ = current_week_bounds(now=now)

    # Protection guard: effective cutoff must not be newer than week_start
    return min(retention_cutoff, week_start)


async def prune_battle_history(
    db: AsyncSession,
    retention_days: int = 30,
) -> dict:
    """
    Prune battle_history records older than retention_days.

    Safety:
    - Calculates retention cutoff: now(UTC) - retention_days.
    - Guards active leaderboard: cutoff is capped at week_start (min(cutoff, week_start)).
    - Executes atomic delete and commit.

    Returns:
        dict: {
            "success": True,
            "deleted_count": int,
            "retention_days": int,
            "cutoff_utc": str (ISO 8601),
        }
    """
    effective_cutoff = get_effective_prune_cutoff(retention_days=retention_days)

    try:
        stmt = delete(BattleHistory).where(BattleHistory.created_at < effective_cutoff)
        result = await db.execute(stmt)
        await db.commit()

        deleted_count = int(result.rowcount) if result.rowcount is not None and result.rowcount >= 0 else 0

        return {
            "success": True,
            "deleted_count": deleted_count,
            "retention_days": retention_days,
            "cutoff_utc": effective_cutoff.isoformat(),
        }
    except Exception:
        await db.rollback()
        raise


async def get_database_table_stats(db: AsyncSession) -> dict:
    """
    Query database table row counts and telemetry for admin dashboard.

    Retrieves:
    - Total row counts for battle_history, player_stats, users, weakness_log,
      user_vocab_mastery, and pets.
    - Count of battle_history rows older than 30 days (eligible for pruning).

    Returns:
        dict: Metrics containing table row counts and pruning telemetry.
    """
    # 1. Total row count per table
    battle_history_count = (
        await db.execute(select(func.count(BattleHistory.id)))
    ).scalar_one()

    player_stats_count = (
        await db.execute(select(func.count(PlayerStats.id)))
    ).scalar_one()

    users_count = (
        await db.execute(select(func.count(User.id)))
    ).scalar_one()

    weakness_log_count = (
        await db.execute(select(func.count(WeaknessLog.id)))
    ).scalar_one()

    user_vocab_mastery_count = (
        await db.execute(select(func.count(UserVocabMastery.id)))
    ).scalar_one()

    pets_count = (
        await db.execute(select(func.count(Pet.id)))
    ).scalar_one()

    # 2. Battle history rows eligible for pruning (> 30 days old, protected by active week guard)
    effective_prune_cutoff = get_effective_prune_cutoff(retention_days=30)
    prunable_battle_count = (
        await db.execute(
            select(func.count(BattleHistory.id)).where(
                BattleHistory.created_at < effective_prune_cutoff
            )
        )
    ).scalar_one()

    return {
        "battle_history": battle_history_count,
        "player_stats": player_stats_count,
        "users": users_count,
        "weakness_log": weakness_log_count,
        "user_vocab_mastery": user_vocab_mastery_count,
        "pets": pets_count,
        "eligible_for_pruning_count": prunable_battle_count,
        "retention_days": 30,
        "effective_prune_cutoff_utc": effective_prune_cutoff.isoformat(),
        "table_counts": {
            "battle_history": battle_history_count,
            "player_stats": player_stats_count,
            "users": users_count,
            "weakness_log": weakness_log_count,
            "user_vocab_mastery": user_vocab_mastery_count,
            "pets": pets_count,
        },
    }
