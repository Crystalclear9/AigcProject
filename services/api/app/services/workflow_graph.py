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
from app.schemas.agent_workflow import AgentPlan, AgentResult, AgentTask, BudgetUsage
from app.schemas.card import ActionCard
from app.services.autonomous_agents import (
    create_plan,
    create_plan_with_model,
    execute_task,
    verify_results,
)
from app.services.rule_extractor import extract_cards_with_rules, preview_actions_for
from app.services.vivo_ocr import VivoOcrClient, clean_ocr_lines
from app.services.workflow_agents import (
    adjudicate,
    build_action_graph as create_action_graph,
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
    agent_plan: dict[str, Any]
    agent_task: dict[str, Any]
    agent_task_results: Annotated[list[dict[str, Any]], operator.add]
    budget_usage: dict[str, Any]
    verification_summary: dict[str, Any]
    unresolved_evidence: list[str]
    retrieval_sources: list[dict[str, Any]]
    replan_count: int
    workflow_deadline_at: float


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
        asyncio.create_task(
            _persist_late_ocr_candidate(
                state["run_id"],
                task,
                cloud=task is cloud_task,
            )
        )
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


async def _persist_late_ocr_candidate(
    run_id: str,
    task: asyncio.Task,
    *,
    cloud: bool,
) -> None:
    try:
        result = await task
        if cloud:
            text = clean_ocr_lines(result)
            candidate = {"text": text, "engine": "vivo-ocr", "confidence": 0.9}
        else:
            candidate = dict(result)
        if not str(candidate.get("text", "")).strip():
            return
        from app.services.workflow_service import submit_ocr_candidate
        from app.schemas.workflow import OcrCandidateRequest

        submit_ocr_candidate(run_id, OcrCandidateRequest(**candidate))
    except (asyncio.CancelledError, TimeoutError):
        return
    except Exception:
        return


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


async def plan_workflow(state: WorkflowState) -> dict[str, Any]:
    started = time.perf_counter()
    plan = await create_plan_with_model(state)
    agents = [task.tool for task in plan.tasks]
    budget = BudgetUsage(
        task_limit=plan.max_tasks,
        tasks_scheduled=len(plan.tasks),
        replan_limit=plan.max_replans,
        deadline_ms=plan.deadline_ms,
        fast_model_calls=1 if plan.created_by == "fast_model" else 0,
    )
    return {
        "route": "supervisor_agents" if agents else "rules",
        "active_agents": agents,
        "decision_reasons": plan.reasons,
        "agent_plan": plan.model_dump(mode="json"),
        "budget_usage": budget.model_dump(mode="json"),
        "workflow_deadline_at": float(state.get("started_at", time.time())) + plan.deadline_ms / 1000,
        "node_trace": [
            _trace(
                "planner",
                started,
                engine=plan.created_by,
                detail=",".join(agents) if agents else "rules-only",
            )
        ],
    }


def dispatch_ready_tasks(state: WorkflowState) -> list[Send] | str:
    plan = AgentPlan(**state.get("agent_plan", {}))
    results = [AgentResult(**item) for item in state.get("agent_task_results", [])]
    completed = {
        result.task_id
        for result in results
        if result.status in {"completed", "degraded", "failed", "skipped"}
    }
    successful_keys = repository.successful_agent_task_keys(str(state.get("run_id", "")))
    pending = [task for task in plan.tasks if task.id not in completed]
    pending = [task for task in pending if task.idempotency_key not in successful_keys]
    ready = [
        task for task in pending
        if set(task.depends_on).issubset(completed)
    ]
    if not ready:
        if not plan.tasks and state.get("route") == "rules":
            return "finalize_rules_fast"
        return "build_action_graph"
    common = {
        key: state.get(key)
        for key in (
            "run_id",
            "ocr_text",
            "ocr_candidates",
            "rule_cards",
            "cards",
            "screenshot_time",
            "overall_confidence",
            "complexity_reasons",
            "validation_errors",
            "has_fast_model",
            "has_expert_model",
            "agent_task_results",
            "started_at",
            "workflow_deadline_at",
        )
    }
    return [
        Send("run_agent_task", {**common, "agent_task": task.model_dump(mode="json")})
        for task in ready
    ]


def _result_to_expert_output(result: AgentResult) -> dict[str, Any]:
    evidence = []
    for claim in result.claims:
        if claim.claim_type not in {"field", "constraint", "entity", "retrieval"}:
            continue
        evidence.append(
            {
                "id": claim.id,
                "source": result.tool,
                "action_id": claim.action_id,
                "field": claim.field,
                "value": claim.value,
                "text": claim.source_text,
                "start": claim.start,
                "end": claim.end,
                "confidence": claim.confidence,
                "engine": result.tool,
                "correlation_group": claim.correlation_group,
                "derived_from": claim.derived_from,
                "citation_url": claim.citation_url,
                "citation_title": claim.citation_title,
                "reliability": claim.confidence,
            }
        )
    return {
        "agent": result.tool,
        "evidence": evidence,
        "cards": result.cards,
        "findings": result.findings,
        "claims": [claim.model_dump(mode="json") for claim in result.claims],
        "risk_level": result.risk_level,
        "retrieval_sources": [source.model_dump(mode="json") for source in result.retrieval_sources],
    }


async def execute_agent_task(state: WorkflowState) -> dict[str, Any]:
    started = time.perf_counter()
    task = state["agent_task"]
    repository.mark_agent_task_running(str(state["run_id"]), task)
    deadline = float(state.get("workflow_deadline_at", time.time() + 1))
    if time.time() >= deadline:
        result = AgentResult(
            task_id=str(task["id"]),
            tool=task["tool"],
            status="skipped",
            findings=["workflow_budget_exhausted"],
            failure_type="budget",
            idempotency_key=str(task["idempotency_key"]),
            model_tier=task.get("model_tier", "none"),
        )
    else:
        result = await execute_task(
            AgentTask(**task),
            state,
        )
    output = _result_to_expert_output(result)
    return {
        "agent_task_results": [result.model_dump(mode="json")],
        "expert_outputs": [output],
        "warnings": (
            [f"{result.tool} degraded: {result.failure_type}"]
            if result.status == "failed"
            else []
        ),
        "node_trace": [
            _trace(
                result.tool,
                started,
                status="degraded" if result.status in {"failed", "degraded", "skipped"} else "completed",
                engine=result.model_tier if result.model_tier != "none" else result.tool,
                detail=result.status,
            )
        ],
    }


def task_barrier(state: WorkflowState) -> dict[str, Any]:
    results = state.get("agent_task_results", [])
    usage = dict(state.get("budget_usage", {}))
    usage["tasks_completed"] = sum(
        result.get("status") in {"completed", "degraded", "skipped"} for result in results
    )
    usage["tasks_failed"] = sum(result.get("status") == "failed" for result in results)
    usage["fast_model_calls"] = (
        1 if state.get("agent_plan", {}).get("created_by") == "fast_model" else 0
    ) + sum(
        result.get("model_tier") == "fast_model" for result in results
    )
    usage["expert_model_calls"] = sum(
        result.get("model_tier") == "expert_model" for result in results
    )
    usage["web_requests"] = sum(result.get("tool") == "web_retriever" for result in results)
    usage["elapsed_ms"] = round(
        (time.time() - float(state.get("started_at", time.time()))) * 1000,
        2,
    )
    if time.time() >= float(state.get("workflow_deadline_at", time.time() + 1)):
        usage["exhausted"] = True
        usage["exhaustion_reason"] = "deadline"
    return {"budget_usage": usage}


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
        "retrieval_sources": [
            source
            for result in state.get("agent_task_results", [])
            for source in result.get("retrieval_sources", [])
        ],
        "node_trace": [_trace("build_action_graph", started, engine="evidence-graph")],
    }


def finalize_rules_fast(state: WorkflowState) -> dict[str, Any]:
    started = time.perf_counter()
    graph = create_action_graph(
        state.get("rule_cards", []),
        [],
        state.get("ocr_text", ""),
        state.get("ocr_candidates", []),
    )
    cards = [ActionCard(**card) for card in state.get("cards", [])]
    final_ms = round((time.time() - float(state["started_at"])) * 1000, 2)
    return {
        "action_graph": graph.model_dump(mode="json"),
        "preview_actions": preview_actions_for(cards),
        "workflow_status": "awaiting_review",
        "pending_action": "confirm",
        "result_stage": "enhanced",
        "revision": int(state.get("revision", 1)) + 1,
        "time_to_final_ms": final_ms,
        "verification_summary": {
            "passed": True,
            "evidence_coverage": 1.0,
            "constraint_errors": [],
            "unresolved_evidence": [],
            "recommended_tasks": [],
            "requires_review": False,
            "reason": "high-confidence deterministic evidence passed the fast-path gate",
        },
        "node_trace": [_trace("finalize_rules_fast", started, engine="rules-fast-path")],
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
    return "verify"


def verify_workflow(state: WorkflowState) -> dict[str, Any]:
    started = time.perf_counter()
    summary = verify_results(state)
    budget = dict(state.get("budget_usage", {}))
    elapsed = round((time.time() - float(state.get("started_at", time.time()))) * 1000, 2)
    budget["elapsed_ms"] = elapsed
    deadline_hit = time.time() >= float(state.get("workflow_deadline_at", time.time() + 1))
    if deadline_hit:
        budget["exhausted"] = True
        budget["exhaustion_reason"] = "deadline"
    return {
        "verification_summary": summary.model_dump(mode="json"),
        "unresolved_evidence": summary.unresolved_evidence,
        "review_requested": bool(
            state.get("review_requested") or summary.requires_review or budget.get("exhausted")
        ),
        "budget_usage": budget,
        "node_trace": [
            _trace(
                "verify_workflow",
                started,
                status="completed" if summary.passed else "degraded",
                engine="evidence-verifier",
                detail=summary.reason,
            )
        ],
    }


def route_after_verification(state: WorkflowState) -> str:
    summary = state.get("verification_summary", {})
    budget = state.get("budget_usage", {})
    can_replan = (
        not summary.get("passed")
        and bool(summary.get("recommended_tasks"))
        and int(state.get("replan_count", 0)) < settings.workflow_agent_max_replans
        and not budget.get("exhausted")
    )
    if can_replan:
        return "replan"
    return "review" if state.get("review_requested") else "project"


def replan_workflow(state: WorkflowState) -> dict[str, Any]:
    started = time.perf_counter()
    replan_count = int(state.get("replan_count", 0)) + 1
    planning_state = dict(state)
    planning_state["replan_count"] = replan_count
    plan = create_plan(
        planning_state,
        list(state.get("verification_summary", {}).get("recommended_tasks", [])),
    )
    usage = dict(state.get("budget_usage", {}))
    remaining = max(0, settings.workflow_agent_max_tasks - int(usage.get("tasks_scheduled", 0)))
    plan.tasks = plan.tasks[:remaining]
    usage["tasks_scheduled"] = int(usage.get("tasks_scheduled", 0)) + len(plan.tasks)
    usage["replans_used"] = replan_count
    if not plan.tasks:
        usage["exhausted"] = True
        usage["exhaustion_reason"] = "task_limit"
    return {
        "agent_plan": plan.model_dump(mode="json"),
        "active_agents": [task.tool for task in plan.tasks],
        "decision_reasons": list(state.get("decision_reasons", [])) + [
            f"replan {replan_count}: {state.get('verification_summary', {}).get('reason', '')}"
        ],
        "budget_usage": usage,
        "replan_count": replan_count,
        "node_trace": [_trace("replan", started, engine="bounded-task-planner")],
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


def build_workflow_graph(checkpointer=None):
    graph = StateGraph(WorkflowState)
    graph.add_node("prepare_text", prepare_text)
    graph.add_node("recognize_image", recognize_image)
    graph.add_node("create_rule_draft", create_rule_draft)
    graph.add_node("supervisor", plan_workflow)
    graph.add_node("run_agent_task", execute_agent_task)
    graph.add_node("task_barrier", task_barrier)
    graph.add_node("build_action_graph", build_action_graph)
    graph.add_node("finalize_rules_fast", finalize_rules_fast)
    graph.add_node("adjudicate_evidence", adjudicate_evidence)
    graph.add_node("verify_workflow", verify_workflow)
    graph.add_node("replan", replan_workflow)
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
    graph.add_conditional_edges("supervisor", dispatch_ready_tasks)
    graph.add_edge("run_agent_task", "task_barrier")
    graph.add_conditional_edges("task_barrier", dispatch_ready_tasks)
    graph.add_edge("build_action_graph", "adjudicate_evidence")
    graph.add_conditional_edges(
        "adjudicate_evidence",
        route_after_adjudication,
        {
            "verify": "verify_workflow",
        },
    )
    graph.add_conditional_edges(
        "verify_workflow",
        route_after_verification,
        {
            "replan": "replan",
            "review": "require_review",
            "project": "project_cards",
        },
    )
    graph.add_conditional_edges("replan", dispatch_ready_tasks)
    graph.add_edge("finalize_rules_fast", END)
    graph.add_edge("require_review", END)
    graph.add_edge("project_cards", END)
    return graph.compile(checkpointer=checkpointer or MemorySaver())
