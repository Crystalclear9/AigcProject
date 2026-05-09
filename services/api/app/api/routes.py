from __future__ import annotations

from fastapi import APIRouter, HTTPException, Query

from app.db.session import CardRepository
from app.schemas.card import (
    ActionCard,
    ActionCardCreate,
    ActionCardUpdate,
    AnalyzeScreenshotTextRequest,
    AnalyzeScreenshotTextResponse,
)
from app.services.llm_client import extract_cards_with_lanxin
from app.services.rule_extractor import extract_cards_with_rules, preview_actions_for

router = APIRouter()
repo = CardRepository()


@router.post("/analyze/screenshot-text", response_model=AnalyzeScreenshotTextResponse)
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


@router.get("/cards", response_model=list[ActionCard])
def list_cards(
    card_type: str | None = Query(default=None),
    status: str | None = Query(default=None),
    q: str | None = Query(default=None),
) -> list[ActionCard]:
    return repo.list(card_type=card_type, status=status, q=q)


@router.post("/cards", response_model=ActionCard)
def create_card(card: ActionCardCreate) -> ActionCard:
    return repo.create(card)


@router.patch("/cards/{card_id}", response_model=ActionCard)
def update_card(card_id: str, patch: ActionCardUpdate) -> ActionCard:
    try:
        return repo.update(card_id, patch)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail="card not found") from exc


@router.post("/cards/{card_id}/complete", response_model=ActionCard)
def complete_card(card_id: str) -> ActionCard:
    try:
        return repo.complete(card_id)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail="card not found") from exc
