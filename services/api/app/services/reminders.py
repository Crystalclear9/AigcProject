from __future__ import annotations


def recommend_reminders(card_type: str, priority: str, has_time: bool) -> list[str]:
    if not has_time:
        return []
    if card_type == "event":
        return ["开始前 1 天", "开始前 30 分钟"]
    if card_type == "promise":
        return ["约定时间前 1 小时"]
    if priority == "high":
        return ["截止前 3 天", "截止前 1 天", "截止前 3 小时", "截止前 30 分钟"]
    return ["截止前 1 天", "截止前 3 小时", "截止前 30 分钟"]
