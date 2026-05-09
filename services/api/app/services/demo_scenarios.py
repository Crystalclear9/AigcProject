from __future__ import annotations

from dataclasses import dataclass

from app.services.rule_extractor import extract_cards_with_rules


@dataclass(frozen=True)
class DemoScenario:
    id: str
    name: str
    text: str
    expected_types: list[str]
    expected_keywords: list[str]


DEMO_SCENARIOS = [
    DemoScenario(
        id="course_notice",
        name="课程通知处理",
        text="请同学们在本周五晚上 22:00 前提交实验报告，提交至学习通，文件命名为学号+姓名。",
        expected_types=["task"],
        expected_keywords=["实验报告", "学习通"],
    ),
    DemoScenario(
        id="competition_signup",
        name="比赛报名处理",
        text="AIGC 创新赛报名截止时间为 5 月 15 日 23:59，请提交报名表和作品说明书，通过官网报名链接提交。",
        expected_types=["task"],
        expected_keywords=["AIGC", "报名表", "作品说明书"],
    ),
    DemoScenario(
        id="club_activity",
        name="社团活动安排",
        text="本周六下午 2 点在大学生活动中心集合，负责签到的同学请提前 30 分钟到场。",
        expected_types=["event"],
        expected_keywords=["社团活动", "大学生活动中心"],
    ),
    DemoScenario(
        id="chat_promise",
        name="聊天承诺识别",
        text="你明天上午能不能帮我把表格发给老师？可以，我明天上午发。",
        expected_types=["promise"],
        expected_keywords=["表格", "老师"],
    ),
    DemoScenario(
        id="meeting_preparation",
        name="会议与准备事项",
        text="明天下午 3 点开组会，每个人准备 5 分钟进展汇报。",
        expected_types=["event", "task"],
        expected_keywords=["组会", "进展汇报"],
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
        }
        for scenario in DEMO_SCENARIOS
    ]


def evaluate_demo_scenarios() -> dict[str, object]:
    results: list[dict[str, object]] = []
    passed_count = 0

    for scenario in DEMO_SCENARIOS:
        cards = extract_cards_with_rules(scenario.text)
        actual_types = [card.card_type for card in cards]
        combined_text = " ".join(
            " ".join(
                [
                    card.title,
                    card.summary,
                    card.location or "",
                    card.submit_method or "",
                    " ".join(card.materials),
                ]
            )
            for card in cards
        )
        type_ok = all(expected in actual_types for expected in scenario.expected_types)
        keyword_ok = all(keyword in combined_text for keyword in scenario.expected_keywords)
        passed = type_ok and keyword_ok
        if passed:
            passed_count += 1
        results.append(
            {
                "id": scenario.id,
                "name": scenario.name,
                "passed": passed,
                "expected_types": scenario.expected_types,
                "actual_types": actual_types,
                "expected_keywords": scenario.expected_keywords,
                "card_titles": [card.title for card in cards],
                "need_confirm": [field for card in cards for field in card.need_confirm],
                "reminders": [reminder for card in cards for reminder in card.reminders],
            }
        )

    total = len(DEMO_SCENARIOS)
    return {
        "total": total,
        "passed": passed_count,
        "pass_rate": round(passed_count / total, 4),
        "results": results,
    }
