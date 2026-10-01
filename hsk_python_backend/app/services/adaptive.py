"""
Adaptive learning service — weakness tracking for boss AI.
Placeholder for Phase 6 full implementation.
"""


async def get_weakest_skill(user_id: int, db) -> str:
    """
    Query the weakness_log table and return the skill_type with
    the lowest last_3_days_accuracy for the given user.

    Used by Boss Enraged mode (<30% HP) to target the player's weakest skill.

    Phase 6: Full implementation with rolling 3-day window calculation.
    """
    from sqlalchemy import select
    from app.models.battle import WeaknessLog

    result = await db.execute(
        select(WeaknessLog)
        .where(WeaknessLog.user_id == user_id)
        .order_by(WeaknessLog.last_3_days_accuracy.asc())
        .limit(1)
    )
    weakest = result.scalar_one_or_none()
    return weakest.skill_type if weakest else "reading"
