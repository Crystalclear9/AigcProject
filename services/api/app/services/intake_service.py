from __future__ import annotations

import uuid
from datetime import datetime, timezone
from typing import Any

from app.repositories.intakes import IntakeRepository
from app.schemas.action_graph import ActionGraph
from app.schemas.card import ActionCard
from app.schemas.intake import IntakeSessionResponse, RoleTemplate, SourceKind
from app.schemas.workflow import WorkflowRunResponse
from app.services.intake_graph import build_intake_graph
from app.services.prompt_envelope import compile_prompt_envelope
from app.services.workflow_service import get_workflow, start_text_workflow

repository = IntakeRepository()
_graph = build_intake_graph()


async def start_intake(
    *,
    text: str,
    source_kind: SourceKind,
    workspace_type: str,
    profile_context: dict[str, Any] | None,
    role_template: RoleTemplate,
    warnings: list[str] | None = None,
) -> IntakeSessionResponse:
    session_id = str(uuid.uuid4())
    envelope = compile_prompt_envelope(role_template, profile_context)
    graph_state = await _graph.ainvoke(
        {
            "text": text,
            "workspace_type": workspace_type,
            "analyzer_results": [],
        }
    )
    workflow: WorkflowRunResponse | None = None
    workflow_run_id: str | None = None
    if graph_state.get("should_create_cards"):
        workflow = await start_text_workflow(
            graph_state.get("canonical_text", text),
            workflow_context={
                "workspace_type": workspace_type,
                "prompt_envelope": envelope.model_dump(),
                "source_session_id": session_id,
            },
        )
        workflow_run_id = workflow.run_id
    now = datetime.now(timezone.utc)
    intake_evidence_cards = [
        ActionCard(**card) for card in graph_state.get("cards", [])
    ]
    stable_cards = (
        [
            card.model_dump(mode="json")
            for card in _merge_intake_evidence(
                workflow.cards,
                intake_evidence_cards,
                workspace_type,
                session_id,
            )
        ]
        if workflow
        else graph_state.get("cards", [])
    )
    state = {
        "session_id": session_id,
        "workflow_run_id": workflow_run_id,
        "source_kind": source_kind,
        "workspace_type": workspace_type,
        "classification": graph_state.get("classification", "informational"),
        "classification_confidence": graph_state.get("classification_confidence", 0),
        "should_create_cards": bool(graph_state.get("should_create_cards")),
        "canonical_text": graph_state.get("canonical_text", ""),
        "cards": stable_cards,
        "intake_evidence_cards": [
            card.model_dump(mode="json") for card in intake_evidence_cards
        ],
        "action_graph": _annotated_graph(
            workflow.action_graph if workflow else ActionGraph(),
            workspace_type,
        ).model_dump(mode="json"),
        "prompt_envelope": envelope.model_dump(),
        "warnings": list(warnings or []) + list(graph_state.get("findings", [])),
        "created_at": now.isoformat(),
        "updated_at": now.isoformat(),
    }
    repository.save(session_id, state)
    return _response(state, workflow)


def get_intake(session_id: str) -> IntakeSessionResponse:
    state = repository.get(session_id)
    workflow = None
    run_id = state.get("workflow_run_id")
    if run_id:
        workflow = get_workflow(run_id)
        evidence_cards = [
            ActionCard(**card) for card in state.get("intake_evidence_cards", [])
        ]
        state["cards"] = [
            card.model_dump(mode="json")
            for card in _merge_intake_evidence(
                workflow.cards,
                evidence_cards,
                state["workspace_type"],
                session_id,
            )
        ]
        state["action_graph"] = _annotated_graph(
            workflow.action_graph,
            state["workspace_type"],
        ).model_dump(mode="json")
        state["updated_at"] = datetime.now(timezone.utc).isoformat()
        repository.save(session_id, state)
    return _response(state, workflow)


def _response(
    state: dict[str, Any],
    workflow: WorkflowRunResponse | None,
) -> IntakeSessionResponse:
    payload = dict(state)
    payload["workflow"] = workflow
    return IntakeSessionResponse(**payload)


def _card_with_workspace(
    card: ActionCard,
    workspace_type: str,
    session_id: str,
) -> ActionCard:
    return card.model_copy(
        update={
            "workspace_type": workspace_type,
            "workspace_id": "team-default" if workspace_type == "team" else "personal",
            "source_session_id": session_id,
        }
    )


def _annotated_graph(graph: ActionGraph, workspace_type: str) -> ActionGraph:
    workspace_id = "team-default" if workspace_type == "team" else "personal"
    actions = [
        action.model_copy(
            update={
                "workspace_type": workspace_type,
                "workspace_id": workspace_id,
            }
        )
        for action in graph.actions
    ]
    return graph.model_copy(
        update={
            "workspace_type": workspace_type,
            "workspace_id": workspace_id,
            "actions": actions,
        }
    )


def _merge_intake_evidence(
    workflow_cards: list[ActionCard],
    intake_cards: list[ActionCard],
    workspace_type: str,
    session_id: str,
) -> list[ActionCard]:
    assignment = _maximum_card_assignment(workflow_cards, intake_cards)
    merged: list[ActionCard] = []
    for index, card in enumerate(workflow_cards):
        evidence = intake_cards[assignment[index]] if index in assignment else None
        merged.append(
            _card_with_workspace(card, workspace_type, session_id).model_copy(
                update={
                    "title": _preferred_title(card, evidence),
                    "assignee_id": card.assignee_id
                    or (evidence.assignee_id if evidence else None),
                    "participant_ids": card.participant_ids
                    or (evidence.participant_ids if evidence else []),
                    "deliverables": card.deliverables
                    or (evidence.deliverables if evidence else []),
                    "dependencies": card.dependencies
                    or (evidence.dependencies if evidence else []),
                    "evidence_summary": list(
                        dict.fromkeys(
                            [
                                *card.evidence_summary,
                                *(evidence.evidence_summary if evidence else []),
                            ]
                        )
                    ),
                }
            )
        )
    return merged


def _preferred_title(card: ActionCard, evidence: ActionCard | None) -> str:
    generic_titles = {
        "相关日程",
        "提交材料",
        "处理截图事项",
        "参加会议",
        "准备会议材料",
    }
    if evidence and card.title in generic_titles and evidence.title not in generic_titles:
        return evidence.title
    return card.title


def _maximum_card_assignment(
    left: list[ActionCard],
    right: list[ActionCard],
) -> dict[int, int]:
    if not left or not right:
        return {}
    scores = [
        [_card_match_score(left_card, right_card) for right_card in right]
        for left_card in left
    ]
    if max(len(left), len(right)) > 10:
        ranked_pairs = sorted(
            (
                (scores[left_index][right_index], left_index, right_index)
                for left_index in range(len(left))
                for right_index in range(len(right))
            ),
            reverse=True,
        )
        assignment: dict[int, int] = {}
        used_right: set[int] = set()
        for score, left_index, right_index in ranked_pairs:
            if score <= 0 or left_index in assignment or right_index in used_right:
                continue
            assignment[left_index] = right_index
            used_right.add(right_index)
        return assignment
    best_score = -1.0
    best: dict[int, int] = {}

    def visit(left_index: int, used: set[int], score: float, current: dict[int, int]):
        nonlocal best_score, best
        if left_index >= len(left):
            if score > best_score:
                best_score, best = score, dict(current)
            return
        visit(left_index + 1, used, score, current)
        for right_index in range(len(right)):
            if right_index in used:
                continue
            match_score = scores[left_index][right_index]
            if match_score <= 0:
                continue
            used.add(right_index)
            current[left_index] = right_index
            visit(left_index + 1, used, score + match_score, current)
            current.pop(left_index, None)
            used.remove(right_index)

    visit(0, set(), 0.0, {})
    return best


def _card_match_score(left: ActionCard, right: ActionCard) -> float:
    score = 0.0
    if left.card_type == right.card_type:
        score += 1.0
    if left.title.strip() == right.title.strip():
        score += 5.0
    score += 4.0 * _bigram_overlap(left.title, right.title)
    score += 2.0 * _bigram_overlap(left.source_text, right.source_text)
    if (left.deadline or left.start_time) == (right.deadline or right.start_time):
        score += 2.0
    return score


def _bigram_overlap(left: str, right: str) -> float:
    def grams(value: str) -> set[str]:
        normalized = "".join(value.lower().split())
        return set(normalized[index : index + 2] for index in range(len(normalized) - 1))

    left_grams, right_grams = grams(left), grams(right)
    if not left_grams or not right_grams:
        return 0.0
    return len(left_grams & right_grams) / min(len(left_grams), len(right_grams))
