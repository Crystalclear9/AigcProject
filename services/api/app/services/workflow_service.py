from __future__ import annotations

import asyncio
import hashlib
import logging
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from langgraph.types import Command

from app.core.config import settings
from app.repositories.workflows import WorkflowRepository
from app.schemas.workflow import (
    ConfirmEffectsRequest,
    ConfirmWorkflowRequest,
    DraftPatchRequest,
    OcrCandidateRequest,
    WorkflowReactRequest,
    WorkflowResumeRequest,
    WorkflowRunResponse,
)
from app.schemas.card import ActionCard
from app.schemas.card import ActionCardCreate
from app.repositories.cards import CardRepository
from app.services.provider_runtime import provider_usage_delta, runtime
from app.services.react_refiner import refine_state_with_react
from app.services.workflow_graph import build_workflow_graph, create_rule_draft, finalize_rules_fast
from app.services.workflow_agents import build_action_graph as create_action_graph
from app.services.ocr_quality import (
    adjudicate_candidates,
    create_trusted_text_candidate,
)

repository = WorkflowRepository()
logger = logging.getLogger(__name__)
_durable_graph = None
_checkpointer_context = None
_graph_loop: asyncio.AbstractEventLoop | None = None
_graph_lock = asyncio.Lock()
_tasks: dict[str, asyncio.Task] = {}
_task_lock = asyncio.Lock()
_workflow_semaphore = asyncio.Semaphore(settings.workflow_max_concurrency)
_runtime_loop: asyncio.AbstractEventLoop | None = None
_worker_id = f"api-{uuid.uuid4().hex[:10]}"
TERMINAL_WORKFLOW_STATUSES = {"completed", "failed", "cancelled"}
DEFAULT_ORPHAN_INPUT_MAX_AGE_SECONDS = 24 * 60 * 60


def _candidate_spans(candidate: dict[str, Any]) -> list[dict[str, Any]]:
    spans = []
    for index, block in enumerate(candidate.get("blocks", [])):
        text = str(block.get("text", "")).strip()
        if text:
            spans.append({
                "id": f"ocr:{candidate.get('engine', 'ocr')}:{index}",
                "text": text,
                "bounds": {key: block.get(key) for key in ("left", "top", "right", "bottom")},
            })
    if not spans and str(candidate.get("text", "")).strip():
        spans.append({"id": f"ocr:{candidate.get('engine', 'ocr')}:text", "text": str(candidate["text"])})
    return spans


def _ensure_loop_runtime() -> None:
    global _runtime_loop, _task_lock, _workflow_semaphore, _tasks, _graph_lock
    loop = asyncio.get_running_loop()
    if _runtime_loop is loop:
        return
    _runtime_loop = loop
    _task_lock = asyncio.Lock()
    _workflow_semaphore = asyncio.Semaphore(settings.workflow_max_concurrency)
    _graph_lock = asyncio.Lock()
    _tasks = {}


async def _graph():
    global _durable_graph, _checkpointer_context, _graph_loop
    loop = asyncio.get_running_loop()
    if _durable_graph is not None and _graph_loop is loop:
        return _durable_graph
    async with _graph_lock:
        if _durable_graph is not None and _graph_loop is loop:
            return _durable_graph
        _durable_graph = None
        _checkpointer_context = None
        try:
            from langgraph.checkpoint.sqlite.aio import AsyncSqliteSaver

            _checkpointer_context = AsyncSqliteSaver.from_conn_string(
                settings.workflow_checkpoint_database_path
            )
            checkpointer = await _checkpointer_context.__aenter__()
            _durable_graph = build_workflow_graph(checkpointer)
        except (ImportError, ModuleNotFoundError, RuntimeError, ValueError, OSError) as error:
            logger.warning("durable LangGraph checkpointer unavailable: %s", error)
            _durable_graph = build_workflow_graph()
        _graph_loop = loop
        return _durable_graph


async def initialize_workflow_runtime() -> None:
    _ensure_loop_runtime()
    await _graph()


async def close_workflow_runtime() -> None:
    global _durable_graph, _checkpointer_context, _graph_loop
    current_loop = asyncio.get_running_loop()
    async with _task_lock:
        tasks = [
            task
            for task in _tasks.values()
            if task.get_loop() is current_loop and not task.done() and not task.get_loop().is_closed()
        ]
        stale = [
            run_id
            for run_id, task in _tasks.items()
            if task.done() or task.get_loop().is_closed() or task.get_loop() is not current_loop
        ]
        for run_id in stale:
            _tasks.pop(run_id, None)
    if tasks:
        await asyncio.gather(*tasks, return_exceptions=True)
    if _checkpointer_context is not None:
        await _checkpointer_context.__aexit__(None, None, None)
    _durable_graph = None
    _checkpointer_context = None
    _graph_loop = None


def _managed_input_path(raw_path: str) -> Path | None:
    input_root = Path(settings.workflow_input_directory).resolve()
    candidate = Path(raw_path).resolve()
    try:
        candidate.relative_to(input_root)
    except ValueError:
        logger.warning("refusing to delete workflow input outside managed directory: %s", candidate)
        return None
    if candidate == input_root:
        logger.warning("refusing to delete workflow input directory itself: %s", candidate)
        return None
    return candidate


def cleanup_workflow_input(run_id: str) -> bool:
    """Delete a terminal run's managed input and clear its persisted path."""
    if repository.get_status(run_id) not in TERMINAL_WORKFLOW_STATUSES:
        return False
    raw_path = repository.input_path_for_run(run_id)
    if raw_path is None:
        return False
    image_path = _managed_input_path(raw_path)
    if image_path is None:
        return False
    try:
        image_path.unlink(missing_ok=True)
    except OSError:
        logger.exception("failed to delete workflow input", extra={"run_id": run_id})
        return False
    return repository.clear_input_path(run_id, raw_path)


def cleanup_stale_workflow_inputs(
    *,
    max_orphan_age_seconds: int = DEFAULT_ORPHAN_INPUT_MAX_AGE_SECONDS,
) -> int:
    """Clean terminal references and old unreferenced managed input files."""
    if max_orphan_age_seconds < 0:
        raise ValueError("max_orphan_age_seconds must not be negative")

    cleaned = 0
    protected_paths: set[Path] = set()
    for run_id, status, raw_path in repository.input_path_records():
        if status in TERMINAL_WORKFLOW_STATUSES:
            cleaned += int(cleanup_workflow_input(run_id))
            continue
        managed_path = _managed_input_path(raw_path)
        if managed_path is not None:
            protected_paths.add(managed_path)

    input_root = Path(settings.workflow_input_directory).resolve()
    if not input_root.exists():
        return cleaned
    cutoff = time.time() - max_orphan_age_seconds
    for candidate in input_root.glob("*.bin"):
        managed_path = _managed_input_path(str(candidate))
        if managed_path is None or managed_path in protected_paths:
            continue
        try:
            if managed_path.stat().st_mtime > cutoff:
                continue
            managed_path.unlink(missing_ok=True)
            cleaned += 1
        except FileNotFoundError:
            continue
        except OSError:
            logger.exception("failed to delete stale workflow input: %s", managed_path)
    return cleaned


def _config(run_id: str) -> dict[str, Any]:
    return {"configurable": {"thread_id": run_id}}


async def _execute(run_id: str, graph_input: dict[str, Any] | Command, preclaimed: bool = False) -> None:
    _ensure_loop_runtime()
    logger.info("workflow started", extra={"run_id": run_id})
    try:
        async with _workflow_semaphore:
            if not preclaimed and not repository.claim_job(
                run_id,
                _worker_id,
                settings.workflow_lease_seconds,
            ):
                return
            current = repository.get_state(run_id)
            if current.get("workflow_status") == "cancelled":
                return
            current["workflow_status"] = "running"
            graph = await _graph()
            runtime_state = dict(current) if isinstance(graph_input, Command) else dict(graph_input)
            heartbeat_at = time.monotonic()
            async for chunk in graph.astream(
                graph_input,
                _config(run_id),
                stream_mode="updates",
                durability="async",
            ):
                for node, updates in chunk.items():
                    if node == "__interrupt__":
                        repository.append_event(
                            run_id,
                            "workflow_interrupted",
                            {"pending_action": runtime_state.get("pending_action")},
                            f"interrupt:{runtime_state.get('pending_action')}:{runtime_state.get('revision', 0)}",
                        )
                        continue
                    if not updates:
                        continue
                    _merge_runtime_state(runtime_state, dict(updates))
                    should_persist = node in {
                        "prepare_text",
                        "recognize_image",
                        "create_rule_draft",
                        "build_action_graph",
                        "project_cards",
                        "require_review",
                        "require_team_review",
                        "require_ocr_review",
                        "run_agent_task",
                        "task_barrier",
                        "verify_workflow",
                        "adjudicate_evidence",
                        "replan",
                        "finalize_rules_fast",
                        "await_card_review",
                        "await_team_review",
                        "await_ocr_review",
                    }
                    if node == "supervisor":
                        should_persist = True
                    if should_persist:
                        await asyncio.to_thread(
                            _commit_node_update,
                            run_id,
                            node,
                            dict(updates),
                            dict(runtime_state),
                        )
                    if time.monotonic() - heartbeat_at >= max(1, settings.workflow_lease_seconds / 3):
                        repository.heartbeat_job(run_id, _worker_id, settings.workflow_lease_seconds)
                        heartbeat_at = time.monotonic()
                    if repository.get_status(run_id) == "cancelled":
                        return
            final = repository.get_state(run_id)
            logger.info(
                "workflow completed",
                extra={"run_id": run_id, "route": final.get("route")},
            )
    except asyncio.CancelledError:
        current = repository.get_state(run_id)
        current["workflow_status"] = "cancelled"
        current["pending_action"] = None
        repository.save(run_id, current)
        raise
    except Exception as error:
        current = repository.get_state(run_id)
        current["workflow_status"] = "failed"
        current["pending_action"] = None
        repository.save(run_id, current, f"{type(error).__name__}: {error}")
        repository.append_event(
            run_id,
            "failed",
            {
                "message": "workflow execution failed",
                "snapshot": _event_snapshot(run_id, current),
            },
        )
        logger.exception("workflow failed", extra={"run_id": run_id})
    finally:
        try:
            status = repository.get_status(run_id)
        except (KeyError, RuntimeError):
            status = None
        if status in {"queued", "running", "awaiting_review", "awaiting_ocr_review"}:
            repository.release_job(run_id, _worker_id)
        if status in TERMINAL_WORKFLOW_STATUSES:
            cleanup_workflow_input(run_id)
        async with _task_lock:
            _tasks.pop(run_id, None)


def _merge_locked_cards(
    current_cards: list[dict[str, Any]],
    incoming_cards: list[dict[str, Any]],
    locked: dict[str, list[str]],
    suggestions: dict[str, dict[str, Any]],
) -> list[dict[str, Any]]:
    current_by_id = {str(card.get("id")): card for card in current_cards}
    merged: list[dict[str, Any]] = []
    for incoming in incoming_cards:
        card = dict(incoming)
        card_id = str(card.get("id"))
        current = current_by_id.get(card_id)
        if current:
            for field in locked.get(card_id, []):
                if field in card and card.get(field) != current.get(field):
                    suggestions.setdefault(card_id, {})[field] = card.get(field)
                card[field] = current.get(field)
        merged.append(card)
    return merged


def _merge_runtime_state(state: dict[str, Any], updates: dict[str, Any]) -> None:
    for key, value in updates.items():
        if key in {"warnings", "node_trace", "expert_outputs", "agent_task_results"}:
            state[key] = list(state.get(key, [])) + list(value)
        else:
            state[key] = value


def _commit_node_update(
    run_id: str,
    node: str,
    updates: dict[str, Any],
    runtime_state: dict[str, Any] | None = None,
) -> None:
    persisted = repository.get_state(run_id)
    if persisted.get("workflow_status") in {"cancelled", "completed"}:
        return
    state = dict(runtime_state) if runtime_state is not None else dict(persisted)
    updates.pop("image_bytes", None)
    if "cards" in updates or persisted.get("user_locked"):
        suggestions = dict(persisted.get("suggestions", {}))
        state["cards"] = _merge_locked_cards(
            persisted.get("cards", []),
            state.get("cards", []),
            persisted.get("user_locked", {}),
            suggestions,
        )
        state["suggestions"] = suggestions
    state["user_locked"] = persisted.get("user_locked") or state.get("user_locked", {})
    state["field_versions"] = persisted.get("field_versions") or state.get("field_versions", {})
    state["revision"] = max(int(state.get("revision", 0)), int(persisted.get("revision", 0)))
    previous_phase = str(persisted.get("workflow_phase", "received"))
    current_phase = str(state.get("workflow_phase", previous_phase))
    phase_transitions = [
        str(phase)
        for phase in updates.pop("phase_transitions", [])
        if phase
    ] or [current_phase]
    state.pop("phase_transitions", None)
    history = list(persisted.get("phase_history", []))
    for phase in phase_transitions:
        if not history or history[-1].get("phase") != phase:
            history.append({"phase": phase, "node": node, "revision": int(state.get("revision", 0)), "at": datetime.now(timezone.utc).isoformat()})
    state["phase_history"] = history[-64:]
    state.update(_provider_snapshot_fields(state))
    state.pop("image_bytes", None)
    events: list[tuple[str, dict[str, Any], str | None]] = []
    revision = int(state.get("revision", 0))
    event_key = f"{node}:{revision}:{len(state.get('node_trace', []))}"
    event_previous_phase = previous_phase
    for phase in phase_transitions:
        if phase == event_previous_phase:
            continue
        events.append(
            (
                "phase_changed",
                {"from": event_previous_phase, "phase": phase, "node": node, "revision": revision},
                f"phase:{phase}:{revision}:{node}",
            )
        )
        event_previous_phase = phase
    events.append(
        ("node_started", {"node": node}, f"node:{event_key}")
    )
    if node == "recognize_image":
        for candidate in state.get("ocr_candidates", []):
            events.append(
                (
                    "ocr_candidate",
                {
                    "engine": candidate.get("engine"),
                    "confidence": candidate.get("confidence"),
                },
                    (
                        f"ocr:{candidate.get('engine')}:"
                        f"{hashlib.sha1(str(candidate.get('text', '')).encode('utf-8')).hexdigest()[:16]}"
                    ),
                )
            )
    elif node == "create_rule_draft":
        events.append(
            (
                "draft_created",
                {
                    "revision": revision,
                    "stage": "provisional",
                    "cards": state.get("cards", []),
                    "overall_confidence": state.get("overall_confidence", 0),
                    "time_to_first_draft_ms": state.get("time_to_first_draft_ms"),
                },
                f"draft-created:{revision}",
            )
        )
    elif node == "supervisor":
        plan = state.get("agent_plan", {})
        events.append(
            (
                "plan_created",
                {
                    "plan_id": plan.get("id"),
                    "round": plan.get("round", 0),
                    "tasks": len(plan.get("tasks", [])),
                    "reasons": plan.get("reasons", []),
                },
                f"plan:{plan.get('id')}",
            )
        )
        for task in plan.get("tasks", []):
            events.append(
                (
                    "task_scheduled",
                    {
                        "task_id": task.get("id"),
                        "tool": task.get("tool"),
                        "depends_on": task.get("depends_on", []),
                        "model_tier": task.get("model_tier"),
                    },
                    f"task-scheduled:{task.get('id')}",
                )
            )
            events.append(
                (
                    "agent_dispatched",
                    {"agent": task.get("tool"), "reasons": state.get("decision_reasons", [])},
                    f"agent:{task.get('id')}",
                )
            )
    elif node == "run_agent_task":
        for result in updates.get("agent_task_results", []):
            task = next(
                (
                    dict(item)
                    for item in state.get("agent_plan", {}).get("tasks", [])
                    if item.get("id") == result.get("task_id")
                ),
                dict(state.get("agent_task", {})),
            )
            repository.save_agent_task(run_id, task, result)
            events.append(
                (
                    "tool_started",
                    {"task_id": result.get("task_id"), "tool": result.get("tool")},
                    f"tool-started:{result.get('task_id')}:{result.get('attempt', 1)}",
                )
            )
            events.append(
                (
                    "tool_completed",
                    {
                        "task_id": result.get("task_id"),
                        "tool": result.get("tool"),
                        "status": result.get("status"),
                        "duration_ms": result.get("duration_ms"),
                        "claim_count": len(result.get("claims", [])),
                        "failure_type": result.get("failure_type"),
                    },
                    f"tool-completed:{result.get('task_id')}:{result.get('attempt', 1)}",
                )
            )
            for source in result.get("retrieval_sources", []):
                events.append(
                    (
                        "retrieval_source_added",
                        source,
                        f"retrieval:{result.get('task_id')}:{hashlib.sha1(str(source.get('url')).encode()).hexdigest()[:16]}",
                    )
                )
    elif node == "build_action_graph":
        graph = state.get("action_graph", {})
        events.append(
            (
                "action_graph_updated",
                {
                    "version": graph.get("version", 1),
                    "actions": len(graph.get("actions", [])),
                    "dependencies": len(graph.get("dependencies", [])),
                },
                f"graph:{revision}:{graph.get('version', 1)}:{state.get('expert_round', 0)}",
            )
        )
    elif node == "adjudicate_evidence":
        events.append(
            (
                "decision_made",
                {
                    "risk_level": state.get("risk_level", "low"),
                    "errors": state.get("validation_errors", []),
                    "overall_confidence": state.get("overall_confidence", 0),
                },
                f"decision:{revision}:{state.get('expert_round', 0)}",
            )
        )
    elif node == "verify_workflow":
        summary = state.get("verification_summary", {})
        if not summary.get("passed"):
            events.append(
                (
                    "verification_failed",
                    {
                        "unresolved_evidence": summary.get("unresolved_evidence", []),
                        "recommended_tasks": summary.get("recommended_tasks", []),
                        "reason": summary.get("reason"),
                    },
                    f"verification:{state.get('replan_count', 0)}:{revision}",
                )
            )
        if state.get("budget_usage", {}).get("exhausted"):
            events.append(
                (
                    "budget_exhausted",
                    state.get("budget_usage", {}),
                    f"budget:{run_id}",
                )
            )
    elif node == "replan":
        plan = state.get("agent_plan", {})
        events.append(
            (
                "plan_revised",
                {
                    "plan_id": plan.get("id"),
                    "round": plan.get("round"),
                    "tasks": len(plan.get("tasks", [])),
                    "replan_count": state.get("replan_count", 0),
                },
                f"replan:{plan.get('id')}",
            )
        )
        for task in plan.get("tasks", []):
            events.append(
                (
                    "task_scheduled",
                    {
                        "task_id": task.get("id"),
                        "tool": task.get("tool"),
                        "depends_on": task.get("depends_on", []),
                        "model_tier": task.get("model_tier"),
                    },
                    f"task-scheduled:{task.get('id')}",
                )
            )
    elif node in {"project_cards", "require_review", "finalize_rules_fast"}:
        if node == "finalize_rules_fast":
            graph = state.get("action_graph", {})
            events.append(
                (
                    "action_graph_updated",
                    {
                        "version": graph.get("version", 1),
                        "actions": len(graph.get("actions", [])),
                        "dependencies": len(graph.get("dependencies", [])),
                    },
                    f"graph:{revision}:{graph.get('version', 1)}:fast",
                )
            )
        events.append(
            (
                "decision_made",
                {
                    "risk_level": state.get("risk_level", "low"),
                    "errors": state.get("validation_errors", []),
                    "overall_confidence": state.get("overall_confidence", 0),
                },
                f"decision:{revision}:{state.get('expert_round', 0)}",
            )
        )
        events.append(
            (
                "draft_updated",
                {
                    "revision": revision,
                    "stage": state.get("result_stage"),
                    "cards": state.get("cards", []),
                },
                f"draft-updated:{revision}",
            )
        )
        events.append(
            (
                "review_required",
                {
                    "revision": revision,
                    "pending_action": state.get("pending_action"),
                    "validation_errors": state.get("validation_errors", []),
                },
                f"review:{revision}",
            )
        )
    snapshot = _event_snapshot(run_id, state)
    events = [
        (event, {**data, "snapshot": snapshot}, idempotency_key)
        if event in {"draft_created", "draft_updated", "review_required", "completed", "failed"}
        else (event, data, idempotency_key)
        for event, data, idempotency_key in events
    ]
    repository.save_with_events(run_id, state, events)


def _provider_snapshot_fields(state: dict[str, Any]) -> dict[str, Any]:
    frozen = state.get("provider_usage")
    if frozen and state.get("workflow_status") in {"awaiting_review", "completed", "failed", "cancelled"}:
        usage = frozen
    else:
        usage = provider_usage_delta(state.get("provider_usage_baseline"))
    return {
        "provider_usage": usage,
        "model_enhancement_status": _enhancement_status(
            usage,
            ("fast_model", "expert_model"),
            configured=bool(state.get("has_fast_model") or state.get("has_expert_model")),
        ),
        "ocr_enhancement_status": _enhancement_status(
            usage,
            ("ocr",),
            configured=bool(state.get("input_kind") == "image" and state.get("has_vivo_ocr")),
        ),
        "image_generation_status": _enhancement_status(
            usage,
            ("image_generation",),
            configured=bool(state.get("has_image_generation")),
        ),
    }


def _enhancement_status(
    provider_usage: dict[str, dict[str, Any]],
    providers: tuple[str, ...],
    *,
    configured: bool,
) -> str:
    if not configured:
        return "not_configured"
    success = sum(int(provider_usage.get(provider, {}).get("success_count_delta", 0)) for provider in providers)
    failures = sum(int(provider_usage.get(provider, {}).get("failure_count_delta", 0)) for provider in providers)
    attempts = sum(int(provider_usage.get(provider, {}).get("request_count_delta", 0)) for provider in providers)
    if success > 0:
        return "succeeded"
    if attempts > 0 or failures > 0:
        return "degraded"
    return "attempted"


def _event_snapshot(run_id: str, state: dict[str, Any]) -> dict[str, Any]:
    phase = _canonical_workflow_phase(state)
    return {
        "run_id": run_id,
        "trace_id": run_id,
        "workflow_status": state.get("workflow_status", "running"),
        "workflow_phase": phase,
        "evidence_status": state.get("evidence_status", state.get("ocr_evidence_status", "trusted")),
        "draft_status": state.get("draft_status", "not_started"),
        "review_items": state.get("review_items", []),
        "effect_status": state.get("effect_status", "not_started"),
        "blocked_reasons": state.get("blocked_reasons", []),
        "checkpoint_id": state.get("checkpoint_id"),
        "command_ids": sorted((state.get("command_ids") or {}).keys()),
        "evidence_envelopes": state.get("evidence_envelopes", []),
        "field_evidence": state.get("field_evidence", []),
        "pending_action": state.get("pending_action"),
        "ocr_text": state.get("ocr_text", ""),
        "ocr_quality_report": state.get("ocr_quality_report"),
        "ocr_review_reasons": state.get("ocr_review_reasons", []),
        "ocr_evidence_status": state.get("ocr_evidence_status", "trusted"),
        "ocr_candidate_versions": state.get("ocr_candidate_versions", state.get("ocr_candidates", [])),
        "ocr_conflicts": state.get("ocr_conflicts", []),
        "evidence_spans": state.get("evidence_spans", []),
        "summary_status": state.get("summary_status", "provisional"),
        "team_tasks": state.get("team_tasks", []),
        "team_workflow_review": state.get("team_workflow_review", {}),
        "cards": state.get("cards", []),
        "preview_actions": state.get("preview_actions", []),
        "engine": state.get("engine", ""),
        "warnings": state.get("warnings", []),
        "node_trace": state.get("node_trace", []),
        "revision": int(state.get("revision", 0)),
        "result_stage": state.get("result_stage", "provisional"),
        "overall_confidence": float(state.get("overall_confidence", 0)),
        "route": state.get("route", "rules"),
        "cache_status": state.get("cache_status") or "bypass",
        "time_to_first_draft_ms": state.get("time_to_first_draft_ms"),
        "time_to_final_ms": state.get("time_to_final_ms"),
        "active_agents": state.get("active_agents", []),
        "decision_reasons": state.get("decision_reasons", []),
        "risk_level": state.get("risk_level", "low"),
        "validation_errors": state.get("validation_errors", []),
        "field_conflicts": state.get("field_conflicts", []),
        "field_versions": state.get("field_versions", {}),
        "react_session": state.get("react_session"),
        "react_suggestions": state.get("react_suggestions", []),
        **_provider_snapshot_fields(state),
    }


def _canonical_workflow_phase(state: dict[str, Any]) -> str:
    status = state.get("workflow_status", "queued")
    if state.get("effect_status") == "executing":
        return "effects_executing"
    if status == "completed":
        return "completed"
    if status == "cancelled":
        return "cancelled"
    if status == "failed":
        return "failed"
    if status == "awaiting_ocr_review":
        return "review_required"
    if status == "awaiting_review":
        return "review_center"
    if status == "running":
        phase = state.get("workflow_phase")
        if phase in {"evidence_collecting", "evidence_adjudication", "draft_generating", "workflow_planning", "agents_running", "evidence_verification", "draft_ready"}:
            return str(phase)
        return "draft_generating"
    if status == "queued":
        return "received"
    return "degraded" if state.get("summary_status") == "degraded" else "evidence_collecting"


async def _schedule(run_id: str, graph_input: dict[str, Any] | Command, preclaimed: bool = False) -> None:
    _ensure_loop_runtime()
    async with _task_lock:
        task = asyncio.create_task(
            _execute(run_id, graph_input, preclaimed=preclaimed),
            name=f"workflow-{run_id}",
        )
        _tasks[run_id] = task


async def _wait_for_checkpoint_interrupt(run_id: str, kind: str, timeout: float = 2.0) -> None:
    deadline = time.monotonic() + timeout
    graph = await _graph()
    while time.monotonic() < deadline:
        snapshot = await graph.aget_state(_config(run_id))
        for task in snapshot.tasks:
            if any(
                isinstance(item.value, dict) and item.value.get("kind") == kind
                for item in task.interrupts
            ):
                return
        async with _task_lock:
            active = _tasks.get(run_id)
        if active is not None and active.done():
            error = active.exception()
            if error is not None:
                raise error
        await asyncio.sleep(0.01)
    raise TimeoutError(f"workflow checkpoint is not ready for {kind}")


def _can_complete_rules_inline(state: dict[str, Any]) -> bool:
    return (
        state.get("input_kind") == "text"
        and len(state.get("rule_cards", [])) == 1
        and float(state.get("overall_confidence", 0)) >= 0.85
        and not state.get("complexity_reasons", [])
        and not settings.has_fast_model_config
        and not settings.has_expert_model_config
    )


def _complete_rules_inline(run_id: str, state: dict[str, Any]) -> None:
    final_update = finalize_rules_fast(state)
    node_trace = list(state.get("node_trace", [])) + list(final_update.get("node_trace", []))
    final_state = {**state, **final_update, "node_trace": node_trace}
    history = list(state.get("phase_history", []))
    previous_phase = str(state.get("workflow_phase", "received"))
    phase_transitions = [
        "evidence_collecting",
        "draft_generating",
        "workflow_planning",
        "draft_ready",
        "review_center",
    ]
    for phase in phase_transitions:
        if not history or history[-1].get("phase") != phase:
            history.append({
                "phase": phase,
                "node": "finalize_rules_fast",
                "revision": int(final_state.get("revision", 0)),
                "at": datetime.now(timezone.utc).isoformat(),
            })
    final_state["phase_history"] = history[-64:]
    final_state.update(_provider_snapshot_fields(final_state))
    revision = int(final_state.get("revision", 0))
    snapshot = _event_snapshot(run_id, final_state)
    graph = final_state.get("action_graph", {})
    events = [
        *[
            (
                "phase_changed",
                {"from": previous, "phase": phase, "node": "finalize_rules_fast", "revision": revision},
                f"phase:{phase}:{revision}:finalize_rules_fast",
            )
            for previous, phase in zip(
                [previous_phase, *phase_transitions[:-1]],
                phase_transitions,
            )
            if previous != phase
        ],
        (
            "draft_created",
            {
                "revision": 1,
                "stage": "provisional",
                "cards": state.get("cards", []),
                "overall_confidence": state.get("overall_confidence", 0),
                "time_to_first_draft_ms": state.get("time_to_first_draft_ms"),
                "snapshot": snapshot,
            },
            "draft-created:1",
        ),
        (
            "action_graph_updated",
            {
                "version": graph.get("version", 1),
                "actions": len(graph.get("actions", [])),
                "dependencies": len(graph.get("dependencies", [])),
            },
            f"graph:{revision}:{graph.get('version', 1)}:inline",
        ),
        (
            "decision_made",
            {
                "risk_level": final_state.get("risk_level", "low"),
                "errors": final_state.get("validation_errors", []),
                "overall_confidence": final_state.get("overall_confidence", 0),
            },
            f"decision:{revision}:inline",
        ),
        (
            "draft_updated",
            {
                "revision": revision,
                "stage": final_state.get("result_stage"),
                "cards": final_state.get("cards", []),
                "snapshot": snapshot,
            },
            f"draft-updated:{revision}",
        ),
        (
            "review_required",
            {
                "revision": revision,
                "pending_action": final_state.get("pending_action"),
                "validation_errors": final_state.get("validation_errors", []),
                "snapshot": snapshot,
            },
            f"review:{revision}",
        ),
    ]
    repository.save_with_events(run_id, final_state, events)


def _initial_state(
    run_id: str,
    input_kind: str,
    text: str = "",
    image_bytes: bytes | None = None,
    screenshot_time: str | None = None,
    workflow_context: dict[str, Any] | None = None,
) -> dict[str, Any]:
    state = {
        "run_id": run_id,
        "input_kind": input_kind,
        "input_text": text,
        "image_bytes": image_bytes or b"",
        "screenshot_time": screenshot_time,
        "started_at": time.time(),
        "repair_count": 0,
        "warnings": [],
        "node_trace": [],
        "workflow_status": "queued",
        "workflow_phase": "received",
        "evidence_status": "trusted" if input_kind == "text" else "review_required",
        "draft_status": "not_started",
        "review_items": [],
        "effect_status": "not_started",
        "blocked_reasons": [],
        "checkpoint_id": run_id,
        "command_ids": {},
        "effect_results": [],
        "phase_history": [{"phase": "received", "node": "start", "revision": 0, "at": datetime.now(timezone.utc).isoformat()}],
        "degraded_reasons": [],
        "evidence_envelopes": [],
        "field_evidence": [],
        "pending_action": None,
        "revision": 0,
        "result_stage": "provisional",
        "overall_confidence": 0,
        "route": "rules",
        "cache_status": "bypass",
        "user_locked": {},
        "suggestions": {},
        "ocr_candidates": [],
        "ocr_evidence_status": "trusted" if input_kind == "text" else "review_required",
        "ocr_candidate_versions": [],
        "ocr_conflicts": [],
        "evidence_spans": [],
        "summary_status": "provisional",
        "team_tasks": [],
        "team_workflow_review": {"required": False, "reasons": [], "tasks": [], "conflicts": []},
        "field_versions": {},
        "field_conflicts": [],
        "action_graph": {},
        "active_agents": [],
        "decision_reasons": [],
        "risk_level": "low",
        "expert_outputs": [],
        "expert_round": 0,
        "agent_plan": None,
        "agent_task_results": [],
        "budget_usage": {
            "task_limit": settings.workflow_agent_max_tasks,
            "tasks_scheduled": 0,
            "tasks_completed": 0,
            "tasks_failed": 0,
            "replan_limit": settings.workflow_agent_max_replans,
            "replans_used": 0,
            "deadline_ms": int(settings.workflow_agent_deadline_seconds * 1000),
            "elapsed_ms": 0,
            "exhausted": False,
            "exhaustion_reason": None,
            "fast_model_calls": 0,
            "expert_model_calls": 0,
            "web_requests": 0,
        },
        "verification_summary": {},
        "unresolved_evidence": [],
        "retrieval_sources": [],
        "replan_count": 0,
        "has_fast_model": settings.has_fast_model_config,
        "has_expert_model": settings.has_expert_model_config,
        "has_vivo_ocr": settings.has_vivo_ocr_config,
        "has_image_generation": settings.has_image_generation_config,
        "provider_usage_baseline": runtime.snapshot(),
        "provider_usage": {},
        "model_enhancement_status": "not_configured"
        if not (settings.has_fast_model_config or settings.has_expert_model_config)
        else "attempted",
        "ocr_enhancement_status": "not_configured"
        if not settings.has_vivo_ocr_config
        else "attempted",
        "image_generation_status": "not_configured"
        if not settings.has_image_generation_config
        else "attempted",
    }
    if workflow_context:
        state.update(workflow_context)
    return state


async def start_text_workflow(
    text: str,
    screenshot_time: str | None = None,
    workflow_context: dict[str, Any] | None = None,
) -> WorkflowRunResponse:
    run_id = str(uuid.uuid4())
    initial = _initial_state(
        run_id,
        "text",
        text=text,
        screenshot_time=screenshot_time,
        workflow_context=workflow_context,
    )
    trusted_candidate = create_trusted_text_candidate(text.strip(), engine="provided-text")
    trusted_spans = _candidate_spans(trusted_candidate)
    primed_state = {
        **initial,
        "ocr_text": text.strip(),
        "ocr_engine": "provided-text",
        "ocr_quality": 1.0,
        "ocr_quality_report": trusted_candidate["quality_report"],
        "ocr_candidates": [trusted_candidate],
        "ocr_candidate_versions": [trusted_candidate],
        "evidence_spans": trusted_spans,
        "evidence_envelopes": [{
            "source_id": f"{run_id}:text:1",
            "source_type": "text",
            "version": 1,
            "raw_text": text.strip(),
            "blocks": trusted_candidate.get("blocks", []),
            "spans": trusted_spans,
            "quality_report": trusted_candidate["quality_report"],
            "trust_status": "user_verified",
            "conflicts": [],
            "created_at": datetime.now(timezone.utc).isoformat(),
        }],
    }
    provisional = await create_rule_draft(primed_state)
    saved_state = {**primed_state, **provisional}
    saved_state["workflow_status"] = "queued"
    saved_state["pending_action"] = None
    initial["time_to_first_draft_ms"] = provisional.get("time_to_first_draft_ms")
    repository.create_run(
        run_id,
        {**saved_state, "image_bytes": ""},
        lease_owner=_worker_id,
        lease_seconds=settings.workflow_lease_seconds,
    )
    started_response = repository.response(run_id)
    if _can_complete_rules_inline(saved_state):
        _complete_rules_inline(run_id, saved_state)
        repository.release_job(run_id, _worker_id)
        return started_response
    await _schedule(run_id, initial, preclaimed=True)
    return started_response


async def start_image_workflow(
    image_bytes: bytes,
    screenshot_time: str | None = None,
    workflow_context: dict[str, Any] | None = None,
) -> WorkflowRunResponse:
    run_id = str(uuid.uuid4())
    input_dir = Path(settings.workflow_input_directory)
    input_dir.mkdir(parents=True, exist_ok=True)
    image_path = input_dir / f"{run_id}.bin"
    image_path.write_bytes(image_bytes)
    initial = _initial_state(
        run_id,
        "image",
        image_bytes=image_bytes,
        screenshot_time=screenshot_time,
        workflow_context=workflow_context,
    )
    initial["image_path"] = str(image_path.resolve())
    repository.create_run(
        run_id,
        {**initial, "image_bytes": ""},
        initial["image_path"],
        lease_owner=_worker_id,
        lease_seconds=settings.workflow_lease_seconds,
    )
    await _schedule(run_id, initial, preclaimed=True)
    return repository.response(run_id)


async def wait_for_result(
    run_id: str,
    timeout: float | None = None,
    accept_provisional: bool = True,
) -> WorkflowRunResponse:
    deadline = time.monotonic() + (timeout if timeout is not None else settings.legacy_sync_wait_seconds)
    while time.monotonic() < deadline:
        response = repository.response(run_id)
        if response.workflow_status in {
            "completed",
            "awaiting_ocr_review",
            "awaiting_review",
            "failed",
            "cancelled",
        }:
            return repository.response(run_id)
        if accept_provisional and response.revision > 0:
            return response
        await asyncio.sleep(0.005)
    return repository.response(run_id)


def get_workflow(run_id: str) -> WorkflowRunResponse:
    return repository.response(run_id)


def submit_ocr_candidate(run_id: str, request: OcrCandidateRequest) -> WorkflowRunResponse:
    state = repository.get_state(run_id)
    if state.get("workflow_status") in {"failed", "cancelled"}:
        raise ValueError(f"workflow is already {state.get('workflow_status')}")
    candidate = request.model_dump()
    candidates = _merge_ocr_candidates(state.get("ocr_candidates", []), candidate)
    adjudication = adjudicate_candidates(candidates[-6:])
    state["ocr_candidates"] = adjudication.candidates
    state["ocr_text"] = adjudication.merged_text
    state["ocr_engine"] = adjudication.selected.get("engine", request.engine)
    state["ocr_quality"] = adjudication.selected.get("quality_score", 0)
    state["ocr_quality_report"] = adjudication.selected.get("quality_report", {})
    state["ocr_review_reasons"] = adjudication.review_reasons
    state["ocr_candidate_versions"] = adjudication.candidates
    state["ocr_conflicts"] = adjudication.critical_conflicts
    state["ocr_evidence_status"] = "review_required" if adjudication.requires_review else "trusted"
    state["evidence_status"] = state["ocr_evidence_status"]
    state["summary_status"] = "blocked" if adjudication.requires_review else "provisional"
    state["workflow_phase"] = "review_required" if adjudication.requires_review else "evidence_adjudication"
    state["review_items"] = [
        {"kind": "ocr", "reason": reason} for reason in adjudication.review_reasons
    ]
    state["blocked_reasons"] = list(adjudication.review_reasons)
    state["evidence_spans"] = _candidate_spans(adjudication.selected)
    state["evidence_envelopes"] = [
        {
            "source_id": f"{run_id}:ocr:{len(candidates)}",
            "source_type": "ocr",
            "version": len(candidates),
            "raw_text": str(adjudication.selected.get("text", "")),
            "blocks": adjudication.selected.get("blocks", []),
            "spans": _candidate_spans(adjudication.selected),
            "quality_report": adjudication.selected.get("quality_report", {}),
            "trust_status": state["evidence_status"],
            "conflicts": adjudication.critical_conflicts,
            "created_at": datetime.now(timezone.utc).isoformat(),
        }
    ]
    conflict = (
        f"OCR candidates conflict ({'; '.join(adjudication.critical_conflicts)}); "
        "review critical fields"
        if adjudication.critical_conflicts
        else None
    )
    state["review_requested"] = adjudication.requires_review
    if adjudication.requires_review:
        state["workflow_status"] = "awaiting_ocr_review"
        state["pending_action"] = "resolve_ocr"
        state["warnings"] = list(
            dict.fromkeys(
                state.get("warnings", [])
                + [f"OCR review required: {reason}" for reason in adjudication.review_reasons]
                + ([conflict] if conflict else [])
            )
        )
    repository.save(run_id, state)
    repository.append_event(
        run_id,
        "ocr_candidate",
        {
            "engine": request.engine,
            "confidence": request.confidence,
            "quality_score": adjudication.selected.get("quality_score", 0),
            "quality_report": adjudication.selected.get("quality_report", {}),
            "candidate_count": len(candidates),
            "conflict": conflict,
        },
        f"ocr:{request.engine}:{hashlib.sha1(request.text.strip().encode('utf-8')).hexdigest()[:16]}",
    )
    if adjudication.requires_review:
        repository.append_event(
            run_id,
            "ocr_review_required",
            {"reasons": adjudication.review_reasons, "quality_score": adjudication.selected.get("quality_score", 0)},
            f"ocr-review:{state.get('revision', 0)}:{request.engine}",
        )
    if adjudication.critical_conflicts:
        repository.append_event(
            run_id,
            "evidence_conflict",
            {"conflicts": adjudication.critical_conflicts},
            f"ocr-conflict:{state.get('revision', 0)}",
        )
    return repository.response(run_id)


def _merge_ocr_candidates(
    existing: list[dict[str, Any]],
    incoming: dict[str, Any],
) -> list[dict[str, Any]]:
    merged = {
        (str(item.get("engine", "ocr")), str(item.get("text", "")).strip()): dict(item)
        for item in existing
        if str(item.get("text", "")).strip()
    }
    key = (str(incoming.get("engine", "ocr")), str(incoming.get("text", "")).strip())
    current = merged.get(key)
    if current is None or float(incoming.get("quality_score", 0)) > float(
        current.get("quality_score", 0)
    ):
        merged[key] = dict(incoming)
    return list(merged.values())


def _ocr_candidate_conflict(candidates: list[dict[str, Any]]) -> str | None:
    from difflib import SequenceMatcher

    if len(candidates) < 2:
        return None
    ordered = sorted(candidates, key=lambda item: float(item.get("confidence", 0)), reverse=True)
    first = " ".join(str(ordered[0].get("text", "")).split())
    second = " ".join(str(ordered[1].get("text", "")).split())
    if not first or not second:
        return None
    similarity = SequenceMatcher(None, first, second).ratio()
    if similarity < 0.72:
        return f"OCR candidates conflict (similarity={similarity:.2f}); review critical fields"
    return None


def patch_draft(run_id: str, request: DraftPatchRequest) -> WorkflowRunResponse:
    state = repository.get_state(run_id)
    revision = int(state.get("revision", 0))
    if request.base_revision != revision and request.cards is not None:
        raise ValueError(f"revision conflict: expected {revision}")
    cards = [dict(card) for card in state.get("cards", [])]
    if request.cards is not None:
        cards = [card.model_dump(mode="json") for card in request.cards]
    locked = dict(state.get("user_locked", {}))
    versions = {
        card_id: dict(fields)
        for card_id, fields in state.get("field_versions", {}).items()
    }
    conflicts: list[dict[str, Any]] = []
    cards_by_id = {str(card.get("id")): card for card in cards}
    for operation in request.operations:
        card = cards_by_id.get(operation.card_id)
        if card is None:
            conflicts.append(
                {"card_id": operation.card_id, "field": operation.field, "reason": "card_not_found"}
            )
            continue
        current_version = int(versions.setdefault(operation.card_id, {}).get(operation.field, 0))
        if (
            operation.base_field_version is not None
            and operation.base_field_version != current_version
        ):
            conflicts.append(
                {
                    "card_id": operation.card_id,
                    "field": operation.field,
                    "reason": "field_version_conflict",
                    "current_value": card.get(operation.field),
                    "user_value": operation.value,
                    "current_version": current_version,
                }
            )
            continue
        if operation.operation == "set":
            card[operation.field] = operation.value
            versions[operation.card_id][operation.field] = current_version + 1
        elif operation.operation == "unset":
            card[operation.field] = None
            versions[operation.card_id][operation.field] = current_version + 1
        elif operation.operation == "lock":
            locked[operation.card_id] = sorted(
                set(locked.get(operation.card_id, [])) | {operation.field}
            )
        elif operation.operation == "unlock":
            locked[operation.card_id] = [
                field for field in locked.get(operation.card_id, []) if field != operation.field
            ]
    if conflicts:
        state["field_conflicts"] = conflicts
        repository.save(run_id, state)
        for conflict in conflicts:
            repository.append_event(run_id, "field_conflict", conflict)
        raise ValueError(f"field conflicts: {conflicts}")
    for card_id, fields in request.locked_fields.items():
        locked[card_id] = sorted(set(locked.get(card_id, [])) | set(fields))
        for field in fields:
            versions.setdefault(card_id, {}).setdefault(field, 1)
    state["cards"] = cards
    state["user_locked"] = locked
    state["field_versions"] = versions
    state["field_conflicts"] = []
    state["user_reviewed"] = True
    _revalidate_user_draft(state)
    state["revision"] = revision + 1
    state["result_stage"] = "enhanced" if state.get("workflow_status") == "running" else state.get("result_stage", "provisional")
    repository.save(run_id, state)
    repository.append_event(
        run_id,
        "draft_updated",
        {
            "revision": state["revision"],
            "stage": state["result_stage"],
            "cards": state["cards"],
            "source": "user",
        },
    )
    return repository.response(run_id)


async def refine_workflow_with_react(
    run_id: str,
    request: WorkflowReactRequest,
) -> WorkflowRunResponse:
    state = repository.get_state(run_id)
    if state.get("workflow_status") in {"completed", "failed", "cancelled"}:
        raise ValueError(f"workflow is already {state.get('workflow_status')}")
    revision = int(state.get("revision", 0))
    if request.base_revision != revision:
        raise ValueError(f"revision conflict: expected {revision}")
    updates = await refine_state_with_react(
        state,
        instruction=request.instruction,
        selected_card_ids=request.selected_card_ids,
    )
    state.update(updates)
    state["revision"] = revision + 1
    state.update(_provider_snapshot_fields(state))
    repository.save_with_events(
        run_id,
        state,
        _react_events(run_id, state),
    )
    return repository.response(run_id)


def _react_events(run_id: str, state: dict[str, Any]) -> list[tuple[str, dict[str, Any], str | None]]:
    revision = int(state.get("revision", 0))
    session = state.get("react_session") or {}
    snapshot = _event_snapshot(run_id, state)
    events: list[tuple[str, dict[str, Any], str | None]] = [
        (
            "node_started",
            {"node": "react_refiner", "session_id": session.get("id")},
            f"react-node:{session.get('id')}",
        )
    ]
    for index, suggestion in enumerate(state.get("react_suggestions", [])):
        events.append(
            (
                "suggestion_added",
                {
                    "session_id": session.get("id"),
                    "suggestion": suggestion,
                    "source": "react_refiner",
                },
                f"react-suggestion:{session.get('id')}:{index}",
            )
        )
    events.extend(
        [
            (
                "decision_made",
                {
                    "risk_level": state.get("risk_level", "low"),
                    "errors": state.get("validation_errors", []),
                    "overall_confidence": state.get("overall_confidence", 0),
                    "react_session": session.get("id"),
                },
                f"react-decision:{session.get('id')}:{revision}",
            ),
            (
                "draft_updated",
                {
                    "revision": revision,
                    "stage": state.get("result_stage"),
                    "cards": state.get("cards", []),
                    "source": "react_refiner",
                    "snapshot": snapshot,
                },
                f"react-draft:{session.get('id')}:{revision}",
            ),
            (
                "review_required",
                {
                    "revision": revision,
                    "pending_action": state.get("pending_action"),
                    "validation_errors": state.get("validation_errors", []),
                    "snapshot": snapshot,
                },
                f"react-review:{session.get('id')}:{revision}",
            ),
        ]
    )
    return events


def _revalidate_user_draft(state: dict[str, Any]) -> None:
    errors: list[str] = []
    normalized: list[dict[str, Any]] = []
    for index, raw in enumerate(state.get("cards", [])):
        try:
            card = ActionCard(**raw)
        except Exception as error:
            errors.append(f"card[{index}] schema: {error}")
            continue
        if not card.title.strip():
            errors.append(f"card[{index}] missing title")
        if card.title.strip() in {"相关日程", "待办事项", "相关事项", "日程提醒", "行动事项"}:
            errors.append(f"card[{index}] title is too generic")
        if card.card_type == "promise" and not (card.deadline or card.start_time):
            errors.append(f"card[{index}] promise requires execution time")
        locked_fields = set(state.get("user_locked", {}).get(card.id, []))
        unresolved_need_confirm = [
            field for field in card.need_confirm if field not in locked_fields
        ]
        if unresolved_need_confirm:
            errors.append(
                f"card[{index}] unresolved confirmation fields: {unresolved_need_confirm}"
            )
            card = card.model_copy(update={"need_confirm": unresolved_need_confirm})
        elif card.need_confirm:
            card = card.model_copy(update={"need_confirm": []})
        normalized.append(card.model_dump(mode="json"))
    state["cards"] = normalized
    state["validation_errors"] = errors
    graph = dict(state.get("action_graph", {}))
    action_to_card = {
        str(card.get("action_id")): str(card.get("id"))
        for card in normalized
        if card.get("action_id")
    }
    card_by_id = {str(card.get("id")): card for card in normalized}
    conflicts = []
    for conflict in graph.get("conflicts", []):
        item = dict(conflict)
        card_id = action_to_card.get(str(item.get("action_id")))
        field = item.get("field")
        if card_id and field and field in set(state.get("user_locked", {}).get(card_id, [])):
            item["resolved"] = True
            item["resolution"] = card_by_id.get(card_id, {}).get(field)
        conflicts.append(item)
    graph["conflicts"] = conflicts
    state["action_graph"] = graph


def _confirm_workflow_once(
    run_id: str,
    request: ConfirmWorkflowRequest,
    *,
    finalize: bool = True,
) -> WorkflowRunResponse:
    state = repository.get_state(run_id)
    if int(state.get("revision", 0)) != request.revision:
        raise ValueError(f"revision conflict: expected {state.get('revision', 0)}")
    _revalidate_user_draft(state)
    evidence_status = state.get("evidence_status", state.get("ocr_evidence_status", "trusted"))
    if evidence_status == "review_required" or state.get("summary_status") == "blocked":
        raise ValueError("evidence review is required before confirmation")
    if state.get("team_workflow_review", {}).get("required") and not state.get("user_reviewed"):
        raise ValueError("team workflow review is required before confirmation")
    if state.get("validation_errors"):
        raise ValueError(f"draft validation failed: {state['validation_errors']}")
    if not state.get("cards"):
        raise ValueError("draft validation failed: at least one action card is required")
    unresolved_high = [
        conflict
        for conflict in state.get("action_graph", {}).get("conflicts", [])
        if conflict.get("severity") == "high" and not conflict.get("resolved")
    ]
    if unresolved_high:
        raise ValueError(f"unresolved high-risk conflicts: {unresolved_high}")
    failed_constraints = [
        constraint
        for constraint in state.get("action_graph", {}).get("constraints", [])
        if not constraint.get("satisfied", True)
    ]
    if failed_constraints:
        raise ValueError(f"action graph constraints failed: {failed_constraints}")
    verification = state.get("verification_summary", {})
    if verification.get("requires_review") and not any(state.get("user_locked", {}).values()):
        raise ValueError(
            "workflow verification requires a reviewed draft before confirmation"
        )
    low_critical_fields = []
    critical_fields = {"title", "deadline", "start_time", "end_time", "location"}
    for card in state.get("cards", []):
        card_id = str(card.get("id"))
        locked = set(state.get("user_locked", {}).get(card_id, []))
        for field, score in state.get("confidence", {}).get(card_id, {}).items():
            if (
                field in critical_fields
                and card.get(field) not in (None, "", [])
                and float(score) < 0.6
                and field not in locked
            ):
                low_critical_fields.append(f"{card_id}:{field}")
    if low_critical_fields and not state.get("user_reviewed"):
        raise ValueError(f"critical fields require review: {low_critical_fields}")
    if not state.get("action_graph", {}).get("actions") and state.get("cards"):
        state["action_graph"] = create_action_graph(
            state.get("cards", []),
            [],
            state.get("ocr_text", ""),
            state.get("ocr_candidates", []),
        ).model_dump(mode="json")
    if not finalize:
        return repository.response(run_id)
    state["workflow_status"] = "completed"
    state["pending_action"] = None
    state["result_stage"] = "final"
    state["confirmed_revision"] = request.revision
    state["time_to_final_ms"] = state.get("time_to_final_ms") or round(
        (time.time() - float(state.get("started_at", time.time()))) * 1000,
        2,
    )
    repository.save(run_id, state)
    repository.append_event(
        run_id,
        "completed",
        {
            "revision": request.revision,
            "source": "user",
            "snapshot": _event_snapshot(run_id, state),
        },
    )
    cleanup_workflow_input(run_id)
    return repository.response(run_id)


def _persist_phase_transition(
    run_id: str,
    state: dict[str, Any],
    phase: str,
    node: str,
) -> None:
    persisted = repository.get_state(run_id)
    previous = str(persisted.get("workflow_phase", "received"))
    history = list(persisted.get("phase_history", []))
    state["workflow_phase"] = phase
    if previous != phase:
        history.append(
            {
                "phase": phase,
                "node": node,
                "revision": int(state.get("revision", 0)),
                "at": datetime.now(timezone.utc).isoformat(),
            }
        )
    state["phase_history"] = history[-64:]
    repository.save(run_id, state)
    if previous != phase:
        repository.append_event(
            run_id,
            "phase_changed",
            {"from": previous, "phase": phase, "node": node, "revision": int(state.get("revision", 0))},
            f"phase:{phase}:{state.get('revision', 0)}:{node}",
        )


def confirm_effects(run_id: str, request: ConfirmEffectsRequest) -> WorkflowRunResponse:
    state = repository.get_state(run_id)
    commands = dict(state.get("command_ids") or {})
    if not request.effect_types:
        raise ValueError("at least one effect type is required")
    persisted = repository.get_effect_command(run_id, request.idempotency_key)
    if persisted and persisted.get("status") == "completed":
        return repository.response(run_id)
    existing = commands.get(request.idempotency_key)
    if existing and existing.get("status") == "completed":
        return repository.response(run_id)
    if int(state.get("revision", 0)) != request.revision:
        raise ValueError(f"revision conflict: expected {state.get('revision', 0)}")
    selected_cards = set(request.confirmed_card_ids)
    if selected_cards and selected_cards - {str(card.get("id")) for card in state.get("cards", [])}:
        raise ValueError("confirmed_card_ids contains an unknown card")
    selected_tasks = set(request.confirmed_team_task_ids)
    if selected_tasks and selected_tasks - {str(task.get("task_id")) for task in state.get("team_tasks", [])}:
        raise ValueError("confirmed_team_task_ids contains an unknown team task")
    legacy_all = request.idempotency_key.startswith("legacy-confirm:")
    if not selected_cards and legacy_all:
        selected_cards = {str(card.get("id")) for card in state.get("cards", [])}
    if not selected_tasks and legacy_all:
        selected_tasks = {str(task.get("task_id")) for task in state.get("team_tasks", [])}
    effects = [
        {"effect_id": f"{effect_type}:{target_id}", "effect_type": effect_type, "target_id": target_id}
        for effect_type in request.effect_types
        for target_id in sorted(selected_cards if effect_type in {"cards", "reminders"} else selected_tasks)
    ]
    if not effects:
        raise ValueError("no confirmed targets for requested effects")
    if persisted:
        effects = list(persisted.get("effects", effects))
    repository.save_effect_command(run_id, request.idempotency_key, request.revision, "pending", effects)
    state["effect_status"] = "pending_confirmation"
    _persist_phase_transition(run_id, state, "confirmed", "confirm_effects")
    state["command_ids"] = {**commands, request.idempotency_key: {"status": "executing", "effects": request.effect_types}}
    state["effect_status"] = "executing"
    _persist_phase_transition(run_id, state, "effects_executing", "confirm_effects")
    repository.append_event(run_id, "decision_made", {"command_id": request.idempotency_key, "effect_types": request.effect_types}, f"command-start:{request.idempotency_key}")
    try:
        _confirm_workflow_once(
            run_id,
            ConfirmWorkflowRequest(revision=request.revision),
            finalize=False,
        )
        effect_results: list[dict[str, Any]] = []
        card_by_id = {str(card.get("id")): dict(card) for card in state.get("cards", [])}
        task_by_id = {str(task.get("task_id")): dict(task) for task in state.get("team_tasks", [])}
        cards = CardRepository()
        for effect in effects:
            effect_type = effect["effect_type"]
            target_id = effect["target_id"]
            status = "completed"
            detail: dict[str, Any] = {}
            if effect_type == "cards":
                payload = card_by_id[target_id]
                try:
                    cards.get(target_id)
                except KeyError:
                    create_payload = {
                        key: value
                        for key, value in payload.items()
                        if key not in {"created_at", "updated_at"}
                    }
                    create_payload["status"] = "confirmed"
                    cards.create(ActionCardCreate.model_validate(create_payload))
                detail = {"card_id": target_id}
            elif effect_type == "team_tasks":
                task = task_by_id[target_id]
                try:
                    cards.get(target_id)
                except KeyError:
                    cards.create(ActionCardCreate(
                        id=target_id,
                        card_type="task",
                        title=str(task["title"]),
                        deadline=task.get("deadline"),
                        workspace_type="team",
                        workspace_id=str(state.get("workspace_id", "team")),
                        assignee_id=task.get("owner_id"),
                        participant_ids=task.get("participant_ids", []),
                        dependencies=task.get("dependency_ids", []),
                        deliverables=task.get("deliverables", []),
                        acceptance_criteria=[
                            str(item.get("description", ""))
                            for item in task.get("acceptance_criteria", [])
                            if item.get("description")
                        ],
                        evidence_summary=task.get("evidence_refs", []),
                        status="confirmed",
                        source_text=str(state.get("ocr_text", "")),
                    ))
                detail = {"team_task_id": target_id}
            else:
                card = card_by_id[target_id]
                status = "pending_device"
                detail = {
                    "reminder_intent": {
                        "effect_id": effect["effect_id"],
                        "card_id": target_id,
                        "reminders": card.get("reminders", []),
                        "reminder_nodes": card.get("reminder_nodes", []),
                    }
                }
            repository.save_effect(
                run_id, request.idempotency_key, effect["effect_id"],
                effect_type, target_id, status, detail,
            )
            effect_results.append({**effect, "status": status, **detail})
    except Exception:
        state = repository.get_state(run_id)
        state["effect_status"] = "degraded"
        state["workflow_phase"] = "degraded"
        commands = dict(state.get("command_ids") or {})
        commands[request.idempotency_key] = {"status": "degraded", "effects": request.effect_types}
        state["command_ids"] = commands
        _persist_phase_transition(run_id, state, "degraded", "confirm_effects")
        repository.save_effect_command(run_id, request.idempotency_key, request.revision, "degraded", effects, {"error": "effect execution failed"})
        raise
    state = repository.get_state(run_id)
    commands = dict(state.get("command_ids") or {})
    commands[request.idempotency_key] = {"status": "completed", "effects": request.effect_types}
    state["command_ids"] = commands
    state["effect_status"] = "completed"
    state["effect_results"] = effect_results
    state["workflow_status"] = "completed"
    state["pending_action"] = None
    state["result_stage"] = "final"
    state["confirmed_revision"] = request.revision
    state["time_to_final_ms"] = state.get("time_to_final_ms") or round(
        (time.time() - float(state.get("started_at", time.time()))) * 1000,
        2,
    )
    _persist_phase_transition(run_id, state, "completed", "confirm_effects")
    repository.save_effect_command(run_id, request.idempotency_key, request.revision, "completed", effects, {"effects": state["effect_results"]})
    repository.append_event(run_id, "completed", {"command_id": request.idempotency_key, "effects_executed": request.effect_types}, f"command-complete:{request.idempotency_key}")
    cleanup_workflow_input(run_id)
    return repository.response(run_id)


def confirm_workflow(run_id: str, request: ConfirmWorkflowRequest) -> WorkflowRunResponse:
    return confirm_effects(
        run_id,
        ConfirmEffectsRequest(
            revision=request.revision,
            confirmed_card_ids=[],
            confirmed_team_task_ids=[],
            effect_types=["cards", "reminders", "team_tasks"],
            idempotency_key=f"legacy-confirm:{run_id}:{request.revision}",
        ),
    )


async def cancel_workflow(run_id: str) -> WorkflowRunResponse:
    state = repository.get_state(run_id)
    if state.get("workflow_status") in {"completed", "failed", "cancelled"}:
        raise ValueError(f"workflow is already {state.get('workflow_status')}")
    state["workflow_status"] = "cancelled"
    state["pending_action"] = None
    repository.save(run_id, state)
    async with _task_lock:
        task = _tasks.get(run_id)
        if task:
            task.cancel()
    if task is not None and task is not asyncio.current_task():
        await asyncio.gather(task, return_exceptions=True)
    cleanup_workflow_input(run_id)
    return repository.response(run_id)


async def resume_workflow(run_id: str, request: WorkflowResumeRequest) -> WorkflowRunResponse:
    if request.command == "cancel":
        return await cancel_workflow(run_id)
    state = repository.get_state(run_id)
    expected_actions = {
        "provide_ocr_text": "resolve_ocr",
        "review_cards": "review_cards",
        "review_team_plan": "review_team_plan",
    }
    if request.command == "provide_ocr_text" and state.get("pending_action") != "resolve_ocr":
        return submit_ocr_candidate(
            run_id,
            OcrCandidateRequest(
                text=request.ocr_text or "",
                engine="user-corrected",
                confidence=1.0,
            ),
        )
    if state.get("pending_action") != expected_actions.get(request.command):
        raise ValueError(f"workflow is not awaiting {request.command}")
    interrupt_kinds = {
        "provide_ocr_text": "ocr_review",
        "review_cards": "card_review",
        "review_team_plan": "team_review",
    }
    await _wait_for_checkpoint_interrupt(run_id, interrupt_kinds[request.command])
    payload = request.model_dump(mode="json", exclude_none=True)
    await _execute(run_id, Command(resume=payload))
    if request.command == "review_cards":
        updated = repository.response(run_id)
        return confirm_workflow(run_id, ConfirmWorkflowRequest(revision=updated.revision))
    return repository.response(run_id)


async def resolve_ocr_text(
    run_id: str,
    request: OcrCandidateRequest,
) -> WorkflowRunResponse:
    state = repository.get_state(run_id)
    if state.get("workflow_status") != "awaiting_ocr_review":
        raise ValueError("workflow is not awaiting OCR review")
    return await resume_workflow(
        run_id,
        WorkflowResumeRequest(command="provide_ocr_text", ocr_text=request.text.strip()),
    )


async def recover_workflows() -> int:
    recovered = 0
    for run_id, input_path in repository.recoverable_jobs():
        try:
            state = repository.get_state(run_id)
        except KeyError:
            continue
        if input_path:
            state["image_path"] = input_path
        state.pop("_created_at", None)
        state.pop("_updated_at", None)
        state.pop("_error", None)
        await _schedule(run_id, state)
        recovered += 1
    return recovered
