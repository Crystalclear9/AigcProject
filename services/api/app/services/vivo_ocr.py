from __future__ import annotations

import base64
import re
import uuid
from dataclasses import dataclass
from typing import Any

import httpx

from app.core.config import settings
from app.services.provider_runtime import runtime

@dataclass(frozen=True)
class OcrLine:
    text: str
    left: float
    top: float
    right: float
    bottom: float

    @property
    def center_y(self) -> float:
        return (self.top + self.bottom) / 2

    @property
    def center_x(self) -> float:
        return (self.left + self.right) / 2


class VivoOcrError(RuntimeError):
    pass


def _format_http_error(error: httpx.HTTPStatusError) -> str:
    body = error.response.text.strip()
    suffix = f": {body}" if body else ""
    return f"vivo OCR HTTP {error.response.status_code}{suffix}"


def _format_request_error(error: httpx.HTTPError) -> str:
    return f"vivo OCR request failed: {error}"


def parse_successful_vivo_ocr_body(body: dict[str, Any]) -> list[OcrLine]:
    if body.get("error_code") != 0:
        error_code = body.get("error_code")
        error_msg = body.get("error_msg") or "vivo OCR failed"
        raise VivoOcrError(f"vivo OCR error_code={error_code}: {error_msg}")
    lines = parse_vivo_ocr_lines(body)
    if not lines:
        raise VivoOcrError("vivo OCR returned no text lines")
    return lines


class VivoOcrClient:
    async def recognize(self, image_bytes: bytes) -> list[OcrLine]:
        if not settings.has_vivo_ocr_config:
            raise VivoOcrError("VIVO_OCR_APP_KEY is missing")

        payload = {
            "image": base64.b64encode(image_bytes).decode("utf-8"),
            "pos": 2,
            "businessid": settings.vivo_ocr_business_id,
        }
        params = {"requestId": str(uuid.uuid4())}
        headers = {
            "Authorization": f"Bearer {settings.vivo_ocr_app_key}",
            "Content-Type": "application/x-www-form-urlencoded",
        }
        if not runtime.allow("ocr"):
            raise VivoOcrError("vivo OCR circuit is open")
        try:
            async with runtime.semaphores["ocr"]:
                response = await runtime.client.post(
                    settings.vivo_ocr_url,
                    data=payload,
                    params=params,
                    headers=headers,
                    timeout=settings.vivo_ocr_timeout_seconds,
                )
                response.raise_for_status()
        except httpx.HTTPStatusError as error:
            runtime.failure("ocr")
            raise VivoOcrError(_format_http_error(error)) from error
        except httpx.HTTPError as error:
            runtime.failure("ocr")
            raise VivoOcrError(_format_request_error(error)) from error

        runtime.success("ocr")
        return parse_successful_vivo_ocr_body(response.json())


def parse_vivo_ocr_lines(body: dict[str, Any]) -> list[OcrLine]:
    result = body.get("result") or {}
    records = result.get("OCR") or result.get("words") or []
    lines: list[OcrLine] = []
    for index, item in enumerate(records):
        text = str(item.get("words") or "").strip()
        if not text:
            continue
        location = item.get("location")
        if location:
            lines.append(_line_from_location(text, location))
        else:
            # pos=0 has no coordinates; keep input order as a weak layout signal.
            lines.append(OcrLine(text=text, left=0.0, top=float(index), right=1.0, bottom=float(index + 1)))
    return lines


def _line_from_location(text: str, location: dict[str, Any]) -> OcrLine:
    points = [
        location.get("top_left") or {},
        location.get("top_right") or {},
        location.get("down_left") or {},
        location.get("down_right") or {},
    ]
    xs = [float(point.get("x", 0.0)) for point in points]
    ys = [float(point.get("y", 0.0)) for point in points]
    return OcrLine(text=text, left=min(xs), top=min(ys), right=max(xs), bottom=max(ys))


def clean_ocr_lines(lines: list[OcrLine]) -> str:
    if not lines:
        return ""

    normalized = _normalize_coordinates(lines)
    candidates = [line for line in normalized if not _is_noise_line(line)]
    if not candidates:
        candidates = [
            line
            for line in normalized
            if line.text.strip()
            and not re.search(r"video_\d{8}_\d{6}\.mp4|\d+(\.\d+)?GB|KB/s", line.text, flags=re.I)
            and not re.fullmatch(r"\d{1,2}:\d{2}", line.text.strip())
        ]
    action_lines = [line for line in candidates if _has_action_signal(line.text)]
    if action_lines:
        candidates = _expand_nearby_block(candidates, action_lines)

    merged = _merge_same_row(candidates)
    text = "\n".join(line for line in merged if line)
    return _strip_residual_noise(text)


def _normalize_coordinates(lines: list[OcrLine]) -> list[OcrLine]:
    max_x = max((line.right for line in lines), default=1.0) or 1.0
    max_y = max((line.bottom for line in lines), default=1.0) or 1.0
    if max_x <= 1.5 and max_y <= 1.5:
        return lines
    return [
        OcrLine(
            text=line.text,
            left=line.left / max_x,
            top=line.top / max_y,
            right=line.right / max_x,
            bottom=line.bottom / max_y,
        )
        for line in lines
    ]


def _is_noise_line(line: OcrLine) -> bool:
    text = line.text.strip()
    compact = re.sub(r"\s+", "", text)
    if not compact:
        return True
    # Top and bottom mobile chrome are layout, not user intent.
    if line.center_y < 0.12 or line.center_y > 0.90:
        return True
    if re.fullmatch(r"\d{1,2}:\d{2}", compact):
        return True
    if any(token in compact for token in ["KB/s", "5G", "发送", "群文件", "撤回了一条消息"]):
        return True
    if re.search(r"video_\d{8}_\d{6}\.mp4", compact, flags=re.I):
        return True
    if re.search(r"\d+(\.\d+)?GB", compact, flags=re.I):
        return True
    if len(compact) <= 2 and not re.search(r"[年月日周点:：]", compact):
        return True
    return False


def _has_action_signal(text: str) -> bool:
    return any(
        token in text
        for token in [
            "通知",
            "会议",
            "请",
            "参加",
            "地点",
            "时间",
            "主题党日",
            "本周",
            "下周",
            "上午",
            "下午",
            "@全体成员",
        ]
    ) or bool(re.search(r"\d{1,2}[.:：]\d{2}|\d{1,2}\s*[月.]\s*\d{1,2}", text))


def _expand_nearby_block(candidates: list[OcrLine], action_lines: list[OcrLine]) -> list[OcrLine]:
    top = max(0.0, min(line.top for line in action_lines) - 0.04)
    bottom = min(1.0, max(line.bottom for line in action_lines) + 0.08)
    left = max(0.0, min(line.left for line in action_lines) - 0.08)
    right = min(1.0, max(line.right for line in action_lines) + 0.08)
    return [
        line
        for line in candidates
        if top <= line.center_y <= bottom and left <= line.center_x <= right
    ]


def _merge_same_row(lines: list[OcrLine]) -> list[str]:
    sorted_lines = sorted(lines, key=lambda line: (line.center_y, line.left))
    rows: list[list[OcrLine]] = []
    for line in sorted_lines:
        if not rows or abs(rows[-1][0].center_y - line.center_y) > 0.018:
            rows.append([line])
        else:
            rows[-1].append(line)

    merged: list[str] = []
    for row in rows:
        pieces = [line.text.strip() for line in sorted(row, key=lambda item: item.left) if line.text.strip()]
        merged.append(" ".join(pieces))
    return merged


def _strip_residual_noise(text: str) -> str:
    lines = []
    for line in text.splitlines():
        compact = re.sub(r"\s+", "", line)
        if re.search(r"video_\d{8}_\d{6}\.mp4|\d+(\.\d+)?GB|撤回了一条消息|群文件", compact, flags=re.I):
            continue
        lines.append(line.strip())
    return "\n".join(lines).strip()
