from __future__ import annotations

import asyncio
import operator
import time
from typing import Annotated, Any, Literal, TypedDict

from langgraph.checkpoint.memory import MemorySaver
from langgraph.graph import END, START, StateGraph
from langgraph.types import Send

from app.core.config import settings
from app.repositories.workflows import WorkflowRepository
from app.schemas.action_graph import ActionGraph
from app.schemas.card import ActionCard
from app.services.rule_extractor import extract_cards_with_rules, preview_actions_for
from app.services.vivo_ocr import VivoOcrClient, clean_ocr_lines
from app.services.workflow_agents import (
    adjudicate,
    build_action_graph as create_action_graph,
    card_evidence,
    plan_agents,
    run_agent,
)

repository = WorkflowRepository()


class WorkflowState(TypedDict, total=False):
    run_id: str
    input_kind: Literal["text", "image"]
    input_text: str
    image_bytes: bytes
    image_path: str
    screenshot_time: str | None
    started_at: float
    ocr_text: str
    ocr_engine: str
    ocr_quality: float
    ocr_candidates: list[dict[str, Any]]
    rule_cards: list[dict[str, Any]]
    cards: list[dict[str, Any]]
    action_graph: dict[str, Any]
    expert_name: str
    expert_outputs: Annotated[list[dict[str, Any]], operator.add]
    active_agents: list[str]
    decision_reasons: list[str]
    confidence: dict[str, dict[str, float]]
    provenance: dict[str, dict[str, str]]
    suggestions: dict[str, dict[str, Any]]
    field_versions: dict[str, dict[str, int]]
    field_conflicts: list[dict[str, Any]]
    overall_confidence: float
    route: str
    complexity_reasons: list[str]
    validation_errors: list[str]
    warnings: Annotated[list[str], operator.add]
    node_trace: Annotated[list[dict[str, Any]], operator.add]
    engine: str
    workflow_status: str
    pending_action: str | None
    revision: int
    result_stage: str
    cache_status: str
    time_to_first_draft_ms: float | None
    time_to_final_ms: float | None
    user_locked: dict[str, list[str]]
    review_requested: bool
    risk_level: str
    expert_round: int
    has_fast_model: bool
    has_expert_model: bool


def _trace(node: str, started: float, status: str = "completed", **extra: Any) -> dict[str, Any]:
    return {
        "node": node,
        "status": status,
        "duration_ms": round((time.perf_counter() - started) * 1000, 2),
        **extra,
    }


def _card_dicts(cards: list[ActionCard]) -> list[dict[str, Any]]:
    return [card.model_dump(mode="json") for card in cards]


async def prepare_text(state: WorkflowState) -> dict[str, Any]:
    started = time.perf_counter()
    text = state.get("input_text", "").strip()
    return {
        "ocr_text": text,
        "ocr_engine": "provided-text",
        "ocr_quality": 1.0,
        "ocr_candidates": [{"text": text, "engine": "provided-text", "confidence": 1.0}],
        "workflow_status": "running",
        "node_trace": [_trace("prepare_text", started, engine="provided-text")],
    }


async def _wait_for_client_ocr(run_id: str, timeout: float) -> dict[str, Any]:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            candidates = repository.get_state(run_id).get("ocr_candidates", [])
        except KeyError:
            candidates = []
        if candidates:
            return max(candidates, key=lambda item: float(item.get("confidence", 0)))
        await asyncio.sleep(0.08)
    raise TimeoutError("client OCR candidate timeout")


async def recognize_image(state: WorkflowState) -> dict[str, Any]:
    started = time.perf_counter()
    image_bytes = state.get("image_bytes", b"")
    if not image_bytes and state.get("image_path"):
        image_bytes = await asyncio.to_thread(_read_bytes, state["image_path"])
    cloud_task = asyncio.create_task(VivoOcrClient().recognize(image_bytes))
    client_task = asyncio.create_task(
        _wait_for_client_ocr(state["run_id"], settings.vivo_ocr_timeout_seconds)
    )
    candidates: list[dict[str, Any]] = list(state.get("ocr_candidates", []))
    warnings: list[str] = []

    done, pending = await asyncio.wait(
        {cloud_task, client_task},
        return_when=asyncio.FIRST_COMPLETED,
        timeout=settings.vivo_ocr_timeout_seconds,
    )
    # Give the second OCR path a short evidence window without delaying the first draft
    # by a full provider timeout.
    if done and pending:
        more_done, pending = await asyncio.wait(pending, timeout=0.15)
        done |= more_done
    for task in done:
        try:
            result = task.result()
            if task is cloud_task:
                text = clean_ocr_lines(result)
                if text:
                    candidates.append({"text": text, "engine": "vivo-ocr", "confidence": 0.9})
            else:
                candidates.append(dict(result))
        except Exception as error:
            warnings.append(f"OCR source degraded: {type(error).__name__}")
    for task in pending:
        task.cancel()
    usable = [candidate for candidate in candidates if str(candidate.get("text", "")).strip()]
    if not usable:
        raise RuntimeError("OCR unavailable")
    best = max(usable, key=lambda item: float(item.get("confidence", 0)))
    return {
        "ocr_text": str(best["text"]).strip(),
        "ocr_engine": str(best.get("engine", "ocr")),
        "ocr_quality": float(best.get("confidence", 0.5)),
        "ocr_candidates": _dedupe_ocr(usable),
        "warnings": warnings,
        "node_trace": [
            _trace(
                "recognize_image",
                started,
                status="degraded" if warnings else "completed",
                engine=str(best.get("engine", "ocr")),
                detail=f"{len(usable)} OCR candidate(s)",
            )
        ],
    }


def _read_bytes(path: str) -> bytes:
    with open(path, "rb") as handle:
        return handle.read()


def _dedupe_ocr(candidates: list[dict[str, Any]]) -> list[dict[str, Any]]:
    deduped: dict[tuple[str, str], dict[str, Any]] = {}
    for candidate in candidates:
        key = (str(candidate.get("engine", "ocr")), str(candidate.get("text", "")).strip())
        current = deduped.get(key)
        if current is None or float(candidate.get("confidence", 0)) > float(current.get("confidence", 0)):
            deduped[key] = candidate
    return list(deduped.values())


def _rule_confidence(cards: list[ActionCard], text: str, ocr_quality: float) -> tuple[float, list[str]]:
    if not cards:
        return 0.0, ["no_cards"]
    reasons: list[str] = []
    critical_scores: list[float] = []
    for card in cards:
        title_score = 1.0 if card.title.strip() else 0.0
        time_score = 1.0
        if card.card_type in {"task", "promise"}:
            time_score = 1.0 if card.deadline else (0.45 if card.card_type == "task" else 0.0)
        elif card.card_type == "event":
            time_score = 1.0 if card.start_time else 0.25
        if card.need_confirm:
            reasons.append("uncertain_fields")
            time_score = min(time_score, 0.58)
        if card.card_type == "promise":
            reasons.append("promise")
        critical_scores.append(min(title_score, time_score, ocr_quality))
    if len(cards) > 1:
        reasons.append("multiple_cards")
    if len(text) > 260:
        reasons.append("long_text")
    score = min(critical_scores) if critical_scores else 0.0
    return max(0.0, min(0.99, score)), sorted(set(reasons))


async def create_rule_draft(state: WorkflowState) -> dict[str, Any]:
    started = time.perf_counter()
    cards = await asyncio.to_thread(
        extract_cards_with_rules,
        state["ocr_text"],
        state.get("screenshot_time"),
    )
    score, reasons = _rule_confidence(cards, state["ocr_text"], float(state.get("ocr_quality", 0.8)))
    card_dicts = _card_dicts(cards)
    confidence = {}
    provenance = {}
    field_versions = {}
    for card in cards:
        payload = card.model_dump(mode="json")
        confidence[card.id] = {}
        provenance[card.id] = {}
        field_versions[card.id] = {}
        for field, value in payload.items():
            if field in {"id", "created_at", "source_text"}:
                continue
            confidence[card.id][field] = round(score if value not in (None, "", []) else 0.3, 3)
            provenance[card.id][field] = "rules"
            field_versions[card.id][field] = 1
    elapsed = round((time.time() - float(state["started_at"])) * 1000, 2)
    return {
        "rule_cards": card_dicts,
        "cards": card_dicts,
        "overall_confidence": round(score, 3),
        "confidence": confidence,
        "provenance": provenance,
        "field_versions": field_versions,
        "complexity_reasons": reasons,
        "revision": 1,
        "result_stage": "provisional",
        "time_to_first_draft_ms": elapsed,
        "preview_actions": preview_actions_for(cards),
        "engine": (
            "rules"
            if state.get("ocr_engine") == "provided-text"
            else f"{state.get('ocr_engine', 'text')}+rules"
        ),
        "workflow_status": "running",
        "node_trace": [_trace("create_rule_draft", started, engine="rules")],
    }


def choose_route(state: WorkflowState) -> dict[str, Any]:
    started = time.perf_counter()
    agents, reasons = plan_agents(state)
    return {
        "route": "supervisor_agents" if agents else "rules",
        "active_agents": agents,
        "decision_reasons": reasons or ["high-confidence deterministic extraction"],
        "node_trace": [
            _trace(
                "supervisor",
                started,
                engine="deterministic-supervisor",
                detail=",".join(agents) if agents else "rules-only",
            )
        ],
    }


def dispatch_experts(state: WorkflowState) -> list[Send] | str:
    agents = state.get("active_agents", [])
    if not agents:
        return "build_action_graph"
    common = {
        key: state.get(key)
        for key in (
            "run_id",
            "ocr_text",
            "ocr_candidates",
            "rule_cards",
            "screenshot_time",
            "overall_confidence",
            "complexity_reasons",
            "validation_errors",
            "has_fast_model",
            "has_expert_model",
        )
    }
    return [Send("run_expert", {**common, "expert_name": agent}) for agent in agents]


async def execute_expert(state: WorkflowState) -> dict[str, Any]:
    started = time.perf_counter()
    agent = state["expert_name"]
    try:
        output = await run_agent(agent, state)
        status = "completed"
        warnings: list[str] = []
    except Exception as error:
        output = {"agent": agent, "evidence": [], "cards": [], "findings": [type(error).__name__]}
        status = "degraded"
        warnings = [f"{agent} degraded; existing evidence retained"]
    return {
        "expert_outputs": [output],
        "warnings": warnings,
        "node_trace": [_trace(agent, started, status=status, engine=agent)],
    }


def build_action_graph(state: WorkflowState) -> dict[str, Any]:
    started = time.perf_counter()
    graph = create_action_graph(
        state.get("rule_cards", []),
        state.get("expert_outputs", []),
        state.get("ocr_text", ""),
        state.get("ocr_candidates", []),
    )
    return {
        "action_graph": graph.model_dump(mode="json"),
        "node_trace": [_trace("build_action_graph", started, engine="evidence-graph")],
    }


def adjudicate_evidence(state: WorkflowState) -> dict[str, Any]:
    started = time.perf_counter()
    graph = ActionGraph(**state.get("action_graph", {}))
    cards, graph, confidence, provenance, errors, risk = adjudicate(
        graph,
        [dict(card) for card in state.get("cards", [])],
        state.get("expert_outputs", []),
        {card_id: dict(versions) for card_id, versions in state.get("field_versions", {}).items()},
        state.get("user_locked", {}),
    )
    critical_scores = [
        score
        for card_id, fields in confidence.items()
        for field, score in fields.items()
        if field in {"title", "deadline", "start_time", "end_time", "location"}
        and next((card.get(field) for card in cards if str(card.get("id")) == card_id), None)
        not in (None, "", [])
    ]
    overall = min(critical_scores) if critical_scores else float(state.get("overall_confidence", 0))
    unresolved_high = any(not item.resolved and item.severity == "high" for item in graph.conflicts)
    needs_more = bool(errors) and int(state.get("expert_round", 0)) < 1 and "quality_agent" not in state.get("active_agents", [])
    return {
        "cards": cards,
        "action_graph": graph.model_dump(mode="json"),
        "confidence": confidence,
        "provenance": provenance,
        "validation_errors": errors,
        "overall_confidence": round(overall, 3),
        "risk_level": risk,
        "review_requested": bool(errors or unresolved_high or risk == "high"),
        "needs_additional_review": needs_more,
        "node_trace": [
            _trace(
                "adjudicate_evidence",
                started,
                engine="constraint-adjudicator",
                detail=f"risk={risk}; errors={len(errors)}",
            )
        ],
    }


def route_after_adjudication(state: WorkflowState) -> str:
    if state.get("needs_additional_review"):
        return "additional_review"
    return "review" if state.get("review_requested") else "project"


async def additional_review(state: WorkflowState) -> dict[str, Any]:
    started = time.perf_counter()
    output = await run_agent("quality_agent", state)
    return {
        "expert_outputs": [output],
        "expert_round": int(state.get("expert_round", 0)) + 1,
        "node_trace": [_trace("additional_review", started, engine="quality_agent")],
    }


def project_cards(state: WorkflowState) -> dict[str, Any]:
    started = time.perf_counter()
    cards = [ActionCard(**card) for card in state.get("cards", [])]
    final_ms = round((time.time() - float(state["started_at"])) * 1000, 2)
    return {
        "cards": _card_dicts(cards),
        "preview_actions": preview_actions_for(cards),
        "engine": (
            "rules"
            if state.get("route") == "rules" and state.get("ocr_engine") == "provided-text"
            else (
                f"{state.get('ocr_engine', 'text')}+rules"
                if state.get("route") == "rules"
                else f"{state.get('ocr_engine', 'text')}+supervisor-agents"
            )
        ),
        "workflow_status": "awaiting_review",
        "pending_action": "confirm",
        "result_stage": "enhanced",
        "revision": int(state.get("revision", 1)) + 1,
        "time_to_final_ms": final_ms,
        "node_trace": [_trace("project_cards", started, engine="card-projection")],
    }


def require_review(state: WorkflowState) -> dict[str, Any]:
    return {
        "workflow_status": "awaiting_review",
        "pending_action": "review_cards",
        "result_stage": "enhanced",
        "revision": int(state.get("revision", 1)) + 1,
    }


# Compatibility helpers retained for callers/tests from the adaptive workflow.
async def fast_and_expert(state: WorkflowState) -> dict[str, Any]:
    fast, expert = await asyncio.gather(
        run_agent("semantic_agent", {**state, "has_fast_model": True, "has_expert_model": False}),
        run_agent("quality_agent", state),
    )
    return {"expert_outputs": [fast, expert], "warnings": [], "node_trace": []}


def build_workflow_graph(checkpointer=None):
    graph = StateGraph(WorkflowState)
    graph.add_node("prepare_text", prepare_text)
    graph.add_node("recognize_image", recognize_image)
    graph.add_node("create_rule_draft", create_rule_draft)
    graph.add_node("supervisor", choose_route)
    graph.add_node("run_expert", execute_expert)
    graph.add_node("build_action_graph", build_action_graph)
    graph.add_node("adjudicate_evidence", adjudicate_evidence)
    graph.add_node("additional_review", additional_review)
    graph.add_node("project_cards", project_cards)
    graph.add_node("require_review", require_review)

    graph.add_conditional_edges(
        START,
        lambda state: "recognize_image" if state.get("input_kind") == "image" else "prepare_text",
        {"prepare_text": "prepare_text", "recognize_image": "recognize_image"},
    )
    graph.add_edge("prepare_text", "create_rule_draft")
    graph.add_edge("recognize_image", "create_rule_draft")
    graph.add_edge("create_rule_draft", "supervisor")
    graph.add_conditional_edges("supervisor", dispatch_experts)
    graph.add_edge("run_expert", "build_action_graph")
    graph.add_edge("build_action_graph", "adjudicate_evidence")
    graph.add_conditional_edges(
        "adjudicate_evidence",
        route_after_adjudication,
        {
            "additional_review": "additional_review",
            "review": "require_review",
            "project": "project_cards",
        },
    )
    graph.add_edge("additional_review", "build_action_graph")
    graph.add_edge("require_review", END)
    graph.add_edge("project_cards", END)
    return graph.compile(checkpointer=checkpointer or MemorySaver())


workflow_graph = build_workflow_graph()
