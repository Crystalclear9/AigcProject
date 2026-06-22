from __future__ import annotations

import asyncio
import hashlib
import logging
import time
import uuid
from pathlib import Path
from typing import Any

from app.core.config import settings
from app.repositories.workflows import WorkflowRepository
from app.schemas.workflow import (
    ConfirmWorkflowRequest,
    DraftPatchRequest,
    OcrCandidateRequest,
    WorkflowReactRequest,
    WorkflowResumeRequest,
    WorkflowRunResponse,
)
from app.schemas.card import ActionCard
from app.services.provider_runtime import provider_usage_delta, runtime
from app.services.react_refiner import refine_state_with_react
from app.services.workflow_graph import build_workflow_graph, create_rule_draft, finalize_rules_fast
from app.services.workflow_agents import build_action_graph as create_action_graph

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


def _config(run_id: str) -> dict[str, Any]:
    return {"configurable": {"thread_id": run_id}}


async def _execute(run_id: str, initial: dict[str, Any], preclaimed: bool = False) -> None:
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
            runtime_state = dict(initial)
            heartbeat_at = time.monotonic()
            async for chunk in graph.astream(
                initial,
                _config(run_id),
                stream_mode="updates",
                durability="exit",
            ):
                for node, updates in chunk.items():
                    if not updates:
                        continue
                    _merge_runtime_state(runtime_state, dict(updates))
                    should_persist = node in {
                        "recognize_image",
                        "create_rule_draft",
                        "build_action_graph",
                        "project_cards",
                        "require_review",
                        "run_agent_task",
                        "task_barrier",
                        "verify_workflow",
                        "replan",
                        "finalize_rules_fast",
                    }
                    if node == "supervisor" and runtime_state.get("active_agents"):
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
        if status in {"queued", "running"}:
            repository.release_job(run_id, _worker_id)
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
    state.update(_provider_snapshot_fields(state))
    state.pop("image_bytes", None)
    events: list[tuple[str, dict[str, Any], str | None]] = []
    revision = int(state.get("revision", 0))
    event_key = f"{node}:{revision}:{len(state.get('node_trace', []))}"
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
    return {
        "run_id": run_id,
        "trace_id": run_id,
        "workflow_status": state.get("workflow_status", "running"),
        "pending_action": state.get("pending_action"),
        "ocr_text": state.get("ocr_text", ""),
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


async def _schedule(run_id: str, initial: dict[str, Any], preclaimed: bool = False) -> None:
    _ensure_loop_runtime()
    async with _task_lock:
        task = asyncio.create_task(
            _execute(run_id, initial, preclaimed=preclaimed),
            name=f"workflow-{run_id}",
        )
        _tasks[run_id] = task


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
    final_state.update(_provider_snapshot_fields(final_state))
    revision = int(final_state.get("revision", 0))
    snapshot = _event_snapshot(run_id, final_state)
    graph = final_state.get("action_graph", {})
    events = [
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
) -> dict[str, Any]:
    return {
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
        "pending_action": None,
        "revision": 0,
        "result_stage": "provisional",
        "overall_confidence": 0,
        "route": "rules",
        "cache_status": "bypass",
        "user_locked": {},
        "suggestions": {},
        "ocr_candidates": [],
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


async def start_text_workflow(text: str, screenshot_time: str | None = None) -> WorkflowRunResponse:
    run_id = str(uuid.uuid4())
    initial = _initial_state(run_id, "text", text=text, screenshot_time=screenshot_time)
    primed_state = {
        **initial,
        "ocr_text": text.strip(),
        "ocr_engine": "provided-text",
        "ocr_quality": 1.0,
        "ocr_candidates": [{"text": text.strip(), "engine": "provided-text", "confidence": 1.0}],
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


async def start_image_workflow(image_bytes: bytes, screenshot_time: str | None = None) -> WorkflowRunResponse:
    run_id = str(uuid.uuid4())
    input_dir = Path(settings.workflow_input_directory)
    input_dir.mkdir(parents=True, exist_ok=True)
    image_path = input_dir / f"{run_id}.bin"
    image_path.write_bytes(image_bytes)
    initial = _initial_state(run_id, "image", image_bytes=image_bytes, screenshot_time=screenshot_time)
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
        if response.workflow_status in {"completed", "awaiting_review", "failed", "cancelled"}:
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
    state["ocr_candidates"] = candidates[-6:]
    conflict = _ocr_candidate_conflict(candidates)
    if conflict:
        state["review_requested"] = True
        state["warnings"] = list(dict.fromkeys(state.get("warnings", []) + [conflict]))
        if state.get("workflow_status") == "awaiting_review":
            state["pending_action"] = "review_cards"
    repository.save(run_id, state)
    repository.append_event(
        run_id,
        "ocr_candidate",
        {
            "engine": request.engine,
            "confidence": request.confidence,
            "candidate_count": len(candidates),
            "conflict": conflict,
        },
        f"ocr:{request.engine}:{hashlib.sha1(request.text.strip().encode('utf-8')).hexdigest()[:16]}",
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
    if current is None or float(incoming.get("confidence", 0)) > float(current.get("confidence", 0)):
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


def confirm_workflow(run_id: str, request: ConfirmWorkflowRequest) -> WorkflowRunResponse:
    state = repository.get_state(run_id)
    if int(state.get("revision", 0)) != request.revision:
        raise ValueError(f"revision conflict: expected {state.get('revision', 0)}")
    _revalidate_user_draft(state)
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
    return repository.response(run_id)


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
    return repository.response(run_id)


async def resume_workflow(run_id: str, request: WorkflowResumeRequest) -> WorkflowRunResponse:
    if request.command == "cancel":
        return await cancel_workflow(run_id)
    if request.command == "provide_ocr_text":
        return submit_ocr_candidate(
            run_id,
            OcrCandidateRequest(text=request.ocr_text or "", engine="mlkit", confidence=0.8),
        )
    state = repository.get_state(run_id)
    patch = DraftPatchRequest(
        base_revision=int(state.get("revision", 0)),
        cards=request.cards or [],
        locked_fields={
            card.id: [
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
            ]
            for card in request.cards or []
        },
    )
    updated = patch_draft(run_id, patch)
    return confirm_workflow(run_id, ConfirmWorkflowRequest(revision=updated.revision))


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
