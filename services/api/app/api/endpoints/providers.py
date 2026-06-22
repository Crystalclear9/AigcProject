from __future__ import annotations

import asyncio
import time
from typing import Any

from fastapi import APIRouter, HTTPException

from app.core.config import settings
from app.services.image_generation import generate_demo_image
from app.services.llm_client import structured_completion
from app.services.provider_runtime import provider_status_snapshot, provider_usage_delta
from app.services.vivo_ocr import VivoOcrClient

router = APIRouter()


@router.get("/status")
async def provider_status() -> dict[str, Any]:
    return {
        "configured": _configured(),
        "providers": provider_status_snapshot(),
    }


@router.post("/probe")
async def provider_probe() -> dict[str, Any]:
    if not settings.enable_provider_probe:
        raise HTTPException(status_code=403, detail="provider probe is disabled")
    before = provider_status_snapshot()
    results = {
        "chat": await _probe_chat(),
        "ocr": await _probe_ocr(),
        "image_generation": await _probe_image_generation(),
    }
    usage = provider_usage_delta(before)
    return {
        "configured": _configured(),
        "results": results,
        "provider_usage_delta": usage,
        "all_succeeded": all(item.get("succeeded") for item in results.values()),
    }


def _configured() -> dict[str, bool]:
    return {
        "chat": settings.has_fast_model_config or settings.has_expert_model_config,
        "ocr": settings.has_vivo_ocr_config,
        "image_generation": settings.has_image_generation_config,
    }


async def _probe_chat() -> dict[str, Any]:
    if not (settings.has_fast_model_config or settings.has_expert_model_config):
        return _result(False, False, "not_configured")
    started = time.perf_counter()
    try:
        await structured_completion(
            "fast_model" if settings.has_fast_model_config else "expert_model",
            system_prompt=(
                "Return only JSON. This is a non-private provider health probe for a "
                "screenshot-to-action workflow."
            ),
            input_payload={
                "sample": "明天10:00提交测试报告",
                "expected": {"has_action": True, "title": "提交测试报告"},
            },
            schema_name="provider_probe",
            schema={
                "type": "object",
                "additionalProperties": False,
                "required": ["ok"],
                "properties": {"ok": {"type": "boolean"}},
            },
            max_tokens=120,
        )
        return _result(True, True, None, started)
    except Exception as error:
        return _result(True, False, type(error).__name__, started)


async def _probe_ocr() -> dict[str, Any]:
    if not settings.has_vivo_ocr_config:
        return _result(False, False, "not_configured")
    started = time.perf_counter()
    try:
        lines = await VivoOcrClient().recognize(_probe_bmp())
        return _result(True, bool(lines), None if lines else "empty_ocr", started)
    except Exception as error:
        return _result(True, False, type(error).__name__, started)


async def _probe_image_generation() -> dict[str, Any]:
    if not settings.has_image_generation_config:
        return _result(False, False, "not_configured")
    started = time.perf_counter()
    try:
        await asyncio.wait_for(
            generate_demo_image("A small blue-white checklist app icon, no text", size="2K"),
            timeout=max(3.0, min(settings.vivo_image_generation_timeout_seconds, 45.0)),
        )
        return _result(True, True, None, started)
    except Exception as error:
        return _result(True, False, type(error).__name__, started)


def _result(
    configured: bool,
    succeeded: bool,
    error_type: str | None,
    started: float | None = None,
) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "configured": configured,
        "attempted": configured,
        "succeeded": succeeded,
        "error_type": error_type,
    }
    if started is not None:
        payload["latency_ms"] = round((time.perf_counter() - started) * 1000, 2)
    return payload


def _probe_bmp() -> bytes:
    width, height = 520, 160
    scale = 10
    text = "TEST 10:00"
    pixels = bytearray([255] * (width * height * 3))
    x = 36
    y = 48
    for char in text:
        if char == " ":
            x += 4 * scale
            continue
        glyph = _FONT.get(char.upper(), _FONT["?"])
        for row, bits in enumerate(glyph):
            for col, bit in enumerate(bits):
                if bit == "1":
                    _fill_rect(pixels, width, x + col * scale, y + row * scale, scale, scale, (0, 0, 0))
        x += (len(glyph[0]) + 1) * scale
    return _bmp24(width, height, pixels)


def _fill_rect(
    pixels: bytearray,
    width: int,
    left: int,
    top: int,
    rect_width: int,
    rect_height: int,
    color: tuple[int, int, int],
) -> None:
    red, green, blue = color
    for y in range(top, top + rect_height):
        for x in range(left, left + rect_width):
            index = (y * width + x) * 3
            pixels[index : index + 3] = bytes((blue, green, red))


def _bmp24(width: int, height: int, pixels: bytearray) -> bytes:
    row_size = ((width * 3 + 3) // 4) * 4
    pixel_array_size = row_size * height
    file_size = 54 + pixel_array_size
    header = bytearray()
    header.extend(b"BM")
    header.extend(file_size.to_bytes(4, "little"))
    header.extend((0).to_bytes(4, "little"))
    header.extend((54).to_bytes(4, "little"))
    header.extend((40).to_bytes(4, "little"))
    header.extend(width.to_bytes(4, "little"))
    header.extend(height.to_bytes(4, "little"))
    header.extend((1).to_bytes(2, "little"))
    header.extend((24).to_bytes(2, "little"))
    header.extend((0).to_bytes(4, "little"))
    header.extend(pixel_array_size.to_bytes(4, "little"))
    header.extend((2835).to_bytes(4, "little"))
    header.extend((2835).to_bytes(4, "little"))
    header.extend((0).to_bytes(4, "little"))
    header.extend((0).to_bytes(4, "little"))

    body = bytearray()
    padding = b"\x00" * (row_size - width * 3)
    for row in range(height - 1, -1, -1):
        start = row * width * 3
        body.extend(pixels[start : start + width * 3])
        body.extend(padding)
    return bytes(header + body)


_FONT = {
    "0": ["111", "101", "101", "101", "101", "101", "111"],
    "1": ["010", "110", "010", "010", "010", "010", "111"],
    ":": ["0", "1", "1", "0", "1", "1", "0"],
    "E": ["111", "100", "100", "111", "100", "100", "111"],
    "S": ["111", "100", "100", "111", "001", "001", "111"],
    "T": ["111", "010", "010", "010", "010", "010", "010"],
    "?": ["111", "001", "001", "011", "010", "000", "010"],
}
