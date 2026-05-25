from __future__ import annotations

import uuid

from app.schemas.card import AnalyzeScreenshotTextRequest, AnalyzeScreenshotTextResponse
from app.services.llm_client import extract_cards_with_lanxin
from app.services.rule_extractor import extract_cards_with_rules, preview_actions_for
from app.services.vivo_ocr import VivoOcrClient, clean_ocr_lines


async def analyze_screenshot_text(request: AnalyzeScreenshotTextRequest) -> AnalyzeScreenshotTextResponse:
    trace_id = str(uuid.uuid4())
    cards, engine, fallback_reason, warnings = await _extract_cards(request.text, request.screenshot_time)
    return AnalyzeScreenshotTextResponse(
        ocr_text=request.text,
        cards=cards,
        preview_actions=preview_actions_for(cards),
        engine=engine,
        trace_id=trace_id,
        fallback_reason=fallback_reason,
        warnings=warnings,
    )


async def analyze_screenshot_image(image_bytes: bytes, screenshot_time: str | None = None) -> AnalyzeScreenshotTextResponse:
    trace_id = str(uuid.uuid4())
    lines = await VivoOcrClient().recognize(image_bytes)
    cleaned_text = clean_ocr_lines(lines)
    if not cleaned_text:
        raise ValueError("vivo OCR did not return usable text")

    cards, text_engine, fallback_reason, warnings = await _extract_cards(cleaned_text, screenshot_time)
    return AnalyzeScreenshotTextResponse(
        ocr_text=cleaned_text,
        cards=cards,
        preview_actions=preview_actions_for(cards),
        engine=f"vivo-ocr+{text_engine}",
        trace_id=trace_id,
        fallback_reason=fallback_reason,
        warnings=warnings,
    )


async def _extract_cards(text: str, screenshot_time: str | None = None):
    warnings: list[str] = []
    try:
        cards = await extract_cards_with_lanxin(text, screenshot_time)
        return cards, "lanxin", None, warnings
    except Exception as error:
        fallback_reason = f"{type(error).__name__}: {error}"
        warnings.append("蓝心大模型不可用，已自动切换到本地规则抽取")
        cards = extract_cards_with_rules(text, screenshot_time)
        return cards, "rules", fallback_reason, warnings
