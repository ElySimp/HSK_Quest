"""
Pet model — Gacha pets with rarity, buffs, and affection (Tamagotchi).
"""
from sqlalchemy import Integer, String, Float, Boolean, ForeignKey
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.database import Base


class Pet(Base):
    __tablename__ = "pets"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(
        Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False
    )

    # --- Identity ---
    name: Mapped[str] = mapped_column(String(50), nullable=False)
    pet_type: Mapped[str] = mapped_column(String(50), nullable=False)  # e.g. "dragon_azure"

    # --- Rarity: common, rare, epic, legendary ---
    rarity: Mapped[str] = mapped_column(String(20), default="common")

    # --- Buff ---
    # "time_extender" | "shield" | "damage_boost" | "none"
    buff_type: Mapped[str] = mapped_column(String(30), default="none")

    # --- Tamagotchi ---
    affection: Mapped[float] = mapped_column(Float, default=50.0)  # 0-100 meter
    is_active: Mapped[bool] = mapped_column(Boolean, default=False)  # Equipped in party

    # --- Sprite reference ---
    sprite_key: Mapped[str] = mapped_column(String(50), default="pet_dummy_1")

    # Relationships
    user: Mapped["User"] = relationship("User", back_populates="pets")
