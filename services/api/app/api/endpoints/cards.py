from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Query

from app.api.deps import get_card_repository
from app.db.session import CardRepository
from app.schemas.card import ActionCard, ActionCardCreate, ActionCardUpdate

router = APIRouter()


@router.get("", response_model=list[ActionCard], summary="List action cards")
def list_cards(
    card_type: str | None = Query(default=None),
    status: str | None = Query(default=None),
    q: str | None = Query(default=None),
    repo: CardRepository = Depends(get_card_repository),
) -> list[ActionCard]:
    return repo.list(card_type=card_type, status=status, q=q)


@router.post("", response_model=ActionCard, summary="Create an action card")
def create_card(
    card: ActionCardCreate,
    repo: CardRepository = Depends(get_card_repository),
) -> ActionCard:
    return repo.create(card)


@router.patch("/{card_id}", response_model=ActionCard, summary="Update an action card")
def update_card(
    card_id: str,
    patch: ActionCardUpdate,
    repo: CardRepository = Depends(get_card_repository),
) -> ActionCard:
    try:
        return repo.update(card_id, patch)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail="card not found") from exc


@router.post("/{card_id}/complete", response_model=ActionCard, summary="Mark card as done")
def complete_card(
    card_id: str,
    repo: CardRepository = Depends(get_card_repository),
) -> ActionCard:
    try:
        return repo.complete(card_id)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail="card not found") from exc
