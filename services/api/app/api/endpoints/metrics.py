from __future__ import annotations

from fastapi import APIRouter, Depends

from app.api.deps import get_card_repository
from app.repositories.cards import CardRepository
from app.services.metrics import build_summary
from app.repositories.workflows import WorkflowRepository

router = APIRouter()


@router.get("/summary", summary="Return product and demo metrics")
def metrics_summary(repo: CardRepository = Depends(get_card_repository)) -> dict[str, object]:
    summary = build_summary(repo.list())
    summary["workflows"] = WorkflowRepository().metrics()
    return summary


@router.get("/performance", summary="Return workflow latency, cache, and routing metrics")
def performance_metrics() -> dict[str, object]:
    return WorkflowRepository().metrics()
