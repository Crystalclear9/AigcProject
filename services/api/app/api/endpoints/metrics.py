from __future__ import annotations

from fastapi import APIRouter, Depends

from app.api.deps import get_card_repository
from app.db.session import CardRepository
from app.services.metrics import build_summary

router = APIRouter()


@router.get("/summary", summary="Return product and demo metrics")
def metrics_summary(repo: CardRepository = Depends(get_card_repository)) -> dict[str, object]:
    return build_summary(repo.list())
