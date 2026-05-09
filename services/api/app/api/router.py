from __future__ import annotations

from fastapi import APIRouter

from app.api.endpoints import analyze, cards, demo, metrics

api_router = APIRouter()
api_router.include_router(analyze.router, prefix="/analyze", tags=["analyze"])
api_router.include_router(cards.router, prefix="/cards", tags=["cards"])
api_router.include_router(demo.router, prefix="/demo", tags=["demo"])
api_router.include_router(metrics.router, prefix="/metrics", tags=["metrics"])
