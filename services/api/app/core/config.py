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
    workflow_checkpoint_database_path: str = os.getenv(
        "WORKFLOW_CHECKPOINT_DATABASE_PATH",
        "./workflow_checkpoint.db",
    )
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
    llm_fast_timeout_seconds: float = float(os.getenv("LLM_FAST_TIMEOUT_SECONDS", "6"))
    fast_model_timeout_seconds: float = float(os.getenv("FAST_MODEL_TIMEOUT_SECONDS", "15"))
    expert_model_timeout_seconds: float = float(os.getenv("EXPERT_MODEL_TIMEOUT_SECONDS", "12"))
    provider_max_concurrency: int = int(os.getenv("PROVIDER_MAX_CONCURRENCY", "8"))
    workflow_max_concurrency: int = int(os.getenv("WORKFLOW_MAX_CONCURRENCY", "20"))
    workflow_agent_max_tasks: int = int(os.getenv("WORKFLOW_AGENT_MAX_TASKS", "8"))
    workflow_agent_max_replans: int = int(os.getenv("WORKFLOW_AGENT_MAX_REPLANS", "2"))
    workflow_agent_deadline_seconds: float = float(os.getenv("WORKFLOW_AGENT_DEADLINE_SECONDS", "15"))
    workflow_tool_max_concurrency: int = int(os.getenv("WORKFLOW_TOOL_MAX_CONCURRENCY", "8"))
    web_retrieval_enabled: bool = os.getenv("WEB_RETRIEVAL_ENABLED", "true").lower() in {"1", "true", "yes"}
    web_retrieval_timeout_seconds: float = float(os.getenv("WEB_RETRIEVAL_TIMEOUT_SECONDS", "3"))
    web_retrieval_base_url: str = os.getenv(
        "WEB_RETRIEVAL_BASE_URL",
        "https://en.wikipedia.org/w/api.php",
    )
    workflow_cache_ttl_seconds: int = int(os.getenv("WORKFLOW_CACHE_TTL_SECONDS", str(7 * 24 * 3600)))
    legacy_sync_wait_seconds: float = float(os.getenv("LEGACY_SYNC_WAIT_SECONDS", "1.5"))
    vivo_ocr_app_id: str = os.getenv("VIVO_OCR_APP_ID", "")
    vivo_ocr_app_key: str = os.getenv("VIVO_OCR_APP_KEY", "")
    vivo_ocr_url: str = os.getenv(
        "VIVO_OCR_URL",
        "http://api-ai.vivo.com.cn/ocr/general_recognition",
    )
    vivo_ocr_business_profile: str = os.getenv("VIVO_OCR_BUSINESS_PROFILE", "rotatable")
    vivo_ocr_timeout_seconds: float = float(os.getenv("VIVO_OCR_TIMEOUT_SECONDS", "5"))
    vivo_image_generation_api_key: str = os.getenv(
        "VIVO_IMAGE_GENERATION_API_KEY",
        os.getenv("LANXIN_API_KEY", ""),
    )
    vivo_image_generation_url: str = os.getenv(
        "VIVO_IMAGE_GENERATION_URL",
        "https://api-ai.vivo.com.cn/api/v1/image_generation",
    )
    vivo_image_generation_model: str = os.getenv(
        "VIVO_IMAGE_GENERATION_MODEL",
        "Doubao-Seedream-4.5",
    )
    vivo_image_generation_timeout_seconds: float = float(
        os.getenv("VIVO_IMAGE_GENERATION_TIMEOUT_SECONDS", "60")
    )
    enable_provider_probe: bool = os.getenv("ENABLE_PROVIDER_PROBE", "false").lower() in {
        "1",
        "true",
        "yes",
    }
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
    def has_image_generation_config(self) -> bool:
        return bool(self.vivo_image_generation_api_key and self.vivo_image_generation_url)

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
