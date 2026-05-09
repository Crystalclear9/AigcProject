from __future__ import annotations

from fastapi import APIRouter

from app.schemas.card import AnalyzeScreenshotTextRequest, AnalyzeScreenshotTextResponse
from app.services.analyzer import analyze_screenshot_text

router = APIRouter()


@router.post(
    "/screenshot-text",
    response_model=AnalyzeScreenshotTextResponse,
    summary="Analyze OCR text and return draft action cards",
)
async def analyze_text(request: AnalyzeScreenshotTextRequest) -> AnalyzeScreenshotTextResponse:
    return await analyze_screenshot_text(request)
