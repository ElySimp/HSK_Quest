"""
HSK Quest — App Configuration
Loads environment variables via pydantic-settings.
"""
from pathlib import Path
from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Application settings loaded from .env file."""

    model_config = SettingsConfigDict(
        env_file=str(Path(__file__).resolve().parent.parent / ".env"),
        env_file_encoding="utf-8",
        extra="ignore",
    )

    # --- Database (Aiven PostgreSQL) ---
    DATABASE_URL: str
    DATABASE_NAME: str = "defaultdb"
    DATABASE_HOST: str = ""
    DATABASE_PORT: int = 12560
    DATABASE_USER: str = ""
    DATABASE_PASSWORD: str = ""
    DATABASE_SSL_MODE: str = "require"

    # --- JWT Auth ---
    JWT_SECRET_KEY: str = "change-me-in-production"
    JWT_ALGORITHM: str = "HS256"
    JWT_ACCESS_TOKEN_EXPIRE_MINUTES: int = 1440  # 24 hours

    # --- AI ---
    GEMINI_API_KEY: str = ""

    @property
    def ca_cert_path(self) -> Path:
        """Path to the Aiven CA certificate."""
        return Path(__file__).resolve().parent.parent / "ca.pem"


@lru_cache()
def get_settings() -> Settings:
    """Cached settings singleton."""
    return Settings()
