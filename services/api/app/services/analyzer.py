from __future__ import annotations

from app.schemas.card import AnalyzeScreenshotTextRequest, AnalyzeScreenshotTextResponse
from app.schemas.workflow import WorkflowRunResponse
from app.services.workflow_service import start_image_workflow, start_text_workflow, wait_for_result


def _legacy_response(run: WorkflowRunResponse) -> AnalyzeScreenshotTextResponse:
    return AnalyzeScreenshotTextResponse(
        ocr_text=run.ocr_text,
        cards=run.cards,
        preview_actions=run.preview_actions,
        engine=run.engine,
        trace_id=run.run_id,
        fallback_reason=run.fallback_reason,
        warnings=run.warnings,
        run_id=run.run_id,
        workflow_status=run.workflow_status,
        pending_action=run.pending_action,
        node_trace=[trace.model_dump() for trace in run.node_trace],
        confidence=run.confidence,
        provenance=run.provenance,
    )


async def analyze_screenshot_text(request: AnalyzeScreenshotTextRequest) -> AnalyzeScreenshotTextResponse:
    started = await start_text_workflow(request.text, request.screenshot_time)
    return _legacy_response(await wait_for_result(started.run_id))


async def analyze_screenshot_image(
    image_bytes: bytes,
    screenshot_time: str | None = None,
) -> AnalyzeScreenshotTextResponse:
    started = await start_image_workflow(image_bytes, screenshot_time)
    return _legacy_response(await wait_for_result(started.run_id))
