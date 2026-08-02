from __future__ import annotations

import asyncio
import unittest
from types import SimpleNamespace
from unittest.mock import patch

import httpx

from app.schemas.card import AnalyzeScreenshotTextRequest
from app.core.config import Settings
from app.services.analyzer import analyze_screenshot_text
from app.services.demo_scenarios import evaluate_demo_scenarios
from app.services.image_generation import generate_demo_image
from app.services.llm_client import (
    ModelProfile,
    _chat_completion_url,
    _extract_json,
    _provider_payload,
    _reasoning_controls,
)
from app.services.rule_extractor import extract_cards_with_rules
from app.services.vivo_ocr import (
    OcrLine,
    VivoOcrClient,
    VivoOcrError,
    _format_http_error,
    _format_request_error,
    clean_ocr_lines,
    parse_successful_vivo_ocr_body,
    parse_vivo_ocr_lines,
)


class FakeAsyncPostClient:
    def __init__(self, response: httpx.Response) -> None:
        self.response = response
        self.calls: list[dict[str, object]] = []

    async def post(self, url: str, **kwargs: object) -> httpx.Response:
        self.calls.append({"url": url, **kwargs})
        return self.response


class VivoOcrTest(unittest.TestCase):
    def test_documented_business_profiles_are_complete_business_ids(self) -> None:
        rotation = Settings(vivo_ocr_business_profile="rotation")
        upright = Settings(vivo_ocr_business_profile="upright_fast")
        explicit = Settings(vivo_ocr_business_id_override="explicit-business")
        app_id = Settings(vivo_ocr_app_id="sample-app")

        self.assertEqual(rotation.vivo_ocr_business_id, "1990173156ceb8a09eee80c293135279")
        self.assertEqual(upright.vivo_ocr_business_id, "8bf312e702043779ad0f2760b37a0806")
        self.assertEqual(explicit.vivo_ocr_business_id, "explicit-business")
        self.assertEqual(app_id.vivo_ocr_business_id, "aigcsample-app")

    def test_chat_completion_url_accepts_base_or_full_endpoint(self) -> None:
        self.assertEqual(
            _chat_completion_url("https://api-ai.vivo.com.cn/v1"),
            "https://api-ai.vivo.com.cn/v1/chat/completions",
        )
        self.assertEqual(
            _chat_completion_url("https://api-ai.vivo.com.cn/v1/chat/completions"),
            "https://api-ai.vivo.com.cn/v1/chat/completions",
        )

    def test_vivo_chat_uses_prompt_schema_instead_of_unsupported_response_format(self) -> None:
        profile = ModelProfile(
            role="fast_model",
            api_key="server-side-key",
            base_url="https://api-ai.vivo.com.cn/v1",
            model="Doubao-Seed-2.0-mini",
            timeout=8,
        )
        payload = {
            "model": profile.model,
            "messages": [{"role": "system", "content": "只返回 JSON"}],
            "response_format": {
                "type": "json_schema",
                "json_schema": {
                    "name": "sample",
                    "schema": {"type": "object", "required": ["ok"]},
                },
            },
        }

        adapted = _provider_payload(profile, payload)

        self.assertNotIn("response_format", adapted)
        self.assertIn("JSON Schema", adapted["messages"][0]["content"])
        self.assertIn('"required":["ok"]', adapted["messages"][0]["content"])
        self.assertEqual(_reasoning_controls(profile)["reasoning_effort"], "minimal")
        self.assertEqual(_reasoning_controls(profile)["thinking"], {"type": "disabled"})

    def test_cleaning_removes_status_and_file_noise(self) -> None:
        lines = [
            OcrLine("10:54", 50, 55, 130, 80),
            OcrLine("video_20260524_111105.mp4", 260, 760, 780, 810),
            OcrLine("请在本周五晚上 22:00 前提交实验报告", 220, 1320, 820, 1455),
            OcrLine("提交至学习通", 220, 1480, 620, 1535),
        ]
        cleaned = clean_ocr_lines(lines)
        self.assertIn("提交实验报告", cleaned)
        self.assertNotIn("10:54", cleaned)
        self.assertNotIn("video_", cleaned)

    def test_cleaning_preserves_action_terms_and_distant_tasks(self) -> None:
        lines = [
            OcrLine("群文件：课程要求.pdf", 120, 300, 780, 360),
            OcrLine("请在6月10日22:01前发送实验报告", 120, 430, 920, 510),
            OcrLine("第二项：6月14日09:07到会议室参加答辩", 100, 1680, 940, 1770),
        ]
        cleaned = clean_ocr_lines(lines)
        self.assertIn("群文件", cleaned)
        self.assertIn("发送实验报告", cleaned)
        self.assertIn("参加答辩", cleaned)

    def test_parse_pos_variants(self) -> None:
        pos2 = {
            "error_code": 0,
            "result": {
                "OCR": [
                    {
                        "words": "通知",
                        "location": {
                            "top_left": {"x": 10, "y": 20},
                            "top_right": {"x": 60, "y": 20},
                            "down_left": {"x": 10, "y": 40},
                            "down_right": {"x": 60, "y": 40},
                        },
                    }
                ]
            },
        }
        self.assertEqual(parse_vivo_ocr_lines(pos2)[0].bottom, 40)
        pos0 = {"error_code": 0, "result": {"words": [{"words": "提交报告"}]}}
        self.assertEqual(parse_vivo_ocr_lines(pos0)[0].text, "提交报告")

    def test_vivo_ocr_uses_configured_provider_url_and_auth(self) -> None:
        response = httpx.Response(
            200,
            json={"error_code": 0, "result": {"OCR": [{"words": "submit report"}]}},
            request=httpx.Request("POST", "http://api-ai.vivo.com.cn/ocr/general_recognition"),
        )
        fake_client = FakeAsyncPostClient(response)
        fake_settings = SimpleNamespace(
            has_vivo_ocr_config=True,
            vivo_ocr_app_key="server-side-key",
            vivo_ocr_url="http://api-ai.vivo.com.cn/ocr/general_recognition",
            vivo_ocr_business_id="aigc-test-app",
            vivo_ocr_timeout_seconds=5,
        )

        async def run() -> None:
            with patch("app.services.vivo_ocr.settings", fake_settings), patch(
                "app.services.vivo_ocr.runtime.client_for_url",
                return_value=fake_client,
            ):
                lines = await VivoOcrClient().recognize(b"image")
            self.assertEqual(lines[0].text, "submit report")

        asyncio.run(run())

        call = fake_client.calls[0]
        self.assertEqual(call["url"], "http://api-ai.vivo.com.cn/ocr/general_recognition")
        self.assertEqual(call["headers"]["Authorization"], "Bearer server-side-key")
        self.assertEqual(call["data"]["businessid"], "aigc-test-app")
        self.assertEqual(call["data"]["pos"], 2)
        self.assertIn("requestId", call["params"])

    def test_ocr_errors_are_mapped(self) -> None:
        with self.assertRaisesRegex(VivoOcrError, "returned no text"):
            parse_successful_vivo_ocr_body({"error_code": 0, "result": {"OCR": []}})
        with self.assertRaisesRegex(VivoOcrError, "error_code=2"):
            parse_successful_vivo_ocr_body({"error_code": 2, "error_msg": "bad image"})

        request = httpx.Request("POST", "http://example.test")
        response = httpx.Response(401, request=request, text="invalid key")
        self.assertEqual(
            _format_http_error(httpx.HTTPStatusError("bad", request=request, response=response)),
            "vivo OCR HTTP 401",
        )
        self.assertIn("timed out", _format_request_error(httpx.ReadTimeout("timed out", request=request)))


class StructuredOutputTest(unittest.TestCase):
    def test_json_parsing_compatibility(self) -> None:
        self.assertEqual(_extract_json('[{"title":"报告"}]')[0]["title"], "报告")
        self.assertEqual(_extract_json('{"cards":[{"title":"报告"}]}')["cards"][0]["title"], "报告")
        self.assertEqual(_extract_json('result: [{"title":"报告"}]')[0]["title"], "报告")


class ImageGenerationProviderTest(unittest.TestCase):
    def test_image_generation_posts_provider_contract_without_leaking_key(self) -> None:
        response = httpx.Response(
            200,
            json={"code": 0, "message": "success", "data": {"images": []}},
            request=httpx.Request("POST", "https://provider.test/image_generation"),
        )
        fake_client = FakeAsyncPostClient(response)
        fake_settings = SimpleNamespace(
            has_image_generation_config=True,
            vivo_image_generation_api_key="server-side-key",
            vivo_image_generation_url="https://provider.test/image_generation",
            vivo_image_generation_model="Doubao-Seedream-4.5",
            vivo_image_generation_timeout_seconds=60,
        )

        async def run() -> dict[str, object]:
            with patch("app.services.image_generation.settings", fake_settings), patch(
                "app.services.image_generation.runtime.client_for_url",
                return_value=fake_client,
            ):
                return await generate_demo_image("complex schedule poster", size="1024x1024")

        body = asyncio.run(run())

        self.assertEqual(body["code"], 0)
        call = fake_client.calls[0]
        self.assertEqual(call["url"], "https://provider.test/image_generation")
        self.assertEqual(call["headers"]["Authorization"], "Bearer server-side-key")
        self.assertEqual(call["json"]["model"], "Doubao-Seedream-4.5")
        self.assertEqual(call["json"]["parameters"]["size"], "1024x1024")
        self.assertEqual(call["params"]["module"], "aigc")
        self.assertIn("request_id", call["params"])
        self.assertIn("system_time", call["params"])


class AnalyzerAndRulesTest(unittest.TestCase):
    def test_legacy_analyzer_returns_fast_rule_result(self) -> None:
        response = asyncio.run(
            analyze_screenshot_text(
                AnalyzeScreenshotTextRequest(
                    text="请在本周五晚上 22:00 前提交实验报告，提交至学习通。",
                    screenshot_time="2026-06-07T10:00:00+08:00",
                )
            )
        )
        self.assertEqual(response.engine, "rules")
        self.assertTrue(response.cards)
        self.assertTrue(response.trace_id)

    def test_demo_scenarios_still_pass(self) -> None:
        result = evaluate_demo_scenarios()
        self.assertEqual(result["passed"], result["total"])
        self.assertEqual(result["field_pass_rate"], 1.0)

    def test_status_bar_time_does_not_override_action_time(self) -> None:
        text = (
            "10:54 5G video_20260524_111105.mp4 1.78GB "
            "本周二（5.26）下午 16:10 在南四楼会议室召开会议，请按时参加。"
        )
        card = extract_cards_with_rules(text, "2026-05-25T10:54:00+08:00")[0]
        self.assertNotEqual(card.start_time, "2026-05-25T10:54:00+08:00")

    def test_comparison_text_generates_comparison_card(self) -> None:
        text = "方案 A 价格 399 元，续航 8 小时；方案 B 价格 459 元，续航 12 小时，帮我对比一下选哪个。"

        card = extract_cards_with_rules(text)[0]

        self.assertEqual(card.card_type, "comparison")
        self.assertIn("对比", card.tags)

    def test_non_action_info_does_not_generate_collection_card(self) -> None:
        text = "图书馆总服务台电话 010-12345678，地址：主校区图书馆一层大厅。"

        cards = extract_cards_with_rules(text)

        self.assertEqual(cards, [])

    def test_random_chat_does_not_generate_card(self) -> None:
        text = "哈哈哈这个截图挺有意思，等会儿再说。"

        cards = extract_cards_with_rules(text)

        self.assertEqual(cards, [])


if __name__ == "__main__":
    unittest.main()
