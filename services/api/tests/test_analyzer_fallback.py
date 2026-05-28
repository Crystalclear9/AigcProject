from __future__ import annotations

import unittest
from unittest.mock import AsyncMock, patch

from app.services.analyzer import analyze_screenshot_image
from app.services.vivo_ocr import OcrLine


class AnalyzerFallbackTest(unittest.IsolatedAsyncioTestCase):
    async def test_image_analysis_uses_rules_when_llm_times_out(self) -> None:
        lines = [
            OcrLine("请同学们在本周五晚上 22:00 前提交实验报告，提交至学习通。", 0.2, 0.3, 0.8, 0.35),
        ]

        with (
            patch("app.services.analyzer.VivoOcrClient.recognize", new=AsyncMock(return_value=lines)),
            patch("app.services.analyzer.extract_cards_with_lanxin", new=AsyncMock(side_effect=TimeoutError("slow"))),
        ):
            result = await analyze_screenshot_image(b"fake-image", "2026-05-25T10:54:00+08:00")

        self.assertEqual(result.engine, "vivo-ocr+rules")
        self.assertTrue(result.cards)
        self.assertEqual(result.cards[0].card_type, "task")


if __name__ == "__main__":
    unittest.main()
