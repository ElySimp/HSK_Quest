"""
User model — authentication & profile.
"""
from datetime import datetime

from sqlalchemy import String, Integer, DateTime, func
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.database import Base


class User(Base):
    __tablename__ = "users"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    username: Mapped[str] = mapped_column(String(50), unique=True, nullable=False, index=True)
    password_hash: Mapped[str] = mapped_column(String(255), nullable=False)
    hsk_target: Mapped[int] = mapped_column(Integer, default=1)  # Target HSK level 1-9
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )

    # Relationships
    stats: Mapped["PlayerStats"] = relationship(
        "PlayerStats", back_populates="user", uselist=False, cascade="all, delete-orphan"
    )
    pets: Mapped[list["Pet"]] = relationship(
        "Pet", back_populates="user", cascade="all, delete-orphan"
    )
    inventory: Mapped[list["Inventory"]] = relationship(
        "Inventory", back_populates="user", cascade="all, delete-orphan"
    )
    battle_history: Mapped[list["BattleHistory"]] = relationship(
        "BattleHistory", back_populates="user", cascade="all, delete-orphan"
    )
    weakness_logs: Mapped[list["WeaknessLog"]] = relationship(
        "WeaknessLog", back_populates="user", cascade="all, delete-orphan"
    )
