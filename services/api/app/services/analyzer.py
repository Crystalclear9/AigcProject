from __future__ import annotations

from app.schemas.card import AnalyzeScreenshotTextRequest, AnalyzeScreenshotTextResponse
from app.services.llm_client import extract_cards_with_lanxin
from app.services.rule_extractor import extract_cards_with_rules, preview_actions_for


async def analyze_screenshot_text(request: AnalyzeScreenshotTextRequest) -> AnalyzeScreenshotTextResponse:
    engine = "rules"
    try:
        cards = await extract_cards_with_lanxin(request.text, request.screenshot_time)
        engine = "lanxin"
    except Exception:
        cards = extract_cards_with_rules(request.text, request.screenshot_time)

    return AnalyzeScreenshotTextResponse(
        ocr_text=request.text,
        cards=cards,
        preview_actions=preview_actions_for(cards),
        engine=engine,
    )
