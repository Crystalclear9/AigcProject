from __future__ import annotations

import unittest
from datetime import datetime, timezone

from app.services.extraction_context import (
    build_llm_context,
    build_summary,
    enrich_need_confirm,
    repair_title,
    should_rewrite_summary,
)
from app.services.llm_client import _normalize_card_payload
from app.services.rule_extractor import extract_cards_with_rules


class ExtractionContextTest(unittest.TestCase):
    def test_llm_context_cleans_noise_and_keeps_action_text(self) -> None:
        text = (
            "10:54\n5G\nvideo_20260524_111105.mp4\n群文件\n"
            "[五月主题党日通知]\n本周二（5.26）下午16：10在\n"
            "南四楼党旗领航教育基地（会议室）召开本月主题党日，请大家务必按时参加。"
        )

        context = build_llm_context(text, "2026-05-25T10:54:00+08:00")

        self.assertNotIn("video_20260524_111105.mp4", context["cleaned_text"])
        self.assertNotIn("群文件", context["cleaned_text"])
        self.assertIn("五月主题党日通知", context["cleaned_text"])
        self.assertIn("南四楼党旗领航教育基地", context["cleaned_text"])
        self.assertTrue(context["detected_hints"]["time_expressions"])
        self.assertIn("南四楼党旗领航教育基地（会议室）", context["detected_hints"]["locations"])

    def test_summary_generation_is_not_raw_truncation(self) -> None:
        text = "请同学们在本周五晚上 22:00 前提交实验报告，提交至学习通，文件命名为学号+姓名。"

        summary = build_summary(
            card_type="task",
            text=text,
            title="提交实验报告",
            submit_method="提交至学习通",
            materials=["实验报告"],
        )

        self.assertIn("实验报告", summary)
        self.assertIn("学习通", summary)
        self.assertNotEqual(summary, text[:120])
        self.assertLessEqual(len(summary), 60)

    def test_summary_generation_for_each_card_type(self) -> None:
        cases = [
            ("event", "明天下午 3 点在 A101 开组会。", "参加组会"),
            ("promise", "你明天上午能不能帮我把表格发给老师？可以，我明天上午发。", "处理聊天承诺"),
            ("comparison", "方案 A 价格 399 元；方案 B 价格 459 元，帮我对比一下选哪个。", "整理对比信息"),
            ("collection", "图书馆总服务台电话 010-12345678，地址：主校区图书馆一层大厅。", "收藏截图信息"),
        ]

        for card_type, text, title in cases:
            with self.subTest(card_type=card_type):
                summary = build_summary(card_type=card_type, text=text, title=title)
                self.assertTrue(summary)
                self.assertNotEqual(summary, text)
                self.assertLessEqual(len(summary), 60)

    def test_llm_payload_normalization_repairs_generic_output(self) -> None:
        text = "请同学们在本周五晚上 22:00 前提交实验报告，提交至学习通。"
        hints = build_llm_context(text)["detected_hints"]

        payload = _normalize_card_payload(
            {
                "card_type": "任务",
                "title": "处理截图事项",
                "summary": text,
                "status": "confirmed",
                "priority": "中",
                "materials": "实验报告",
                "reminders": "",
            },
            text,
            "card-1",
            datetime.now(timezone.utc),
            hints,
        )

        self.assertEqual(payload["card_type"], "task")
        self.assertEqual(payload["status"], "confirmed")
        self.assertEqual(payload["priority"], "normal")
        self.assertEqual(payload["title"], "提交实验报告")
        self.assertNotEqual(payload["summary"], text)
        self.assertEqual(payload["materials"], ["实验报告"])

    def test_need_confirm_enrichment_adds_missing_key_fields(self) -> None:
        fields = enrich_need_confirm(
            card_type="event",
            need_confirm=[],
            deadline=None,
            start_time=None,
            location=None,
            submit_method=None,
            hints={"fuzzy_time_expressions": []},
        )

        self.assertIn("时间", fields)
        self.assertIn("地点", fields)

    def test_raw_like_summary_should_be_rewritten(self) -> None:
        text = "请同学们在本周五晚上 22:00 前提交实验报告，提交至学习通。"

        self.assertTrue(should_rewrite_summary(text, text))
        self.assertFalse(should_rewrite_summary("周五 22:00 前提交实验报告至学习通", text))


class RuleSummaryAndSplitTest(unittest.TestCase):
    def test_rule_fallback_summary_is_structured(self) -> None:
        text = "请同学们在本周五晚上 22:00 前提交实验报告，提交至学习通，文件命名为学号+姓名。"

        card = extract_cards_with_rules(text)[0]

        self.assertIn("实验报告", card.summary)
        self.assertIn("学习通", card.summary)
        self.assertNotEqual(card.summary, text[:120])

    def test_signup_and_event_generates_two_cards(self) -> None:
        text = "校园创新赛报名截止 5 月 15 日 23:59，需提交报名表和作品说明书；决赛路演 5 月 20 日下午 2 点在大学生活动中心举行。"

        cards = extract_cards_with_rules(text)

        self.assertIn("task", [card.card_type for card in cards])
        self.assertIn("event", [card.card_type for card in cards])

    def test_long_notice_generates_multiple_task_cards(self) -> None:
        text = "课程通知：请本周五 22:00 前提交实验报告至学习通；另请下周一上午 9 点前把项目 PPT 发送至指定邮箱。"

        cards = extract_cards_with_rules(text)

        self.assertGreaterEqual([card.card_type for card in cards].count("task"), 2)

    def test_mixed_deadlines_registration_and_meeting_split_into_specific_cards(self) -> None:
        text = "课程通知：6月26日22:00前提交实验报告到学习通，文件命名为学号+姓名；6月27日18:00前完成报名表提交；6月28日9:30参加项目汇报会。"

        cards = extract_cards_with_rules(text, "2026-06-21T15:14:00+08:00")

        self.assertEqual(len(cards), 3)
        self.assertEqual([card.title for card in cards], ["提交实验报告", "提交报名表", "参加项目汇报"])
        self.assertEqual([card.card_type for card in cards], ["task", "task", "event"])
        self.assertNotIn("相关日程", [card.title for card in cards])

    def test_fuzzy_time_enters_need_confirm(self) -> None:
        text = "请各组在本月底前完成项目材料整理，并提交商业计划书和团队信息表。"

        card = extract_cards_with_rules(text)[0]

        self.assertIn("时间", card.need_confirm)

    def test_non_action_text_returns_no_cards(self) -> None:
        cases = [
            "这是一段普通文章内容，主要介绍校园建筑历史和开放时间。",
            "商品详情：白色水杯，容量 500ml，图片仅供参考。",
            "图书馆总服务台电话 010-12345678，地址：主校区图书馆一层大厅。",
        ]

        for text in cases:
            with self.subTest(text=text):
                self.assertEqual(extract_cards_with_rules(text), [])

    def test_core_action_types_still_generate_cards(self) -> None:
        cases = [
            ("请同学们在本周五晚上 22:00 前提交实验报告，提交至学习通。", "task"),
            ("明天下午 3 点在 A101 开组会。", "event"),
            ("你明天上午能不能帮我把表格发给老师？可以，我明天上午发。", "promise"),
            ("方案 A 价格 399 元；方案 B 价格 459 元，帮我对比一下选哪个。", "comparison"),
        ]

        for text, card_type in cases:
            with self.subTest(card_type=card_type):
                cards = extract_cards_with_rules(text)
                self.assertTrue(cards)
                self.assertEqual(cards[0].card_type, card_type)


if __name__ == "__main__":
    unittest.main()
