from __future__ import annotations

import asyncio
import json
import shutil
import uuid
from pathlib import Path

from fastapi import APIRouter, File, Form, Header, HTTPException, Response, UploadFile
from fastapi.responses import StreamingResponse
from pydantic import ValidationError

from app.core.config import settings
from app.repositories.workflows import WorkflowRepository
from app.schemas.card_refinement import (
    CardRefinementConfirmRequest,
    CardRefinementReactRequest,
    CardRefinementRunResponse,
    CardRefinementStartPayload,
    RefinementOptions,
    UserProfileContext,
)
from app.schemas.card import ActionCard
from app.services.card_refinement_service import (
    cancel_card_refinement,
    confirm_card_refinement,
    get_card_refinement,
    react_card_refinement,
    start_card_refinement,
)

router = APIRouter()
repository = WorkflowRepository()
MAX_FILES = 8


@router.post("", response_model=CardRefinementRunResponse, status_code=202)
async def start_refinement(
    response: Response,
    card: str = Form(...),
    options: str = Form(default="{}"),
    profile_context: str | None = Form(default=None),
    instruction: str = Form(default=""),
    files: list[UploadFile] | None = File(default=None),
) -> CardRefinementRunResponse:
    uploads = files or []
    if len(uploads) > MAX_FILES:
        raise HTTPException(status_code=413, detail=f"最多上传 {MAX_FILES} 个文件")
    try:
        payload = CardRefinementStartPayload(
            card=ActionCard.model_validate_json(card),
            options=RefinementOptions.model_validate_json(options),
            profile_context=(
                UserProfileContext.model_validate_json(profile_context)
                if profile_context
                else None
            ),
            instruction=instruction,
        )
    except ValidationError as error:
        raise HTTPException(status_code=422, detail=error.errors()) from error

    staging_directory = (
        Path(settings.workflow_input_directory)
        / "card_refinements"
        / uuid.uuid4().hex
    )
    staged: list[tuple[Path, str, str, str]] = []
    total = 0
    try:
        if uploads:
            staging_directory.mkdir(parents=True, exist_ok=False)
        for upload in uploads:
            original_name = Path(upload.filename or "attachment").name
            if not original_name or original_name in {".", ".."}:
                original_name = "attachment"
            content = await _read_attachment(upload)
            total += len(content)
            if total > settings.max_refinement_total_bytes:
                raise HTTPException(status_code=413, detail="附件总大小超过限制")
            attachment_id = str(uuid.uuid4())
            suffix = Path(original_name).suffix[:12]
            path = staging_directory / f"{attachment_id}{suffix}"
            path.write_bytes(content)
            staged.append(
                (
                    path,
                    original_name,
                    upload.content_type or "application/octet-stream",
                    attachment_id,
                )
            )
        result = await start_card_refinement(payload, staged)
    except Exception:
        shutil.rmtree(staging_directory, ignore_errors=True)
        raise
    response.headers["Location"] = f"/api/card-refinements/{result.run_id}"
    return result


@router.get("/{run_id}", response_model=CardRefinementRunResponse)
def get_refinement(run_id: str) -> CardRefinementRunResponse:
    try:
        return get_card_refinement(run_id)
    except KeyError as error:
        raise HTTPException(status_code=404, detail="card refinement not found") from error


@router.get("/{run_id}/events")
async def stream_refinement_events(
    run_id: str,
    last_event_id: int | None = Header(default=None, alias="Last-Event-ID"),
) -> StreamingResponse:
    try:
        get_card_refinement(run_id)
    except KeyError as error:
        raise HTTPException(status_code=404, detail="card refinement not found") from error

    async def generate():
        cursor = last_event_id or 0
        heartbeat_at = asyncio.get_running_loop().time()
        while True:
            events = repository.events_after(run_id, cursor)
            for event in events:
                cursor = event.id
                yield (
                    "retry: 1000\n"
                    f"id: {event.id}\n"
                    f"event: {event.event}\n"
                    f"data: {json.dumps(event.data, ensure_ascii=False, default=str)}\n\n"
                )
            state = repository.get_state(run_id)
            if state.get("workflow_status") in {
                "awaiting_review",
                "completed",
                "failed",
                "cancelled",
            } and not events:
                break
            now = asyncio.get_running_loop().time()
            if now - heartbeat_at >= 15:
                heartbeat_at = now
                yield ": heartbeat\n\n"
            await asyncio.to_thread(
                repository.wait_for_events,
                min(15.0, max(0.1, 15 - (now - heartbeat_at))),
            )

    return StreamingResponse(
        generate(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


@router.post("/{run_id}/react", response_model=CardRefinementRunResponse)
async def react_refinement(
    run_id: str,
    request: CardRefinementReactRequest,
) -> CardRefinementRunResponse:
    try:
        return await react_card_refinement(run_id, request)
    except KeyError as error:
        raise HTTPException(status_code=404, detail="card refinement not found") from error
    except ValueError as error:
        raise HTTPException(status_code=409, detail=str(error)) from error


@router.post("/{run_id}/confirm", response_model=CardRefinementRunResponse)
def confirm_refinement(
    run_id: str,
    request: CardRefinementConfirmRequest,
) -> CardRefinementRunResponse:
    try:
        return confirm_card_refinement(run_id, request)
    except KeyError as error:
        raise HTTPException(status_code=404, detail="card refinement not found") from error
    except ValueError as error:
        raise HTTPException(status_code=409, detail=str(error)) from error


@router.delete("/{run_id}", response_model=CardRefinementRunResponse)
async def cancel_refinement(run_id: str) -> CardRefinementRunResponse:
    try:
        return await cancel_card_refinement(run_id)
    except KeyError as error:
        raise HTTPException(status_code=404, detail="card refinement not found") from error


async def _read_attachment(upload: UploadFile) -> bytes:
    if upload.size is not None and upload.size > settings.max_refinement_file_bytes:
        raise HTTPException(status_code=413, detail=f"{upload.filename or '附件'} 超过单文件限制")
    content = bytearray()
    while True:
        remaining = settings.max_refinement_file_bytes - len(content)
        chunk = await upload.read(min(1024 * 1024, remaining + 1))
        if not chunk:
            break
        content.extend(chunk)
        if len(content) > settings.max_refinement_file_bytes:
            raise HTTPException(status_code=413, detail=f"{upload.filename or '附件'} 超过单文件限制")
    if not content:
        raise HTTPException(status_code=400, detail=f"{upload.filename or '附件'} 为空")
    return bytes(content)

