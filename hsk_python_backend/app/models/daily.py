"""
Daily claim model — one row per user per claimed day.
Kept as a separate table because Base.metadata.create_all() cannot add
columns to the existing player_stats table.
"""
from datetime import date, datetime

from sqlalchemy import Date, DateTime, ForeignKey, Integer, UniqueConstraint, func
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base


class DailyClaim(Base):
    __tablename__ = "daily_claims"
    __table_args__ = (UniqueConstraint("user_id", "claim_date", name="uq_daily_claim_user_date"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(
        Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True
    )
    claim_date: Mapped[date] = mapped_column(Date, nullable=False)
    streak: Mapped[int] = mapped_column(Integer, default=1)
    coins_awarded: Mapped[int] = mapped_column(Integer, default=0)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )
