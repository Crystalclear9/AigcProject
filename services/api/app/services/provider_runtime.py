from __future__ import annotations

import asyncio
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any

import httpx

from app.core.config import settings


@dataclass
class CircuitState:
    failures: int = 0
    opened_at: float | None = None


@dataclass
class ProviderTelemetry:
    request_count: int = 0
    success_count: int = 0
    failure_count: int = 0
    last_success_at: str | None = None
    last_failure_at: str | None = None
    last_error_type: str | None = None
    last_latency_ms: float | None = None
    circuit_open: bool = False
    recent_errors: list[str] = field(default_factory=list)

    def snapshot(self) -> dict[str, Any]:
        return {
            "request_count": self.request_count,
            "success_count": self.success_count,
            "failure_count": self.failure_count,
            "last_success_at": self.last_success_at,
            "last_failure_at": self.last_failure_at,
            "last_error_type": self.last_error_type,
            "last_latency_ms": self.last_latency_ms,
            "circuit_open": self.circuit_open,
        }


class ProviderRuntime:
    def __init__(self) -> None:
        limits = httpx.Limits(
            max_connections=max(20, settings.workflow_max_concurrency * 2),
            max_keepalive_connections=max(10, settings.workflow_max_concurrency),
            keepalive_expiry=30,
        )
        self.client = httpx.AsyncClient(http2=True, limits=limits)
        self.semaphores = {
            "ocr": asyncio.Semaphore(settings.provider_max_concurrency),
            "fast_model": asyncio.Semaphore(settings.provider_max_concurrency),
            "expert_model": asyncio.Semaphore(max(2, settings.provider_max_concurrency // 2)),
            "image_generation": asyncio.Semaphore(max(1, settings.provider_max_concurrency // 4)),
            "web": asyncio.Semaphore(max(2, settings.workflow_tool_max_concurrency // 2)),
        }
        self.circuits = {name: CircuitState() for name in self.semaphores}
        self.telemetry = {name: ProviderTelemetry() for name in self.semaphores}

    async def close(self) -> None:
        await self.client.aclose()

    def allow(self, provider: str) -> bool:
        circuit = self.circuits[provider]
        self.telemetry[provider].circuit_open = circuit.opened_at is not None
        if circuit.opened_at is None:
            return True
        if time.monotonic() - circuit.opened_at >= 30:
            circuit.failures = 0
            circuit.opened_at = None
            self.telemetry[provider].circuit_open = False
            return True
        return False

    def attempt(self, provider: str) -> float:
        self.telemetry[provider].request_count += 1
        return time.perf_counter()

    def success(self, provider: str, started: float | None = None) -> None:
        self.circuits[provider] = CircuitState()
        telemetry = self.telemetry[provider]
        telemetry.success_count += 1
        telemetry.last_success_at = _utc_now()
        telemetry.last_error_type = None
        telemetry.circuit_open = False
        if started is not None:
            telemetry.last_latency_ms = _elapsed_ms(started)

    def failure(self, provider: str, error_type: str | None = None, started: float | None = None) -> None:
        circuit = self.circuits[provider]
        circuit.failures += 1
        if circuit.failures >= 3:
            circuit.opened_at = time.monotonic()
        telemetry = self.telemetry[provider]
        telemetry.failure_count += 1
        telemetry.last_failure_at = _utc_now()
        telemetry.last_error_type = error_type or "provider_error"
        telemetry.circuit_open = circuit.opened_at is not None
        if started is not None:
            telemetry.last_latency_ms = _elapsed_ms(started)
        telemetry.recent_errors = (telemetry.recent_errors + [telemetry.last_error_type])[-5:]

    def snapshot(self) -> dict[str, dict[str, Any]]:
        for provider in self.telemetry:
            self.allow(provider)
        return {provider: item.snapshot() for provider, item in self.telemetry.items()}


def provider_usage_delta(
    baseline: dict[str, dict[str, Any]] | None,
    current: dict[str, dict[str, Any]] | None = None,
) -> dict[str, dict[str, Any]]:
    before = baseline or {}
    after = current or runtime.snapshot()
    usage: dict[str, dict[str, Any]] = {}
    for provider, counters in after.items():
        previous = before.get(provider, {})
        usage[provider] = {
            "request_count_delta": int(counters.get("request_count", 0))
            - int(previous.get("request_count", 0)),
            "success_count_delta": int(counters.get("success_count", 0))
            - int(previous.get("success_count", 0)),
            "failure_count_delta": int(counters.get("failure_count", 0))
            - int(previous.get("failure_count", 0)),
            "last_success_at": counters.get("last_success_at"),
            "last_error_type": counters.get("last_error_type"),
            "latency_ms": counters.get("last_latency_ms"),
            "circuit_open": bool(counters.get("circuit_open", False)),
        }
    return usage


def provider_status_snapshot() -> dict[str, dict[str, Any]]:
    return runtime.snapshot()


def _elapsed_ms(started: float) -> float:
    return round((time.perf_counter() - started) * 1000, 2)


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


runtime = ProviderRuntime()
