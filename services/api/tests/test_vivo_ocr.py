from __future__ import annotations

import unittest

from app.services.rule_extractor import extract_cards_with_rules
from app.services.vivo_ocr import OcrLine, clean_ocr_lines, parse_vivo_ocr_lines


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

    def test_comparison_text_generates_comparison_card(self) -> None:
        text = "方案 A 价格 399 元，续航 8 小时；方案 B 价格 459 元，续航 12 小时，帮我对比一下选哪个。"

        card = extract_cards_with_rules(text)[0]

        self.assertEqual(card.card_type, "comparison")
        self.assertIn("对比", card.tags)

    def test_fallback_text_generates_collection_card(self) -> None:
        text = "图书馆总服务台电话 010-12345678，地址：主校区图书馆一层大厅。"

        card = extract_cards_with_rules(text)[0]

        self.assertEqual(card.card_type, "collection")
        self.assertIn("收藏", card.tags)


if __name__ == "__main__":
    unittest.main()
