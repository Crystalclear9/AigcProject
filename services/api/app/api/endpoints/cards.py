from __future__ import annotations

from fastapi import APIRouter, Depends, Header, HTTPException, Query

from app.api.deps import get_card_repository, get_team_repository
from app.repositories.cards import CardRepository
from app.repositories.teams import TeamRepository
from app.schemas.card import ActionCard, ActionCardCreate, ActionCardUpdate
from app.schemas.intake import CardReplanRequest, CardReplanResponse
from app.services.planning_graph import build_planning_graph

router = APIRouter()
planning_graph = build_planning_graph()


def _require_team_membership(
    workspace_ids: set[str],
    user_id: str | None,
    teams: TeamRepository,
) -> None:
    """Authorize every team workspace touched by a card mutation."""
    if not workspace_ids:
        return
    normalized_user_id = user_id.strip() if user_id else ""
    if not normalized_user_id:
        raise HTTPException(status_code=422, detail="X-User-Id header required")
    try:
        for workspace_id in workspace_ids:
            teams.require_member(workspace_id, normalized_user_id)
    except PermissionError as exc:
        raise HTTPException(status_code=403, detail=str(exc)) from exc


def _team_workspaces_for_update(
    current: ActionCard, patch: ActionCardUpdate
) -> set[str]:
    """Return current and prospective teams so workspace moves cannot bypass authorization."""
    workspace_ids: set[str] = set()
    if current.workspace_type == "team" and current.workspace_id not in ("", "personal"):
        workspace_ids.add(current.workspace_id)
    target_type = patch.workspace_type or current.workspace_type
    target_id = patch.workspace_id or current.workspace_id
    if target_type == "team" and target_id not in ("", "personal"):
        workspace_ids.add(target_id)
    return workspace_ids


def _resolve_team_names(card, teams: TeamRepository):
    """Map plain-name assignee/participant hints to team member user ids.

    Rule extraction stores "小王" style hints; once the card lands in a real
    team workspace the names become stable member ids. Unknown names are kept
    verbatim so nothing is silently dropped.
    """
    if card.workspace_type != "team" or card.workspace_id in ("", "personal"):
        return card
    try:
        team = teams.get_team(card.workspace_id)
    except KeyError:
        return card
    member_ids = {member.user_id for member in team.members}
    by_nickname = {member.nickname: member.user_id for member in team.members}
    updates = {}
    if card.assignee_id and card.assignee_id not in member_ids:
        updates["assignee_id"] = by_nickname.get(card.assignee_id, card.assignee_id)
    if card.participant_ids:
        resolved = [
            participant
            if participant in member_ids
            else by_nickname.get(participant, participant)
            for participant in card.participant_ids
        ]
        if resolved != card.participant_ids:
            updates["participant_ids"] = resolved
    return card.model_copy(update=updates) if updates else card


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
    teams: TeamRepository = Depends(get_team_repository),
) -> ActionCard:
    return repo.create(_resolve_team_names(card, teams))


@router.patch("/{card_id}", response_model=ActionCard, summary="Update an action card")
def update_card(
    card_id: str,
    patch: ActionCardUpdate,
    user_id: str | None = Header(default=None, alias="X-User-Id"),
    repo: CardRepository = Depends(get_card_repository),
    teams: TeamRepository = Depends(get_team_repository),
) -> ActionCard:
    try:
        current = repo.get(card_id)
        _require_team_membership(
            _team_workspaces_for_update(current, patch), user_id, teams
        )
        updated = repo.update(card_id, patch)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail="card not found") from exc
    resolved = _resolve_team_names(updated, teams)
    if resolved is not updated:
        updated = repo.update(
            card_id,
            ActionCardUpdate(
                assignee_id=resolved.assignee_id,
                participant_ids=resolved.participant_ids,
            ),
        )
    return updated


@router.post("/{card_id}/complete", response_model=ActionCard, summary="Mark card as done")
def complete_card(
    card_id: str,
    user_id: str | None = Header(default=None, alias="X-User-Id"),
    repo: CardRepository = Depends(get_card_repository),
    teams: TeamRepository = Depends(get_team_repository),
) -> ActionCard:
    try:
        current = repo.get(card_id)
        _require_team_membership(
            _team_workspaces_for_update(current, ActionCardUpdate()), user_id, teams
        )
        return repo.complete(card_id)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail="card not found") from exc


@router.post("/{card_id}/replan", response_model=CardReplanResponse)
def replan_card(
    card_id: str,
    request: CardReplanRequest,
    user_id: str | None = Header(default=None, alias="X-User-Id"),
    repo: CardRepository = Depends(get_card_repository),
    teams: TeamRepository = Depends(get_team_repository),
) -> CardReplanResponse:
    try:
        current = repo.get(card_id)
        _require_team_membership(
            _team_workspaces_for_update(current, ActionCardUpdate()), user_id, teams
        )
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
