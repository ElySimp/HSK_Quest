"""
Leaderboard model — weekly damage rankings and league tiers.
"""
from datetime import datetime, date

from sqlalchemy import Integer, String, Date, DateTime, ForeignKey, func
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.database import Base


class Leaderboard(Base):
    __tablename__ = "leaderboard"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(
        Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True
    )

    # --- Weekly total damage accumulation (Mon-Sun) ---
    weekly_total_damage: Mapped[int] = mapped_column(Integer, default=0)

    # --- League tier ---
    # HSK 1-3 max: bronze_5..bronze_1
    # HSK 4-6 unlocks: silver_5..gold_1
    # HSK 7-9 unlocks: radiant
    league: Mapped[str] = mapped_column(String(20), default="bronze_5")

    # --- Ranking (computed weekly) ---
    rank: Mapped[int] = mapped_column(Integer, default=0)

    # --- Week boundary ---
    week_start: Mapped[date] = mapped_column(Date, nullable=False)

    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now()
    )
