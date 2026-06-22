from __future__ import annotations

import json
import re
import uuid
import asyncio
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Literal

import httpx

from app.core.config import settings
from app.schemas.card import ActionCard
from app.services.provider_runtime import runtime
from app.services.rule_extractor import filter_action_cards
from app.services.extraction_context import (
    build_llm_context,
    build_summary,
    dedupe_cards,
    enrich_need_confirm,
    repair_title,
    should_rewrite_summary,
)

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
                    "card_type": {
                        "type": "string",
                        "enum": ["task", "event", "promise", "comparison", "collection"],
                    },
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


def _provider_rejected_response_format(error: httpx.HTTPStatusError) -> bool:
    if error.response.status_code not in {400, 422}:
        return False
    detail = error.response.text.lower()
    return any(
        marker in detail
        for marker in (
            "response_format",
            "json_schema",
            "schema",
            "unknown field",
            "unsupported",
            "invalid parameter",
            "extra fields",
        )
    )


def _without_response_format(payload: dict[str, Any], instruction: str) -> dict[str, Any]:
    fallback = dict(payload)
    fallback.pop("response_format", None)
    messages = [dict(message) for message in payload.get("messages", [])]
    if messages:
        messages[0]["content"] = f"{messages[0].get('content', '')}\n{instruction}".strip()
    fallback["messages"] = messages
    return fallback


async def _post_chat_completion(profile: ModelProfile, payload: dict[str, Any]) -> dict[str, Any]:
    headers = {"Authorization": f"Bearer {profile.api_key}", "Content-Type": "application/json"}
    url = _chat_completion_url(profile.base_url)
    telemetry_started: float | None = None

    async def send(body: dict[str, Any]) -> httpx.Response:
        return await runtime.client.post(
            url,
            params={"request_id": str(uuid.uuid4())},
            json=body,
            headers=headers,
            timeout=profile.timeout,
        )

    try:
        async with runtime.semaphores[profile.role]:
            telemetry_started = runtime.attempt(profile.role)
            try:
                response = await send(payload)
                response.raise_for_status()
            except httpx.HTTPStatusError as error:
                if "response_format" not in payload or not _provider_rejected_response_format(error):
                    raise
                response = await send(
                    _without_response_format(
                        payload,
                        "如果服务端不支持 response_format/json_schema，也必须只返回一个严格 JSON 对象；不要输出解释、Markdown 或代码块。",
                    )
                )
                response.raise_for_status()
    except asyncio.CancelledError:
        runtime.failure(profile.role, "CancelledError", telemetry_started)
        raise
    except httpx.HTTPError as error:
        runtime.failure(profile.role, type(error).__name__, telemetry_started)
        raise
    runtime.success(profile.role, telemetry_started)
    return response.json()


def _chat_completion_url(base_url: str) -> str:
    normalized = base_url.rstrip("/")
    if normalized.endswith("/chat/completions"):
        return normalized
    return normalized + "/chat/completions"


def _message_content(response_json: dict[str, Any]) -> str:
    content = response_json["choices"][0]["message"]["content"]
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts: list[str] = []
        for item in content:
            if isinstance(item, dict):
                value = item.get("text") or item.get("content")
                if value:
                    parts.append(str(value))
            elif item is not None:
                parts.append(str(item))
        return "\n".join(parts)
    return str(content)


def _coerce_list(value: Any) -> list[str]:
    if value in (None, ""):
        return []
    if isinstance(value, list):
        return [str(item) for item in value if item not in (None, "")]
    return [str(value)]


def _normalize_card_payload(
    item: dict[str, Any],
    text: str,
    card_id: str,
    now: datetime,
    hints: dict[str, Any],
) -> dict[str, Any]:
    payload = dict(item)
    payload["id"] = payload.get("id") or card_id
    payload["created_at"] = payload.get("created_at") or now
    payload["source_text"] = payload.get("source_text") or text
    payload["status"] = (
        payload.get("status")
        if payload.get("status") in {"draft", "confirmed", "done", "archived"}
        else "draft"
    )
    priority_aliases = {"medium": "normal", "中": "normal", "普通": "normal", "低": "low", "高": "high"}
    priority = priority_aliases.get(str(payload.get("priority", "")).lower(), payload.get("priority"))
    payload["priority"] = priority if priority in {"low", "normal", "high"} else "normal"
    type_aliases = {
        "任务": "task",
        "事件": "event",
        "承诺": "promise",
        "对比": "comparison",
        "收藏": "collection",
        "资料": "collection",
        "笔记": "collection",
        "note": "collection",
    }
    card_type = type_aliases.get(str(payload.get("card_type", "")).lower(), payload.get("card_type"))
    payload["card_type"] = (
        card_type
        if card_type in {"task", "event", "promise", "comparison", "collection"}
        else "task"
    )
    for field in ("materials", "tags", "reminders", "need_confirm"):
        payload[field] = _coerce_list(payload.get(field))
    payload["title"] = repair_title(payload["card_type"], payload.get("title"), text, hints)
    if should_rewrite_summary(payload.get("summary"), text):
        payload["summary"] = build_summary(
            card_type=payload["card_type"],
            text=payload.get("source_text") or text,
            title=payload["title"],
            deadline=payload.get("deadline"),
            start_time=payload.get("start_time"),
            location=payload.get("location"),
            materials=payload.get("materials"),
            submit_method=payload.get("submit_method"),
            hints=hints,
        )
    payload["need_confirm"] = enrich_need_confirm(
        card_type=payload["card_type"],
        need_confirm=payload["need_confirm"],
        deadline=payload.get("deadline"),
        start_time=payload.get("start_time"),
        location=payload.get("location"),
        submit_method=payload.get("submit_method"),
        hints=hints,
    )
    return payload


def _normalize_card(
    item: dict[str, Any],
    text: str,
    index: int,
    hints: dict[str, Any] | None = None,
) -> ActionCard:
    now = datetime.now(timezone.utc)
    payload = _normalize_card_payload(
        item,
        text,
        f"model-{index}-{uuid.uuid4().hex[:8]}",
        now,
        hints or {},
    )
    payload["status"] = "draft"
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
    context = build_llm_context(text, screenshot_time)

    payload = {
        "model": profile.model,
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {
                "role": "user",
                "content": json.dumps(
                    {
                        **context,
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
    content = _message_content(await _post_chat_completion(profile, payload))
    parsed = _extract_json(content)
    raw_cards = parsed.get("cards", parsed) if isinstance(parsed, dict) else parsed
    if not isinstance(raw_cards, list):
        raise ValueError("model response must contain a cards array")
    cards = [
        _normalize_card(item, text, index, context.get("detected_hints", {}))
        for index, item in enumerate(raw_cards)
        if isinstance(item, dict)
    ]
    return filter_action_cards(dedupe_cards(cards), text)


async def structured_completion(
    role: Literal["fast_model", "expert_model"],
    *,
    system_prompt: str,
    input_payload: dict[str, Any],
    schema_name: str,
    schema: dict[str, Any],
    max_tokens: int = 1200,
) -> dict[str, Any]:
    profile = model_profile(role)
    if not profile.configured:
        raise RuntimeError(f"{role} is not configured")
    if not runtime.allow(role):
        raise RuntimeError(f"{role} circuit is open")
    payload = {
        "model": profile.model,
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": json.dumps(input_payload, ensure_ascii=False)},
        ],
        "temperature": 0.0,
        "max_tokens": max_tokens,
        "stream": False,
        "response_format": {
            "type": "json_schema",
            "json_schema": {
                "name": schema_name,
                "strict": True,
                "schema": schema,
            },
        },
    }
    content = _message_content(await _post_chat_completion(profile, payload))
    parsed = _extract_json(content)
    if not isinstance(parsed, dict):
        raise ValueError("structured model response must be an object")
    return parsed


async def extract_cards_with_lanxin(
    text: str,
    screenshot_time: str | None = None,
    validation_errors: list[str] | None = None,
) -> list[ActionCard]:
    return await extract_cards_with_model(text, "fast_model", screenshot_time, validation_errors)
