from __future__ import annotations

import time
import uuid
from typing import Any

import httpx

from app.core.config import settings
from app.services.provider_runtime import runtime


class ImageGenerationError(RuntimeError):
    pass


async def generate_demo_image(prompt: str, *, size: str = "1024x1024") -> dict[str, Any]:
    if not settings.has_image_generation_config:
        raise ImageGenerationError("vivo image generation is not configured")
    payload = {
        "model": settings.vivo_image_generation_model,
        "prompt": prompt,
        "parameters": {
            "size": size,
            "sequential_image_generation": "disabled",
            "watermark": False,
        },
    }
    headers = {
        "Authorization": f"Bearer {settings.vivo_image_generation_api_key}",
        "Content-Type": "application/json",
    }
    params = {
        "module": "aigc",
        "request_id": str(uuid.uuid4()),
        "system_time": str(int(time.time())),
    }
    if not runtime.allow("image_generation"):
        raise ImageGenerationError("vivo image generation circuit is open")
    try:
        async with runtime.semaphores["image_generation"]:
            response = await runtime.client.post(
                settings.vivo_image_generation_url,
                params=params,
                json=payload,
                headers=headers,
                timeout=settings.vivo_image_generation_timeout_seconds,
            )
            response.raise_for_status()
    except httpx.HTTPError as error:
        runtime.failure("image_generation")
        raise ImageGenerationError(f"vivo image generation failed: {error}") from error
    body = response.json()
    if body.get("code") not in (0, "0", None):
        runtime.failure("image_generation")
        raise ImageGenerationError(f"vivo image generation error: {body.get('message') or body.get('code')}")
    runtime.success("image_generation")
    return body
