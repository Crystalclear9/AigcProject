from __future__ import annotations

import importlib.metadata
import importlib.util
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.api.router import api_router
from app.core.config import settings
from app.db.connection import init_db
from app.services.provider_runtime import runtime
from app.repositories.workflows import WorkflowRepository
from app.services.workflow_service import close_workflow_runtime, initialize_workflow_runtime, recover_workflows

logger = logging.getLogger(__name__)
EXPECTED_LANGGRAPH_VERSION = "1.2.1"


def runtime_health() -> tuple[bool, dict[str, object]]:
    actual = importlib.metadata.version("langgraph")
    sqlite_available = importlib.util.find_spec("langgraph.checkpoint.sqlite") is not None
    database_writable = WorkflowRepository().healthcheck()
    checks = {
        "langgraph_version": actual,
        "langgraph_version_match": actual == EXPECTED_LANGGRAPH_VERSION,
        "sqlite_checkpointer_available": sqlite_available,
        "workflow_database_writable": database_writable,
        "fast_model_configured": settings.has_fast_model_config,
        "expert_model_configured": settings.has_expert_model_config,
        "vivo_ocr_configured": settings.has_vivo_ocr_config,
    }
    ready = bool(checks["langgraph_version_match"] and sqlite_available and database_writable)
    return ready, checks


@asynccontextmanager
async def lifespan(_: FastAPI):
    ready, checks = runtime_health()
    if not ready:
        logger.warning("workflow runtime degraded: %s", checks)
    await initialize_workflow_runtime()
    recovered = await recover_workflows()
    if recovered:
        logger.info("recovered %s workflow(s)", recovered)
    try:
        yield
    finally:
        await close_workflow_runtime()
        await runtime.close()


def create_app() -> FastAPI:
    init_db()
    app = FastAPI(title=settings.app_name, version="1.1.0", lifespan=lifespan)
    app.add_middleware(
        CORSMiddleware,
        allow_origins=settings.cors_origin_list,
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )
    app.include_router(api_router, prefix=settings.api_prefix)

    @app.get("/health")
    def health() -> dict[str, object]:
        ready, checks = runtime_health()
        return {"status": "ok" if ready else "degraded", "ready": ready, **checks}

    @app.get("/ready")
    def readiness() -> dict[str, object]:
        ready, checks = runtime_health()
        return {"status": "ready" if ready else "not_ready", "ready": ready, **checks}

    return app


app = create_app()
