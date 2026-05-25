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
    cors_origins: str = os.getenv("CORS_ORIGINS", "*")
    lanxin_api_key: str = os.getenv("LANXIN_API_KEY", "")
    lanxin_base_url: str = os.getenv("LANXIN_BASE_URL", "")
    lanxin_model: str = os.getenv("LANXIN_MODEL", "lanxin-pro")
    request_timeout_seconds: float = float(os.getenv("REQUEST_TIMEOUT_SECONDS", "20"))
    vivo_ocr_app_id: str = os.getenv("VIVO_OCR_APP_ID", "")
    vivo_ocr_app_key: str = os.getenv("VIVO_OCR_APP_KEY", "")
    vivo_ocr_business_profile: str = os.getenv("VIVO_OCR_BUSINESS_PROFILE", "rotatable")
    vivo_ocr_timeout_seconds: float = float(os.getenv("VIVO_OCR_TIMEOUT_SECONDS", "5"))
    max_upload_image_bytes: int = int(os.getenv("MAX_UPLOAD_IMAGE_BYTES", str(5 * 1024 * 1024)))

    @property
    def has_llm_config(self) -> bool:
        return bool(self.lanxin_api_key and self.lanxin_base_url)

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
