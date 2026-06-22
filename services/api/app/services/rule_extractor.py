from __future__ import annotations

import re
import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

from app.schemas.card import ActionCard
from app.services.extraction_context import build_summary, detect_hints
from app.services.reminders import recommend_reminders

CN_TZ = timezone(timedelta(hours=8))

TASK_WORDS = ["提交", "报名", "上传", "填写", "截止", "作业", "报告", "发送", "准备", "完成", "整理"]
EVENT_WORDS = ["开会", "会议", "组会", "讲座", "集合", "活动", "考试", "面试", "召开", "举行", "参加"]
PROMISE_WORDS = ["帮我", "帮你", "答应", "可以，我", "我来", "没问题", "承诺", "说好了"]
COMPARISON_WORDS = ["对比", "比较", "区别", "选哪个", "哪款", "哪个更", "还是", "vs", "VS"]
OBJECT_WORDS = ["老师", "同学", "同学们", "各组", "全体", "负责人", "报名表", "作品说明书", "实验报告", "进展汇报", "PPT", "商业计划书", "团队信息表", "表格", "材料", "证件", "文件"]


@dataclass
class TimeGuess:
    value: str | None
    fuzzy: bool = False


def _now() -> datetime:
    return datetime.now(CN_TZ)


def _next_weekday(base: datetime, weekday: int, next_week: bool = False) -> datetime:
    days = (weekday - base.weekday()) % 7
    if next_week:
        days += 7
    if days == 0 and not next_week:
        days = 7
    return base + timedelta(days=days)


def _extract_hour(text: str, default_hour: int = 9) -> tuple[int, int, bool]:
    minute = 0
    # Screenshots often contain status-bar/file times before the real event time.
    for match in re.finditer(r"(?P<hour>\d{1,2})[:：](?P<minute>\d{2})", text):
        if not _is_action_time_candidate(text, match):
            continue
        hour = int(match.group("hour"))
        minute = int(match.group("minute"))
        prefix = _nearby_time_prefix(text, match.start())
        if prefix in {"下午", "晚上", "今晚", "晚"} and hour < 12:
            hour += 12
        if prefix == "中午" and hour < 11:
            hour += 12
        return hour, minute, False

    pattern = r"(上午|早上|中午|下午|晚上|今晚|晚)?\s*(?P<hour>\d{1,2})\s*点\s*(?P<minute>\d{1,2})?分?"
    for match in re.finditer(pattern, text):
        if not _is_action_time_candidate(text, match):
            continue
        hour = int(match.group("hour"))
        minute_group = match.groupdict().get("minute")
        minute = int(minute_group) if minute_group else 0
        prefix = match.group(1) if match.lastindex and match.group(1) else ""
        if prefix in {"下午", "晚上", "今晚", "晚"} and hour < 12:
            hour += 12
        if prefix == "中午" and hour < 11:
            hour += 12
        return hour, minute, False

    if any(word in text for word in ["上午", "早上"]):
        return 9, 0, True
    if "中午" in text:
        return 12, 0, True
    if any(word in text for word in ["下午", "晚上", "今晚"]):
        return 15 if "下午" in text else 20, 0, True
    return default_hour, minute, True


def _nearby_time_prefix(text: str, start: int) -> str:
    prefix_match = re.search(r"(上午|早上|中午|下午|晚上|今晚|晚)\s*$", text[max(0, start - 8):start])
    return prefix_match.group(1) if prefix_match else ""


def _is_action_time_candidate(text: str, match: re.Match[str]) -> bool:
    window = text[max(0, match.start() - 28):min(len(text), match.end() + 28)]
    small_window = text[max(0, match.start() - 12):min(len(text), match.end() + 12)]
    compact = re.sub(r"\s+", "", window)
    small_compact = re.sub(r"\s+", "", small_window)
    if re.search(r"video_\d{8}_\d{6}|KB/s|5G|群文件|\d+(\.\d+)?GB|昨天\d{1,2}[:：]\d{2}", small_compact, flags=re.I):
        return False
    return any(word in compact for word in ["通知", "会议", "请", "参加", "地点", "时间", "本周", "下周", "上午", "下午", "晚上", "今晚", "前"])


def extract_time(text: str, screenshot_time: str | None = None) -> TimeGuess:
    base = _now()
    if screenshot_time:
        try:
            base = datetime.fromisoformat(screenshot_time).astimezone(CN_TZ)
        except ValueError:
            pass

    hour, minute, fuzzy_hour = _extract_hour(text)

    for month_day in re.finditer(r"(?P<month>\d{1,2})\s*(?:月|[.．])\s*(?P<day>\d{1,2})\s*[日号]?", text):
        month = int(month_day.group("month"))
        day = int(month_day.group("day"))
        if not (1 <= month <= 12 and 1 <= day <= 31):
            continue
        year = base.year
        candidate = datetime(year, month, day, hour, minute, tzinfo=CN_TZ)
        if candidate < base - timedelta(days=1):
            candidate = candidate.replace(year=year + 1)
        return TimeGuess(candidate.isoformat(), fuzzy=fuzzy_hour)

    if "明天" in text:
        candidate = base + timedelta(days=1)
        return TimeGuess(candidate.replace(hour=hour, minute=minute, second=0, microsecond=0).isoformat(), fuzzy=True)

    weekday_map = {
        "一": 0,
        "二": 1,
        "三": 2,
        "四": 3,
        "五": 4,
        "六": 5,
        "日": 6,
        "天": 6,
    }
    week_match = re.search(r"(?P<prefix>本周|这周|下周|周|星期)(?P<weekday>[一二三四五六日天])", text)
    if week_match:
        candidate = _next_weekday(
            base,
            weekday_map[week_match.group("weekday")],
            next_week=week_match.group("prefix") == "下周",
        )
        return TimeGuess(candidate.replace(hour=hour, minute=minute, second=0, microsecond=0).isoformat(), fuzzy=fuzzy_hour)

    if any(word in text for word in ["今天", "今晚"]):
        candidate = base.replace(hour=hour, minute=minute, second=0, microsecond=0)
        return TimeGuess(candidate.isoformat(), fuzzy=fuzzy_hour)

    return TimeGuess(None, fuzzy=False)


def _extract_materials(text: str) -> list[str]:
    candidates = ["报名表", "作品说明书", "实验报告", "进展汇报", "PPT", "商业计划书", "团队信息表", "表格", "材料", "证件", "文件"]
    return [item for item in candidates if item in text]


def _has_time_signal(text: str) -> bool:
    time_patterns = [
        r"\d{1,2}\s*(?:月|[.．])\s*\d{1,2}\s*[日号]?",
        r"(?:本周|这周|下周|周|星期)[一二三四五六日天]",
        r"(?:今天|明天|后天|今晚|上午|早上|中午|下午|晚上)",
        r"\d{1,2}[:：]\d{2}",
        r"\d{1,2}\s*点",
        r"本月底|月底|近期|近日|\d{1,2}\s*月\s*(?:上旬|中旬|下旬)",
    ]
    return any(re.search(pattern, text) for pattern in time_patterns)


def _has_key_signal(text: str) -> bool:
    # 平衡策略：不要求字段完整，但必须能看到时间、地点、提交物、平台、对象或对比选项等锚点。
    return any(
        [
            _has_time_signal(text),
            bool(_extract_materials(text)),
            _extract_location(text) is not None,
            _extract_submit_method(text) is not None,
            any(word in text for word in OBJECT_WORDS),
            re.search(r"(A|B|方案|选项|¥|￥|\d+\s*元)", text) is not None,
        ]
    )


def is_actionable_text(text: str) -> bool:
    normalized = re.sub(r"\s+", " ", text).strip()
    if len(normalized) < 4:
        return False
    has_action_signal = any(
        [
            any(word in normalized for word in TASK_WORDS),
            any(word in normalized for word in EVENT_WORDS),
            any(word in normalized for word in PROMISE_WORDS),
            any(word in normalized for word in COMPARISON_WORDS),
        ]
    )
    return has_action_signal and _has_key_signal(normalized)


def filter_action_cards(cards: list[ActionCard], source_text: str) -> list[ActionCard]:
    result: list[ActionCard] = []
    for card in cards:
        if card.card_type == "collection":
            continue
        # 用单卡 source_text 复核，避免 LLM 从无关截图里硬编任务卡。
        if is_actionable_text(card.source_text or source_text):
            result.append(card)
    return result


def _extract_location(text: str) -> str | None:
    patterns = [
        r"在(?P<location>[^，。；\n]{2,40})(集合|开会|参加|签到|考试|召开|举行)",
        r"地点[:：]\s*(?P<location>[^，。；\n]{2,32})",
        r"至(?P<location>学习通|官网|邮箱|指定邮箱)",
    ]
    for pattern in patterns:
        match = re.search(pattern, text)
        if match:
            return match.group("location").strip()
    if "学习通" in text:
        return "学习通"
    if "官网" in text:
        return "官网"
    return None


def _extract_submit_method(text: str) -> str | None:
    if "学习通" in text:
        return "提交至学习通"
    if "邮箱" in text:
        return "发送至指定邮箱" if "指定邮箱" in text else "发送至邮箱"
    if "官网" in text or "报名链接" in text:
        return "官网报名链接"
    return None


def _is_comparison_text(text: str) -> bool:
    price_or_option = re.search(r"(A|B|方案|选项|¥|￥|\d+\s*元)", text) is not None
    return any(word in text for word in COMPARISON_WORDS) and price_or_option


def _title_for(text: str, card_type: str) -> str:
    if card_type == "comparison":
        return "整理对比信息"
    if card_type == "collection":
        return "收藏截图信息"
    if "实验报告" in text:
        return "提交实验报告"
    if "报名" in text and "比赛" in text:
        return "完成比赛报名"
    if "AIGC" in text:
        return "完成 AIGC 创新赛报名"
    if "报名表" in text:
        return "提交报名表"
    if "组会" in text:
        return "参加组会"
    if "进展汇报" in text:
        return "准备进展汇报"
    if "汇报会" in text or ("项目" in text and "汇报" in text):
        return "参加项目汇报"
    if "社团" in text or "集合" in text:
        return "社团活动集合"
    if "表格" in text and "老师" in text:
        return "帮同学把表格发给老师"
    if "PPT" in text:
        return "发送项目 PPT"
    if "商业计划书" in text or "团队信息表" in text:
        return "提交项目材料"
    if "提交" in text:
        return "提交材料"
    if "开会" in text or "会议" in text:
        return "参加会议"
    return "处理截图事项"


def _classify(text: str) -> str | None:
    if _is_comparison_text(text):
        return "comparison"
    if any(word in text for word in PROMISE_WORDS):
        return "promise"
    if any(word in text for word in EVENT_WORDS):
        return "event"
    if any(word in text for word in TASK_WORDS):
        return "task"
    return None


def _tags(text: str, card_type: str) -> list[str]:
    tags: list[str] = []
    for keyword, tag in [
        ("课程", "课程"),
        ("实验报告", "课程作业"),
        ("比赛", "比赛"),
        ("AIGC", "比赛"),
        ("社团", "社团"),
        ("组会", "会议"),
        ("会议", "会议"),
        ("考试", "考试"),
        ("报名", "报名"),
    ]:
        if keyword in text and tag not in tags:
            tags.append(tag)
    if not tags:
        tags.append(
            {
                "task": "任务",
                "event": "日程",
                "promise": "承诺",
                "comparison": "对比",
                "collection": "收藏",
            }[card_type]
        )
    return tags


def _need_confirm(text: str, time_guess: TimeGuess, location: str | None, submit_method: str | None) -> list[str]:
    fields: list[str] = []
    if time_guess.fuzzy or any(word in text for word in ["本月底", "月底", "近期", "近日", "中旬", "上旬", "下旬"]):
        fields.append("时间")
    if any(word in text for word in ["指定邮箱", "指定平台"]) and submit_method:
        fields.append("提交方式")
    if any(word in text for word in ["活动", "会议", "集合", "考试"]) and not location:
        fields.append("地点")
    if "表格" in text and "位置" not in text:
        fields.append("表格位置")
    return fields


def build_card(text: str, card_type: str, screenshot_time: str | None = None, title: str | None = None) -> ActionCard:
    time_guess = extract_time(text, screenshot_time)
    materials = _extract_materials(text)
    location = _extract_location(text)
    submit_method = _extract_submit_method(text)
    priority = "high" if any(word in text for word in ["截止", "逾期", "前提交", "考试", "报名"]) else "normal"
    has_time = bool(time_guess.value)
    deadline = time_guess.value if card_type in {"task", "promise"} else None
    start_time = time_guess.value if card_type == "event" else None
    need_confirm = _need_confirm(text, time_guess, location, submit_method)
    reminders = recommend_reminders(card_type, priority, has_time)
    card_title = title or _title_for(text, card_type)
    hints = detect_hints(text, screenshot_time)

    return ActionCard(
        id=str(uuid.uuid4()),
        card_type=card_type,
        title=card_title,
        summary=build_summary(
            card_type=card_type,
            text=text,
            title=card_title,
            deadline=deadline,
            start_time=start_time,
            location=location,
            materials=materials,
            submit_method=submit_method,
            hints=hints,
        ),
        deadline=deadline,
        start_time=start_time,
        end_time=None,
        location=location,
        materials=materials,
        submit_method=submit_method,
        priority=priority,
        tags=_tags(text, card_type),
        reminders=reminders,
        need_confirm=need_confirm,
        status="draft",
        source_text=text,
        created_at=datetime.now(timezone.utc),
    )


def extract_cards_with_rules(text: str, screenshot_time: str | None = None) -> list[ActionCard]:
    normalized = re.sub(r"\s+", " ", text).strip()
    cards: list[ActionCard] = []

    if not is_actionable_text(normalized):
        return []

    if any(word in normalized for word in ["组会", "开会", "会议"]) and any(word in normalized for word in ["准备", "汇报"]):
        cards.append(build_card(normalized, "event", screenshot_time, title="参加组会" if "组会" in normalized else "参加会议"))
        cards.append(build_card(normalized, "task", screenshot_time, title="准备进展汇报" if "汇报" in normalized else "准备会议材料"))
        return cards

    if "报名" in normalized and "截止" in normalized and any(word in normalized for word in ["举行", "路演", "活动时间"]):
        cards.append(build_card(normalized, "task", screenshot_time, title=_title_for(normalized, "task")))
        cards.append(build_card(normalized, "event", screenshot_time, title="参加比赛活动"))
        return cards

    if "比赛" in normalized and "报名" in normalized and any(word in normalized for word in ["提交材料", "作品说明书", "报名表"]):
        cards.append(build_card(normalized, "task", screenshot_time, title=_title_for(normalized, "task")))
        if any(word in normalized for word in ["举行", "路演", "活动时间"]) and any(word in normalized for word in ["活动中心", "地点", "在"]):
            cards.append(build_card(normalized, "event", screenshot_time, title="参加比赛活动"))
        return cards

    action_segments = _split_action_segments(normalized)
    if len(action_segments) > 1:
        for segment in action_segments:
            segment_type = _classify(segment) or "task"
            cards.append(build_card(segment, segment_type, screenshot_time, title=_title_for(segment, segment_type)))
        return cards

    card_type = _classify(normalized)
    if card_type is None:
        return []
    cards.append(build_card(normalized, card_type, screenshot_time))
    return cards


def _split_action_segments(text: str) -> list[str]:
    pieces = [piece.strip(" ，,。；;、") for piece in re.split(r"[；;。]", text) if piece.strip()]
    action_pieces: list[str] = []
    for piece in pieces:
        if len(piece) < 4:
            continue
        if is_actionable_text(piece):
            action_pieces.append(piece)
            continue
        has_action = any(word in piece for word in TASK_WORDS + EVENT_WORDS + PROMISE_WORDS)
        has_object = any(word in piece for word in OBJECT_WORDS + ["汇报", "汇报会", "报名"])
        if has_action and (has_object or _has_time_signal(piece)):
            action_pieces.append(piece)
    return action_pieces if len(action_pieces) > 1 else []


def preview_actions_for(cards: list[ActionCard]) -> list[str]:
    actions: list[str] = []
    for card in cards:
        if card.card_type == "event":
            actions.append(f"创建日历事件：{card.title}")
        elif card.card_type == "task":
            actions.append(f"创建待办任务：{card.title}")
        elif card.card_type == "promise":
            actions.append(f"创建承诺提醒：{card.title}")
        elif card.card_type == "comparison":
            actions.append(f"生成对比卡：{card.title}")
        else:
            actions.append(f"保存收藏卡：{card.title}")
        if card.reminders:
            actions.append(f"设置提醒：{'、'.join(card.reminders)}")
        if card.need_confirm:
            actions.append(f"需要确认：{'、'.join(card.need_confirm)}")
    return actions
