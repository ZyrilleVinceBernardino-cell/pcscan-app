"""
Settings configuration — NO pydantic-settings dependency required.
Uses plain Pydantic v2 BaseModel with manual python-dotenv loader.

This was rewritten to avoid needing the `pydantic-settings` package which
caused ModuleNotFoundError when installing due to sandbox filesystem
permission restrictions. Functionality is 100% identical to the original
BaseSettings subclass.
"""
import os
from dotenv import load_dotenv
from pydantic import BaseModel, Field
from typing import Optional

load_dotenv()

def _env(key: str, default: Optional[str] = None):
    val = os.environ.get(key)
    if val is None:
        return default
    return val

def _env_bool(key: str, default: bool = False) -> bool:
    v = _env(key)
    if v is None:
        return default
    s = str(v).strip().lower()
    return s in ("1", "true", "yes", "y", "on")

def _env_int(key: str, default: int) -> int:
    v = _env(key)
    if v is None or str(v).strip() == "":
        return default
    try:
        return int(str(v).strip())
    except (ValueError, TypeError):
        return default

def _env_float(key: str, default: float) -> float:
    v = _env(key)
    if v is None or str(v).strip() == "":
        return default
    try:
        return float(str(v).strip())
    except (ValueError, TypeError):
        return default


class Settings(BaseModel):
    GOOGLE_API_KEY: str = Field(
        default_factory=lambda: _env("GOOGLE_API_KEY", "")
    )
    GEMINI_MODEL: str = Field(
        default_factory=lambda: _env("GEMINI_MODEL", "gemini-2.5-flash")
    )
    GEMINI_TEMPERATURE: float = Field(
        default_factory=lambda: _env_float("GEMINI_TEMPERATURE", 0.0)
    )
    GEMINI_MAX_OUTPUT_TOKENS: int = Field(
        default_factory=lambda: _env_int("GEMINI_MAX_OUTPUT_TOKENS", 1024)
    )

    OPENAI_API_KEY: str = Field(
        default_factory=lambda: _env("OPENAI_API_KEY", "")
    )
    OPENAI_MODEL: str = Field(
        default_factory=lambda: _env("OPENAI_MODEL", "gpt-4o-mini")
    )
    OPENAI_TEMPERATURE: float = Field(
        default_factory=lambda: _env_float("OPENAI_TEMPERATURE", 0.0)
    )
    OPENAI_MAX_TOKENS: int = Field(
        default_factory=lambda: _env_int("OPENAI_MAX_TOKENS", 1024)
    )
    OPENAI_ORG_ID: Optional[str] = Field(
        default_factory=lambda: _env("OPENAI_ORG_ID", None)
    )

    HOST: str = Field(default_factory=lambda: _env("HOST", "0.0.0.0"))
    PORT: int = Field(default_factory=lambda: _env_int("PORT", 8000))
    ENVIRONMENT: str = Field(default_factory=lambda: _env("ENVIRONMENT", "development"))
    LOG_LEVEL: str = Field(default_factory=lambda: _env("LOG_LEVEL", "info"))

    CORS_ORIGINS: str = Field(default_factory=lambda: _env("CORS_ORIGINS", "*"))
    CORS_ALLOW_CREDENTIALS: bool = Field(default_factory=lambda: _env_bool("CORS_ALLOW_CREDENTIALS", True))
    CORS_ALLOW_METHODS: str = Field(default_factory=lambda: _env("CORS_ALLOW_METHODS", "GET,POST,PUT,DELETE,OPTIONS"))
    CORS_ALLOW_HEADERS: str = Field(default_factory=lambda: _env("CORS_ALLOW_HEADERS", "*"))

    MAX_UPLOAD_SIZE_MB: int = Field(default_factory=lambda: _env_int("MAX_UPLOAD_SIZE_MB", 10))
    ALLOWED_IMAGE_TYPES: str = Field(default_factory=lambda: _env("ALLOWED_IMAGE_TYPES", "image/jpeg,image/png,image/webp"))
    UPLOAD_DIR: str = Field(default_factory=lambda: _env("UPLOAD_DIR", "./temp_uploads"))

    RATE_LIMIT_PER_MINUTE: int = Field(default_factory=lambda: _env_int("RATE_LIMIT_PER_MINUTE", 60))

    MOCK_MODE: bool = Field(default_factory=lambda: _env_bool("MOCK_MODE", True))
    AUTO_FALLBACK: bool = Field(default_factory=lambda: _env_bool("AUTO_FALLBACK", True))


settings = Settings()
