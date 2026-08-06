from __future__ import annotations

import asyncio
import operator
import time
import unicodedata
from datetime import datetime, timezone
from typing import Annotated, Any, Literal, TypedDict

from langgraph.checkpoint.memory import MemorySaver
from langgraph.graph import END, START, StateGraph
from langgraph.types import Send, interrupt

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
from app.services.ocr_quality import (
    adjudicate_candidates,
    create_trusted_text_candidate,
    evaluate_candidate,
)
from app.services.workflow_agents import (
    adjudicate,
    build_action_graph as create_action_graph,
)
from app.services.team_workflow import validate_team_tasks
from app.schemas.agent_contracts import AGENT_CONTRACT_VERSION

repository = WorkflowRepository()


def _last_value(_: Any, value: Any) -> Any:
    return value


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
    ocr_quality_report: dict[str, Any]
    ocr_review_reasons: list[str]
    ocr_candidates: list[dict[str, Any]]
    ocr_evidence_status: Literal["trusted", "review_required", "user_verified"]
    evidence_status: Literal["trusted", "review_required", "user_verified"]
    ocr_candidate_versions: list[dict[str, Any]]
    ocr_conflicts: list[str]
    evidence_spans: list[dict[str, Any]]
    summary_status: Literal["blocked", "provisional", "grounded", "degraded"]
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
    workspace_type: Literal["personal", "team"]
    prompt_envelope: dict[str, Any]
    team_tasks: list[dict[str, Any]]
    team_workflow_review: dict[str, Any]
    evidence_envelopes: list[dict[str, Any]]
    field_evidence: list[dict[str, Any]]
    workflow_phase: Annotated[str, _last_value]
    degraded_reasons: Annotated[list[str], operator.add]


def _trace(node: str, started: float, status: str = "completed", **extra: Any) -> dict[str, Any]:
    return {
        "node": node,
        "status": status,
        "duration_ms": round((time.perf_counter() - started) * 1000, 2),
        **extra,
    }


def _card_dicts(cards: list[ActionCard]) -> list[dict[str, Any]]:
    return [card.model_dump(mode="json") for card in cards]


def _candidate_spans(candidate: dict[str, Any]) -> list[dict[str, Any]]:
    """Expose normalized OCR blocks as stable, auditable evidence spans."""
    spans = []
    for index, block in enumerate(candidate.get("blocks", [])):
        text = str(block.get("text", "")).strip()
        if not text:
            continue
        spans.append({
            "id": f"ocr:{candidate.get('engine', 'ocr')}:{index}",
            "text": text,
            "start": block.get("start"),
            "end": block.get("end"),
            "bounds": {key: block.get(key) for key in ("left", "top", "right", "bottom")},
            "engine": candidate.get("engine", "ocr"),
        })
    if not spans and str(candidate.get("text", "")).strip():
        spans.append({"id": f"ocr:{candidate.get('engine', 'ocr')}:text", "text": str(candidate["text"])})
    return spans


def _field_evidence(cards: list[ActionCard], spans: list[dict[str, Any]], version: int = 1) -> list[dict[str, Any]]:
    """Bind generated fields to source spans; unsupported values stay reviewable."""
    def canonical(value: Any) -> str:
        return "".join(unicodedata.normalize("NFKC", str(value)).split())

    evidence: list[dict[str, Any]] = []
    supported_fields = {
        "title", "summary", "deadline", "start_time", "end_time", "location",
        "assignee_id", "materials", "submit_method",
    }
    for card in cards:
        payload = card.model_dump(mode="json")
        card_source = canonical(card.source_text)
        card_refs = [
            str(span["id"])
            for span in spans
            if card_source and (
                card_source in canonical(span.get("text", ""))
                or canonical(span.get("text", "")) in card_source
            )
        ]
        for field, value in payload.items():
            if field not in supported_fields or value in (None, "", []):
                continue
            rendered = " ".join(str(item) for item in value) if isinstance(value, list) else str(value)
            refs = [
                str(span["id"])
                for span in spans
                if rendered and canonical(rendered) in canonical(span.get("text", ""))
            ]
            if not refs:
                refs = card_refs
            evidence.append({
                "field": f"{card.id}.{field}",
                "value": value if refs else None,
                "evidence_refs": refs,
                "confidence": 1.0 if refs else 0.0,
                "source_version": version,
                "locked": field in set(),
                "needs_confirmation": not bool(refs),
            })
    return evidence


async def prepare_text(state: WorkflowState) -> dict[str, Any]:
    started = time.perf_counter()
    text = state.get("input_text", "").strip()
    engine = (
        "user-corrected"
        if state.get("ocr_engine") == "user-corrected"
        else "provided-text"
    )
    candidate = create_trusted_text_candidate(text, engine=engine)
    spans = _candidate_spans(candidate)
    return {
        "workflow_phase": "evidence_collecting",
        "ocr_text": text,
        "ocr_engine": engine,
        "ocr_quality": 1.0,
        "ocr_quality_report": candidate["quality_report"],
        "ocr_review_reasons": [],
        "ocr_candidates": [candidate],
        "ocr_evidence_status": "user_verified",
        "ocr_candidate_versions": [candidate],
        "ocr_conflicts": [],
        "evidence_spans": spans,
        "evidence_envelopes": [{
            "source_id": f"{state['run_id']}:text:1", "source_type": "user_edit" if engine == "user-corrected" else "text",
            "version": 1, "raw_text": text, "blocks": candidate.get("blocks", []), "spans": spans,
            "quality_report": candidate["quality_report"], "trust_status": "user_verified",
            "conflicts": [], "created_at": datetime.now(timezone.utc).isoformat(),
        }],
        "summary_status": "provisional",
        "workflow_status": "running",
        "node_trace": [_trace("prepare_text", started, engine=engine)],
    }


async def _wait_for_client_ocr(run_id: str, timeout: float) -> dict[str, Any]:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            candidates = repository.get_state(run_id).get("ocr_candidates", [])
        except KeyError:
            candidates = []
        if candidates:
            return adjudicate_candidates(candidates).selected
        await asyncio.sleep(0.08)
    raise TimeoutError("client OCR candidate timeout")


async def recognize_image(state: WorkflowState) -> dict[str, Any]:
    started = time.perf_counter()
    ocr_deadline = time.monotonic() + settings.vivo_ocr_timeout_seconds
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
    # A failed cloud request must not win the race against a client OCR candidate
    # that is still crossing the start-image/candidate API boundary.
    if done and pending:
        has_usable_result = False
        for task in done:
            if task.cancelled() or task.exception() is not None:
                continue
            result = task.result()
            if task is cloud_task:
                has_usable_result = bool(clean_ocr_lines(result).strip())
            else:
                has_usable_result = bool(str(result.get("text", "")).strip())
            if has_usable_result:
                break
        evidence_window = 0.15 if has_usable_result else max(0, ocr_deadline - time.monotonic())
        more_done, pending = await asyncio.wait(pending, timeout=evidence_window)
        done |= more_done
    for task in done:
        try:
            result = task.result()
            if task is cloud_task:
                text = clean_ocr_lines(result)
                if text:
                    candidates.append(
                        {
                            "text": text,
                            "engine": "vivo-ocr",
                            "confidence": 0.5,
                            "variant": "cloud",
                            "blocks": [
                                {
                                    "text": line.text,
                                    "left": line.left,
                                    "top": line.top,
                                    "right": line.right,
                                    "bottom": line.bottom,
                                    "line_index": index,
                                }
                                for index, line in enumerate(result)
                                if line.text.strip()
                            ],
                        }
                    )
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
        reason = "no usable OCR candidate; provide or correct the recognized text"
        return {
            "ocr_text": "",
            "ocr_engine": "unavailable",
            "ocr_quality": 0.0,
            "ocr_quality_report": {
                "quality_score": 0.0,
                "garbled_ratio": 0.0,
                "completeness_score": 0.0,
                "layout_score": 0.0,
                "evidence_score": 0.0,
                "agreement_score": 0.0,
                "duplicate_ratio": 0.0,
                "noise_ratio": 0.0,
                "block_count": 0,
                "time_expressions": [],
                "reasons": [reason],
            },
            "ocr_review_reasons": [reason],
            "ocr_candidates": [],
            "ocr_evidence_status": "review_required",
            "ocr_candidate_versions": [],
            "ocr_conflicts": [],
            "evidence_spans": [],
            "summary_status": "blocked",
            "review_requested": True,
            "pending_action": "resolve_ocr",
            "warnings": [*warnings, reason],
            "degraded_reasons": [*warnings, reason],
            "node_trace": [
                _trace(
                    "recognize_image",
                    started,
                    status="degraded",
                    engine="unavailable",
                    detail="0 OCR candidates; review required",
                )
            ],
        }
    adjudication = adjudicate_candidates(_dedupe_ocr(usable))
    best = adjudication.selected
    return {
        "ocr_text": adjudication.merged_text,
        "ocr_engine": str(best.get("engine", "ocr")),
        "ocr_quality": float(best.get("quality_score", 0)),
        "ocr_quality_report": dict(best.get("quality_report", {})),
        "ocr_review_reasons": adjudication.review_reasons,
        "ocr_candidates": adjudication.candidates,
        "ocr_evidence_status": "review_required" if adjudication.requires_review else "trusted",
        "ocr_candidate_versions": adjudication.candidates,
        "ocr_conflicts": adjudication.critical_conflicts,
        "evidence_spans": _candidate_spans(best),
        "summary_status": "blocked" if adjudication.requires_review else "provisional",
        "review_requested": adjudication.requires_review,
        "pending_action": "resolve_ocr" if adjudication.requires_review else None,
        "warnings": warnings,
        "degraded_reasons": warnings,
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
            candidate = {
                "text": text,
                "engine": "vivo-ocr",
                "confidence": 0.5,
                "variant": "cloud",
                "blocks": [
                    {
                        "text": line.text,
                        "left": line.left,
                        "top": line.top,
                        "right": line.right,
                        "bottom": line.bottom,
                        "line_index": index,
                    }
                    for index, line in enumerate(result)
                    if line.text.strip()
                ],
            }
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
        if current is None or float(candidate.get("quality_score", 0)) > float(
            current.get("quality_score", 0)
        ):
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
    # Late callbacks and retries must not bypass the OCR evidence gate.
    if state.get("ocr_evidence_status") == "review_required":
        return {
            "cards": [],
            "rule_cards": [],
            "summary_status": "blocked",
            "workflow_status": "awaiting_ocr_review",
            "pending_action": "resolve_ocr",
            "review_requested": True,
            "node_trace": [{"node": "create_rule_draft", "status": "interrupted", "duration_ms": 0, "detail": "ocr_evidence_blocked"}],
        }
    started = time.perf_counter()
    cards = extract_cards_with_rules(state["ocr_text"], state.get("screenshot_time"))
    score, reasons = _rule_confidence(cards, state["ocr_text"], float(state.get("ocr_quality", 0.8)))
    card_dicts = _card_dicts(cards)
    source_spans = list(state.get("evidence_spans", []))
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
    previous_elapsed = state.get("time_to_first_draft_ms")
    if previous_elapsed is None:
        elapsed = round((time.time() - float(state["started_at"])) * 1000, 2)
    else:
        elapsed = round(float(previous_elapsed), 2)
    elapsed = max(1.0, elapsed)
    return {
        "workflow_phase": "draft_generating",
        "rule_cards": card_dicts,
        "cards": card_dicts,
        "overall_confidence": round(score, 3),
        "confidence": confidence,
        "provenance": provenance,
        "field_versions": field_versions,
        "field_evidence": _field_evidence(cards, source_spans, len(state.get("ocr_candidate_versions", [])) or 1),
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
        "workflow_phase": "workflow_planning",
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
            "agent_plan",
            "prompt_envelope",
            "workspace_type",
            "user_locked",
            "evidence_spans",
            "budget_usage",
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
    semantic_cards = []
    if result.output_type == "semantic_decomposition":
        semantic_cards = [
            {
                "action_id": action.get("action_id"),
                "id": action.get("action_id"),
                "card_type": action.get("card_type", "task"),
                "title": action.get("title", ""),
                "summary": action.get("summary", ""),
                "source_text": action.get("evidence", {}).get("source_text", ""),
            }
            for action in result.validated_output.get("actions", [])
        ]
    return {
        "agent": result.tool,
        "evidence": evidence,
        "cards": semantic_cards,
        "findings": result.findings,
        "claims": [claim.model_dump(mode="json") for claim in result.claims],
        "risk_level": result.risk_level,
        "retrieval_sources": [source.model_dump(mode="json") for source in result.retrieval_sources],
        "contract_version": result.contract_version,
        "output_type": result.output_type,
        "validated_output": result.validated_output,
        "contract_errors": result.contract_errors,
        "evidence_coverage": result.evidence_coverage,
        "dependency_failures": result.dependency_failures,
        "decision_summary": result.decision_summary,
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
        "workflow_phase": "agents_running",
        "agent_task_results": [result.model_dump(mode="json")],
        "expert_outputs": [output],
        "warnings": (
            [f"{result.tool} degraded: {result.failure_type}"]
            if result.status == "failed"
            else []
        ),
        "degraded_reasons": [f"{result.tool}:{finding}" for finding in result.findings]
        if result.status in {"failed", "degraded", "skipped"} else [],
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
        "workflow_phase": "review_center",
        "phase_transitions": ["draft_ready", "review_center"],
        "draft_status": "ready",
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
        "workflow_phase": "evidence_adjudication",
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
    team_review = state.get("team_workflow_review", {})
    team_tasks = state.get("team_tasks", [])
    if state.get("workspace_type") == "team" and not team_tasks:
        team_tasks = [
            {
                "task_id": str(card.get("id")),
                "title": str(card.get("title", "")),
                "owner_id": card.get("assignee_id"),
                "participant_ids": card.get("participant_ids", []),
                "deliverables": card.get("materials", []),
                "evidence_refs": card.get("evidence_summary", []),
                "status": "ready" if card.get("assignee_id") else "unassigned",
                "acceptance_criteria": [],
            }
            for card in state.get("cards", [])
        ]
    if state.get("workspace_type") == "team":
        team_review = validate_team_tasks(team_tasks).model_dump(mode="json")
    return {
        "workflow_phase": "evidence_verification",
        "agent_contract_version": AGENT_CONTRACT_VERSION,
        "agent_outputs": [
            {
                "task_id": result.get("task_id"),
                "tool": result.get("tool"),
                "status": result.get("status"),
                "contract_version": result.get("contract_version"),
                "output_type": result.get("output_type"),
                "validated_output": result.get("validated_output", {}),
                "contract_errors": result.get("contract_errors", []),
                "evidence_coverage": result.get("evidence_coverage", 0),
                "dependency_failures": result.get("dependency_failures", []),
                "decision_summary": result.get("decision_summary", ""),
            }
            for result in state.get("agent_task_results", [])
        ],
        "verification_summary": summary.model_dump(mode="json"),
        "team_tasks": team_tasks,
        "team_workflow_review": team_review,
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
    if state.get("team_workflow_review", {}).get("required"):
        return "team_review"
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
    retained_ids = {task.id for task in plan.tasks}
    for task in plan.tasks:
        task.depends_on = [dependency for dependency in task.depends_on if dependency in retained_ids]
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
        "workflow_phase": "review_center",
        "phase_transitions": ["draft_ready", "review_center"],
        "draft_status": "ready",
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
        "summary_status": "grounded" if state.get("ocr_evidence_status") in {"trusted", "user_verified"} else "degraded",
        "revision": int(state.get("revision", 1)) + 1,
        "time_to_final_ms": final_ms,
        "node_trace": [_trace("project_cards", started, engine="card-projection")],
    }


def require_review(state: WorkflowState) -> dict[str, Any]:
    return {
        "workflow_phase": "review_center",
        "workflow_status": "awaiting_review",
        "pending_action": "review_cards",
        "result_stage": "enhanced",
        "revision": int(state.get("revision", 1)) + 1,
    }


def require_team_review(state: WorkflowState) -> dict[str, Any]:
    return {
        "workflow_phase": "review_center",
        "workflow_status": "awaiting_review",
        "pending_action": "review_team_plan",
        "summary_status": "grounded" if state.get("ocr_evidence_status") in {"trusted", "user_verified"} else "degraded",
        "team_workflow_review": state.get("team_workflow_review", {}),
        "revision": int(state.get("revision", 0)) + 1,
    }


def require_ocr_review(state: WorkflowState) -> dict[str, Any]:
    return {
        "workflow_phase": "review_required",
        "workflow_status": "awaiting_ocr_review",
        "pending_action": "resolve_ocr",
        "result_stage": "provisional",
        "summary_status": "blocked",
        "revision": int(state.get("revision", 0)) + 1,
        "warnings": [
            f"OCR review required: {reason}"
            for reason in state.get("ocr_review_reasons", [])
        ],
    }


def await_ocr_review(state: WorkflowState) -> dict[str, Any]:
    payload = interrupt({
        "kind": "ocr_review",
        "run_id": state["run_id"],
        "revision": int(state.get("revision", 0)),
        "reasons": list(state.get("ocr_review_reasons", [])),
        "conflicts": list(state.get("ocr_conflicts", [])),
    })
    if not isinstance(payload, dict) or payload.get("command") != "provide_ocr_text":
        raise ValueError("OCR review requires provide_ocr_text")
    corrected = str(payload.get("ocr_text", "")).strip()
    if not corrected:
        raise ValueError("ocr_text is required for OCR review")
    candidate = create_trusted_text_candidate(corrected, engine="user-corrected")
    spans = _candidate_spans(candidate)
    version = len(state.get("ocr_candidate_versions", [])) + 1
    return {
        "input_kind": "text",
        "input_text": corrected,
        "ocr_text": corrected,
        "ocr_engine": "user-corrected",
        "ocr_quality": 1.0,
        "ocr_quality_report": candidate["quality_report"],
        "ocr_review_reasons": [],
        "ocr_candidates": [candidate, *state.get("ocr_candidates", [])],
        "ocr_candidate_versions": [*state.get("ocr_candidate_versions", []), candidate],
        "ocr_conflicts": [],
        "ocr_evidence_status": "user_verified",
        "evidence_status": "user_verified",
        "evidence_spans": spans,
        "evidence_envelopes": [*state.get("evidence_envelopes", []), {
            "source_id": f"{state['run_id']}:user-edit:{version}",
            "source_type": "user_edit",
            "version": version,
            "raw_text": corrected,
            "blocks": candidate.get("blocks", []),
            "spans": spans,
            "quality_report": candidate["quality_report"],
            "trust_status": "user_verified",
            "conflicts": [],
            "created_at": datetime.now(timezone.utc).isoformat(),
        }],
        "summary_status": "provisional",
        "review_items": [],
        "blocked_reasons": [],
        "workflow_phase": "evidence_adjudication",
        "workflow_status": "awaiting_review",
        "pending_action": "confirm",
        "review_requested": False,
        "revision": int(state.get("revision", 0)) + 1,
    }


def await_card_review(state: WorkflowState) -> dict[str, Any]:
    payload = interrupt({
        "kind": "card_review",
        "run_id": state["run_id"],
        "revision": int(state.get("revision", 0)),
    })
    if not isinstance(payload, dict) or payload.get("command") != "review_cards":
        raise ValueError("card review requires review_cards")
    cards = [ActionCard.model_validate(item) for item in payload.get("cards", [])]
    locked = {
        str(card.id): [
            field for field, value in card.model_dump(mode="json").items()
            if field != "id" and value not in (None, "", [])
        ]
        for card in cards
    }
    return {
        "cards": _card_dicts(cards),
        "field_evidence": _field_evidence(cards, list(state.get("evidence_spans", []))),
        "user_locked": locked,
        "user_reviewed": True,
        "workflow_phase": "review_center",
        "workflow_status": "running",
        "pending_action": None,
        "revision": int(state.get("revision", 0)) + 1,
    }


def await_team_review(state: WorkflowState) -> dict[str, Any]:
    payload = interrupt({
        "kind": "team_review",
        "run_id": state["run_id"],
        "revision": int(state.get("revision", 0)),
    })
    if not isinstance(payload, dict) or payload.get("command") != "review_team_plan":
        raise ValueError("team review requires review_team_plan")
    tasks = list(payload.get("team_tasks", []))
    review = validate_team_tasks(tasks)
    if review.required:
        raise ValueError(f"team plan validation failed: {review.reasons or review.conflicts}")
    return {
        "team_tasks": [task.model_dump(mode="json") for task in review.tasks],
        "team_workflow_review": review.model_dump(mode="json"),
        "user_reviewed": True,
        "workflow_phase": "review_center",
        "workflow_status": "awaiting_review",
        "pending_action": "confirm",
        "revision": int(state.get("revision", 0)) + 1,
    }


def route_after_ocr(state: WorkflowState) -> str:
    return "ocr_review" if state.get("review_requested") else "draft"


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
    graph.add_node("require_team_review", require_team_review)
    graph.add_node("require_ocr_review", require_ocr_review)
    graph.add_node("await_card_review", await_card_review)
    graph.add_node("await_team_review", await_team_review)
    graph.add_node("await_ocr_review", await_ocr_review)

    graph.add_conditional_edges(
        START,
        lambda state: "recognize_image" if state.get("input_kind") == "image" else "prepare_text",
        {"prepare_text": "prepare_text", "recognize_image": "recognize_image"},
    )
    graph.add_edge("prepare_text", "create_rule_draft")
    graph.add_conditional_edges(
        "recognize_image",
        route_after_ocr,
        {
            "ocr_review": "require_ocr_review",
            "draft": "create_rule_draft",
        },
    )
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
            "team_review": "require_team_review",
            "project": "project_cards",
        },
    )
    graph.add_conditional_edges("replan", dispatch_ready_tasks)
    graph.add_edge("finalize_rules_fast", END)
    graph.add_edge("require_review", "await_card_review")
    graph.add_edge("require_team_review", "await_team_review")
    graph.add_edge("require_ocr_review", "await_ocr_review")
    graph.add_edge("await_card_review", END)
    graph.add_edge("await_team_review", END)
    graph.add_edge("await_ocr_review", "create_rule_draft")
    graph.add_edge("project_cards", END)
    return graph.compile(checkpointer=checkpointer or MemorySaver())
