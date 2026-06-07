from __future__ import annotations

import hashlib
import json
import re
import uuid
from datetime import datetime, timezone
from difflib import SequenceMatcher
from typing import Any

from app.schemas.action_graph import (
    ActionConflict,
    ActionConstraint,
    ActionDependency,
    ActionGraph,
    ActionNode,
    ActionSuggestion,
    EntityNode,
    EvidenceItem,
)
from app.schemas.card import ActionCard
from app.services.llm_client import extract_cards_with_model

AGENT_NAMES = {
    "semantic_agent",
    "temporal_agent",
    "entity_agent",
    "risk_agent",
    "duplicate_agent",
    "quality_agent",
}
CARD_FIELDS = {
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
}
CRITICAL_FIELDS = {"title", "deadline", "start_time", "end_time", "location"}


def stable_id(prefix: str, *parts: object) -> str:
    raw = "\0".join(str(part) for part in parts)
    return f"{prefix}-{hashlib.sha1(raw.encode('utf-8')).hexdigest()[:16]}"


def text_span(text: str, value: Any) -> tuple[int | None, int | None]:
    if value in (None, "", []):
        return None, None
    needle = str(value[0] if isinstance(value, list) and value else value)
    index = text.find(needle)
    return (index, index + len(needle)) if index >= 0 else (None, None)


def card_evidence(
    cards: list[dict[str, Any]],
    source: str,
    text: str,
    confidence: float,
    engine: str,
) -> list[dict[str, Any]]:
    evidence: list[dict[str, Any]] = []
    for card in cards:
        action_id = str(card.get("action_id") or stable_id("action", card.get("id"), card.get("title")))
        for field in CARD_FIELDS:
            value = card.get(field)
            if value in (None, "", []):
                continue
            start, end = text_span(text, value)
            evidence.append(
                EvidenceItem(
                    id=stable_id("evidence", source, action_id, field, value),
                    source=source,
                    action_id=action_id,
                    field=field,
                    value=value,
                    text=text[start:end] if start is not None else "",
                    start=start,
                    end=end,
                    confidence=confidence,
                    engine=engine,
                ).model_dump(mode="json")
            )
    return evidence


def plan_agents(state: dict[str, Any]) -> tuple[list[str], list[str]]:
    reasons = set(state.get("complexity_reasons", []))
    cards = state.get("rule_cards", [])
    if float(state.get("overall_confidence", 0)) >= 0.85 and not reasons:
        return [], ["high-confidence deterministic extraction"]
    agents: list[str] = []
    decision_reasons: list[str] = []

    if "multiple_cards" in reasons or "long_text" in reasons:
        agents.append("semantic_agent")
        decision_reasons.append("multi-item or long input requires semantic decomposition")
    if any(card.get("deadline") or card.get("start_time") or card.get("need_confirm") for card in cards):
        agents.append("temporal_agent")
        decision_reasons.append("time constraints require independent verification")
    if any(card.get("location") or card.get("materials") or card.get("submit_method") for card in cards):
        agents.append("entity_agent")
        decision_reasons.append("entities and resources were detected")
    if "promise" in reasons or any(token in state.get("ocr_text", "") for token in ("密码", "身份证", "银行卡", "隐私")):
        agents.append("risk_agent")
        decision_reasons.append("commitment or sensitive content requires policy review")
    if len(cards) > 1:
        agents.append("duplicate_agent")
        decision_reasons.append("multiple actions require identity and dependency matching")
    if float(state.get("overall_confidence", 0)) < 0.85 or reasons:
        agents.append("quality_agent")
        decision_reasons.append("quality gate requested independent evidence review")

    # A simple, complete extraction does not pay the expert latency tax.
    return list(dict.fromkeys(agents))[:6], decision_reasons


async def run_agent(agent: str, state: dict[str, Any]) -> dict[str, Any]:
    if agent not in AGENT_NAMES:
        raise ValueError(f"unsupported agent: {agent}")
    text = state.get("ocr_text", "")
    cards = [dict(card) for card in state.get("rule_cards", [])]
    output: dict[str, Any] = {
        "agent": agent,
        "evidence": [],
        "cards": [],
        "findings": [],
        "risk_level": "low",
    }

    if agent == "semantic_agent":
        role = "fast_model" if state.get("has_fast_model") else "expert_model"
        configured = state.get("has_fast_model") or state.get("has_expert_model")
        if configured:
            try:
                model_cards = await extract_cards_with_model(
                    text,
                    role,
                    state.get("screenshot_time"),
                    state.get("validation_errors"),
                )
                output["cards"] = [card.model_dump(mode="json") for card in model_cards]
                output["evidence"] = card_evidence(output["cards"], role, text, 0.82, role)
                return output
            except Exception as error:
                output["findings"].append(f"semantic model degraded: {type(error).__name__}")
        output["evidence"] = card_evidence(cards, "semantic_agent", text, 0.64, "deterministic-semantic")
    elif agent == "temporal_agent":
        temporal = {"deadline", "start_time", "end_time"}
        output["evidence"] = [
            item for item in card_evidence(cards, "temporal_agent", text, 0.84, "temporal-rules")
            if item.get("field") in temporal
        ]
        if any(field in (card.get("need_confirm") or []) for card in cards for field in ("时间", "time")):
            output["findings"].append("ambiguous_time")
    elif agent == "entity_agent":
        entity_fields = {"location", "materials", "submit_method"}
        output["evidence"] = [
            item for item in card_evidence(cards, "entity_agent", text, 0.78, "entity-rules")
            if item.get("field") in entity_fields
        ]
    elif agent == "risk_agent":
        sensitive = [token for token in ("密码", "身份证", "银行卡", "手机号") if token in text]
        promises = [card for card in cards if card.get("card_type") == "promise"]
        if sensitive:
            output["risk_level"] = "high"
            output["findings"].append("sensitive_content:" + ",".join(sensitive))
        elif promises:
            output["risk_level"] = "medium"
            output["findings"].append("user_commitment")
    elif agent == "duplicate_agent":
        for left_index, left in enumerate(cards):
            for right in cards[left_index + 1:]:
                ratio = SequenceMatcher(
                    None,
                    normalize_title(str(left.get("title", ""))),
                    normalize_title(str(right.get("title", ""))),
                ).ratio()
                if ratio >= 0.82:
                    output["findings"].append(f"possible_duplicate:{left.get('id')}:{right.get('id')}")
    elif agent == "quality_agent":
        for card in cards:
            missing = []
            if not card.get("title"):
                missing.append("title")
            if card.get("card_type") == "promise" and not (card.get("deadline") or card.get("start_time")):
                missing.append("execution_time")
            if missing:
                output["findings"].append(f"missing:{card.get('id')}:{','.join(missing)}")
        output["evidence"] = card_evidence(cards, "quality_agent", text, 0.7, "schema-quality")
    return output


def normalize_title(title: str) -> str:
    return re.sub(r"[^\w\u4e00-\u9fff]+", "", title.lower())


def card_similarity(left: dict[str, Any], right: dict[str, Any]) -> float:
    title = SequenceMatcher(
        None,
        normalize_title(str(left.get("title", ""))),
        normalize_title(str(right.get("title", ""))),
    ).ratio()
    same_type = 1.0 if left.get("card_type") == right.get("card_type") else 0.0
    left_time = left.get("start_time") or left.get("deadline")
    right_time = right.get("start_time") or right.get("deadline")
    time_score = 1.0 if left_time and right_time and str(left_time)[:16] == str(right_time)[:16] else 0.0
    source_overlap = SequenceMatcher(
        None,
        str(left.get("source_text", ""))[:160],
        str(right.get("source_text", ""))[:160],
    ).ratio()
    return title * 0.45 + same_type * 0.2 + time_score * 0.15 + source_overlap * 0.2


def align_cards(rule_cards: list[dict[str, Any]], agent_outputs: list[dict[str, Any]]) -> list[list[tuple[str, dict[str, Any]]]]:
    groups: list[list[tuple[str, dict[str, Any]]]] = [[("rules", dict(card))] for card in rule_cards]
    for output in agent_outputs:
        source = str(output.get("agent", "agent"))
        candidates = [dict(candidate) for candidate in output.get("cards", [])]
        if not candidates:
            continue
        if not groups:
            groups.extend([[(source, candidate)] for candidate in candidates])
            continue
        scores = [
            [
                max(card_similarity(candidate, existing) for _, existing in group)
                for group in groups
            ]
            for candidate in candidates
        ]
        assignments: list[tuple[int, int]] = []
        try:
            from scipy.optimize import linear_sum_assignment

            rows, columns = linear_sum_assignment(
                [[1.0 - score for score in row] for row in scores]
            )
            assignments = list(zip(rows.tolist(), columns.tolist()))
        except (ImportError, ValueError):
            remaining = set(range(len(groups)))
            for row_index, row in enumerate(scores):
                if not remaining:
                    break
                column = max(remaining, key=lambda index: row[index])
                assignments.append((row_index, column))
                remaining.remove(column)
        matched: set[int] = set()
        for row, column in assignments:
            if scores[row][column] >= 0.58:
                groups[column].append((source, candidates[row]))
                matched.add(row)
        groups.extend(
            [(source, candidate)]
            for index, candidate in enumerate(candidates)
            if index not in matched
        )
    return groups


def build_action_graph(
    rule_cards: list[dict[str, Any]],
    agent_outputs: list[dict[str, Any]],
    ocr_text: str,
    ocr_candidates: list[dict[str, Any]],
) -> ActionGraph:
    all_evidence = card_evidence(rule_cards, "rules", ocr_text, 0.72, "rules")
    for output in agent_outputs:
        all_evidence.extend(output.get("evidence", []))
    for candidate in ocr_candidates:
        all_evidence.append(
            EvidenceItem(
                id=stable_id("evidence", "ocr", candidate.get("engine"), candidate.get("text")),
                source="ocr",
                text=str(candidate.get("text", "")),
                value=candidate.get("text", ""),
                confidence=float(candidate.get("confidence", 0.5)),
                engine=str(candidate.get("engine", "ocr")),
            ).model_dump(mode="json")
        )

    actions: list[ActionNode] = []
    constraints: list[ActionConstraint] = []
    entities: list[EntityNode] = []
    groups = align_cards(rule_cards, agent_outputs)
    action_aliases: dict[str, str] = {}
    for group in groups:
        _, primary = group[0]
        card_id = str(primary.get("id") or uuid.uuid4())
        action_id = str(primary.get("action_id") or stable_id("action", card_id, primary.get("title")))
        primary["action_id"] = action_id
        for _, candidate in group:
            candidate_action_id = str(
                candidate.get("action_id")
                or stable_id("action", candidate.get("id"), candidate.get("title"))
            )
            action_aliases[candidate_action_id] = action_id
        field_evidence: dict[str, list[str]] = {}
        for item in all_evidence:
            if item.get("action_id") == action_id or item.get("value") in primary.values():
                field = item.get("field")
                if field:
                    field_evidence.setdefault(str(field), []).append(str(item["id"]))
        actions.append(
            ActionNode(
                id=action_id,
                card_id=card_id,
                card_type=str(primary.get("card_type", "task")),
                title=str(primary.get("title", "")),
                field_values={field: primary.get(field) for field in CARD_FIELDS if field in primary},
                field_evidence=field_evidence,
            )
        )
        for field in ("deadline", "start_time", "end_time"):
            if primary.get(field):
                constraints.append(
                    ActionConstraint(
                        id=stable_id("constraint", action_id, field),
                        action_id=action_id,
                        constraint_type=field,
                        value=primary[field],
                        evidence_ids=field_evidence.get(field, []),
                    )
                )
        for entity_type, field in (("location", "location"), ("material", "materials")):
            values = primary.get(field) or []
            if not isinstance(values, list):
                values = [values]
            for value in values:
                entities.append(
                    EntityNode(
                        id=stable_id("entity", entity_type, value),
                        entity_type=entity_type,
                        name=str(value),
                        evidence_ids=field_evidence.get(field, []),
                    )
                )

    dependencies: list[ActionDependency] = []
    for task in actions:
        for event in actions:
            if task.id == event.id:
                continue
            if task.card_type == "task" and event.card_type == "event":
                same_source = bool(
                    set(task.field_evidence.get("summary", []))
                    & set(event.field_evidence.get("summary", []))
                )
                prepare_words = ("准备", "材料", "汇报", "报名")
                if same_source or any(word in task.title for word in prepare_words):
                    dependencies.append(
                        ActionDependency(
                            id=stable_id("dependency", task.id, event.id, "prerequisite"),
                            source_action_id=task.id,
                            target_action_id=event.id,
                            dependency_type="prerequisite",
                            confidence=0.76,
                        )
                    )
    card_to_action = {action.card_id: action.id for action in actions}
    for output in agent_outputs:
        for claim in output.get("claims", []):
            if claim.get("claim_type") != "dependency":
                continue
            value = claim.get("value") or {}
            source_action = card_to_action.get(str(value.get("source_card_id")))
            target_action = card_to_action.get(str(value.get("target_card_id")))
            dependency_type = value.get("dependency_type", "same_matter")
            if source_action and target_action and dependency_type in {
                "prerequisite",
                "subtask",
                "same_matter",
                "time_conflict",
                "resource_dependency",
            }:
                dependencies.append(
                    ActionDependency(
                        id=stable_id("dependency", source_action, target_action, dependency_type),
                        source_action_id=source_action,
                        target_action_id=target_action,
                        dependency_type=dependency_type,
                        confidence=float(claim.get("confidence", 0.5)),
                        evidence_ids=[str(claim.get("id"))],
                    )
                )
    for item in all_evidence:
        if item.get("action_id") in action_aliases:
            item["action_id"] = action_aliases[str(item["action_id"])]
    dependencies = list({dependency.id: dependency for dependency in dependencies}.values())
    cycle_nodes = _dependency_cycle_nodes(dependencies)
    conflicts: list[ActionConflict] = []
    if cycle_nodes:
        conflicts.append(
            ActionConflict(
                id=stable_id("conflict", "dependency_cycle", *sorted(cycle_nodes)),
                kind="time",
                severity="high",
                candidate_values=sorted(cycle_nodes),
            )
        )
    return ActionGraph(
        actions=actions,
        entities=list({entity.id: entity for entity in entities}.values()),
        constraints=constraints,
        dependencies=dependencies,
        evidence=[EvidenceItem(**item) for item in {item["id"]: item for item in all_evidence}.values()],
        conflicts=conflicts,
    )


def _dependency_cycle_nodes(dependencies: list[ActionDependency]) -> set[str]:
    adjacency: dict[str, list[str]] = {}
    for dependency in dependencies:
        if dependency.dependency_type not in {"prerequisite", "subtask"}:
            continue
        adjacency.setdefault(dependency.source_action_id, []).append(dependency.target_action_id)
    visiting: set[str] = set()
    visited: set[str] = set()
    cycle: set[str] = set()

    def visit(node: str) -> None:
        if node in visiting:
            cycle.add(node)
            return
        if node in visited:
            return
        visiting.add(node)
        for target in adjacency.get(node, []):
            visit(target)
            if target in cycle:
                cycle.add(node)
        visiting.remove(node)
        visited.add(node)

    for node in adjacency:
        visit(node)
    return cycle


def adjudicate(
    graph: ActionGraph,
    cards: list[dict[str, Any]],
    agent_outputs: list[dict[str, Any]],
    field_versions: dict[str, dict[str, int]],
    user_locked: dict[str, list[str]],
) -> tuple[list[dict[str, Any]], ActionGraph, dict[str, dict[str, float]], dict[str, dict[str, str]], list[str], str]:
    evidence_by_action: dict[str, list[EvidenceItem]] = {}
    for evidence in graph.evidence:
        if evidence.action_id:
            evidence_by_action.setdefault(evidence.action_id, []).append(evidence)
    confidence: dict[str, dict[str, float]] = {}
    provenance: dict[str, dict[str, str]] = {}
    errors: list[str] = []
    risk_order = {"low": 0, "medium": 1, "high": 2}
    risk_level = max(
        (str(output.get("risk_level", "low")) for output in agent_outputs),
        key=lambda value: risk_order.get(value, 0),
        default="low",
    )

    action_by_card = {action.card_id: action for action in graph.actions}
    existing_card_ids = {str(card.get("id")) for card in cards}
    for action in graph.actions:
        if action.card_id in existing_card_ids:
            continue
        payload = dict(action.field_values)
        payload.update(
            {
                "id": action.card_id,
                "action_id": action.id,
                "card_type": action.card_type,
                "title": action.title,
                "created_at": datetime.now(timezone.utc).isoformat(),
                "source_text": "",
                "status": "draft",
            }
        )
        cards.append(ActionCard(**payload).model_dump(mode="json"))
        existing_card_ids.add(action.card_id)
    for card in cards:
        card_id = str(card.get("id"))
        action = action_by_card.get(card_id)
        if action:
            card["action_id"] = action.id
            card["dependencies"] = [
                dep.source_action_id
                for dep in graph.dependencies
                if dep.target_action_id == action.id
            ]
            card["evidence_summary"] = [
                f"{item.source}:{item.field}"
                for item in evidence_by_action.get(action.id, [])[:6]
            ]
        confidence[card_id] = {}
        provenance[card_id] = {}
        locked = set(user_locked.get(card_id, []))
        for field in CARD_FIELDS:
            if field not in card:
                continue
            field_evidence = [
                item for item in evidence_by_action.get(str(card.get("action_id")), [])
                if item.field == field and item.value not in (None, "", [])
            ]
            candidates: dict[str, dict[str, Any]] = {}
            for item in field_evidence:
                key = json.dumps(item.value, ensure_ascii=False, sort_keys=True, default=str)
                candidate = candidates.setdefault(
                    key,
                    {
                        "value": item.value,
                        "score": 0.0,
                        "sources": set(),
                        "groups": {},
                        "evidence_ids": [],
                    },
                )
                group = item.correlation_group or f"{item.source}:{item.engine}"
                weighted = item.confidence * item.reliability
                candidate["groups"][group] = max(candidate["groups"].get(group, 0), weighted)
                candidate["score"] = sum(candidate["groups"].values())
                candidate["sources"].add(item.source)
                candidate["evidence_ids"].append(item.id)
            ranked = sorted(
                candidates.values(),
                key=lambda item: (item["score"] + 0.08 * len(item["sources"])),
                reverse=True,
            )
            if ranked and field not in locked:
                selected = ranked[0]["value"]
                if field in {"materials", "tags", "reminders", "need_confirm"} and not isinstance(selected, list):
                    selected = [selected]
                card[field] = selected
            relevant = [
                item for item in field_evidence
                if item.value == card.get(field)
            ]
            score = min(
                0.99,
                max((item.confidence for item in relevant), default=0.55)
                + (0.04 if len({item.source for item in relevant}) > 1 else 0),
            )
            if field in locked:
                score = 1.0
                provenance[card_id][field] = "user"
            else:
                provenance[card_id][field] = relevant[0].source if relevant else "rules"
            confidence[card_id][field] = round(score, 3)
            field_versions.setdefault(card_id, {}).setdefault(field, 1)
            if field in CRITICAL_FIELDS and len(ranked) > 1:
                first_score = ranked[0]["score"] + 0.08 * len(ranked[0]["sources"])
                second_score = ranked[1]["score"] + 0.08 * len(ranked[1]["sources"])
                if second_score >= first_score * 0.88 and ranked[0]["value"] != ranked[1]["value"]:
                    graph.conflicts.append(
                        ActionConflict(
                            id=stable_id("conflict", card_id, field, ranked[0]["value"], ranked[1]["value"]),
                            action_id=str(card.get("action_id")),
                            field=field,
                            kind="value",
                            severity="high" if field in {"deadline", "start_time", "title"} else "medium",
                            candidate_values=[ranked[0]["value"], ranked[1]["value"]],
                            evidence_ids=ranked[0]["evidence_ids"] + ranked[1]["evidence_ids"],
                        )
                    )
        critical = [
            confidence[card_id].get(field, 0)
            for field in CRITICAL_FIELDS
            if card.get(field) not in (None, "", [])
        ]
        if not card.get("title"):
            errors.append(f"{card_id}: missing title")
        if card.get("card_type") == "promise" and not (card.get("deadline") or card.get("start_time")):
            errors.append(f"{card_id}: promise requires execution time")
        if critical and min(critical) < 0.6:
            errors.append(f"{card_id}: critical field confidence below threshold")

    for output in agent_outputs:
        for finding in output.get("findings", []):
            if finding.startswith("possible_duplicate:"):
                _, left, right = finding.split(":", 2)
                graph.conflicts.append(
                    ActionConflict(
                        id=stable_id("conflict", finding),
                        kind="identity",
                        severity="medium",
                        candidate_values=[left, right],
                    )
                )
            if finding.startswith("missing:"):
                errors.append(finding)
    return cards, graph, confidence, provenance, sorted(set(errors)), risk_level
