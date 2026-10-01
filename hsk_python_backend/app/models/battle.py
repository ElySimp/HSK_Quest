"""
Battle-related models — history log and weakness tracking.
"""
from datetime import datetime

from sqlalchemy import Integer, String, Float, DateTime, ForeignKey, func
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.database import Base


class BattleHistory(Base):
    """Log of every battle outcome for leaderboard and anti-cheat."""
    __tablename__ = "battle_history"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(
        Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True
    )

    # --- Battle context ---
    monster_id: Mapped[int] = mapped_column(Integer, nullable=False)  # Monster index in area
    area_id: Mapped[int] = mapped_column(Integer, nullable=False)  # HSK level 1-9
    monster_type: Mapped[str] = mapped_column(String(20), default="normal")  # normal/elite/boss

    # --- Result ---
    result: Mapped[str] = mapped_column(String(10), nullable=False)  # "win" or "lose"
    damage_dealt: Mapped[int] = mapped_column(Integer, default=0)
    damage_taken: Mapped[int] = mapped_column(Integer, default=0)
    questions_answered: Mapped[int] = mapped_column(Integer, default=0)
    correct_answers: Mapped[int] = mapped_column(Integer, default=0)

    # --- Skill type tested ---
    # "reading" | "writing" | "listening" | "speaking" | "mixed"
    skill_type: Mapped[str] = mapped_column(String(20), default="reading")

    # --- Rewards earned ---
    xp_earned: Mapped[int] = mapped_column(Integer, default=0)
    coins_earned: Mapped[int] = mapped_column(Integer, default=0)

    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )

    # Relationships
    user: Mapped["User"] = relationship("User", back_populates="battle_history")


class WeaknessLog(Base):
    """Per-user per-skill accuracy tracking for adaptive boss AI."""
    __tablename__ = "weakness_log"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(
        Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True
    )

    # --- Skill category ---
    # "reading" | "writing" | "listening" | "speaking"
    skill_type: Mapped[str] = mapped_column(String(20), nullable=False)

    # --- Cumulative counters ---
    total_correct: Mapped[int] = mapped_column(Integer, default=0)
    total_wrong: Mapped[int] = mapped_column(Integer, default=0)

    # --- Rolling 3-day window accuracy (0.0 - 1.0) ---
    last_3_days_accuracy: Mapped[float] = mapped_column(Float, default=0.5)

    # --- Last updated ---
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now()
    )

    # Relationships
    user: Mapped["User"] = relationship("User", back_populates="weakness_logs")
