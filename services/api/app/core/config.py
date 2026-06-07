from __future__ import annotations

import os
from dataclasses import dataclass

from dotenv import load_dotenv

load_dotenv()


@dataclass(frozen=True)
class Settings:
    app_name: str = "随手办 API"
    api_prefix: str = "/api"
    database_path: str = os.getenv("DATABASE_PATH", "./suishouban.db")
    workflow_database_path: str = os.getenv("WORKFLOW_DATABASE_PATH", "./workflow.db")
    workflow_input_directory: str = os.getenv("WORKFLOW_INPUT_DIRECTORY", "./workflow_inputs")
    workflow_lease_seconds: int = int(os.getenv("WORKFLOW_LEASE_SECONDS", "30"))
    workflow_environment: str = os.getenv("WORKFLOW_ENVIRONMENT", "development")
    cors_origins: str = os.getenv("CORS_ORIGINS", "*")
    lanxin_api_key: str = os.getenv("LANXIN_API_KEY", "")
    lanxin_base_url: str = os.getenv("LANXIN_BASE_URL", "https://api-ai.vivo.com.cn/v1")
    lanxin_model: str = os.getenv("LANXIN_MODEL", "Doubao-Seed-2.0-mini")
    fast_model_api_key: str = os.getenv("FAST_MODEL_API_KEY", os.getenv("LANXIN_API_KEY", ""))
    fast_model_base_url: str = os.getenv("FAST_MODEL_BASE_URL", os.getenv("LANXIN_BASE_URL", "https://api-ai.vivo.com.cn/v1"))
    fast_model_name: str = os.getenv("FAST_MODEL_NAME", os.getenv("LANXIN_MODEL", "Doubao-Seed-2.0-mini"))
    expert_model_api_key: str = os.getenv("EXPERT_MODEL_API_KEY", os.getenv("LANXIN_API_KEY", ""))
    expert_model_base_url: str = os.getenv("EXPERT_MODEL_BASE_URL", os.getenv("LANXIN_BASE_URL", "https://api-ai.vivo.com.cn/v1"))
    expert_model_name: str = os.getenv("EXPERT_MODEL_NAME", os.getenv("LANXIN_MODEL", "Doubao-Seed-2.0-mini"))
    request_timeout_seconds: float = float(os.getenv("REQUEST_TIMEOUT_SECONDS", "20"))
    fast_model_timeout_seconds: float = float(os.getenv("FAST_MODEL_TIMEOUT_SECONDS", "4"))
    expert_model_timeout_seconds: float = float(os.getenv("EXPERT_MODEL_TIMEOUT_SECONDS", "12"))
    provider_max_concurrency: int = int(os.getenv("PROVIDER_MAX_CONCURRENCY", "8"))
    workflow_max_concurrency: int = int(os.getenv("WORKFLOW_MAX_CONCURRENCY", "20"))
    workflow_cache_ttl_seconds: int = int(os.getenv("WORKFLOW_CACHE_TTL_SECONDS", str(7 * 24 * 3600)))
    legacy_sync_wait_seconds: float = float(os.getenv("LEGACY_SYNC_WAIT_SECONDS", "1.5"))
    vivo_ocr_app_id: str = os.getenv("VIVO_OCR_APP_ID", "")
    vivo_ocr_app_key: str = os.getenv("VIVO_OCR_APP_KEY", "")
    vivo_ocr_business_profile: str = os.getenv("VIVO_OCR_BUSINESS_PROFILE", "rotatable")
    vivo_ocr_timeout_seconds: float = float(os.getenv("VIVO_OCR_TIMEOUT_SECONDS", "5"))
    max_upload_image_bytes: int = int(os.getenv("MAX_UPLOAD_IMAGE_BYTES", str(5 * 1024 * 1024)))

    @property
    def has_llm_config(self) -> bool:
        return bool(self.lanxin_api_key and self.lanxin_base_url)

    @property
    def has_fast_model_config(self) -> bool:
        return bool(self.fast_model_api_key and self.fast_model_base_url and self.fast_model_name)

    @property
    def has_expert_model_config(self) -> bool:
        return bool(self.expert_model_api_key and self.expert_model_base_url and self.expert_model_name)

    @property
    def has_vivo_ocr_config(self) -> bool:
        return bool(self.vivo_ocr_app_key)

    @property
    def vivo_ocr_business_id(self) -> str:
        if self.vivo_ocr_app_id.startswith("aigc"):
            return self.vivo_ocr_app_id
        if self.vivo_ocr_app_id:
            return f"aigc{self.vivo_ocr_app_id}"
        defaults = {
            "rotatable": "aigc1990173156ceb8a09eee80c293135279",
            "upright": "aigc8bf312e702043779ad0f2760b37a0806",
        }
        return defaults.get(self.vivo_ocr_business_profile, defaults["rotatable"])

    @property
    def cors_origin_list(self) -> list[str]:
        if self.cors_origins.strip() == "*":
            return ["*"]
        return [item.strip() for item in self.cors_origins.split(",") if item.strip()]


settings = Settings()
