from __future__ import annotations

from fastapi import APIRouter, File, Form, HTTPException, UploadFile

from app.schemas.card import AnalyzeScreenshotTextRequest, AnalyzeScreenshotTextResponse
from app.services.analyzer import analyze_screenshot_image, analyze_screenshot_text
from app.core.config import settings
from app.services.vivo_ocr import VivoOcrError

router = APIRouter()


@router.post(
    "/screenshot-text",
    response_model=AnalyzeScreenshotTextResponse,
    summary="Analyze OCR text and return draft action cards",
)
async def analyze_text(request: AnalyzeScreenshotTextRequest) -> AnalyzeScreenshotTextResponse:
    return await analyze_screenshot_text(request)


@router.post(
    "/screenshot-image",
    response_model=AnalyzeScreenshotTextResponse,
    summary="Analyze a screenshot image through vivo OCR and return draft action cards",
)
async def analyze_image(
    image: UploadFile = File(...),
    screenshot_time: str | None = Form(default=None),
) -> AnalyzeScreenshotTextResponse:
    content_type = (image.content_type or "").lower()
    if content_type and content_type not in {"image/jpeg", "image/jpg", "image/png", "image/bmp"}:
        raise HTTPException(status_code=415, detail="只支持 jpg、png、bmp 图片")

    image_bytes = await image.read()
    if not image_bytes:
        raise HTTPException(status_code=400, detail="图片为空")
    if len(image_bytes) > settings.max_upload_image_bytes:
        raise HTTPException(status_code=413, detail="图片超过上传大小限制")

    try:
        return await analyze_screenshot_image(image_bytes, screenshot_time)
    except VivoOcrError as error:
        raise HTTPException(status_code=502, detail=str(error)) from error
    except ValueError as error:
        raise HTTPException(status_code=422, detail=str(error)) from error
