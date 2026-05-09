from __future__ import annotations

import os
from dataclasses import dataclass

from dotenv import load_dotenv

load_dotenv()


@dataclass(frozen=True)
class Settings:
    app_name: str = "随手办 API"
    api_prefix: str = "/api/v1"
    database_path: str = os.getenv("DATABASE_PATH", "./suishouban.db")
    cors_origins: str = os.getenv("CORS_ORIGINS", "*")
    lanxin_api_key: str = os.getenv("LANXIN_API_KEY", "")
    lanxin_base_url: str = os.getenv("LANXIN_BASE_URL", "")
    lanxin_model: str = os.getenv("LANXIN_MODEL", "lanxin-pro")
    request_timeout_seconds: float = float(os.getenv("REQUEST_TIMEOUT_SECONDS", "20"))

    @property
    def has_llm_config(self) -> bool:
        return bool(self.lanxin_api_key and self.lanxin_base_url)

    @property
    def cors_origin_list(self) -> list[str]:
        if self.cors_origins.strip() == "*":
            return ["*"]
        return [item.strip() for item in self.cors_origins.split(",") if item.strip()]


settings = Settings()
