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
from app.services.demo_scenarios import evaluate_demo_scenarios, scenario_catalog
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


@router.get("/demo/scenarios")
def list_demo_scenarios() -> list[dict[str, object]]:
    return scenario_catalog()


@router.get("/demo/evaluate")
def evaluate_demo() -> dict[str, object]:
    return evaluate_demo_scenarios()


@router.get("/metrics/summary")
def metrics_summary() -> dict[str, object]:
    cards = repo.list()
    active_cards = [card for card in cards if card.status not in {"done", "archived"}]
    confirmed_cards = [card for card in cards if card.status == "confirmed"]
    reminders = sum(len(card.reminders) for card in cards)
    need_confirm = sum(len(card.need_confirm) for card in cards)
    card_types = {card.card_type for card in cards}
    estimated_minutes_saved = round(len(cards) * 2.5, 1)
    return {
        "cards_total": len(cards),
        "cards_active": len(active_cards),
        "cards_confirmed": len(confirmed_cards),
        "reminders_total": reminders,
        "need_confirm_fields": need_confirm,
        "card_type_coverage": sorted(card_types),
        "estimated_minutes_saved": estimated_minutes_saved,
        "demo_eval": evaluate_demo_scenarios(),
    }
