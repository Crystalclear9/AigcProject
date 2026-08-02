from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass
from typing import Iterable

MOJIBAKE_MARKERS = re.compile(
    r"(?:锟斤拷|鏃堕棿|鎻愪氦|璇峰湪|Ã.|Â.|â€|ä½|å¥|æ[\x80-\xff]|�|□|■)"
)
RANDOM_TOKEN = re.compile(r"(?<![A-Za-z0-9])[A-Za-z0-9_-]{12,}(?![A-Za-z0-9])")
UI_NOISE = re.compile(
    r"(?:欢迎来到|功能介绍|未分类|清除全部|通知中心|返回|首页|设置|消息|我的|"
    r"^\s*\d{1,2}:\d{2}\s*$|(?:4G|5G|Wi-?Fi|电量\s*\d*%?|KB/s))",
    re.IGNORECASE | re.MULTILINE,
)
ACTION_OR_TIME = re.compile(
    r"(?:提交|完成|参加|报名|发送|准备|汇报|会议|考试|截止|截至|"
    r"\d{1,2}\s*月\s*\d{1,2}|\d{1,2}\s*[:：]\s*\d{2})"
)
SAFE_LATIN_TOKEN = re.compile(
    r"^(?:PPTX?|PDF|DOCX?|XLSX?|TXT|MD|DDL|AI|AIGC|URL|ID|[A-Z]\d{2,5})$",
    re.IGNORECASE,
)


@dataclass(frozen=True)
class TextIntegrityReport:
    text: str
    score: float
    reasons: tuple[str, ...]
    repaired: bool = False
    garbled_ratio: float = 0.0
    noise_ratio: float = 0.0

    @property
    def reliable(self) -> bool:
        return self.score >= 0.78 and not {
            "mojibake",
            "random_identifier",
            "invalid_unicode",
        }.intersection(self.reasons)


@dataclass(frozen=True)
class SummaryQualityReport:
    text: str
    score: float
    reasons: tuple[str, ...]
    evidence_coverage: float

    @property
    def acceptable(self) -> bool:
        return self.score >= 0.72 and not {
            "mojibake",
            "random_identifier",
            "ui_noise",
            "unsupported_summary",
        }.intersection(self.reasons)


def evaluate_text_integrity(value: str) -> TextIntegrityReport:
    normalized = _normalize(value)
    repaired, changed = _repair_reversible_mojibake(normalized)
    reasons: list[str] = []
    marker_count = sum(len(match.group(0)) for match in MOJIBAKE_MARKERS.finditer(repaired))
    if marker_count:
        reasons.append("mojibake")
    if _has_suspicious_random_token(repaired):
        reasons.append("random_identifier")
    noisy_lines = [line for line in repaired.splitlines() if UI_NOISE.search(line)]
    if noisy_lines:
        reasons.append("ui_noise")
    controls = sum(
        unicodedata.category(char) in {"Cc", "Cs", "Co", "Cn"}
        for char in repaired
        if char not in "\n\t"
    )
    if controls:
        reasons.append("invalid_unicode")
    length = max(1, len(repaired.replace("\n", "")))
    garbled_ratio = min(1.0, (marker_count + controls) / length)
    lines = [line for line in repaired.splitlines() if line.strip()]
    noise_ratio = len(noisy_lines) / max(1, len(lines))
    score = 1.0
    score -= min(0.62, garbled_ratio * 8)
    score -= 0.28 if "random_identifier" in reasons else 0
    score -= min(0.30, noise_ratio * 0.35)
    if len(repaired) < 4:
        score -= 0.35
        reasons.append("too_short")
    return TextIntegrityReport(
        text=repaired,
        score=round(max(0.0, min(1.0, score)), 4),
        reasons=tuple(dict.fromkeys(reasons)),
        repaired=changed,
        garbled_ratio=round(garbled_ratio, 4),
        noise_ratio=round(noise_ratio, 4),
    )


def sanitize_summary(value: str) -> str:
    report = evaluate_text_integrity(value)
    text = RANDOM_TOKEN.sub("", report.text)
    useful = [_clean_line(line) for line in re.split(r"[\n。；;]+", text)]
    useful = [line for line in useful if line and not UI_NOISE.search(line)]
    action_parts = [line for line in useful if ACTION_OR_TIME.search(line)]
    selected = action_parts or useful
    return "；".join(selected[:2])[:80].strip(" ,，。；")


def compose_evidence_summary(
    *,
    title: str,
    deadline: str | None = None,
    start_time: str | None = None,
    location: str | None = None,
    materials: Iterable[str] = (),
    submit_method: str | None = None,
    evidence_spans: Iterable[str] = (),
) -> str:
    title_value = sanitize_summary(title)
    if not title_value or not evaluate_text_integrity(title_value).reliable:
        return "摘要待复核"
    parts = [title_value]
    time_value = deadline or start_time
    if time_value:
        parts.append(f"时间：{_human_time(time_value)}")
    if location:
        parts.append(f"地点/平台：{sanitize_summary(location)}")
    material_values = [sanitize_summary(item) for item in materials]
    material_values = [item for item in material_values if item]
    if material_values:
        parts.append(f"材料：{'、'.join(material_values[:3])}")
    if submit_method:
        parts.append(f"方式：{sanitize_summary(submit_method)}")
    summary = "；".join(part for part in parts if part and not part.endswith("："))[:100]
    # Every component above is already a validated field. Raw evidence is retained for
    # audit, but strict substring matching would reject normalized dates and labels.
    report = evaluate_summary_quality(summary)
    return summary if report.acceptable else "摘要待复核"


def evaluate_summary_quality(
    value: str,
    *,
    evidence_spans: Iterable[str] = (),
) -> SummaryQualityReport:
    sanitized = sanitize_summary(value)
    integrity = evaluate_text_integrity(value)
    reasons = list(integrity.reasons)
    if sanitized != str(value or "").strip(" ,，。；"):
        reasons.append("summary_requires_sanitization")
    compact_summary = _compact(sanitized)
    compact_evidence = [_compact(span) for span in evidence_spans if str(span).strip()]
    meaningful_tokens = [
        token
        for token in re.findall(r"[\u4e00-\u9fff]{2,}|[A-Za-z0-9@._+-]{2,}", compact_summary)
        if not SAFE_LATIN_TOKEN.fullmatch(token)
    ]
    supported = sum(any(token in span for span in compact_evidence) for token in meaningful_tokens)
    coverage = supported / len(meaningful_tokens) if meaningful_tokens and compact_evidence else 1.0
    if compact_evidence and coverage < 0.45:
        reasons.append("unsupported_summary")
    if UI_NOISE.search(value):
        reasons.append("ui_noise")
    score = integrity.score * 0.72 + coverage * 0.28
    if not sanitized or sanitized == "摘要待复核":
        score = 0.0
        reasons.append("empty_summary")
    return SummaryQualityReport(
        text=sanitized,
        score=round(max(0.0, min(1.0, score)), 4),
        reasons=tuple(dict.fromkeys(reasons)),
        evidence_coverage=round(coverage, 4),
    )


def choose_better_summary(
    current: str,
    incoming: str,
    *,
    evidence_spans: Iterable[str] = (),
    current_user_locked: bool = False,
) -> str:
    if current_user_locked:
        return current
    current_report = evaluate_summary_quality(current, evidence_spans=evidence_spans)
    incoming_report = evaluate_summary_quality(incoming, evidence_spans=evidence_spans)
    if incoming_report.acceptable and (
        not current_report.acceptable or incoming_report.score >= current_report.score + 0.08
    ):
        return incoming_report.text
    if current_report.acceptable:
        return current_report.text
    return incoming_report.text if incoming_report.acceptable else "摘要待复核"


def summary_needs_rewrite(value: str) -> bool:
    return not evaluate_summary_quality(value).acceptable


def _has_suspicious_random_token(text: str) -> bool:
    if not re.search(r"[\u4e00-\u9fff]", text):
        return False
    return any(
        not token.lower().startswith(("http", "www"))
        and "@" not in token
        and not SAFE_LATIN_TOKEN.fullmatch(token)
        for token in RANDOM_TOKEN.findall(text)
    )


def _repair_reversible_mojibake(text: str) -> tuple[str, bool]:
    if not MOJIBAKE_MARKERS.search(text):
        return text, False
    original_digits = re.findall(r"\d", text)
    candidates = [text]
    for source in ("latin1", "cp1252", "gbk"):
        try:
            repaired = text.encode(source).decode("utf-8")
            if re.findall(r"\d", repaired) == original_digits:
                candidates.append(repaired)
        except (UnicodeEncodeError, UnicodeDecodeError):
            pass
    best = min(
        candidates,
        key=lambda item: (
            len(MOJIBAKE_MARKERS.findall(item)),
            -sum("\u4e00" <= char <= "\u9fff" for char in item),
        ),
    )
    improved = len(MOJIBAKE_MARKERS.findall(best)) < len(MOJIBAKE_MARKERS.findall(text))
    return (best, True) if improved else (text, False)


def _normalize(value: str) -> str:
    text = unicodedata.normalize("NFKC", str(value or "")).replace("\r\n", "\n").replace("\r", "\n")
    return "\n".join(re.sub(r"[ \t]+", " ", line).strip() for line in text.splitlines() if line.strip())


def _clean_line(value: str) -> str:
    return re.sub(r"\s+", " ", value).strip(" ,，。:：;；-|_")


def _compact(value: str) -> str:
    return re.sub(r"[\s，。；;:：、]", "", value)


def _human_time(value: str) -> str:
    return value.replace("T", " ").replace("+08:00", "").replace("Z", "").strip()
