from __future__ import annotations

import math
import re
import unicodedata
from dataclasses import dataclass
from difflib import SequenceMatcher
from typing import Any

ACTION_TERMS = (
    "提交",
    "完成",
    "参加",
    "报名",
    "发送",
    "准备",
    "汇报",
    "开会",
    "考试",
    "提醒",
    "负责",
)
OBJECT_TERMS = (
    "报告",
    "作业",
    "材料",
    "表格",
    "PPT",
    "文档",
    "会议",
    "课程",
    "项目",
    "申请",
)
PLACE_TERMS = (
    "教室",
    "会议室",
    "学习通",
    "腾讯会议",
    "邮箱",
    "群文件",
    "线上",
    "线下",
)
TIME_PATTERN = re.compile(
    r"(?:20\d{2}[-/.年])?\d{1,2}[-/.月]\d{1,2}(?:日|号)?"
    r"|(?:周|星期)[一二三四五六日天]"
    r"|(?:今天|明天|后天|今晚|上午|下午|晚上)"
    r"|\d{1,2}\s*[:：]\s*\d{2}"
)
STATUS_PATTERN = re.compile(
    r"(?:\b[345]G\b|Wi-?Fi|电量\s*\d+%|\d{1,2}:\d{2}\s+\d{1,3}%|返回|通知中心|清除全部)",
    re.IGNORECASE,
)
REPLACEMENT_PATTERN = re.compile(r"[\uFFFD□■�]")
MOJIBAKE_PATTERN = re.compile(r"(?:锟斤拷|烫烫烫|屯屯屯|鈻|鏃堕棿|鎻愪氦)")


@dataclass(frozen=True)
class OcrAdjudication:
    selected: dict[str, Any]
    candidates: list[dict[str, Any]]
    merged_text: str
    requires_review: bool
    review_reasons: list[str]
    critical_conflicts: list[str]


def create_trusted_text_candidate(
    text: str,
    *,
    engine: str,
) -> dict[str, Any]:
    """Build a complete OCR-shaped candidate for text verified by the user."""
    candidate = evaluate_candidate(
        {
            "text": text,
            "engine": engine,
            "confidence": 1.0,
        }
    )
    report = dict(candidate["quality_report"])
    report.update(
        {
            "quality_score": 1.0,
            "agreement_score": 1.0,
            "reasons": ["user_verified_text"],
        }
    )
    candidate.update(
        {
            "confidence": 1.0,
            "quality_score": 1.0,
            "quality_report": report,
        }
    )
    return candidate


def evaluate_candidate(
    candidate: dict[str, Any],
    peers: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    candidate = _normalize_candidate_blocks(candidate)
    text = _normalize_text(str(candidate.get("text", "")))
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    compact = "".join(lines)
    char_count = len(compact)
    replacement_count = sum(bool(REPLACEMENT_PATTERN.search(char)) for char in compact)
    replacement_count += sum(len(match.group(0)) for match in MOJIBAKE_PATTERN.finditer(compact))
    replacement_ratio = _ratio(replacement_count, char_count)
    invalid_ratio = _ratio(sum(_is_invalid_character(char) for char in compact), char_count)
    duplicate_ratio = _duplicate_ratio(lines)
    noise_ratio = _ratio(sum(bool(STATUS_PATTERN.search(line)) for line in lines), len(lines))
    action_hits = sum(term in text for term in ACTION_TERMS)
    object_hits = sum(term.lower() in text.lower() for term in OBJECT_TERMS)
    place_hits = sum(term.lower() in text.lower() for term in PLACE_TERMS)
    time_hits = len(TIME_PATTERN.findall(text))
    block_count = max(len(candidate.get("blocks") or []), len(lines))
    layout_score = _layout_score(candidate.get("blocks") or [], block_count)
    completeness = min(1.0, math.log1p(char_count) / math.log(180)) if char_count else 0.0
    evidence_score = min(
        1.0,
        0.34 * min(action_hits, 2)
        + 0.22 * min(object_hits, 2)
        + 0.22 * min(time_hits, 2)
        + 0.12 * min(place_hits, 2)
        + 0.10 * min(block_count / 4, 1),
    )
    agreement = _peer_agreement(text, peers or [])
    provider_hint = float(candidate.get("confidence", 0.5))
    quality = (
        0.24 * completeness
        + 0.24 * evidence_score
        + 0.14 * layout_score
        + 0.14 * agreement
        + 0.04 * max(0.0, min(provider_hint, 1.0))
        + 0.20 * (1.0 - min(1.0, replacement_ratio + invalid_ratio))
        - 0.10 * duplicate_ratio
        - 0.08 * noise_ratio
    )
    quality = max(0.0, min(1.0, quality))
    reasons: list[str] = []
    if char_count < 8:
        reasons.append("text_too_short")
    if replacement_ratio + invalid_ratio >= 0.08:
        reasons.append("garbled_characters")
    if duplicate_ratio >= 0.35:
        reasons.append("duplicate_blocks")
    if noise_ratio >= 0.45:
        reasons.append("chrome_noise")
    if action_hits and not (object_hits or time_hits):
        reasons.append("incomplete_action_evidence")
    if not action_hits and not object_hits:
        reasons.append("no_action_evidence")
    report = {
        "quality_score": round(quality, 4),
        "garbled_ratio": round(replacement_ratio + invalid_ratio, 4),
        "completeness_score": round(completeness, 4),
        "layout_score": round(layout_score, 4),
        "evidence_score": round(evidence_score, 4),
        "agreement_score": round(agreement, 4),
        "duplicate_ratio": round(duplicate_ratio, 4),
        "noise_ratio": round(noise_ratio, 4),
        "block_count": block_count,
        "time_expressions": sorted(set(TIME_PATTERN.findall(text))),
        "reasons": reasons,
    }
    enriched = dict(candidate)
    enriched["text"] = text
    enriched["quality_score"] = report["quality_score"]
    enriched["quality_report"] = report
    return enriched


def _normalize_candidate_blocks(candidate: dict[str, Any]) -> dict[str, Any]:
    enriched = dict(candidate)
    width = float(candidate.get("image_width") or 0)
    height = float(candidate.get("image_height") or 0)
    normalized: list[dict[str, Any]] = []
    for index, raw in enumerate(candidate.get("blocks") or []):
        block = raw if isinstance(raw, dict) else {}
        bounds = block.get("bounds") or {}
        left = block.get("left", bounds.get("left"))
        top = block.get("top", bounds.get("top"))
        right = block.get("right", bounds.get("right"))
        bottom = block.get("bottom", bounds.get("bottom"))
        if None in {left, top, right, bottom}:
            continue
        left_value, top_value = float(left), float(top)
        right_value, bottom_value = float(right), float(bottom)
        if width > 1 and max(left_value, right_value) > 1.5:
            left_value, right_value = left_value / width, right_value / width
        if height > 1 and max(top_value, bottom_value) > 1.5:
            top_value, bottom_value = top_value / height, bottom_value / height
        normalized.append(
            {
                "text": str(block.get("text", "")).strip(),
                "left": max(0.0, min(1.0, left_value)),
                "top": max(0.0, min(1.0, top_value)),
                "right": max(0.0, min(1.0, right_value)),
                "bottom": max(0.0, min(1.0, bottom_value)),
                "line_index": block.get("line_index", index),
            }
        )
    enriched["blocks"] = sorted(
        normalized,
        key=lambda item: (item["top"], item["left"], item["line_index"]),
    )
    return enriched


def adjudicate_candidates(candidates: list[dict[str, Any]]) -> OcrAdjudication:
    usable = [candidate for candidate in candidates if str(candidate.get("text", "")).strip()]
    if not usable:
        raise ValueError("no usable OCR candidates")
    first_pass = [evaluate_candidate(candidate) for candidate in usable]
    scored = [
        evaluate_candidate(candidate, [peer for peer in first_pass if peer is not candidate])
        for candidate in first_pass
    ]
    scored.sort(key=lambda item: float(item.get("quality_score", 0)), reverse=True)
    selected = scored[0]
    conflicts = list(
        dict.fromkeys(
            conflict
            for left_index in range(len(scored))
            for right_index in range(left_index + 1, len(scored))
            for conflict in _critical_conflicts(
                [scored[left_index], scored[right_index]]
            )
        )
    )
    reasons = list(selected.get("quality_report", {}).get("reasons", []))
    if float(selected.get("quality_score", 0)) < 0.72:
        reasons.append("low_ocr_quality")
    if conflicts:
        reasons.append("critical_field_conflict")
    if _task_boundary_uncertain(selected["text"]):
        reasons.append("uncertain_task_boundary")
    merged_text = _merge_complementary_lines(scored, conflicts)
    return OcrAdjudication(
        selected=selected,
        candidates=scored,
        merged_text=merged_text,
        requires_review=bool(
            float(selected.get("quality_score", 0)) < 0.72
            or conflicts
            or "garbled_characters" in reasons
            or "uncertain_task_boundary" in reasons
        ),
        review_reasons=list(dict.fromkeys(reasons)),
        critical_conflicts=conflicts,
    )


def _normalize_text(value: str) -> str:
    value = unicodedata.normalize("NFKC", value).replace("\r\n", "\n").replace("\r", "\n")
    lines = [re.sub(r"[ \t]+", " ", line).strip() for line in value.splitlines()]
    return "\n".join(line for line in lines if line)


def _is_invalid_character(char: str) -> bool:
    if char.isspace() or char.isalnum() or "\u4e00" <= char <= "\u9fff":
        return False
    category = unicodedata.category(char)
    return category in {"Cc", "Cs", "Co", "Cn"}


def _ratio(value: int, total: int) -> float:
    return value / total if total else 0.0


def _duplicate_ratio(lines: list[str]) -> float:
    if len(lines) < 2:
        return 0.0
    normalized = [re.sub(r"\s+", "", line).lower() for line in lines]
    return 1.0 - len(set(normalized)) / len(normalized)


def _layout_score(blocks: list[dict[str, Any]], block_count: int) -> float:
    if not blocks:
        # Plain OCR clients may not expose geometry. Missing optional coordinates is
        # neutral evidence, not proof of a broken reading order.
        return 0.70 if block_count else 0.0
    positions: list[tuple[float, float]] = []
    for block in blocks:
        top = block.get("top")
        left = block.get("left")
        if top is None or left is None:
            bounds = block.get("bounds") or {}
            top, left = bounds.get("top"), bounds.get("left")
        if top is not None and left is not None:
            positions.append((float(top), float(left)))
    if len(positions) < 2:
        return 0.65
    ordered = sum(
        positions[index] <= positions[index + 1]
        for index in range(len(positions) - 1)
    )
    return 0.55 + 0.45 * ordered / (len(positions) - 1)


def _peer_agreement(text: str, peers: list[dict[str, Any]]) -> float:
    comparable = [
        SequenceMatcher(None, re.sub(r"\s+", "", text), re.sub(r"\s+", "", str(peer.get("text", "")))).ratio()
        for peer in peers
        if str(peer.get("text", "")).strip() and str(peer.get("text", "")).strip() != text.strip()
    ]
    return max(comparable, default=0.55)


def _critical_conflicts(candidates: list[dict[str, Any]]) -> list[str]:
    if len(candidates) < 2:
        return []
    left_times = set(candidates[0].get("quality_report", {}).get("time_expressions", []))
    right_times = set(candidates[1].get("quality_report", {}).get("time_expressions", []))
    conflicts: list[str] = []
    for kind, pattern in (
        ("date", re.compile(r"(?:20\d{2}[-/.年])?\d{1,2}[-/.月]\d{1,2}(?:日|号)?|(?:周|星期)[一二三四五六日天]|今天|明天|后天")),
        ("clock", re.compile(r"\d{1,2}\s*[:：]\s*\d{2}|上午|下午|晚上|今晚")),
    ):
        left_values = {value for value in left_times if pattern.search(value)}
        right_values = {value for value in right_times if pattern.search(value)}
        if left_values and right_values and left_values.isdisjoint(right_values):
            conflicts.append(f"{kind}:{sorted(left_values)}!={sorted(right_values)}")
    left_text = re.sub(r"\s+", "", str(candidates[0].get("text", "")))
    right_text = re.sub(r"\s+", "", str(candidates[1].get("text", "")))
    similarity = SequenceMatcher(None, left_text, right_text).ratio()
    both_actionable = all(
        any(term in text for term in ACTION_TERMS)
        for text in (left_text, right_text)
    )
    if both_actionable and similarity < 0.45:
        conflicts.append(f"content:similarity={similarity:.2f}")
    return conflicts


def _task_boundary_uncertain(text: str) -> bool:
    action_lines = [
        line
        for line in text.splitlines()
        if any(term in line for term in ACTION_TERMS)
    ]
    if len(action_lines) <= 1:
        return False
    distinct_times = set(TIME_PATTERN.findall(text))
    has_boundaries = bool(re.search(r"(?:^|\n|\s)[1-9][.、)]|[；;]\s*", text))
    return len(distinct_times) > 1 and not has_boundaries and len(action_lines) >= 3


def _merge_complementary_lines(
    candidates: list[dict[str, Any]],
    conflicts: list[str],
) -> str:
    best = str(candidates[0]["text"]).strip()
    if len(candidates) < 2 or conflicts:
        return best
    second = str(candidates[1]["text"]).strip()
    similarity = SequenceMatcher(None, re.sub(r"\s+", "", best), re.sub(r"\s+", "", second)).ratio()
    if similarity < 0.55:
        return best
    lines = [line for line in best.splitlines() if line.strip()]
    block_lines = _merge_spatial_blocks(candidates[:2])
    if block_lines:
        lines = block_lines
    for line in second.splitlines():
        normalized = re.sub(r"\s+", "", line)
        if not normalized:
            continue
        if max(
            (
                SequenceMatcher(None, normalized, re.sub(r"\s+", "", existing)).ratio()
                for existing in lines
            ),
            default=0.0,
        ) < 0.72 and (
            any(term in line for term in ACTION_TERMS + OBJECT_TERMS + PLACE_TERMS)
            or TIME_PATTERN.search(line)
        ):
            lines.append(line.strip())
    return "\n".join(lines)


def _merge_spatial_blocks(candidates: list[dict[str, Any]]) -> list[str]:
    positioned = [
        block
        for candidate in candidates
        for block in candidate.get("blocks", [])
        if str(block.get("text", "")).strip()
    ]
    if not positioned:
        return []
    merged: list[dict[str, Any]] = []
    for block in sorted(positioned, key=lambda item: (item["top"], item["left"])):
        text = str(block["text"]).strip()
        match = next(
            (
                existing
                for existing in merged
                if abs(float(existing["top"]) - float(block["top"])) <= 0.025
                and SequenceMatcher(
                    None,
                    re.sub(r"\s+", "", str(existing["text"])),
                    re.sub(r"\s+", "", text),
                ).ratio()
                >= 0.68
            ),
            None,
        )
        if match is None:
            merged.append(dict(block))
        elif len(text) > len(str(match["text"])):
            match.update(block)
    return [str(block["text"]).strip() for block in merged]
