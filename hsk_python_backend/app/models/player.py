"""
PlayerStats model — RPG character stats, currencies, and league.
"""
from sqlalchemy import Integer, Float, String, ForeignKey
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.database import Base


class PlayerStats(Base):
    __tablename__ = "player_stats"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(
        Integer, ForeignKey("users.id", ondelete="CASCADE"), unique=True, nullable=False
    )

    # --- Progression ---
    level: Mapped[int] = mapped_column(Integer, default=1)
    xp: Mapped[int] = mapped_column(Integer, default=0)
    unspent_stat_points: Mapped[int] = mapped_column(Integer, default=0)

    # --- Combat Stats ---
    # STR: +5 Raw Damage / point
    stat_str: Mapped[int] = mapped_column(Integer, default=0)
    # DEX: +2% Critical Damage / point (base 150%)
    stat_dex: Mapped[int] = mapped_column(Integer, default=0)
    # DEF: -2 Raw Damage received / point
    stat_def: Mapped[int] = mapped_column(Integer, default=0)
    # VIT: +25 Max HP / point (base 100)
    stat_vit: Mapped[int] = mapped_column(Integer, default=0)

    # --- HP ---
    hp: Mapped[int] = mapped_column(Integer, default=100)  # Current HP
    max_hp: Mapped[int] = mapped_column(Integer, default=100)  # Base 100 + VIT*25
    survival_hp: Mapped[int] = mapped_column(Integer, default=100)  # Account survival HP

    # --- Currency ---
    coins: Mapped[int] = mapped_column(Integer, default=0)
    diamonds: Mapped[int] = mapped_column(Integer, default=0)

    # --- League (leaderboard tier) ---
    # bronze_5..bronze_1, silver_5..silver_1, gold_5..gold_1, radiant
    league: Mapped[str] = mapped_column(String(20), default="bronze_5")

    # --- Current area progression ---
    highest_area_cleared: Mapped[int] = mapped_column(Integer, default=0)  # 0-9

    # Relationships
    user: Mapped["User"] = relationship("User", back_populates="stats")
