from __future__ import annotations

import os


# python-dotenv preserves existing environment values by default. Empty
# credentials keep developer .env files from changing test routing at import.
for provider_key in (
    "LANXIN_API_KEY",
    "FAST_MODEL_API_KEY",
    "EXPERT_MODEL_API_KEY",
    "VIVO_OCR_APP_KEY",
    "VIVO_IMAGE_GENERATION_API_KEY",
):
    os.environ[provider_key] = ""
