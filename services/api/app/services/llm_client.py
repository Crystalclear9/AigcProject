from __future__ import annotations

import json
import re
import uuid
from datetime import datetime, timezone
from typing import Any

import httpx

from app.core.config import settings
from app.schemas.card import ActionCard
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
你是“随手办”的行动信息抽取引擎，任务是把截图 OCR 文本转成可编辑行动卡。
请严格按以下流程思考，但最终只输出 JSON，不要输出解释：
1. 判断截图场景：课程/比赛/活动/会议/聊天承诺/对比决策。
2. 先拆分行动单元。同一截图中有多个 DDL、报名截止+活动时间、会议+准备事项、多项材料任务时，必须输出多张卡。
3. 对每个行动单元分类：task/event/promise/comparison/collection。
4. 抽取字段并生成卡片摘要。

输出必须是 JSON 数组或 {"cards": [...]}。每个对象必须包含：
card_type, title, summary, deadline, start_time, end_time, location, materials,
submit_method, priority, tags, reminders, need_confirm, status, source_text。

字段规则：
- card_type 只能是 task/event/promise/comparison/collection；status 固定 draft。
- task 用 deadline；event 用 start_time/end_time；promise 有明确时间时用 deadline。
- comparison 通常不设置提醒，不自动日历化。
- 不要为了兜底输出 collection。没有明确行动、事件、承诺或对比决策时输出空数组 []。
- 时间尽量输出 ISO-8601 字符串；如果只有“本月底、下周三、5月中旬”等模糊表达，把字段放进 need_confirm。
- 不要把 OCR 原文整段复制到 summary。summary 必须是 20-60 字中文短摘要，包含“主体 + 关键信息 + 行动/价值”。
- 低置信度、推断字段、缺失关键字段必须放入 need_confirm，例如：时间、地点、提交方式、对比选项。
- source_text 填该卡对应的原文片段，不要填无关噪声。
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


def _normalize_card_payload(
    item: dict[str, Any],
    text: str,
    card_id: str,
    now: datetime,
    hints: dict[str, Any],
) -> dict[str, Any]:
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

    card_type = normalized["card_type"]
    normalized["title"] = repair_title(card_type, normalized.get("title"), text, hints)
    if should_rewrite_summary(normalized.get("summary"), text):
        normalized["summary"] = build_summary(
            card_type=card_type,
            text=normalized.get("source_text") or text,
            title=normalized.get("title"),
            deadline=normalized.get("deadline"),
            start_time=normalized.get("start_time"),
            location=normalized.get("location"),
            materials=normalized.get("materials"),
            submit_method=normalized.get("submit_method"),
            hints=hints,
        )
    normalized["need_confirm"] = enrich_need_confirm(
        card_type=card_type,
        need_confirm=normalized["need_confirm"],
        deadline=normalized.get("deadline"),
        start_time=normalized.get("start_time"),
        location=normalized.get("location"),
        submit_method=normalized.get("submit_method"),
        hints=hints,
    )

    return normalized


async def extract_cards_with_lanxin(text: str, screenshot_time: str | None = None) -> list[ActionCard]:
    if not settings.has_llm_config:
        raise RuntimeError("LANXIN_API_KEY or LANXIN_BASE_URL is missing")

    # Accept either the API root ending in /v1 or the full chat completions endpoint.
    base_url = settings.lanxin_base_url.rstrip("/")
    url = base_url if base_url.endswith("/chat/completions") else f"{base_url}/chat/completions"
    user_prompt = build_llm_context(text, screenshot_time)
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

    # LLM 只负责增强抽卡；短超时失败后由规则抽取接管，避免拖慢截图弹窗。
    async with httpx.AsyncClient(timeout=settings.llm_fast_timeout_seconds) as client:
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
        if not isinstance(item, dict):
            continue
        card_id = f"llm-{len(cards) + 1}-{int(now.timestamp())}"
        cards.append(ActionCard(**_normalize_card_payload(item, text, card_id, now, user_prompt["detected_hints"])))
    return filter_action_cards(dedupe_cards(cards), text)
