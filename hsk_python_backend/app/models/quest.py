"""
Daily quest record model — tracks per-day quest progress and claim status per user.
"""
from datetime import date, datetime

from sqlalchemy import Boolean, Date, DateTime, ForeignKey, Integer, String, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base


class DailyQuestRecord(Base):
    """Per-user, per-calendar-day quest progress and Diamond claim status."""

    __tablename__ = "daily_quest_records"
    __table_args__ = (
        UniqueConstraint("user_id", "quest_date", "quest_key", name="uq_daily_quest_user_date_key"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(
        Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True
    )
    quest_date: Mapped[date] = mapped_column(Date, nullable=False, index=True)
    quest_key: Mapped[str] = mapped_column(String(40), nullable=False)
    progress: Mapped[int] = mapped_column(Integer, default=0)
    is_claimed: Mapped[bool] = mapped_column(Boolean, default=False)
    claimed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
