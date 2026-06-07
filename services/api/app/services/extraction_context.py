from __future__ import annotations

import re
from datetime import datetime, timezone
from typing import Any

from app.schemas.card import ActionCard

NOISE_PATTERNS = [
    r"^\d{1,2}:\d{2}$",
    r"^(5G|4G|LTE|Wi-?Fi|KB/s|MB/s)$",
    r"video_\d{8}_\d{6}\.(mp4|mov)",
    r"IMG_\d+\.(jpg|jpeg|png)",
    r"\d+(\.\d+)?GB",
]

NOISE_TOKENS = {
    "发送",
    "群文件",
    "撤回了一条消息",
    "按住说话",
    "输入消息",
    "转发",
    "收藏",
}

MATERIAL_WORDS = [
    "报名表",
    "作品说明书",
    "实验报告",
    "进展汇报",
    "PPT",
    "商业计划书",
    "团队信息表",
    "表格",
    "材料",
    "证件",
    "文件",
]

PROMISE_WORDS = ["帮我", "帮你", "答应", "可以，我", "我来", "没问题", "承诺", "说好了"]

GENERIC_TITLES = {"", "处理截图事项", "整理截图信息", "行动卡", "事项", "待处理事项"}


def preprocess_ocr_text(text: str) -> str:
    """Remove common screenshot chrome while preserving user-visible content."""
    normalized = text.replace("\r\n", "\n").replace("\r", "\n")
    raw_lines = normalized.splitlines()
    if len(raw_lines) <= 1:
        raw_lines = re.split(r"\s{2,}", normalized)

    lines: list[str] = []
    for line in raw_lines:
        cleaned = _normalize_line(line)
        if _is_noise_line(cleaned):
            continue
        if cleaned and cleaned not in lines:
            lines.append(cleaned)
    return "\n".join(lines).strip()


def split_text_blocks(text: str) -> list[str]:
    cleaned = preprocess_ocr_text(text)
    blocks: list[str] = []
    for line in cleaned.splitlines():
        pieces = re.split(r"(?<=[。！？!?；;])\s*", line)
        for piece in pieces:
            compact = piece.strip(" ，,")
            if len(compact) < 3:
                continue
            if len(compact) > 120:
                blocks.extend(_split_long_piece(compact))
            else:
                blocks.append(compact)
    return _unique_keep_order(blocks)[:12]


def detect_hints(text: str, screenshot_time: str | None = None) -> dict[str, Any]:
    cleaned = preprocess_ocr_text(text)
    return {
        "time_expressions": _find_time_expressions(cleaned),
        "fuzzy_time_expressions": _find_fuzzy_time_expressions(cleaned),
        "locations": _find_locations(cleaned),
        "submit_methods": _find_submit_methods(cleaned),
        "materials": [word for word in MATERIAL_WORDS if word in cleaned],
        "amounts": _unique_keep_order(re.findall(r"(?:[¥￥]\s*)?\d+(?:\.\d+)?\s*元", cleaned)),
        "links": _unique_keep_order(re.findall(r"https?://[^\s，。；]+|www\.[^\s，。；]+", cleaned, flags=re.I)),
        "meeting_ids": _find_meeting_ids(cleaned),
        "comparison_options": _find_comparison_options(cleaned),
        "promise_signals": [word for word in PROMISE_WORDS if word in cleaned],
        "screenshot_time": screenshot_time,
    }


def build_llm_context(text: str, screenshot_time: str | None = None) -> dict[str, Any]:
    cleaned = preprocess_ocr_text(text)
    return {
        "raw_ocr_text": text,
        "cleaned_text": cleaned,
        "text_blocks": split_text_blocks(cleaned),
        "detected_hints": detect_hints(cleaned, screenshot_time),
        "screenshot_time": screenshot_time,
        "current_time": datetime.now(timezone.utc).isoformat(),
    }


def repair_title(card_type: str, title: str | None, text: str, hints: dict[str, Any] | None = None) -> str:
    current = (title or "").strip()
    if current and current not in GENERIC_TITLES:
        return current[:40]

    hints = hints or detect_hints(text)
    materials = hints.get("materials") or []
    locations = hints.get("locations") or []
    options = hints.get("comparison_options") or []

    if card_type == "task":
        return f"提交{materials[0]}" if materials else "处理待办任务"
    if card_type == "event":
        if "组会" in text:
            return "参加组会"
        return f"参加{locations[0]}事项" if locations else "参加日程事件"
    if card_type == "promise":
        return "处理聊天承诺"
    if card_type == "comparison":
        return f"对比{'、'.join(options[:2])}" if len(options) >= 2 else "整理对比信息"
    if card_type == "collection":
        if "图书馆" in text and "电话" in text:
            return "收藏图书馆电话"
        if "地址" in text:
            return "收藏地址信息"
        return "收藏截图信息"
    return "处理截图事项"


def build_summary(
    card_type: str,
    text: str,
    title: str | None = None,
    deadline: str | None = None,
    start_time: str | None = None,
    location: str | None = None,
    materials: list[str] | None = None,
    submit_method: str | None = None,
    hints: dict[str, Any] | None = None,
) -> str:
    hints = hints or detect_hints(text)
    source = preprocess_ocr_text(text)
    time_expr = _first(hints.get("time_expressions")) or _compact_time(deadline or start_time)
    location_text = location or _first(hints.get("locations"))
    material_text = _first(materials or hints.get("materials"))
    submit_text = submit_method or _first(hints.get("submit_methods"))
    title_text = repair_title(card_type, title, source, hints)

    if card_type == "task":
        parts = [time_expr and f"{time_expr}前", title_text, submit_text and f"通过{submit_text}"]
    elif card_type == "event":
        parts = [time_expr, location_text and f"在{location_text}", title_text]
    elif card_type == "promise":
        parts = [time_expr, title_text, "需跟进提醒"]
    elif card_type == "comparison":
        options = hints.get("comparison_options") or []
        target = "、".join(options[:3]) if options else title_text
        parts = [f"整理{target}的关键差异", "辅助选择"]
    elif card_type == "collection":
        parts = [title_text, _collection_value(source)]
    else:
        parts = [title_text, material_text]

    summary = "".join(part for part in parts if part)
    if len(summary) < 10:
        summary = _best_sentence(source)
    return _trim_summary(summary)


def should_rewrite_summary(summary: Any, source_text: str) -> bool:
    value = str(summary or "").strip()
    if not value or len(value) > 80:
        return True
    normalized_value = _compact(value)
    normalized_source = _compact(source_text)
    if normalized_value == normalized_source:
        return True
    return len(normalized_value) > 40 and normalized_value in normalized_source


def enrich_need_confirm(
    card_type: str,
    need_confirm: list[str],
    deadline: str | None,
    start_time: str | None,
    location: str | None,
    submit_method: str | None,
    hints: dict[str, Any],
) -> list[str]:
    fields = list(need_confirm)
    if card_type == "task" and not deadline:
        _append_once(fields, "截止时间")
    if card_type == "event":
        if not start_time:
            _append_once(fields, "时间")
        if not location:
            _append_once(fields, "地点")
    if card_type == "promise" and not deadline:
        _append_once(fields, "时间")
    if card_type == "task" and not submit_method and hints.get("submit_methods"):
        _append_once(fields, "提交方式")
    if card_type == "comparison" and len(hints.get("comparison_options") or []) < 2:
        _append_once(fields, "对比选项")
    if hints.get("fuzzy_time_expressions"):
        _append_once(fields, "时间")
    return fields


def dedupe_cards(cards: list[ActionCard]) -> list[ActionCard]:
    seen: set[tuple[str, str, str | None]] = set()
    result: list[ActionCard] = []
    for card in cards:
        key = (card.card_type, _compact(card.title), card.start_time or card.deadline)
        if key in seen:
            continue
        seen.add(key)
        result.append(card)
    return result


def _normalize_line(line: str) -> str:
    return re.sub(r"\s+", " ", line.strip())


def _is_noise_line(line: str) -> bool:
    compact = _compact(line)
    if not compact:
        return True
    if len(compact) <= 2 and not re.search(r"[年月日周点:：]|[\u4e00-\u9fff]{2}", compact):
        return True
    if any(token in compact for token in NOISE_TOKENS):
        return True
    return any(re.search(pattern, compact, flags=re.I) for pattern in NOISE_PATTERNS)


def _split_long_piece(piece: str) -> list[str]:
    chunks = [item.strip(" ，,") for item in re.split(r"[，,]", piece) if len(item.strip()) >= 3]
    return chunks or [piece[:120]]


def _find_time_expressions(text: str) -> list[str]:
    patterns = [
        r"(?:本周|这周|下周|周|星期)[一二三四五六日天](?:上午|早上|中午|下午|晚上|今晚)?\s*\d{0,2}\s*(?:点|:\d{2}|：\d{2})?",
        r"\d{1,2}\s*(?:月|[.．])\s*\d{1,2}\s*[日号]?(?:\s*(?:上午|早上|中午|下午|晚上)?\s*\d{1,2}[:：点]\d{0,2})?",
        r"(?:今天|明天|后天|今晚)(?:上午|早上|中午|下午|晚上)?\s*\d{0,2}\s*(?:点|:\d{2}|：\d{2})?",
        r"\d{1,2}[:：]\d{2}",
    ]
    return _unique_keep_order(match.group(0).strip() for pattern in patterns for match in re.finditer(pattern, text))


def _find_fuzzy_time_expressions(text: str) -> list[str]:
    patterns = [r"本月底", r"月底", r"近期", r"近日", r"改天", r"\d{1,2}\s*月\s*(?:上旬|中旬|下旬)"]
    return _unique_keep_order(match.group(0).strip() for pattern in patterns for match in re.finditer(pattern, text))


def _find_locations(text: str) -> list[str]:
    locations: list[str] = []
    for pattern in [
        r"地点[:：]\s*([^，。；\n]{2,32})",
        r"在\s*([^，。；\n]{2,40})(?:集合|开会|参加|签到|考试|召开|举行)",
    ]:
        locations.extend(match.group(1).strip() for match in re.finditer(pattern, text))
    for token in ["学习通", "官网", "邮箱", "腾讯会议", "Zoom"]:
        if token in text:
            locations.append(token)
    return _unique_keep_order(locations)


def _find_submit_methods(text: str) -> list[str]:
    methods: list[str] = []
    if "学习通" in text:
        methods.append("学习通")
    if "指定邮箱" in text:
        methods.append("指定邮箱")
    elif "邮箱" in text:
        methods.append("邮箱")
    if "官网" in text or "报名链接" in text:
        methods.append("官网报名链接")
    return methods


def _find_meeting_ids(text: str) -> list[str]:
    ids = re.findall(r"\d{3}[- ]\d{3}[- ]\d{3,4}|\d{9,11}", text)
    return _unique_keep_order(ids)


def _find_comparison_options(text: str) -> list[str]:
    options = re.findall(r"(?:方案|选项)\s*([A-Za-z0-9一二三四五六七八九十]+)", text)
    options.extend(re.findall(r"\b([A-Z])\s*(?:价格|¥|￥|\d+\s*元)", text))
    return _unique_keep_order(f"方案 {option}" if len(option) <= 3 else option for option in options)


def _best_sentence(text: str) -> str:
    cleaned = preprocess_ocr_text(text)
    pieces = re.split(r"[。！？!?；;\n]", cleaned)
    for piece in pieces:
        compact = piece.strip(" ，,")
        if len(compact) >= 10:
            return _trim_summary(compact)
    return _trim_summary(cleaned)


def _collection_value(text: str) -> str:
    phone = re.search(r"1\d{10}|0\d{2,3}-?\d{7,8}", text)
    if phone:
        if "图书馆" in text:
            return f"图书馆电话 {phone.group(0)}"
        return f"包含联系方式 {phone.group(0)}"
    address = re.search(r"地址[:：]\s*([^，。；\n]{2,40})", text)
    if address:
        return f"包含地址 {address.group(1)}"
    return _best_sentence(text)


def _compact_time(value: str | None) -> str | None:
    if not value:
        return None
    return value.replace("T", " ")[:16]


def _trim_summary(value: str) -> str:
    text = re.sub(r"\s+", " ", value).strip(" ，,。")
    return text[:60]


def _compact(value: str) -> str:
    return re.sub(r"\s+", "", value)


def _first(values: Any) -> str | None:
    if isinstance(values, list) and values:
        return str(values[0])
    return None


def _append_once(values: list[str], item: str) -> None:
    if item not in values:
        values.append(item)


def _unique_keep_order(values: Any) -> list[str]:
    result: list[str] = []
    for value in values:
        text = str(value).strip()
        if text and text not in result:
            result.append(text)
    return result
