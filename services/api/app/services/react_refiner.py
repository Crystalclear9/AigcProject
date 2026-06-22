from __future__ import annotations

import time
import uuid
from difflib import SequenceMatcher
from typing import Any, Literal

from app.schemas.agent_workflow import ReActSession, ReActStep, ReActToolName
from app.schemas.card import ActionCard
from app.services.llm_client import extract_cards_with_model
from app.services.rule_extractor import extract_cards_with_rules, preview_actions_for
from app.services.workflow_agents import build_action_graph as create_action_graph

EDITABLE_FIELDS = (
    "card_type",
    "title",
    "summary",
    "deadline",
    "start_time",
    "end_time",
    "location",
    "materials",
    "submit_method",
    "priority",
    "tags",
    "reminders",
    "need_confirm",
)
CRITICAL_FIELDS = ("title", "deadline", "start_time", "location", "submit_method", "materials")
GENERIC_TITLES = ("相关日程", "待办事项", "相关事项", "日程提醒", "行动事项")


async def refine_state_with_react(
    state: dict[str, Any],
    *,
    instruction: str,
    selected_card_ids: list[str],
) -> dict[str, Any]:
    started = time.perf_counter()
    text = str(state.get("ocr_text") or state.get("input_text") or "").strip()
    screenshot_time = state.get("screenshot_time")
    locked = {str(key): list(value) for key, value in state.get("user_locked", {}).items()}
    suggestions = {
        str(card_id): dict(fields)
        for card_id, fields in state.get("suggestions", {}).items()
    }
    steps: list[ReActStep] = []
    actions_taken: list[ReActToolName] = []
    observations: list[str] = []
    incoming_cards: list[ActionCard] = []
    failure_type: str | None = None

    def step(
        turn: int,
        tool: ReActToolName,
        *,
        reason: str,
        action: str,
        observation: str,
        step_started: float,
        step_suggestions: list[str] | None = None,
    ) -> None:
        actions_taken.append(tool)
        observations.append(observation)
        steps.append(
            ReActStep(
                turn=turn,
                tool=tool,
                reason_summary=reason,
                action=action,
                observation=observation,
                suggestions=step_suggestions or [],
                duration_ms=round((time.perf_counter() - step_started) * 1000, 2),
            )
        )

    observe_started = time.perf_counter()
    current_cards = [_card_from_dict(card) for card in state.get("cards", [])]
    current_ids = {card.id for card in current_cards}
    selected_ids = set(selected_card_ids) if selected_card_ids else current_ids
    selected_count = len(selected_card_ids) if selected_card_ids else len(current_cards)
    step(
        1,
        "observe",
        reason="Review current draft, user instruction, and OCR evidence without saving anything.",
        action="observe_current_draft",
        observation=f"{len(current_cards)} current card(s), {selected_count} selected for refinement.",
        step_started=observe_started,
    )

    local_started = time.perf_counter()
    local_cards = extract_cards_with_rules(text, screenshot_time)
    incoming_cards.extend(local_cards)
    step(
        2,
        "local_rule_refiner",
        reason="Use deterministic extraction to find missed actions and concrete fields first.",
        action="extract_cards_with_rules",
        observation=f"Local rules proposed {len(local_cards)} card(s).",
        step_started=local_started,
        step_suggestions=_quality_suggestions(local_cards),
    )

    model_role: Literal["fast_model", "expert_model"] | None = None
    if state.get("has_fast_model"):
        model_role = "fast_model"
    elif state.get("has_expert_model"):
        model_role = "expert_model"
    if model_role:
        model_started = time.perf_counter()
        try:
            model_cards = await extract_cards_with_model(
                _model_input(text, instruction, current_cards),
                model_role,
                screenshot_time,
                validation_errors=[
                    *[str(item) for item in state.get("validation_errors", [])],
                    f"user_refinement_instruction:{instruction or '继续检查并补全'}",
                ],
            )
            incoming_cards.extend(model_cards)
            step(
                3,
                "model_enhancer",
                reason="Ask the configured vivo-compatible model to repair generic or incomplete draft fields.",
                action=model_role,
                observation=f"{model_role} proposed {len(model_cards)} card(s).",
                step_started=model_started,
                step_suggestions=_quality_suggestions(model_cards),
            )
        except Exception as error:
            failure_type = type(error).__name__
            step(
                3,
                "model_enhancer",
                reason="Model enhancement was attempted but degraded; keep local candidates.",
                action=model_role,
                observation=f"Model enhancement degraded: {failure_type}.",
                step_started=model_started,
                step_suggestions=["云端模型暂不可用，已保留本地规则草稿"],
            )

    merged_cards = _merge_cards(current_cards, incoming_cards, locked, suggestions, selected_ids)
    graph = create_action_graph(
        [card.model_dump(mode="json") for card in merged_cards],
        [],
        text,
        state.get("ocr_candidates", []),
    )
    react_suggestions = _dedupe(
        [
            suggestion
            for step_item in steps
            for suggestion in step_item.suggestions
        ]
        + _quality_suggestions(merged_cards)
        + _instruction_suggestions(instruction, merged_cards)
    )[:8]
    session = ReActSession(
        id=f"react-{uuid.uuid4().hex[:12]}",
        instruction=instruction.strip(),
        status="degraded" if failure_type else "completed",
        rounds_used=len(steps),
        actions_taken=actions_taken,
        observations=observations,
        suggestions=react_suggestions,
        steps=steps,
        failure_type=failure_type,
    )
    confidence, provenance, versions = _merge_field_metadata(
        state,
        merged_cards,
        model_used=any(step.tool == "model_enhancer" and "proposed" in step.observation for step in steps),
    )
    final_ms = round((time.time() - float(state.get("started_at", time.time()))) * 1000, 2)
    return {
        "cards": [card.model_dump(mode="json") for card in merged_cards],
        "preview_actions": preview_actions_for(merged_cards),
        "action_graph": graph.model_dump(mode="json"),
        "confidence": confidence,
        "provenance": provenance,
        "field_versions": versions,
        "suggestions": suggestions,
        "react_session": session.model_dump(mode="json"),
        "react_suggestions": react_suggestions,
        "workflow_status": "awaiting_review",
        "pending_action": "review_cards",
        "result_stage": "enhanced",
        "route": "supervisor_agents",
        "engine": _react_engine(state, session),
        "active_agents": _dedupe([*state.get("active_agents", []), "react_refiner"]),
        "decision_reasons": _dedupe(
            [
                *state.get("decision_reasons", []),
                "react_refinement_requested_by_user",
                *(react_suggestions[:3]),
            ]
        ),
        "warnings": _dedupe(
            [
                *state.get("warnings", []),
                *([] if not failure_type else [f"ReAct model enhancement degraded: {failure_type}"]),
            ]
        ),
        "time_to_final_ms": final_ms,
        "node_trace": [
            *state.get("node_trace", []),
            {
                "node": "react_refiner",
                "status": "degraded" if failure_type else "completed",
                "duration_ms": round((time.perf_counter() - started) * 1000, 2),
                "engine": _react_engine(state, session),
                "detail": f"{len(steps)} bounded ReAct step(s)",
            },
        ],
    }


def _model_input(text: str, instruction: str, current_cards: list[ActionCard]) -> str:
    current = [
        {
            "title": card.title,
            "deadline": card.deadline,
            "start_time": card.start_time,
            "location": card.location,
            "materials": card.materials,
            "submit_method": card.submit_method,
        }
        for card in current_cards
    ]
    return (
        f"OCR_TEXT:\n{text}\n\n"
        f"CURRENT_DRAFT:\n{current}\n\n"
        f"USER_REFINEMENT_INSTRUCTION:\n{instruction or '继续检查遗漏事项，补全具体字段，避免泛化标题。'}"
    )


def _card_from_dict(raw: dict[str, Any]) -> ActionCard:
    return ActionCard(**raw)


def _merge_cards(
    current_cards: list[ActionCard],
    incoming_cards: list[ActionCard],
    locked: dict[str, list[str]],
    suggestions: dict[str, dict[str, Any]],
    selected_ids: set[str],
) -> list[ActionCard]:
    merged = list(current_cards)
    for incoming in incoming_cards:
        match_index = _best_match_index(merged, incoming)
        if match_index < 0:
            merged.append(incoming)
            continue
        existing = merged[match_index]
        if existing.id not in selected_ids:
            _record_unselected_suggestions(existing, incoming, suggestions)
            continue
        merged[match_index] = _fill_without_overwrite(existing, incoming, locked, suggestions)
    return _dedupe_cards(merged)


def _record_unselected_suggestions(
    existing: ActionCard,
    incoming: ActionCard,
    suggestions: dict[str, dict[str, Any]],
) -> None:
    payload = existing.model_dump(mode="json")
    incoming_payload = incoming.model_dump(mode="json")
    for field in EDITABLE_FIELDS:
        current_value = payload.get(field)
        incoming_value = incoming_payload.get(field)
        if incoming_value in (None, "", []) or incoming_value == current_value:
            continue
        if _is_empty_or_generic(field, current_value) or field in {"evidence_summary", "materials", "tags", "reminders", "need_confirm"}:
            suggestions.setdefault(existing.id, {})[field] = incoming_value


def _fill_without_overwrite(
    existing: ActionCard,
    incoming: ActionCard,
    locked: dict[str, list[str]],
    suggestions: dict[str, dict[str, Any]],
) -> ActionCard:
    payload = existing.model_dump(mode="json")
    incoming_payload = incoming.model_dump(mode="json")
    card_locked = set(locked.get(existing.id, []))
    for field in EDITABLE_FIELDS:
        current_value = payload.get(field)
        incoming_value = incoming_payload.get(field)
        if incoming_value in (None, "", []):
            continue
        if field in card_locked:
            if incoming_value != current_value:
                suggestions.setdefault(existing.id, {})[field] = incoming_value
            continue
        if _is_empty_or_generic(field, current_value):
            payload[field] = incoming_value
        elif field in {"evidence_summary", "materials", "tags", "reminders", "need_confirm"}:
            payload[field] = _dedupe([*(current_value or []), *(incoming_value or [])])[:8]
    return ActionCard(**payload)


def _best_match_index(cards: list[ActionCard], incoming: ActionCard) -> int:
    best_index = -1
    best_score = 0.0
    for index, card in enumerate(cards):
        score = _match_score(card, incoming)
        if score > best_score:
            best_index = index
            best_score = score
    return best_index if best_score >= 0.58 else -1


def _match_score(left: ActionCard, right: ActionCard) -> float:
    if left.card_type != right.card_type:
        return 0.0
    score = 0.0
    left_time = left.deadline or left.start_time
    right_time = right.deadline or right.start_time
    if left_time and right_time and left_time[:16] == right_time[:16]:
        score += 0.45
    if set(left.materials).intersection(right.materials):
        score += 0.2
    title_similarity = SequenceMatcher(None, _normalize(left.title), _normalize(right.title)).ratio()
    score += title_similarity * 0.45
    if title_similarity >= 0.92:
        score += 0.2
    return min(1.0, score)


def _dedupe_cards(cards: list[ActionCard]) -> list[ActionCard]:
    result: list[ActionCard] = []
    for card in cards:
        if _best_match_index(result, card) < 0:
            result.append(card)
    return result


def _is_empty_or_generic(field: str, value: Any) -> bool:
    if value in (None, "", []):
        return True
    if field == "title":
        return any(str(value).strip() == title for title in GENERIC_TITLES)
    return False


def _quality_suggestions(cards: list[ActionCard]) -> list[str]:
    suggestions: list[str] = []
    for card in cards:
        if any(card.title.strip() == title for title in GENERIC_TITLES):
            suggestions.append("标题仍然偏泛化，需要改成具体动作")
        if card.card_type in {"task", "promise"} and not card.deadline:
            suggestions.append(f"{card.title} 缺少截止或执行时间")
        if not card.submit_method and any(word in card.source_text for word in ("提交", "报名", "上传")):
            suggestions.append(f"{card.title} 需要确认提交方式或平台")
        if card.materials:
            suggestions.append(f"{card.title} 已提取材料：{', '.join(card.materials[:3])}")
    return _dedupe(suggestions)


def _instruction_suggestions(instruction: str, cards: list[ActionCard]) -> list[str]:
    if not instruction.strip():
        return []
    if "拆" in instruction and len(cards) <= 1:
        return ["未发现足够证据拆成多张卡，建议用户补充上下文"]
    if "截止" in instruction and not any(card.deadline for card in cards):
        return ["没有稳定截止时间，需用户确认后再创建提醒"]
    return ["已按用户要求重新检查候选行动卡"]


def _merge_field_metadata(
    state: dict[str, Any],
    cards: list[ActionCard],
    *,
    model_used: bool,
) -> tuple[dict[str, dict[str, float]], dict[str, dict[str, str]], dict[str, dict[str, int]]]:
    confidence = {
        str(card_id): {str(field): float(score) for field, score in fields.items()}
        for card_id, fields in state.get("confidence", {}).items()
    }
    provenance = {
        str(card_id): {str(field): str(source) for field, source in fields.items()}
        for card_id, fields in state.get("provenance", {}).items()
    }
    versions = {
        str(card_id): {str(field): int(version) for field, version in fields.items()}
        for card_id, fields in state.get("field_versions", {}).items()
    }
    source = "react:model" if model_used else "react:rules"
    score = 0.82 if model_used else 0.72
    for card in cards:
        payload = card.model_dump(mode="json")
        confidence.setdefault(card.id, {})
        provenance.setdefault(card.id, {})
        versions.setdefault(card.id, {})
        for field in EDITABLE_FIELDS:
            value = payload.get(field)
            if value in (None, "", []):
                continue
            confidence[card.id].setdefault(field, score if field in CRITICAL_FIELDS else max(0.68, score - 0.08))
            provenance[card.id].setdefault(field, source)
            versions[card.id].setdefault(field, 1)
    return confidence, provenance, versions


def _react_engine(state: dict[str, Any], session: ReActSession) -> str:
    base = str(state.get("engine") or "rules")
    if "model_enhancer" in session.actions_taken and session.status == "completed":
        return f"{base}+react+model"
    return f"{base}+react"


def _normalize(value: str) -> str:
    return "".join(ch for ch in value.lower() if ch.isalnum() or "\u4e00" <= ch <= "\u9fff")


def _dedupe(values: list[Any]) -> list[Any]:
    result: list[Any] = []
    for value in values:
        if value not in result:
            result.append(value)
    return result
