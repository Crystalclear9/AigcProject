from __future__ import annotations

import asyncio
import hashlib
import shutil
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from app.core.config import settings
from app.repositories.workflows import WorkflowRepository
from app.schemas.card_refinement import (
    AttachmentDescriptor,
    CardRefinementConfirmRequest,
    CardRefinementPlan,
    CardRefinementReactRequest,
    CardRefinementRunResponse,
    CardRefinementStartPayload,
    PlanItem,
    UserProfileContext,
)
from app.services.card_refinement_graph import (
    build_card_refinement_graph,
    deterministic_plan,
    validate_plan,
)
from app.services.document_extractor import ExtractedDocument, extract_document
from app.services.llm_client import structured_completion
from app.services.prompt_envelope import compile_profile_policy, render_system_prompt
from app.services.provider_runtime import provider_usage_delta, runtime

repository = WorkflowRepository()
_graph = build_card_refinement_graph()
_tasks: dict[str, asyncio.Task[None]] = {}
_task_lock = asyncio.Lock()


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _response(run_id: str) -> CardRefinementRunResponse:
    state = repository.get_state(run_id)
    usage = state.get("provider_usage") or provider_usage_delta(
        state.get("provider_usage_baseline")
    )
    model_attempts = usage.get("fast_model", {})
    if not settings.has_fast_model_config:
        model_status = "not_configured"
    elif int(model_attempts.get("success_count_delta", 0)) > 0:
        model_status = "succeeded"
    elif int(model_attempts.get("request_count_delta", 0)) > 0:
        model_status = "degraded"
    else:
        model_status = "attempted"
    return CardRefinementRunResponse(
        run_id=run_id,
        trace_id=run_id,
        status=state.get("workflow_status", "failed"),
        pending_action=state.get("pending_action"),
        plan=state.get("plan"),
        attachments=state.get("attachments", []),
        warnings=state.get("warnings", []),
        validation_errors=state.get("validation_errors", []),
        provider_usage=usage,
        model_enhancement_status=state.get("model_enhancement_status", model_status),
        revision=int(state.get("revision", 0)),
        created_at=state["_created_at"],
        updated_at=state["_updated_at"],
        error=state["_error"],
    )


async def start_card_refinement(
    payload: CardRefinementStartPayload,
    staged_files: list[tuple[Path, str, str, str]],
) -> CardRefinementRunResponse:
    run_id = str(uuid.uuid4())
    descriptors = [
        AttachmentDescriptor(
            id=attachment_id,
            name=name,
            mime_type=mime_type or "application/octet-stream",
            size_bytes=path.stat().st_size,
            sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
        ).model_dump(mode="json")
        for path, name, mime_type, attachment_id in staged_files
    ]
    state: dict[str, Any] = {
        "run_id": run_id,
        "run_kind": "card_refinement",
        "input_kind": "card_refinement",
        "workflow_status": "queued",
        "pending_action": None,
        "card": payload.card.model_dump(mode="json"),
        "options": payload.options.model_dump(mode="json"),
        "profile_context": (
            payload.profile_context.model_dump(mode="json")
            if payload.profile_context
            else None
        ),
        "instruction": payload.instruction,
        "attachments": descriptors,
        "plan": None,
        "warnings": [],
        "validation_errors": [],
        "revision": 0,
        "provider_usage_baseline": runtime.snapshot(),
    }
    repository.create_run(run_id, state)
    task = asyncio.create_task(
        _run_refinement(run_id, payload, staged_files),
        name=f"card-refinement:{run_id}",
    )
    async with _task_lock:
        _tasks[run_id] = task
    task.add_done_callback(lambda _: asyncio.create_task(_forget_task(run_id)))
    return _response(run_id)


async def _forget_task(run_id: str) -> None:
    async with _task_lock:
        _tasks.pop(run_id, None)


async def _run_refinement(
    run_id: str,
    payload: CardRefinementStartPayload,
    staged_files: list[tuple[Path, str, str, str]],
) -> None:
    state = repository.get_state(run_id)
    state["workflow_status"] = "running"
    repository.save_with_events(
        run_id,
        state,
        [("node_started", {"node": "attachment_extraction"}, "refinement-extraction-start")],
    )
    extracted: list[ExtractedDocument] = []
    try:
        results = await asyncio.gather(
            *[
                extract_document(
                    path,
                    name=name,
                    declared_mime=mime_type,
                    attachment_id=attachment_id,
                )
                for path, name, mime_type, attachment_id in staged_files
            ]
        )
        extracted.extend(results)
        for item in extracted:
            repository.append_event(
                run_id,
                "attachment_extracted",
                item.descriptor.model_dump(mode="json"),
                f"attachment:{item.descriptor.id}:{item.descriptor.extraction_status}",
            )
        graph_state = await _graph.ainvoke(
            {
                "run_id": run_id,
                "card": payload.card.model_dump(mode="json"),
                "options": payload.options.model_dump(mode="json"),
                "profile_context": (
                    payload.profile_context.model_dump(mode="json")
                    if payload.profile_context
                    else None
                ),
                "instruction": payload.instruction,
                "documents": [
                    {"name": item.descriptor.name, "text": item.text}
                    for item in extracted
                    if item.text
                ],
                "warnings": [
                    item.descriptor.warning
                    for item in extracted
                    if item.descriptor.warning
                ],
            }
        )
        plan = CardRefinementPlan(**graph_state["plan"])
        state = repository.get_state(run_id)
        state.update(
            {
                "workflow_status": "awaiting_review",
                "pending_action": "review_plan",
                "attachments": [
                    item.descriptor.model_dump(mode="json") for item in extracted
                ],
                "plan": plan.model_dump(mode="json"),
                "warnings": graph_state.get("warnings", []),
                "validation_errors": graph_state.get("validation_errors", []),
                "revision": plan.revision,
                "provider_usage": provider_usage_delta(
                    state.get("provider_usage_baseline")
                ),
            }
        )
        repository.save_with_events(
            run_id,
            state,
            [
                (
                    "plan_draft_created",
                    {"revision": plan.revision, "item_count": len(plan.items)},
                    f"plan-draft:{plan.revision}",
                ),
                (
                    "review_required",
                    {"revision": plan.revision, "snapshot": _safe_snapshot(state)},
                    f"plan-review:{plan.revision}",
                ),
            ],
        )
    except asyncio.CancelledError:
        state = repository.get_state(run_id)
        state.update(
            {
                "workflow_status": "cancelled",
                "pending_action": None,
                "provider_usage": provider_usage_delta(
                    state.get("provider_usage_baseline")
                ),
            }
        )
        repository.save(run_id, state)
        raise
    except Exception as error:
        state = repository.get_state(run_id)
        state.update(
            {
                "workflow_status": "failed",
                "pending_action": None,
                "provider_usage": provider_usage_delta(
                    state.get("provider_usage_baseline")
                ),
            }
        )
        repository.save_with_events(
            run_id,
            state,
            [
                (
                    "failed",
                    {"error_type": type(error).__name__},
                    "refinement-failed",
                )
            ],
            error=f"{type(error).__name__}: {error}",
        )
    finally:
        for path, *_ in staged_files:
            path.unlink(missing_ok=True)
        directories = {path.parent for path, *_ in staged_files}
        for directory in directories:
            shutil.rmtree(directory, ignore_errors=True)


def get_card_refinement(run_id: str) -> CardRefinementRunResponse:
    _require_refinement(run_id)
    return _response(run_id)


async def react_card_refinement(
    run_id: str,
    request: CardRefinementReactRequest,
) -> CardRefinementRunResponse:
    state = _require_refinement(run_id)
    if state.get("workflow_status") != "awaiting_review":
        raise ValueError("card refinement is not awaiting review")
    if int(state.get("revision", 0)) != request.base_revision:
        raise ValueError("revision conflict")
    if not request.selected_item_ids:
        raise ValueError("selected_item_ids is required")
    plan = CardRefinementPlan(**state["plan"])
    selected = set(request.selected_item_ids)
    known = {item.id for item in plan.items}
    if selected - known:
        raise ValueError("selected plan item not found")
    refined = await _refine_selected_items(
        plan,
        selected,
        request.instruction,
        state,
    )
    errors = validate_plan(refined, payload_card(state), payload_profile(state))
    if errors:
        raise ValueError("; ".join(errors))
    revision = int(state["revision"]) + 1
    refined = refined.model_copy(update={"revision": revision})
    state.update(
        {
            "plan": refined.model_dump(mode="json"),
            "revision": revision,
            "validation_errors": [],
            "provider_usage": provider_usage_delta(
                state.get("provider_usage_baseline")
            ),
        }
    )
    repository.save_with_events(
        run_id,
        state,
        [
            (
                "plan_updated",
                {"revision": revision, "item_count": len(refined.items)},
                f"plan-react:{revision}",
            ),
            (
                "review_required",
                {"revision": revision, "snapshot": _safe_snapshot(state)},
                f"plan-review:{revision}",
            ),
        ],
    )
    return _response(run_id)


def confirm_card_refinement(
    run_id: str,
    request: CardRefinementConfirmRequest,
) -> CardRefinementRunResponse:
    state = _require_refinement(run_id)
    if state.get("workflow_status") != "awaiting_review":
        raise ValueError("card refinement is not awaiting review")
    if int(state.get("revision", 0)) != request.revision:
        raise ValueError("revision conflict")
    current = CardRefinementPlan(**state["plan"])
    if request.items is not None:
        selected_items = request.items
    else:
        selected = set(request.selected_item_ids)
        selected_items = [item for item in current.items if item.id in selected]
    if not selected_items:
        raise ValueError("at least one plan item must be selected")
    accepted = current.model_copy(
        update={
            "items": [
                item.model_copy(update={"status": "accepted"})
                for item in selected_items
            ],
            "status": "accepted",
        }
    )
    errors = validate_plan(accepted, payload_card(state), payload_profile(state))
    if errors:
        raise ValueError("; ".join(errors))
    state.update(
        {
            "workflow_status": "completed",
            "pending_action": None,
            "plan": accepted.model_dump(mode="json"),
            "provider_usage": provider_usage_delta(
                state.get("provider_usage_baseline")
            ),
        }
    )
    repository.save_with_events(
        run_id,
        state,
        [
            (
                "completed",
                {"revision": request.revision, "snapshot": _safe_snapshot(state)},
                f"plan-completed:{request.revision}",
            )
        ],
    )
    return _response(run_id)


async def cancel_card_refinement(run_id: str) -> CardRefinementRunResponse:
    state = _require_refinement(run_id)
    if state.get("workflow_status") in {"completed", "failed", "cancelled"}:
        return _response(run_id)
    async with _task_lock:
        task = _tasks.get(run_id)
    if task and not task.done():
        task.cancel()
        await asyncio.gather(task, return_exceptions=True)
    state = repository.get_state(run_id)
    state.update({"workflow_status": "cancelled", "pending_action": None})
    repository.save_with_events(
        run_id,
        state,
        [("cancelled", {}, "refinement-cancelled")],
    )
    return _response(run_id)


def _require_refinement(run_id: str) -> dict[str, Any]:
    state = repository.get_state(run_id)
    if state.get("run_kind") != "card_refinement":
        raise KeyError(run_id)
    return state


def payload_card(state: dict[str, Any]):
    from app.schemas.card import ActionCard

    return ActionCard(**state["card"])


def payload_profile(state: dict[str, Any]) -> UserProfileContext | None:
    payload = state.get("profile_context")
    return UserProfileContext(**payload) if payload else None


async def _refine_selected_items(
    plan: CardRefinementPlan,
    selected: set[str],
    instruction: str,
    state: dict[str, Any],
) -> CardRefinementPlan:
    if settings.has_fast_model_config:
        try:
            card = payload_card(state)
            profile = payload_profile(state)
            envelope = compile_profile_policy(
                "team_coordinator" if card.workspace_type == "team" else "personal_planner",
                profile,
            )
            item_schema = PlanItem.model_json_schema()
            item_schema["additionalProperties"] = False
            result = await structured_completion(
                "fast_model",
                system_prompt=(
                    render_system_prompt(envelope)
                    + "\n只修改 selected_items。保持 id，不改父卡事实字段；可以新增 parent_id "
                    "指向已选项目的 step。只输出契约规定的完整 items 数组。"
                ),
                input_payload={
                    "instruction": instruction,
                    "selected_items": [
                        item.model_dump(mode="json")
                        for item in plan.items
                        if item.id in selected
                    ],
                    "parent_card": state["card"],
                    "evidence": plan.evidence_summary,
                },
                schema_name="refined_plan_items",
                schema={
                    "type": "object",
                    "properties": {
                        "items": {
                            "type": "array",
                            "items": item_schema,
                        }
                    },
                    "required": ["items"],
                    "additionalProperties": False,
                },
                max_tokens=2200,
            )
            replacements = {
                str(item.get("id")): item
                for item in result.get("items", [])
                if isinstance(item, dict) and item.get("id") in selected
            }
            merged = [
                PlanItem(**{**item.model_dump(), **replacements[item.id]})
                if item.id in replacements
                else item
                for item in plan.items
            ]
            return plan.model_copy(
                update={
                    "items": merged,
                    "generated_by": f"{plan.generated_by}+react",
                }
            )
        except Exception:
            pass
    merged: list[PlanItem] = []
    for item in plan.items:
        if item.id not in selected:
            merged.append(item)
            continue
        suffix = instruction.strip()
        description = item.description
        if suffix and suffix not in description:
            description = f"{description}\n调整要求：{suffix}".strip()
        merged.append(
            item.model_copy(
                update={
                    "description": description,
                    "need_confirm": list(dict.fromkeys([*item.need_confirm, "已按本地规则调整，请复核"])),
                }
            )
        )
    return plan.model_copy(
        update={"items": merged, "generated_by": f"{plan.generated_by}+local_react"}
    )


def _safe_snapshot(state: dict[str, Any]) -> dict[str, Any]:
    return {
        "status": state.get("workflow_status"),
        "pending_action": state.get("pending_action"),
        "revision": state.get("revision", 0),
        "plan": state.get("plan"),
        "attachments": state.get("attachments", []),
        "warnings": state.get("warnings", []),
        "validation_errors": state.get("validation_errors", []),
        "provider_usage": state.get("provider_usage", {}),
    }


def local_refinement_fallback(payload: CardRefinementStartPayload) -> CardRefinementPlan:
    return deterministic_plan(
        payload.card,
        options=payload.options,
        profile=payload.profile_context if payload.options.use_profile else None,
        evidence_summary=payload.card.evidence_summary,
        instruction=payload.instruction,
    )
