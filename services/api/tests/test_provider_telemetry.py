from __future__ import annotations

import asyncio
import json
import unittest
from unittest.mock import AsyncMock, patch

from fastapi.testclient import TestClient

from app.core.config import settings
from app.main import app
from app.services.provider_runtime import provider_usage_delta, runtime
from app.services.vivo_ocr import OcrLine


class ProviderTelemetryTest(unittest.TestCase):
    def test_provider_usage_delta_counts_success_without_payloads(self) -> None:
        before = runtime.snapshot()
        started = runtime.attempt("fast_model")
        runtime.success("fast_model", started)
        usage = provider_usage_delta(before)

        self.assertEqual(usage["fast_model"]["request_count_delta"], 1)
        self.assertEqual(usage["fast_model"]["success_count_delta"], 1)
        self.assertEqual(usage["fast_model"]["failure_count_delta"], 0)
        self.assertNotIn("prompt", json.dumps(usage).lower())

    def test_provider_probe_is_disabled_by_default(self) -> None:
        original = settings.enable_provider_probe
        object.__setattr__(settings, "enable_provider_probe", False)
        try:
            with TestClient(app) as client:
                response = client.post("/api/providers/probe")
            self.assertEqual(response.status_code, 403)
        finally:
            object.__setattr__(settings, "enable_provider_probe", original)

    def test_provider_urls_allow_official_http_ocr_but_fail_arbitrary_http(self) -> None:
        originals = {
            "vivo_ocr_url": settings.vivo_ocr_url,
            "vivo_ocr_app_key": settings.vivo_ocr_app_key,
        }
        object.__setattr__(settings, "vivo_ocr_url", "http://api-ai.vivo.com.cn/ocr/general_recognition")
        object.__setattr__(settings, "vivo_ocr_app_key", "server-side-key")
        try:
            with TestClient(app) as client:
                response = client.get("/ready")
            body = response.json()
            self.assertTrue(body["ocr_configured"])
            self.assertNotIn("vivo_ocr", body["provider_url_errors"])
            self.assertNotIn("server-side-key", response.text)

            object.__setattr__(settings, "vivo_ocr_url", "http://evil.example.com/ocr/general_recognition")
            with TestClient(app) as client:
                response = client.get("/ready")
            body = response.json()
            self.assertFalse(body["ready"])
            self.assertIn("vivo_ocr", body["provider_url_errors"])
        finally:
            for name, value in originals.items():
                object.__setattr__(settings, name, value)

    def test_provider_probe_calls_all_configured_providers_without_leaking_key(self) -> None:
        originals = {
            "enable_provider_probe": settings.enable_provider_probe,
            "fast_model_api_key": settings.fast_model_api_key,
            "vivo_ocr_app_key": settings.vivo_ocr_app_key,
            "vivo_image_generation_api_key": settings.vivo_image_generation_api_key,
        }
        object.__setattr__(settings, "enable_provider_probe", True)
        object.__setattr__(settings, "fast_model_api_key", "server-side-key")
        object.__setattr__(settings, "vivo_ocr_app_key", "server-side-key")
        object.__setattr__(settings, "vivo_image_generation_api_key", "server-side-key")
        try:
            with patch(
                "app.api.endpoints.providers.structured_completion",
                new=AsyncMock(return_value={"ok": True}),
            ) as chat, patch(
                "app.api.endpoints.providers.VivoOcrClient.recognize",
                new=AsyncMock(return_value=[OcrLine("TEST 10:00", 0, 0, 1, 1)]),
            ) as ocr, patch(
                "app.api.endpoints.providers.generate_demo_image",
                new=AsyncMock(return_value={"code": 0}),
            ) as image:
                with TestClient(app) as client:
                    response = client.post("/api/providers/probe")

            self.assertEqual(response.status_code, 200)
            body = response.json()
            self.assertTrue(body["all_succeeded"])
            self.assertTrue(body["results"]["chat"]["succeeded"])
            self.assertTrue(body["results"]["ocr"]["succeeded"])
            self.assertTrue(body["results"]["image_generation"]["succeeded"])
            self.assertEqual(chat.await_count, 1)
            self.assertEqual(ocr.await_count, 1)
            self.assertEqual(image.await_count, 1)
            self.assertNotIn("server-side-key", response.text)
        finally:
            for name, value in originals.items():
                object.__setattr__(settings, name, value)


if __name__ == "__main__":
    unittest.main()
