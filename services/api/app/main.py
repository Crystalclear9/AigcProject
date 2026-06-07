from __future__ import annotations

import importlib.metadata
import importlib.util
import logging

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.api.router import api_router
from app.core.config import settings
from app.db.connection import init_db
from app.services.provider_runtime import runtime
from app.services.workflow_service import recover_workflows

logger = logging.getLogger(__name__)
EXPECTED_LANGGRAPH_VERSION = "1.2.1"


def create_app() -> FastAPI:
    init_db()
    app = FastAPI(title=settings.app_name, version="1.0.0")
    app.add_middleware(
        CORSMiddleware,
        allow_origins=settings.cors_origin_list,
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )
    app.include_router(api_router, prefix=settings.api_prefix)

    @app.on_event("startup")
    async def verify_runtime() -> None:
        actual = importlib.metadata.version("langgraph")
        if actual != EXPECTED_LANGGRAPH_VERSION:
            logger.warning(
                "LangGraph version mismatch: expected %s, running %s",
                EXPECTED_LANGGRAPH_VERSION,
                actual,
            )
        recovered = await recover_workflows()
        if recovered:
            logger.info("recovered %s workflow(s)", recovered)

    @app.on_event("shutdown")
    async def close_clients() -> None:
        await runtime.close()

    @app.get("/health")
    def health() -> dict[str, object]:
        actual = importlib.metadata.version("langgraph")
        return {
            "status": "ok",
            "langgraph_version": actual,
            "langgraph_version_match": actual == EXPECTED_LANGGRAPH_VERSION,
            "sqlite_checkpointer_available": importlib.util.find_spec("langgraph.checkpoint.sqlite") is not None,
        }

    return app


app = create_app()
