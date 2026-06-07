from __future__ import annotations

import asyncio
import unittest

import httpx

from app.schemas.card import AnalyzeScreenshotTextRequest
from app.services.analyzer import analyze_screenshot_text
from app.services.demo_scenarios import evaluate_demo_scenarios
from app.services.llm_client import _extract_json
from app.services.rule_extractor import extract_cards_with_rules
from app.services.vivo_ocr import (
    OcrLine,
    VivoOcrError,
    _format_http_error,
    _format_request_error,
    clean_ocr_lines,
    parse_successful_vivo_ocr_body,
    parse_vivo_ocr_lines,
)


class VivoOcrTest(unittest.TestCase):
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

    def test_ocr_errors_are_mapped(self) -> None:
        with self.assertRaisesRegex(VivoOcrError, "returned no text"):
            parse_successful_vivo_ocr_body({"error_code": 0, "result": {"OCR": []}})
        with self.assertRaisesRegex(VivoOcrError, "error_code=2"):
            parse_successful_vivo_ocr_body({"error_code": 2, "error_msg": "bad image"})

        request = httpx.Request("POST", "http://example.test")
        response = httpx.Response(401, request=request, text="invalid key")
        self.assertIn("invalid key", _format_http_error(httpx.HTTPStatusError("bad", request=request, response=response)))
        self.assertIn("timed out", _format_request_error(httpx.ReadTimeout("timed out", request=request)))


class StructuredOutputTest(unittest.TestCase):
    def test_json_parsing_compatibility(self) -> None:
        self.assertEqual(_extract_json('[{"title":"报告"}]')[0]["title"], "报告")
        self.assertEqual(_extract_json('{"cards":[{"title":"报告"}]}')["cards"][0]["title"], "报告")
        self.assertEqual(_extract_json('result: [{"title":"报告"}]')[0]["title"], "报告")


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
        self.assertEqual(result["passed"], 5)
        self.assertEqual(result["field_pass_rate"], 1.0)

    def test_status_bar_time_does_not_override_action_time(self) -> None:
        text = (
            "10:54 5G video_20260524_111105.mp4 1.78GB "
            "本周二（5.26）下午 16:10 在南四楼会议室召开会议，请按时参加。"
        )
        card = extract_cards_with_rules(text, "2026-05-25T10:54:00+08:00")[0]
        self.assertNotEqual(card.start_time, "2026-05-25T10:54:00+08:00")


if __name__ == "__main__":
    unittest.main()
