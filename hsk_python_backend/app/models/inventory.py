"""
Inventory model — items, food, and consumables.
"""
from sqlalchemy import Integer, String, ForeignKey
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.database import Base


class Inventory(Base):
    __tablename__ = "inventory"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(
        Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True
    )

    # --- Item identification ---
    # "food" | "potion" | "special"
    item_type: Mapped[str] = mapped_column(String(30), nullable=False)
    item_id: Mapped[str] = mapped_column(String(50), nullable=False)  # e.g. "bread_hsk1"
    quantity: Mapped[int] = mapped_column(Integer, default=1)

    # Relationships
    user: Mapped["User"] = relationship("User", back_populates="inventory")
