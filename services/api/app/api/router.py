from __future__ import annotations

from fastapi import APIRouter

from app.api.endpoints import (
    analyze,
    card_refinements,
    cards,
    demo,
    intakes,
    harness,
    metrics,
    onboarding,
    providers,
    teams,
    users,
    workflows,
)

api_router = APIRouter()
api_router.include_router(analyze.router, prefix="/analyze", tags=["analyze"])
api_router.include_router(cards.router, prefix="/cards", tags=["cards"])
api_router.include_router(demo.router, prefix="/demo", tags=["demo"])
api_router.include_router(intakes.router, prefix="/intakes", tags=["intakes"])
api_router.include_router(harness.router, prefix="/harness", tags=["development"])
api_router.include_router(metrics.router, prefix="/metrics", tags=["metrics"])
api_router.include_router(onboarding.router, prefix="/onboarding", tags=["onboarding"])
api_router.include_router(providers.router, prefix="/providers", tags=["providers"])
api_router.include_router(teams.router, prefix="/teams", tags=["teams"])
api_router.include_router(users.router, prefix="/users", tags=["users"])
api_router.include_router(workflows.router, prefix="/workflows", tags=["workflows"])
api_router.include_router(
    card_refinements.router,
    prefix="/card-refinements",
    tags=["card-refinements"],
)
