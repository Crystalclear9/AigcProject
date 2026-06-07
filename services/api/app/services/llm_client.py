from __future__ import annotations

import json
import re
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Literal

import httpx

from app.core.config import settings
from app.schemas.card import ActionCard
from app.services.provider_runtime import runtime

SYSTEM_PROMPT = """
你是“随手办”的行动信息抽取引擎。把截图 OCR 文本转换为行动卡。
必须严格按提供的 JSON Schema 输出。不要解释。
不确定字段写入 need_confirm；status 固定为 draft；时间使用 ISO-8601。
""".strip()
PROMPT_VERSION = "adaptive-v1"


@dataclass(frozen=True)
class ModelProfile:
    role: Literal["fast_model", "expert_model"]
    api_key: str
    base_url: str
    model: str
    timeout: float

    @property
    def configured(self) -> bool:
        return bool(self.api_key and self.base_url and self.model)

    @property
    def signature(self) -> str:
        return f"{self.role}:{self.base_url}:{self.model}:{PROMPT_VERSION}"


def model_profile(role: Literal["fast_model", "expert_model"]) -> ModelProfile:
    if role == "fast_model":
        return ModelProfile(
            role=role,
            api_key=settings.fast_model_api_key,
            base_url=settings.fast_model_base_url,
            model=settings.fast_model_name,
            timeout=settings.fast_model_timeout_seconds,
        )
    return ModelProfile(
        role=role,
        api_key=settings.expert_model_api_key,
        base_url=settings.expert_model_base_url,
        model=settings.expert_model_name,
        timeout=settings.expert_model_timeout_seconds,
    )


CARD_SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "required": ["cards"],
    "properties": {
        "cards": {
            "type": "array",
            "items": {
                "type": "object",
                "additionalProperties": True,
                "required": ["card_type", "title"],
                "properties": {
                    "card_type": {"type": "string", "enum": ["task", "event", "promise", "note"]},
                    "title": {"type": "string"},
                    "summary": {"type": "string"},
                    "deadline": {"type": ["string", "null"]},
                    "start_time": {"type": ["string", "null"]},
                    "end_time": {"type": ["string", "null"]},
                    "location": {"type": ["string", "null"]},
                    "materials": {"type": "array", "items": {"type": "string"}},
                    "submit_method": {"type": ["string", "null"]},
                    "priority": {"type": "string", "enum": ["low", "normal", "high"]},
                    "tags": {"type": "array", "items": {"type": "string"}},
                    "reminders": {"type": "array", "items": {"type": "string"}},
                    "need_confirm": {"type": "array", "items": {"type": "string"}},
                    "status": {"type": "string"},
                    "source_text": {"type": "string"},
                },
            },
        }
    },
}


def _extract_json(text: str) -> Any:
    # Kept for compatibility with providers that ignore response_format.
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        match = re.search(r"(\[.*\]|\{.*\})", text, flags=re.S)
        if not match:
            raise
        return json.loads(match.group(1))


def _coerce_list(value: Any) -> list[str]:
    if value in (None, ""):
        return []
    if isinstance(value, list):
        return [str(item) for item in value if item not in (None, "")]
    return [str(value)]


def _normalize_card(item: dict[str, Any], text: str, index: int) -> ActionCard:
    now = datetime.now(timezone.utc)
    payload = dict(item)
    payload["id"] = payload.get("id") or f"model-{index}-{uuid.uuid4().hex[:8]}"
    payload["created_at"] = payload.get("created_at") or now
    payload["source_text"] = payload.get("source_text") or text
    payload["status"] = "draft"
    payload["priority"] = payload.get("priority") if payload.get("priority") in {"low", "normal", "high"} else "normal"
    payload["card_type"] = payload.get("card_type") if payload.get("card_type") in {"task", "event", "promise", "note"} else "task"
    for field in ("materials", "tags", "reminders", "need_confirm"):
        payload[field] = _coerce_list(payload.get(field))
    return ActionCard(**payload)


async def extract_cards_with_model(
    text: str,
    role: Literal["fast_model", "expert_model"],
    screenshot_time: str | None = None,
    validation_errors: list[str] | None = None,
) -> list[ActionCard]:
    profile = model_profile(role)
    if not profile.configured:
        raise RuntimeError(f"{role} is not configured")
    if not runtime.allow(role):
        raise RuntimeError(f"{role} circuit is open")

    payload = {
        "model": profile.model,
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {
                "role": "user",
                "content": json.dumps(
                    {
                        "ocr_text": text,
                        "screenshot_time": screenshot_time,
                        "current_time": datetime.now(timezone.utc).isoformat(),
                        "validation_errors_to_fix": validation_errors or [],
                    },
                    ensure_ascii=False,
                ),
            },
        ],
        "temperature": 0.0,
        "max_tokens": 1600 if role == "fast_model" else 2400,
        "stream": False,
        "response_format": {
            "type": "json_schema",
            "json_schema": {
                "name": "action_cards",
                "strict": True,
                "schema": CARD_SCHEMA,
            },
        },
    }
    headers = {"Authorization": f"Bearer {profile.api_key}", "Content-Type": "application/json"}
    params = {"request_id": str(uuid.uuid4())}
    try:
        async with runtime.semaphores[role]:
            response = await runtime.client.post(
                profile.base_url.rstrip("/") + "/chat/completions",
                params=params,
                json=payload,
                headers=headers,
                timeout=profile.timeout,
            )
            response.raise_for_status()
    except httpx.HTTPError:
        runtime.failure(role)
        raise
    runtime.success(role)
    content = response.json()["choices"][0]["message"]["content"]
    parsed = _extract_json(content)
    raw_cards = parsed.get("cards", parsed) if isinstance(parsed, dict) else parsed
    if not isinstance(raw_cards, list):
        raise ValueError("model response must contain a cards array")
    return [_normalize_card(item, text, index) for index, item in enumerate(raw_cards)]


async def extract_cards_with_lanxin(
    text: str,
    screenshot_time: str | None = None,
    validation_errors: list[str] | None = None,
) -> list[ActionCard]:
    return await extract_cards_with_model(text, "fast_model", screenshot_time, validation_errors)
