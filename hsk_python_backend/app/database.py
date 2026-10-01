"""
HSK Quest — Database Engine & Session Factory
Async SQLAlchemy engine connected to Aiven PostgreSQL with SSL.
"""
import ssl
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.orm import DeclarativeBase

from app.config import get_settings

settings = get_settings()

# ---------------------------------------------------------------------------
# SSL context for Aiven PostgreSQL
# ---------------------------------------------------------------------------
ssl_context = ssl.create_default_context(cafile=str(settings.ca_cert_path))

# ---------------------------------------------------------------------------
# Async Engine
# ---------------------------------------------------------------------------
engine = create_async_engine(
    settings.DATABASE_URL,
    echo=False,
    pool_size=5,
    max_overflow=10,
    connect_args={"ssl": ssl_context},
)

# ---------------------------------------------------------------------------
# Session factory
# ---------------------------------------------------------------------------
async_session = async_sessionmaker(
    engine,
    class_=AsyncSession,
    expire_on_commit=False,
)


# ---------------------------------------------------------------------------
# Base model class for all ORM models
# ---------------------------------------------------------------------------
class Base(DeclarativeBase):
    """Declarative base for all SQLAlchemy models."""
    pass


# ---------------------------------------------------------------------------
# Dependency for FastAPI route injection
# ---------------------------------------------------------------------------
async def get_db() -> AsyncSession:
    """Yield an async database session, auto-closing on exit."""
    async with async_session() as session:
        try:
            yield session
            await session.commit()
        except Exception:
            await session.rollback()
            raise
        finally:
            await session.close()
