from __future__ import annotations

import asyncio
import time
from dataclasses import dataclass

import httpx

from app.core.config import settings


@dataclass
class CircuitState:
    failures: int = 0
    opened_at: float | None = None


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
            "web": asyncio.Semaphore(max(2, settings.workflow_tool_max_concurrency // 2)),
        }
        self.circuits = {name: CircuitState() for name in self.semaphores}

    async def close(self) -> None:
        await self.client.aclose()

    def allow(self, provider: str) -> bool:
        circuit = self.circuits[provider]
        if circuit.opened_at is None:
            return True
        if time.monotonic() - circuit.opened_at >= 30:
            circuit.failures = 0
            circuit.opened_at = None
            return True
        return False

    def success(self, provider: str) -> None:
        self.circuits[provider] = CircuitState()

    def failure(self, provider: str) -> None:
        circuit = self.circuits[provider]
        circuit.failures += 1
        if circuit.failures >= 3:
            circuit.opened_at = time.monotonic()


runtime = ProviderRuntime()
