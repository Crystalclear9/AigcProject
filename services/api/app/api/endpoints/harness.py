from __future__ import annotations

from fastapi import APIRouter, HTTPException, Query

from app.core.config import settings
from pathlib import Path

from app.services.workflow_harness import run_harness, run_image_harness

router = APIRouter()


@router.post("/run")
async def execute_harness(
    limit: int = Query(default=150, ge=1, le=200),
    mode: str = Query(default="text", pattern="^(text|image)$"),
) -> dict[str, object]:
    if not settings.enable_workflow_harness or settings.workflow_environment == "production":
        raise HTTPException(status_code=404, detail="workflow harness is disabled")
    if mode == "image":
        manifest = (
            Path(__file__).resolve().parents[5]
            / "docs"
            / "test-assets"
            / "screenshots"
            / "manifest.jsonl"
        )
        if not manifest.is_file():
            raise HTTPException(status_code=503, detail="image harness manifest unavailable")
        return await run_image_harness(manifest, limit=limit)
    return await run_harness(limit)
