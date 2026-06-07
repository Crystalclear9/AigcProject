from __future__ import annotations

import asyncio
import json

from fastapi import APIRouter, File, Form, Header, HTTPException, Response, UploadFile
from fastapi.responses import StreamingResponse

from app.core.config import settings
from app.schemas.workflow import (
    WorkflowResumeRequest,
    WorkflowRunResponse,
    WorkflowStartTextRequest,
    OcrCandidateRequest,
    DraftPatchRequest,
    ConfirmWorkflowRequest,
)
from app.services.workflow_service import (
    confirm_workflow,
    get_workflow,
    patch_draft,
    resume_workflow,
    start_image_workflow,
    start_text_workflow,
    submit_ocr_candidate,
)
from app.repositories.workflows import WorkflowRepository

router = APIRouter()


@router.post("/screenshot-text", response_model=WorkflowRunResponse, status_code=202)
async def start_text(request: WorkflowStartTextRequest, response: Response) -> WorkflowRunResponse:
    result = await start_text_workflow(request.text, request.screenshot_time)
    response.headers["Location"] = f"/api/workflows/{result.run_id}"
    return result


@router.post("/screenshot-image", response_model=WorkflowRunResponse, status_code=202)
async def start_image(
    response: Response,
    image: UploadFile = File(...),
    screenshot_time: str | None = Form(default=None),
) -> WorkflowRunResponse:
    content_type = (image.content_type or "").lower()
    if content_type and content_type not in {"image/jpeg", "image/jpg", "image/png", "image/bmp"}:
        raise HTTPException(status_code=415, detail="只支持 jpg、png、bmp 图片")
    image_bytes = await image.read()
    if not image_bytes:
        raise HTTPException(status_code=400, detail="图片为空")
    if len(image_bytes) > settings.max_upload_image_bytes:
        raise HTTPException(status_code=413, detail="图片超过上传大小限制")
    result = await start_image_workflow(image_bytes, screenshot_time)
    response.headers["Location"] = f"/api/workflows/{result.run_id}"
    return result


@router.get("/{run_id}/events")
async def stream_events(
    run_id: str,
    last_event_id: int | None = Header(default=None, alias="Last-Event-ID"),
) -> StreamingResponse:
    repo = WorkflowRepository()
    try:
        repo.get_state(run_id)
    except KeyError as error:
        raise HTTPException(status_code=404, detail="workflow not found") from error

    async def generate():
        cursor = last_event_id or 0
        heartbeat_at = asyncio.get_running_loop().time()
        while True:
            events = repo.events_after(run_id, cursor)
            for event in events:
                cursor = event.id
                yield (
                    f"id: {event.id}\n"
                    f"event: {event.event}\n"
                    f"data: {json.dumps(event.data, ensure_ascii=False, default=str)}\n\n"
                )
            state = repo.get_state(run_id)
            if state.get("workflow_status") in {"awaiting_review", "completed", "failed", "cancelled"} and not events:
                break
            now = asyncio.get_running_loop().time()
            if now - heartbeat_at >= 15:
                heartbeat_at = now
                yield ": heartbeat\n\n"
            await asyncio.to_thread(repo.wait_for_events, min(15.0, max(0.1, 15 - (now - heartbeat_at))))

    return StreamingResponse(
        generate(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


@router.post("/{run_id}/ocr-candidates", response_model=WorkflowRunResponse)
def add_ocr_candidate(run_id: str, request: OcrCandidateRequest) -> WorkflowRunResponse:
    try:
        return submit_ocr_candidate(run_id, request)
    except KeyError as error:
        raise HTTPException(status_code=404, detail="workflow not found") from error
    except ValueError as error:
        raise HTTPException(status_code=409, detail=str(error)) from error


@router.patch("/{run_id}/draft", response_model=WorkflowRunResponse)
def update_draft(run_id: str, request: DraftPatchRequest) -> WorkflowRunResponse:
    try:
        return patch_draft(run_id, request)
    except KeyError as error:
        raise HTTPException(status_code=404, detail="workflow not found") from error
    except ValueError as error:
        raise HTTPException(status_code=409, detail=str(error)) from error


@router.post("/{run_id}/confirm", response_model=WorkflowRunResponse)
def confirm_run(run_id: str, request: ConfirmWorkflowRequest) -> WorkflowRunResponse:
    try:
        return confirm_workflow(run_id, request)
    except KeyError as error:
        raise HTTPException(status_code=404, detail="workflow not found") from error
    except ValueError as error:
        raise HTTPException(status_code=409, detail=str(error)) from error


@router.get("/{run_id}", response_model=WorkflowRunResponse)
def get_run(run_id: str) -> WorkflowRunResponse:
    try:
        return get_workflow(run_id)
    except KeyError as error:
        raise HTTPException(status_code=404, detail="workflow not found") from error


@router.post("/{run_id}/resume", response_model=WorkflowRunResponse)
async def resume_run(run_id: str, request: WorkflowResumeRequest) -> WorkflowRunResponse:
    try:
        return await resume_workflow(run_id, request)
    except KeyError as error:
        raise HTTPException(status_code=404, detail="workflow not found") from error
    except ValueError as error:
        raise HTTPException(status_code=409, detail=str(error)) from error
