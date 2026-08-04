from __future__ import annotations

import asyncio
import json
import shutil
import uuid
from pathlib import Path

from fastapi import APIRouter, File, Form, Header, HTTPException, UploadFile
from fastapi.responses import StreamingResponse

from app.core.config import settings
from app.repositories.intakes import IntakeRepository
from app.repositories.workflows import WorkflowRepository
from app.schemas.intake import (
    IntakeConfirmRequest,
    IntakeRefineRequest,
    IntakeSessionResponse,
)
from app.schemas.card_refinement import CardRefinementRunResponse, CardRefinementStartPayload
from app.schemas.workflow import OcrCandidateRequest, WorkflowRunResponse
from app.services.document_extractor import extract_document
from app.services.intake_graph import merge_overlapping_lines
from app.services.intake_service import (
    append_intake_attachments,
    confirm_intake,
    get_intake,
    refine_intake,
    start_intake,
)
from app.services.workflow_service import resolve_ocr_text, submit_ocr_candidate

router = APIRouter()
intake_repository = IntakeRepository()
workflow_repository = WorkflowRepository()
MAX_FILES = 8


@router.post("", response_model=IntakeSessionResponse, status_code=202)
async def create_intake(
    text: str = Form(default=""),
    source_kind: str = Form(default="text"),
    workspace_type: str = Form(default="personal"),
    role_template: str = Form(default="action_analyst"),
    profile_context: str = Form(default="{}"),
    files: list[UploadFile] | None = File(default=None),
) -> IntakeSessionResponse:
    if source_kind not in {
        "text",
        "screenshot",
        "long_screenshot",
        "chat",
        "document",
        "mixed",
    }:
        raise HTTPException(status_code=422, detail="unsupported source_kind")
    if workspace_type not in {"personal", "team"}:
        raise HTTPException(status_code=422, detail="unsupported workspace_type")
    if role_template not in {
        "action_analyst",
        "personal_planner",
        "team_coordinator",
    }:
        raise HTTPException(status_code=422, detail="unsupported role_template")
    try:
        profile = json.loads(profile_context or "{}")
        if not isinstance(profile, dict):
            raise ValueError
    except (json.JSONDecodeError, ValueError) as error:
        raise HTTPException(status_code=422, detail="invalid profile_context") from error

    uploads = files or []
    if len(uploads) > MAX_FILES:
        raise HTTPException(status_code=413, detail=f"最多上传 {MAX_FILES} 个文件")
    staging = Path(settings.workflow_input_directory) / "intakes" / uuid.uuid4().hex
    extracted_texts: list[str] = []
    warnings: list[str] = []
    total = 0
    try:
        if uploads:
            staging.mkdir(parents=True, exist_ok=False)
        for upload in uploads:
            data = await _read_file(upload)
            total += len(data)
            if total > settings.max_refinement_total_bytes:
                raise HTTPException(status_code=413, detail="附件总大小超过限制")
            name = Path(upload.filename or "attachment").name
            path = staging / f"{uuid.uuid4().hex}{Path(name).suffix[:12]}"
            path.write_bytes(data)
            extracted = await extract_document(
                path,
                name=name,
                declared_mime=upload.content_type or "application/octet-stream",
                attachment_id=uuid.uuid4().hex,
            )
            if extracted.text:
                extracted_texts.append(extracted.text)
            if extracted.descriptor.warning:
                warnings.append(f"{name}: {extracted.descriptor.warning}")
        canonical = merge_overlapping_lines(
            "\n\n".join(value for value in [text, *extracted_texts] if value.strip())
        )
        if not canonical:
            raise HTTPException(status_code=422, detail="未提取到可分析内容")
        return await start_intake(
            text=canonical,
            source_kind=source_kind,
            workspace_type=workspace_type,
            profile_context=profile,
            role_template=role_template,
            warnings=warnings,
        )
    finally:
        shutil.rmtree(staging, ignore_errors=True)


@router.get("/{session_id}", response_model=IntakeSessionResponse)
def read_intake(session_id: str) -> IntakeSessionResponse:
    try:
        return get_intake(session_id)
    except KeyError as error:
        raise HTTPException(status_code=404, detail="intake session not found") from error


@router.get("/{session_id}/events")
async def stream_intake_events(
    session_id: str,
    last_event_id: int | None = Header(default=None, alias="Last-Event-ID"),
) -> StreamingResponse:
    try:
        state = intake_repository.get(session_id)
    except KeyError as error:
        raise HTTPException(status_code=404, detail="intake session not found") from error
    run_id = state.get("workflow_run_id")

    async def generate():
        if not run_id:
            snapshot = get_intake(session_id).model_dump(mode="json")
            yield f"id: 1\nevent: completed\ndata: {json.dumps(snapshot, ensure_ascii=False)}\n\n"
            return
        cursor = last_event_id or 0
        while True:
            events = workflow_repository.events_after(run_id, cursor)
            for event in events:
                cursor = event.id
                yield (
                    f"id: {event.id}\n"
                    f"event: {event.event}\n"
                    f"data: {json.dumps(event.data, ensure_ascii=False, default=str)}\n\n"
                )
            status = workflow_repository.get_status(run_id)
            if status in {"awaiting_ocr_review", "awaiting_review", "completed", "failed", "cancelled"} and not events:
                break
            await asyncio.to_thread(workflow_repository.wait_for_events, 15.0)

    return StreamingResponse(
        generate(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


@router.post("/{session_id}/ocr-candidates", response_model=WorkflowRunResponse)
def add_intake_ocr_candidate(
    session_id: str,
    request: OcrCandidateRequest,
) -> WorkflowRunResponse:
    try:
        state = intake_repository.get(session_id)
        run_id = state.get("workflow_run_id")
        if not run_id:
            raise ValueError("intake has no active image workflow")
        return submit_ocr_candidate(run_id, request)
    except KeyError as error:
        raise HTTPException(status_code=404, detail="intake session not found") from error
    except ValueError as error:
        raise HTTPException(status_code=409, detail=str(error)) from error


@router.post("/{session_id}/resolve-ocr", response_model=WorkflowRunResponse)
async def resolve_intake_ocr(
    session_id: str,
    request: OcrCandidateRequest,
) -> WorkflowRunResponse:
    try:
        state = intake_repository.get(session_id)
        run_id = state.get("workflow_run_id")
        if not run_id:
            raise ValueError("intake has no active image workflow")
        return await resolve_ocr_text(run_id, request)
    except KeyError as error:
        raise HTTPException(status_code=404, detail="intake session not found") from error
    except ValueError as error:
        raise HTTPException(status_code=409, detail=str(error)) from error


@router.post("/{session_id}/attachments", response_model=IntakeSessionResponse)
async def add_intake_attachments(
    session_id: str,
    files: list[UploadFile] = File(...),
) -> IntakeSessionResponse:
    if not files or len(files) > MAX_FILES:
        raise HTTPException(status_code=413, detail=f"最多上传 {MAX_FILES} 个文件")
    try:
        intake_repository.get(session_id)
    except KeyError as error:
        raise HTTPException(status_code=404, detail="intake session not found") from error
    staging = Path(settings.workflow_input_directory) / "intakes" / uuid.uuid4().hex
    descriptors = []
    extracted_texts: list[str] = []
    warnings: list[str] = []
    total = 0
    try:
        staging.mkdir(parents=True, exist_ok=False)
        for upload in files:
            data = await _read_file(upload)
            total += len(data)
            if total > settings.max_refinement_total_bytes:
                raise HTTPException(status_code=413, detail="附件总大小超过限制")
            name = Path(upload.filename or "attachment").name
            attachment_id = uuid.uuid4().hex
            path = staging / f"{attachment_id}{Path(name).suffix[:12]}"
            path.write_bytes(data)
            extracted = await extract_document(
                path,
                name=name,
                declared_mime=upload.content_type or "application/octet-stream",
                attachment_id=attachment_id,
            )
            descriptors.append(extracted.descriptor)
            if extracted.text:
                extracted_texts.append(extracted.text)
            if extracted.descriptor.warning:
                warnings.append(f"{name}: {extracted.descriptor.warning}")
        return append_intake_attachments(session_id, descriptors, extracted_texts, warnings)
    finally:
        shutil.rmtree(staging, ignore_errors=True)


@router.post("/{session_id}/refine", response_model=CardRefinementRunResponse, status_code=202)
async def start_intake_refinement(
    session_id: str,
    request: IntakeRefineRequest,
) -> CardRefinementRunResponse:
    try:
        state = intake_repository.get(session_id)
        card = next(
            (item for item in state.get("cards", []) if item.get("id") == request.card_id),
            None,
        )
        if card is None:
            raise ValueError("selected card is not part of this intake")
        return await refine_intake(
            session_id,
            CardRefinementStartPayload(
                card=card,
                options=request.options,
                profile_context=request.profile_context,
                instruction=request.instruction,
            ),
        )
    except KeyError as error:
        raise HTTPException(status_code=404, detail="intake session not found") from error
    except ValueError as error:
        raise HTTPException(status_code=409, detail=str(error)) from error


@router.post("/{session_id}/confirm", response_model=IntakeSessionResponse)
def confirm_intake_session(
    session_id: str,
    request: IntakeConfirmRequest,
) -> IntakeSessionResponse:
    try:
        return confirm_intake(session_id, request.revision, request.selected_card_ids)
    except KeyError as error:
        raise HTTPException(status_code=404, detail="intake session not found") from error
    except ValueError as error:
        raise HTTPException(status_code=409, detail=str(error)) from error


async def _read_file(upload: UploadFile) -> bytes:
    content = bytearray()
    while True:
        chunk = await upload.read(1024 * 1024)
        if not chunk:
            break
        content.extend(chunk)
        if len(content) > settings.max_refinement_file_bytes:
            raise HTTPException(status_code=413, detail=f"{upload.filename} 超过单文件限制")
    if not content:
        raise HTTPException(status_code=400, detail=f"{upload.filename} 为空")
    return bytes(content)
