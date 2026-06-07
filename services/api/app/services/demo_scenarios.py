from __future__ import annotations

from dataclasses import dataclass, field

from app.schemas.card import ActionCard
from app.services.rule_extractor import extract_cards_with_rules


@dataclass(frozen=True)
class DemoScenario:
    id: str
    name: str
    text: str
    expected_types: list[str]
    expected_keywords: list[str]
    expected_titles: list[str] = field(default_factory=list)
    expected_card_count: int | None = None
    expected_time_fields: list[str] = field(default_factory=list)
    expected_location_or_platform: list[str] = field(default_factory=list)
    expected_materials: list[str] = field(default_factory=list)
    expected_reminders: list[str] = field(default_factory=list)
    expected_need_confirm: list[str] = field(default_factory=list)


DEMO_SCENARIOS = [
    DemoScenario(
        id="course_notice",
        name="课程通知处理",
        text="请同学们在本周五晚上 22:00 前提交实验报告，提交至学习通，文件命名为学号+姓名。",
        expected_types=["task"],
        expected_keywords=["实验报告", "学习通"],
        expected_titles=["提交实验报告"],
        expected_card_count=1,
        expected_time_fields=["deadline"],
        expected_location_or_platform=["学习通"],
        expected_materials=["实验报告"],
        expected_reminders=["截止前 3 天", "截止前 1 天", "截止前 3 小时", "截止前 30 分钟"],
        expected_need_confirm=[],
    ),
    DemoScenario(
        id="competition_signup",
        name="比赛报名处理",
        text="AIGC 创新赛报名截止时间为 5 月 15 日 23:59，请提交报名表和作品说明书，通过官网报名链接提交。",
        expected_types=["task"],
        expected_keywords=["AIGC", "报名表", "作品说明书"],
        expected_titles=["完成 AIGC 创新赛报名"],
        expected_card_count=1,
        expected_time_fields=["deadline"],
        expected_location_or_platform=["官网报名链接"],
        expected_materials=["报名表", "作品说明书"],
        expected_reminders=["截止前 3 天", "截止前 1 天", "截止前 3 小时", "截止前 30 分钟"],
        expected_need_confirm=[],
    ),
    DemoScenario(
        id="club_activity",
        name="社团活动安排",
        text="本周六下午 2 点在大学生活动中心集合，负责签到的同学请提前 30 分钟到场。",
        expected_types=["event"],
        expected_keywords=["社团活动", "大学生活动中心"],
        expected_titles=["社团活动集合"],
        expected_card_count=1,
        expected_time_fields=["start_time"],
        expected_location_or_platform=["大学生活动中心"],
        expected_materials=[],
        expected_reminders=["开始前 1 天", "开始前 30 分钟"],
        expected_need_confirm=[],
    ),
    DemoScenario(
        id="chat_promise",
        name="聊天承诺识别",
        text="你明天上午能不能帮我把表格发给老师？可以，我明天上午发。",
        expected_types=["promise"],
        expected_keywords=["表格", "老师"],
        expected_titles=["帮同学把表格发给老师"],
        expected_card_count=1,
        expected_time_fields=["deadline"],
        expected_location_or_platform=[],
        expected_materials=["表格"],
        expected_reminders=["约定时间前 1 小时"],
        expected_need_confirm=["时间", "表格位置"],
    ),
    DemoScenario(
        id="meeting_preparation",
        name="会议与准备事项",
        text="明天下午 3 点开组会，每个人准备 5 分钟进展汇报。",
        expected_types=["event", "task"],
        expected_keywords=["组会", "进展汇报"],
        expected_titles=["参加组会", "准备进展汇报"],
        expected_card_count=2,
        expected_time_fields=["start_time", "deadline"],
        expected_location_or_platform=[],
        expected_materials=["进展汇报"],
        expected_reminders=["开始前 1 天", "开始前 30 分钟", "截止前 1 天", "截止前 3 小时", "截止前 30 分钟"],
        expected_need_confirm=["时间"],
    ),
    DemoScenario(
        id="option_comparison",
        name="选项对比识别",
        text="方案 A 价格 399 元，续航 8 小时；方案 B 价格 459 元，续航 12 小时，帮我对比一下选哪个。",
        expected_types=["comparison"],
        expected_keywords=["对比", "方案"],
    ),
    DemoScenario(
        id="non_action_info",
        name="非行动信息过滤",
        text="图书馆总服务台电话 010-12345678，地址：主校区图书馆一层大厅。",
        expected_types=[],
        expected_keywords=[],
        expected_card_count=0,
    ),
    DemoScenario(
        id="signup_and_event",
        name="报名截止与活动时间拆分",
        text="校园创新赛报名截止 5 月 15 日 23:59，需提交报名表和作品说明书；决赛路演 5 月 20 日下午 2 点在大学生活动中心举行。",
        expected_types=["task", "event"],
        expected_keywords=["报名表", "大学生活动中心"],
    ),
    DemoScenario(
        id="long_multi_task_notice",
        name="长通知多任务识别",
        text="课程通知：请本周五 22:00 前提交实验报告至学习通；另请下周一上午 9 点前把项目 PPT 发送至指定邮箱。",
        expected_types=["task", "task"],
        expected_keywords=["实验报告", "PPT", "学习通", "邮箱"],
    ),
    DemoScenario(
        id="fuzzy_time_notice",
        name="模糊时间待确认",
        text="请各组在本月底前完成项目材料整理，并提交商业计划书和团队信息表。",
        expected_types=["task"],
        expected_keywords=["商业计划书", "团队信息表"],
    ),
]


def scenario_catalog() -> list[dict[str, object]]:
    return [
        {
            "id": scenario.id,
            "name": scenario.name,
            "text": scenario.text,
            "expected_types": scenario.expected_types,
            "expected_keywords": scenario.expected_keywords,
            "expected_titles": scenario.expected_titles,
            "expected_card_count": scenario.expected_card_count,
        }
        for scenario in DEMO_SCENARIOS
    ]


def _combined_text(cards: list[ActionCard]) -> str:
    return " ".join(
        " ".join(
            [
                card.title,
                card.summary,
                card.location or "",
                card.submit_method or "",
                " ".join(card.materials),
                " ".join(card.reminders),
                " ".join(card.need_confirm),
            ]
        )
        for card in cards
    )


def _field_checks(scenario: DemoScenario, cards: list[ActionCard]) -> dict[str, bool]:
    actual_types = [card.card_type for card in cards]
    combined_text = _combined_text(cards)
    time_field_ok = all(
        any(getattr(card, field) for card in cards)
        for field in scenario.expected_time_fields
    )
    return {
        "card_count": scenario.expected_card_count is None or len(cards) == scenario.expected_card_count,
        "card_types": all(expected in actual_types for expected in scenario.expected_types),
        "titles": all(title in [card.title for card in cards] for title in scenario.expected_titles),
        "keywords": all(keyword in combined_text for keyword in scenario.expected_keywords),
        "time_fields": time_field_ok,
        "location_or_platform": all(item in combined_text for item in scenario.expected_location_or_platform),
        "materials": all(item in [material for card in cards for material in card.materials] for item in scenario.expected_materials),
        "reminders": all(item in [reminder for card in cards for reminder in card.reminders] for item in scenario.expected_reminders),
        "need_confirm": all(item in [field for card in cards for field in card.need_confirm] for item in scenario.expected_need_confirm),
    }


def evaluate_demo_scenarios() -> dict[str, object]:
    results: list[dict[str, object]] = []
    passed_count = 0
    field_passed_count = 0

    for scenario in DEMO_SCENARIOS:
        cards = extract_cards_with_rules(scenario.text)
        actual_types = [card.card_type for card in cards]
        checks = _field_checks(scenario, cards)
        passed = all(checks.values())
        if passed:
            passed_count += 1
        field_passed_count += sum(1 for ok in checks.values() if ok)
        results.append(
            {
                "id": scenario.id,
                "name": scenario.name,
                "passed": passed,
                "field_checks": checks,
                "expected_types": scenario.expected_types,
                "actual_types": actual_types,
                "expected_card_count": scenario.expected_card_count,
                "actual_card_count": len(cards),
                "expected_keywords": scenario.expected_keywords,
                "card_titles": [card.title for card in cards],
                "time_fields": [
                    {
                        "title": card.title,
                        "deadline": card.deadline,
                        "start_time": card.start_time,
                        "end_time": card.end_time,
                    }
                    for card in cards
                ],
                "locations": [card.location for card in cards if card.location],
                "submit_methods": [card.submit_method for card in cards if card.submit_method],
                "materials": [material for card in cards for material in card.materials],
                "need_confirm": [field for card in cards for field in card.need_confirm],
                "reminders": [reminder for card in cards for reminder in card.reminders],
            }
        )

    total = len(DEMO_SCENARIOS)
    field_total = sum(len(_field_checks(scenario, extract_cards_with_rules(scenario.text))) for scenario in DEMO_SCENARIOS)
    return {
        "total": total,
        "passed": passed_count,
        "pass_rate": round(passed_count / total, 4),
        "field_checks_total": field_total,
        "field_checks_passed": field_passed_count,
        "field_pass_rate": round(field_passed_count / field_total, 4) if field_total else 0,
        "results": results,
    }
