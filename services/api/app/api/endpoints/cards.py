from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Query

from app.api.deps import get_card_repository
from app.repositories.cards import CardRepository
from app.schemas.card import ActionCard, ActionCardCreate, ActionCardUpdate
from app.schemas.intake import CardReplanRequest, CardReplanResponse
from app.services.planning_graph import build_planning_graph

router = APIRouter()
planning_graph = build_planning_graph()


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


@router.post("/{card_id}/replan", response_model=CardReplanResponse)
def replan_card(
    card_id: str,
    request: CardReplanRequest,
    repo: CardRepository = Depends(get_card_repository),
) -> CardReplanResponse:
    try:
        current = repo.get(card_id)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail="card not found") from exc
    result = planning_graph.invoke(
        {
            "card": current,
            "request": request,
            "profile": request.profile_context,
            "warnings": [],
        }
    )
    replanned = result["priority_card"]
    changed = replanned != current
    if changed:
        replanned = repo.update(
            card_id,
            ActionCardUpdate(
                priority=replanned.priority,
                priority_mode=replanned.priority_mode,
                priority_score=replanned.priority_score,
                priority_reason=replanned.priority_reason,
                priority_updated_at=replanned.priority_updated_at,
                priority_locked=replanned.priority_locked,
            ),
        )
    return CardReplanResponse(
        card=replanned,
        changed=changed,
        plan=result["plan"],
        calendar_actions=result.get("calendar_actions", []),
        verification_summary=result.get("verification_summary", ""),
        warnings=result.get("warnings", []),
    )
