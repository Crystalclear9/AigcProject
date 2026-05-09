from __future__ import annotations

import json
import re
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
card_type 只能是 task/event/promise/note；status 固定 draft。
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
        "response_format": {"type": "json_object"},
    }
    headers = {
        "Authorization": f"Bearer {settings.lanxin_api_key}",
        "Content-Type": "application/json",
    }

    async with httpx.AsyncClient(timeout=settings.request_timeout_seconds) as client:
        response = await client.post(url, json=payload, headers=headers)
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
        item.setdefault("id", "")
        item["id"] = item["id"] or f"llm-{len(cards) + 1}-{int(now.timestamp())}"
        item.setdefault("created_at", now)
        item.setdefault("source_text", text)
        item.setdefault("status", "draft")
        item.setdefault("materials", [])
        item.setdefault("tags", [])
        item.setdefault("reminders", [])
        item.setdefault("need_confirm", [])
        cards.append(ActionCard(**item))
    return cards
