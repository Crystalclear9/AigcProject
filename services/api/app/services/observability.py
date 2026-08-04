from __future__ import annotations

import logging
from dataclasses import dataclass

from app.core.config import settings

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class ObservabilityStatus:
    configured: bool
    active: bool
    exporter: str
    error: str | None = None


_status = ObservabilityStatus(
    configured=False,
    active=False,
    exporter="none",
)


def configure_observability() -> ObservabilityStatus:
    """Configure a vendor-neutral OTLP exporter that Phoenix can ingest."""
    global _status
    endpoint = settings.otel_exporter_otlp_endpoint.strip()
    if not endpoint:
        _status = ObservabilityStatus(False, False, "none")
        return _status
    try:
        from opentelemetry import trace
        from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
        from opentelemetry.sdk.resources import Resource
        from opentelemetry.sdk.trace import TracerProvider
        from opentelemetry.sdk.trace.export import BatchSpanProcessor

        provider = TracerProvider(
            resource=Resource.create(
                {
                    "service.name": settings.otel_service_name,
                    "deployment.environment": settings.workflow_environment,
                }
            )
        )
        exporter = OTLPSpanExporter(endpoint=endpoint)
        provider.add_span_processor(BatchSpanProcessor(exporter))
        trace.set_tracer_provider(provider)
        _status = ObservabilityStatus(True, True, "otlp_http")
    except Exception as exc:
        logger.warning("OTLP tracing unavailable: %s", type(exc).__name__)
        _status = ObservabilityStatus(
            configured=True,
            active=False,
            exporter="otlp_http",
            error=type(exc).__name__,
        )
    return _status


def observability_status() -> ObservabilityStatus:
    return _status
