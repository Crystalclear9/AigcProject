from __future__ import annotations

from app.schemas.card import AnalyzeScreenshotTextRequest, AnalyzeScreenshotTextResponse
from app.services.llm_client import extract_cards_with_lanxin
from app.services.rule_extractor import extract_cards_with_rules, preview_actions_for
from app.services.vivo_ocr import VivoOcrClient, clean_ocr_lines


async def analyze_screenshot_text(request: AnalyzeScreenshotTextRequest) -> AnalyzeScreenshotTextResponse:
    cards, engine = await _extract_cards(request.text, request.screenshot_time)
    return AnalyzeScreenshotTextResponse(
        ocr_text=request.text,
        cards=cards,
        preview_actions=preview_actions_for(cards),
        engine=engine,
    )


async def analyze_screenshot_image(image_bytes: bytes, screenshot_time: str | None = None) -> AnalyzeScreenshotTextResponse:
    lines = await VivoOcrClient().recognize(image_bytes)
    cleaned_text = clean_ocr_lines(lines)
    if not cleaned_text:
        raise ValueError("vivo OCR did not return usable text")

    cards, text_engine = await _extract_cards(cleaned_text, screenshot_time)
    return AnalyzeScreenshotTextResponse(
        ocr_text=cleaned_text,
        cards=cards,
        preview_actions=preview_actions_for(cards),
        engine=f"vivo-ocr+{text_engine}",
    )


async def _extract_cards(text: str, screenshot_time: str | None = None):
    engine = "rules"
    try:
        cards = await extract_cards_with_lanxin(text, screenshot_time)
        engine = "lanxin"
    except Exception:
        cards = extract_cards_with_rules(text, screenshot_time)
    return cards, engine
