"""Central configuration for QuantBridge.

Environment variables are the source of truth. A local `.env` file is loaded
for developer workflows, while staging/production values should come from the
runtime secret manager.
"""

from __future__ import annotations

from pathlib import Path
from typing import Annotated, Any

from pydantic import Field, field_validator, model_validator
from pydantic_settings import BaseSettings, NoDecode, SettingsConfigDict


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_DATABASE_URL = "postgresql://quantbridge:quantbridge@localhost:5432/quantbridge"


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=ROOT / ".env",
        env_file_encoding="utf-8",
        extra="ignore",
        populate_by_name=True,
    )

    root_dir: Path = ROOT
    google_key_path: Path = Field(default=ROOT / "key.json", validation_alias="QUANT_GOOGLE_KEY_PATH")
    google_key_json: str = Field(default="", validation_alias="QUANT_GOOGLE_KEY_JSON")
    spreadsheet_id: str = Field(
        default="YOUR_GOOGLE_SHEET_ID",
        validation_alias="QUANT_SPREADSHEET_ID",
    )
    spreadsheet_name: str = Field(default="QuantBridge_Demo_Workbook", validation_alias="QUANT_SPREADSHEET_NAME")
    database_url: str = Field(default=DEFAULT_DATABASE_URL, validation_alias="QUANT_DATABASE_URL")
    enable_postgres: bool = Field(default=False, validation_alias="QUANT_ENABLE_POSTGRES")
    data_lake_dir: Path = Field(default=ROOT / "data_lake", validation_alias="QUANT_DATA_LAKE_DIR")
    enable_parquet: bool = Field(default=True, validation_alias="QUANT_ENABLE_PARQUET")
    api_sqlite_path: Path = Field(
        default=ROOT / "api" / "quantbridge.sqlite3",
        validation_alias="QUANT_API_SQLITE_PATH",
    )
    api_env: str = Field(default="local", validation_alias="QUANT_API_ENV")
    cors_origins: Annotated[tuple[str, ...], NoDecode] = Field(
        default=("*",),
        validation_alias="QUANT_CORS_ORIGINS",
    )
    auth_rate_limit_per_minute: int = Field(default=20, validation_alias="QUANT_AUTH_RATE_LIMIT_PER_MINUTE")
    naver_client_id: str = Field(default="", validation_alias="NAVER_CLIENT_ID")
    naver_client_secret: str = Field(default="", validation_alias="NAVER_CLIENT_SECRET")
    gemini_api_key: str = Field(default="", validation_alias="GEMINI_API_KEY")
    news_impact_llm_enabled: bool = Field(default=False, validation_alias="NEWS_IMPACT_LLM_ENABLED")
    news_impact_llm_model: str = Field(default="gemini-2.5-flash", validation_alias="NEWS_IMPACT_LLM_MODEL")
    news_impact_llm_max_items: int = Field(default=8, validation_alias="NEWS_IMPACT_LLM_MAX_ITEMS")
    news_impact_llm_timeout_seconds: float = Field(default=8.0, validation_alias="NEWS_IMPACT_LLM_TIMEOUT_SECONDS")
    api_allow_external_fetch: bool = Field(default=False, validation_alias="QUANT_API_ALLOW_EXTERNAL_FETCH")
    kiwoom_credentials_path: Path = Field(
        default=ROOT / "kiwoom_credentials.json",
        validation_alias="QUANT_KIWOOM_CREDENTIALS_PATH",
    )
    pipeline_runner: str = Field(default="legacy", validation_alias="QUANT_PIPELINE_RUNNER")
    ml_blend_weight: float = Field(default=0.25, validation_alias="QUANT_ML_BLEND_WEIGHT")
    ml_blend_weak_weight: float = Field(default=0.10, validation_alias="QUANT_ML_BLEND_WEAK_WEIGHT")
    ml_blend_strong_weight: float = Field(default=0.35, validation_alias="QUANT_ML_BLEND_STRONG_WEIGHT")
    ml_auto_weight: bool = Field(default=True, validation_alias="QUANT_ML_AUTO_WEIGHT")
    ml_factor_score_col: str = Field(default="Final_Score", validation_alias="QUANT_ML_FACTOR_SCORE_COL")

    @field_validator("google_key_path", "data_lake_dir", "api_sqlite_path", "kiwoom_credentials_path")
    @classmethod
    def _resolve_project_path(cls, value: Path) -> Path:
        path = Path(value).expanduser()
        return path if path.is_absolute() else ROOT / path

    @field_validator("api_env", "pipeline_runner", mode="before")
    @classmethod
    def _normalize_lower(cls, value: Any) -> str:
        return str(value or "").strip().lower() or "local"

    @field_validator("ml_factor_score_col", mode="before")
    @classmethod
    def _normalize_score_col(cls, value: Any) -> str:
        return str(value or "").strip() or "Final_Score"

    @field_validator("news_impact_llm_model", mode="before")
    @classmethod
    def _normalize_llm_model(cls, value: Any) -> str:
        return str(value or "").strip() or "gemini-2.5-flash"

    @field_validator("cors_origins", mode="before")
    @classmethod
    def _parse_cors_origins(cls, value: Any) -> tuple[str, ...]:
        if value is None:
            return ("*",)
        if isinstance(value, str):
            origins = tuple(item.strip() for item in value.split(",") if item.strip())
            return origins or ("*",)
        if isinstance(value, (list, tuple, set)):
            origins = tuple(str(item).strip() for item in value if str(item).strip())
            return origins or ("*",)
        return (str(value).strip(),)

    @model_validator(mode="after")
    def _validate_runtime_posture(self) -> "Settings":
        if self.auth_rate_limit_per_minute < 1:
            raise ValueError("QUANT_AUTH_RATE_LIMIT_PER_MINUTE must be at least 1")
        for name, value in (
            ("QUANT_ML_BLEND_WEIGHT", self.ml_blend_weight),
            ("QUANT_ML_BLEND_WEAK_WEIGHT", self.ml_blend_weak_weight),
            ("QUANT_ML_BLEND_STRONG_WEIGHT", self.ml_blend_strong_weight),
        ):
            if not 0 <= value <= 1:
                raise ValueError(f"{name} must be between 0 and 1")
        if self.news_impact_llm_max_items < 0:
            raise ValueError("NEWS_IMPACT_LLM_MAX_ITEMS must be at least 0")
        if self.news_impact_llm_timeout_seconds <= 0:
            raise ValueError("NEWS_IMPACT_LLM_TIMEOUT_SECONDS must be greater than 0")
        if self.api_env in {"prod", "production"} and "*" in self.cors_origins:
            raise ValueError("QUANT_CORS_ORIGINS must not be '*' in production")
        if self.api_env in {"prod", "production"} and self.enable_postgres and self.database_url == DEFAULT_DATABASE_URL:
            raise ValueError("QUANT_DATABASE_URL must be set for production PostgreSQL")
        return self

    @property
    def allow_cors_credentials(self) -> bool:
        return "*" not in self.cors_origins

    @property
    def has_google_credentials(self) -> bool:
        return bool(self.google_key_json.strip()) or self.google_key_path.exists()


def get_settings() -> Settings:
    return Settings()
