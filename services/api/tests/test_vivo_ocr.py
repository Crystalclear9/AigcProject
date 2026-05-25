from __future__ import annotations

import asyncio
from datetime import datetime, timezone
import unittest
from unittest.mock import patch

import httpx

from app.schemas.card import ActionCard, AnalyzeScreenshotTextRequest
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


class VivoOcrCleaningTest(unittest.TestCase):
    def test_clean_wechat_screenshot_keeps_action_bubble(self) -> None:
        lines = [
            OcrLine("10:54", 50, 55, 130, 80),
            OcrLine("2023级本科生第一党支部(40)", 190, 150, 820, 190),
            OcrLine("video_20260524_111105.mp4", 260, 760, 780, 810),
            OcrLine("1.78GB", 260, 820, 380, 860),
            OcrLine("群文件", 240, 900, 370, 950),
            OcrLine("你撤回了一条消息，你猜猜撤回了什么", 250, 1130, 830, 1170),
            OcrLine("[五月主题党日通知]", 220, 1320, 700, 1370),
            OcrLine("本周二（5.26）下午16：10在", 220, 1400, 820, 1455),
            OcrLine("南四楼党旗领航教育基地（会议室）召开本月主题党日，请", 220, 1480, 900, 1535),
            OcrLine("大家务必按时参加。", 220, 1560, 620, 1610),
            OcrLine("@全体成员", 220, 1840, 520, 1890),
            OcrLine("发送", 850, 2150, 950, 2210),
        ]

        cleaned = clean_ocr_lines(lines)

        self.assertIn("五月主题党日通知", cleaned)
        self.assertIn("下午16：10", cleaned)
        self.assertIn("南四楼党旗领航教育基地", cleaned)
        self.assertNotIn("10:54", cleaned)
        self.assertNotIn("video_20260524_111105.mp4", cleaned)
        self.assertNotIn("1.78GB", cleaned)

    def test_parse_vivo_pos2_coordinates(self) -> None:
        payload = {
            "error_code": 0,
            "result": {
                "OCR": [
                    {
                        "words": "通知",
                        "location": {
                            "top_left": {"x": 10.0, "y": 20.0},
                            "top_right": {"x": 60.0, "y": 20.0},
                            "down_left": {"x": 10.0, "y": 40.0},
                            "down_right": {"x": 60.0, "y": 40.0},
                        },
                    }
                ]
            },
        }

        lines = parse_vivo_ocr_lines(payload)

        self.assertEqual(lines[0].text, "通知")
        self.assertEqual(lines[0].left, 10.0)
        self.assertEqual(lines[0].bottom, 40.0)

    def test_parse_vivo_pos0_words(self) -> None:
        payload = {
            "error_code": 0,
            "result": {
                "words": [
                    {"words": "请提交实验报告"},
                    {"words": "截止时间 22:00"},
                ]
            },
        }

        lines = parse_vivo_ocr_lines(payload)

        self.assertEqual([line.text for line in lines], ["请提交实验报告", "截止时间 22:00"])
        self.assertEqual(lines[1].top, 1.0)

    def test_parse_vivo_pos1_coordinates(self) -> None:
        payload = {
            "error_code": 0,
            "result": {
                "OCR": [
                    {
                        "words": "地点",
                        "location": {
                            "top_left": {"x": 1, "y": 2},
                            "top_right": {"x": 8, "y": 2},
                            "down_left": {"x": 1, "y": 6},
                            "down_right": {"x": 8, "y": 6},
                        },
                    }
                ]
            },
        }

        lines = parse_vivo_ocr_lines(payload)

        self.assertEqual(lines[0].right, 8.0)
        self.assertEqual(lines[0].center_y, 4.0)

    def test_vivo_http_error_message_includes_status_and_body(self) -> None:
        request = httpx.Request("POST", "http://api-ai.vivo.com.cn/ocr/general_recognition")
        response = httpx.Response(401, request=request, text='{"message":"invalid api-key"}')
        error = httpx.HTTPStatusError("Unauthorized", request=request, response=response)

        message = _format_http_error(error)

        self.assertIn("HTTP 401", message)
        self.assertIn("invalid api-key", message)

    def test_empty_ocr_body_can_be_mapped_to_error(self) -> None:
        self.assertEqual(parse_vivo_ocr_lines({"error_code": 0, "result": {"OCR": []}}), [])
        with self.assertRaisesRegex(VivoOcrError, "returned no text lines"):
            parse_successful_vivo_ocr_body({"error_code": 0, "result": {"OCR": []}})

    def test_vivo_nonzero_error_code_is_mapped_to_error(self) -> None:
        with self.assertRaisesRegex(VivoOcrError, "error_code=2: 图像错误"):
            parse_successful_vivo_ocr_body({"error_code": 2, "error_msg": "图像错误"})

    def test_vivo_timeout_message_is_mapped_to_request_error(self) -> None:
        request = httpx.Request("POST", "http://api-ai.vivo.com.cn/ocr/general_recognition")
        error = httpx.ReadTimeout("timed out", request=request)

        message = _format_request_error(error)

        self.assertIn("request failed", message)
        self.assertIn("timed out", message)


class LanxinParsingTest(unittest.TestCase):
    def test_extract_json_accepts_plain_array(self) -> None:
        parsed = _extract_json('[{"title":"提交实验报告"}]')

        self.assertEqual(parsed[0]["title"], "提交实验报告")

    def test_extract_json_accepts_cards_object(self) -> None:
        parsed = _extract_json('{"cards":[{"title":"提交实验报告"}]}')

        self.assertEqual(parsed["cards"][0]["title"], "提交实验报告")

    def test_extract_json_accepts_wrapped_text(self) -> None:
        parsed = _extract_json('结果如下：\n[{"title":"提交实验报告"}]\n请确认。')

        self.assertEqual(parsed[0]["title"], "提交实验报告")


class AnalyzerFallbackTest(unittest.TestCase):
    def test_lanxin_failure_falls_back_to_rules_with_reason(self) -> None:
        async def failing_lanxin(text: str, screenshot_time: str | None = None) -> list[ActionCard]:
            raise RuntimeError("network timeout")

        with patch("app.services.analyzer.extract_cards_with_lanxin", failing_lanxin):
            response = asyncio.run(
                analyze_screenshot_text(
                    AnalyzeScreenshotTextRequest(
                        text="请同学们在本周五晚上 22:00 前提交实验报告，提交至学习通。",
                        screenshot_time="2026-05-25T10:00:00+08:00",
                    )
                )
            )

        self.assertEqual(response.engine, "rules")
        self.assertIn("network timeout", response.fallback_reason or "")
        self.assertIn("蓝心大模型不可用", response.warnings[0])
        self.assertTrue(response.trace_id)

    def test_lanxin_success_keeps_no_fallback_reason(self) -> None:
        now = datetime.now(timezone.utc)

        async def working_lanxin(text: str, screenshot_time: str | None = None) -> list[ActionCard]:
            return [
                ActionCard(
                    id="llm-test",
                    card_type="task",
                    title="提交实验报告",
                    created_at=now,
                    source_text=text,
                )
            ]

        with patch("app.services.analyzer.extract_cards_with_lanxin", working_lanxin):
            response = asyncio.run(analyze_screenshot_text(AnalyzeScreenshotTextRequest(text="提交实验报告")))

        self.assertEqual(response.engine, "lanxin")
        self.assertIsNone(response.fallback_reason)
        self.assertEqual(response.warnings, [])


class DemoScenarioEvaluationTest(unittest.TestCase):
    def test_demo_evaluation_reports_field_level_checks(self) -> None:
        result = evaluate_demo_scenarios()

        self.assertEqual(result["total"], 5)
        self.assertEqual(result["passed"], 5)
        self.assertEqual(result["field_pass_rate"], 1.0)
        for item in result["results"]:
            self.assertTrue(all(item["field_checks"].values()))


class RuleExtractorNoiseTest(unittest.TestCase):
    def test_status_bar_time_does_not_override_action_time(self) -> None:
        text = (
            "10:54 5G 2023级本科生第一党支部(40) "
            "video_20260524_111105.mp4 1.78GB 群文件 "
            "[五月主题党日通知] 本周二（5.26）下午16：10在"
            "南四楼党旗领航教育基地（会议室）召开本月主题党日，请大家务必按时参加。"
        )

        card = extract_cards_with_rules(text, "2026-05-25T10:54:00+08:00")[0]

        self.assertEqual(card.start_time, "2026-05-26T16:10:00+08:00")
        self.assertIn("南四楼党旗领航教育基地", card.location or "")
        self.assertNotEqual(card.start_time, "2026-05-25T10:54:00+08:00")


if __name__ == "__main__":
    unittest.main()
