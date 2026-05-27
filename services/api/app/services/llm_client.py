from __future__ import annotations

import json
import re
import uuid
from datetime import datetime, timezone
from typing import Any

import httpx

from app.core.config import settings
from app.schemas.card import ActionCard


SYSTEM_PROMPT = """
你是“随手办”的行动信息抽取引擎。请把截图 OCR 文本抽取为 JSON 数组。
只输出 JSON，不要输出解释。每个对象字段必须包含：
card_type, title, summary, deadline, start_time, end_time, location, materials,
submit_method, priority, tags, reminders, need_confirm, status, source_text。
card_type 只能是 task/event/promise/comparison/collection；status 固定 draft。
时间请尽量输出 ISO-8601 字符串；不确定的字段放进 need_confirm。
"""


def _extract_json(text: str) -> Any:
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        match = re.search(r"(\[.*\]|\{.*\})", text, flags=re.S)
        if not match:
            raise
        return json.loads(match.group(1))


def _coerce_list(value: Any) -> list[str]:
    if value is None or value == "":
        return []
    if isinstance(value, list):
        return [str(item) for item in value if item not in (None, "")]
    return [str(value)]


def _normalize_choice(value: Any, allowed: set[str], default: str, aliases: dict[str, str] | None = None) -> str:
    text = str(value or "").strip().lower()
    if aliases and text in aliases:
        text = aliases[text]
    return text if text in allowed else default


def _normalize_card_payload(item: dict[str, Any], text: str, card_id: str, now: datetime) -> dict[str, Any]:
    normalized = dict(item)
    normalized["id"] = normalized.get("id") or card_id
    normalized.setdefault("created_at", now)
    normalized.setdefault("source_text", text)
    normalized["status"] = _normalize_choice(normalized.get("status"), {"draft", "confirmed", "done", "archived"}, "draft")
    normalized["priority"] = _normalize_choice(
        normalized.get("priority"),
        {"low", "normal", "high"},
        "normal",
        {"medium": "normal", "中": "normal", "普通": "normal", "低": "low", "高": "high"},
    )
    normalized["card_type"] = _normalize_choice(
        normalized.get("card_type"),
        {"task", "event", "promise", "comparison", "collection"},
        "task",
        {
            "任务": "task",
            "事件": "event",
            "承诺": "promise",
            "对比": "comparison",
            "收藏": "collection",
            "资料": "collection",
            "笔记": "collection",
            "note": "collection",
        },
    )

    # LLMs often return a single string for list fields; normalize before Pydantic validation.
    for field in ("materials", "tags", "reminders", "need_confirm"):
        normalized[field] = _coerce_list(normalized.get(field))

    return normalized


async def extract_cards_with_lanxin(text: str, screenshot_time: str | None = None) -> list[ActionCard]:
    if not settings.has_llm_config:
        raise RuntimeError("LANXIN_API_KEY or LANXIN_BASE_URL is missing")

    url = settings.lanxin_base_url.rstrip("/") + "/chat/completions"
    user_prompt = {
        "ocr_text": text,
        "screenshot_time": screenshot_time,
        "current_time": datetime.now(timezone.utc).isoformat(),
    }
    payload = {
        "model": settings.lanxin_model,
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT.strip()},
            {"role": "user", "content": json.dumps(user_prompt, ensure_ascii=False)},
        ],
        "temperature": 0.1,
        "max_tokens": 2048,
        "stream": False,
    }
    headers = {
        "Authorization": f"Bearer {settings.lanxin_api_key}",
        "Content-Type": "application/json; charset=utf-8",
    }
    # vivo API requires a per-request UUID in the query string.
    params = {"request_id": str(uuid.uuid4())}

    async with httpx.AsyncClient(timeout=settings.request_timeout_seconds) as client:
        response = await client.post(url, params=params, json=payload, headers=headers)
        response.raise_for_status()
        body = response.json()

    content = body["choices"][0]["message"]["content"]
    parsed = _extract_json(content)
    raw_cards = parsed.get("cards", parsed) if isinstance(parsed, dict) else parsed
    if not isinstance(raw_cards, list):
        raise ValueError("LLM response must be a JSON array or {cards: [...]}")

    cards: list[ActionCard] = []
    now = datetime.now(timezone.utc)
    for item in raw_cards:
        card_id = f"llm-{len(cards) + 1}-{int(now.timestamp())}"
        cards.append(ActionCard(**_normalize_card_payload(item, text, card_id, now)))
    return cards
