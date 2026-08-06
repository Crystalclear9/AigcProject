from __future__ import annotations

import operator
import re
import uuid
from datetime import datetime, timezone
from typing import Annotated, Any, TypedDict

from langgraph.graph import END, START, StateGraph
from langgraph.types import Send

from app.services.rule_extractor import extract_cards_with_rules
from app.services.extraction_context import build_summary
from app.services.text_integrity import evaluate_text_integrity
from app.schemas.card import ActionCard

ACTION_WORDS = (
    "提交",
    "上传",
    "交",
    "参加",
    "完成",
    "报名",
    "准备",
    "发送",
    "发到",
    "回复",
    "发送",
    "发给",
    "我会",
    "答应",
    "开会",
    "会议",
    "答辩",
    "开题",
    "评审",
    "考试",
    "作业",
    "截止",
    "提醒",
    "请于",
    "务必",
    "submit",
    "upload",
    "finish",
    "complete",
    "prepare",
    "review",
    "publish",
    "update",
    "book",
    "summarize",
    "must",
    "deadline",
)
TIME_PATTERN = re.compile(
    r"(?:20\d{2}[年./-])?\d{1,2}[月./-]\d{1,2}[日号]?|"
    r"\d{1,2}:\d{2}|今天|明天|后天|本周[一二三四五六日天]|截止"
)
NOISE_WORDS = ("电量", "信号", "返回", "设置", "首页", "购物", "立即购买", "广告")
COMMERCE_WORDS = (
    "限时秒杀",
    "优惠",
    "满减",
    "购物车",
    "下单",
    "抽奖",
    "直播间",
    "红包",
    "立即抢购",
)
ACTION_OBJECTS = (
    "报告",
    "答辩",
    "报名表",
    "报名材料",
    "申请表",
    "报价表",
    "合同",
    "作业",
    "方案",
    "PPT",
    "材料",
    "数据",
    "会议",
    "选题表",
    "参考文献",
    "原型",
    "PDF",
    "report",
    "assignment",
    "budget",
    "contract",
    "notes",
    "prototype",
    "spreadsheet",
    "screenshots",
    "deliverable",
)
PLATFORM_PATTERNS = ("学习通", "腾讯会议", "群文件", "雨课堂", "钉钉", "飞书", "Moodle")
DELIVERABLE_PATTERNS = (
    "实验报告",
    "答辩PPT",
    "PPT",
    "报名表",
    "报名材料",
    "测试报告",
    "需求分析",
    "实验数据",
    "数据",
    "申请表",
    "报价表",
    "合同",
    "选题表",
    "参考文献",
    "原型",
    "PDF",
)
NEGATED_ACTIONS = ("无需完成", "不用提交", "不需要参加", "仅供参考", "无需报名")
DECORATION_PHRASES = ("会议群聊天记录", "通知中心", "截图文字可能有空格")
INFORMATIONAL_PHRASES = ("背景介绍", "仅供阅读", "课程目标", "评分标准", "参考信息")


class IntakeGraphState(TypedDict, total=False):
    text: str
    workspace_type: str
    canonical_text: str
    classification: str
    classification_confidence: float
    should_create_cards: bool
    segments: list[str]
    analyzer: str
    analyzer_results: Annotated[list[dict[str, Any]], operator.add]
    cards: list[dict[str, Any]]
    team_tasks: list[dict[str, Any]]
    findings: list[str]


def normalize_input(state: IntakeGraphState) -> dict[str, Any]:
    return {
        "canonical_text": normalize_ocr_spacing(
            merge_overlapping_lines(state.get("text", ""))
        )
    }


def classify_input(state: IntakeGraphState) -> dict[str, Any]:
    text = state.get("canonical_text", "")
    scored_text = text
    for phrase in DECORATION_PHRASES:
        scored_text = scored_text.replace(phrase, "")
    lowered = scored_text.lower()
    action_hits = sum(word.lower() in lowered for word in ACTION_WORDS)
    time_hits = len(TIME_PATTERN.findall(scored_text))
    object_hits = sum(word in scored_text for word in ACTION_OBJECTS)
    noise_hits = sum(word in scored_text for word in NOISE_WORDS)
    commerce_hits = sum(word in scored_text for word in COMMERCE_WORDS)
    negated_hits = sum(word in scored_text for word in NEGATED_ACTIONS)
    integrity = evaluate_text_integrity(text)
    action_hits = max(0, action_hits - negated_hits)
    segments = split_action_segments(
        text,
        split_commas=state.get("workspace_type") == "team",
    )
    actionable_segments = contextualize_action_segments(
        segments,
        workspace_type=state.get("workspace_type", "personal"),
    )
    if "mojibake" in integrity.reasons:
        classification, confidence = "uncertain", min(0.55, integrity.score)
    elif not text.strip() or (
        noise_hits >= 2
        and (action_hits == 0 or (negated_hits > 0 and time_hits == 0 and object_hits == 0))
    ) or (noise_hits >= 1 and negated_hits > 0 and time_hits == 0) or (
        commerce_hits >= 2 and object_hits == 0
    ):
        classification, confidence = "noise", 0.92
    elif action_hits == 0:
        classification, confidence = "informational", 0.78
    elif time_hits == 0 and object_hits == 0:
        classification, confidence = "uncertain", 0.52
    elif any(phrase in scored_text for phrase in INFORMATIONAL_PHRASES):
        classification, confidence = "mixed", min(0.95, 0.68 + action_hits * 0.05)
    else:
        classification = "actionable"
        confidence = min(0.98, 0.7 + action_hits * 0.05 + time_hits * 0.04)
    return {
        "classification": classification,
        "classification_confidence": confidence,
        "should_create_cards": classification in {"actionable", "mixed"},
        "segments": actionable_segments or segments,
    }


def dispatch_analyzers(state: IntakeGraphState):
    if not state.get("should_create_cards"):
        return "finalize"
    return [
        Send(
            "analyze",
            {
                "analyzer": analyzer,
                "canonical_text": state.get("canonical_text", ""),
                "segments": state.get("segments", []),
                "workspace_type": state.get("workspace_type", "personal"),
            },
        )
        for analyzer in ("semantic", "temporal", "team", "quality")
    ]


def analyze(state: IntakeGraphState) -> dict[str, Any]:
    analyzer = state["analyzer"]
    text = state.get("canonical_text", "")
    if analyzer == "semantic":
        cards = []
        seen: set[tuple[str, str, str | None]] = set()
        segments = state.get("segments", []) or [text]
        for index, segment in enumerate(segments):
            context_segment = (
                f"{segments[index - 1]}\n{segment}"
                if index > 0 and any(token in segment for token in ("我会", "答应", "可以"))
                else segment
            )
            extracted = extract_cards_with_rules(segment)
            if not extracted and context_segment != segment:
                extracted = extract_cards_with_rules(context_segment)
            if not extracted and any(
                token in context_segment for token in ("我会", "答应", "可以")
            ):
                deliverable = next(
                    (
                        value
                        for value in DELIVERABLE_PATTERNS
                        if value in context_segment
                    ),
                    None,
                )
                if deliverable:
                    extracted = [
                        ActionCard(
                            id=str(uuid.uuid4()),
                            card_type="promise",
                            title=f"发送{deliverable}",
                            summary=build_summary(
                                card_type="promise",
                                text=context_segment,
                                title=f"发送{deliverable}",
                                materials=[deliverable],
                            ),
                            materials=[deliverable],
                            source_text=context_segment,
                            evidence_summary=[context_segment],
                            need_confirm=["deadline"],
                            created_at=datetime.now(timezone.utc),
                        )
                    ]
            if (
                not extracted
                and state.get("workspace_type") == "team"
                and (match := re.search(
                    r"(?P<person>[\u4e00-\u9fffA-Za-z]{2,12})负责(?P<task>.+)",
                    segment,
                ))
            ):
                extracted = [
                    ActionCard(
                        id=str(uuid.uuid4()),
                        title=match.group("task").strip("，。；; "),
                        summary=build_summary(
                            card_type="task",
                            text=segment,
                            title=match.group("task").strip("，。；; "),
                        ),
                        assignee_id=match.group("person"),
                        participant_ids=[match.group("person")],
                        workspace_type="team",
                        workspace_id="team-default",
                        source_text=segment,
                        evidence_summary=[segment],
                        created_at=datetime.now(timezone.utc),
                    )
                ]
            for card in extracted:
                assignee = _segment_assignee(segment)
                updates: dict[str, Any] = {}
                specific_title = _specific_action_title(context_segment, card.title)
                if specific_title:
                    updates["title"] = specific_title
                if assignee and state.get("workspace_type") == "team":
                    updates.update(
                        {
                            "assignee_id": assignee,
                            "participant_ids": [assignee],
                            "workspace_type": "team",
                            "workspace_id": "team-default",
                        }
                    )
                if updates:
                    card = card.model_copy(update=updates)
                card = _enrich_card_from_segment(card, context_segment)
                key = (card.card_type, card.title.strip(), card.deadline or card.start_time)
                if key not in seen:
                    seen.add(key)
                    cards.append(card)
        return {
            "analyzer_results": [
                {
                    "analyzer": analyzer,
                    "cards": [card.model_dump(mode="json") for card in cards],
                }
            ]
        }
    if analyzer == "temporal":
        return {
            "analyzer_results": [
                {"analyzer": analyzer, "time_expressions": TIME_PATTERN.findall(text)}
            ]
        }
    if analyzer == "team":
        participants = _participants(text)
        return {
            "analyzer_results": [
                {
                    "analyzer": analyzer,
                    "participants": participants,
                    "unassigned": state.get("workspace_type") == "team" and not participants,
                }
            ]
        }
    quality_cards = extract_cards_with_rules(text)
    return {
        "analyzer_results": [
            {
                "analyzer": analyzer,
                "generic_title": any(card.title == "相关日程" for card in quality_cards),
                "card_count": len(quality_cards),
                "segment_count": len(state.get("segments", [])),
            }
        ]
    }


def finalize(state: IntakeGraphState) -> dict[str, Any]:
    semantic = next(
        (
            result
            for result in state.get("analyzer_results", [])
            if result.get("analyzer") == "semantic"
        ),
        {},
    )
    cards = semantic.get("cards", [])
    workspace = state.get("workspace_type", "personal")
    participants = next(
        (
            result.get("participants", [])
            for result in state.get("analyzer_results", [])
            if result.get("analyzer") == "team"
        ),
        [],
    )
    normalized_cards = []
    for card in cards:
        value = dict(card)
        value["workspace_type"] = workspace
        value["workspace_id"] = "team-default" if workspace == "team" else "personal"
        value["participant_ids"] = value.get("participant_ids") or participants
        normalized_cards.append(value)
    if len(normalized_cards) > 1:
        normalized_cards = [
            value
            for value in normalized_cards
            if not _is_advisory_support_card(value, normalized_cards)
        ]
    findings = []
    temporal = next(
        (
            result
            for result in state.get("analyzer_results", [])
            if result.get("analyzer") == "temporal"
        ),
        {},
    )
    quality = next(
        (
            result
            for result in state.get("analyzer_results", [])
            if result.get("analyzer") == "quality"
        ),
        {},
    )
    if quality.get("generic_title"):
        findings.append("存在泛化标题，需要人工确认具体行动")
        for value in normalized_cards:
            if value.get("title") == "相关日程":
                value["need_confirm"] = list(
                    dict.fromkeys([*value.get("need_confirm", []), "title"])
                )
    if (
        len(temporal.get("time_expressions", [])) > len(normalized_cards)
        and normalized_cards
    ):
        findings.append("检测到多个时间表达，请逐卡确认时间归属")
    if workspace == "team" and not participants:
        findings.append("团队任务尚未分配负责人")
    team_tasks = []
    if workspace == "team":
        card_ids = {str(card.get("id")) for card in normalized_cards}
        team_tasks = [
            {
                "task_id": str(card.get("id")),
                "title": str(card.get("title", "")),
                "owner_id": card.get("assignee_id"),
                "participant_ids": card.get("participant_ids", []),
                "dependency_ids": [
                    str(item) for item in card.get("dependencies", []) if str(item) in card_ids
                ],
                "deliverables": card.get("deliverables") or card.get("materials", []),
                "acceptance_criteria": [],
                "deadline": card.get("deadline"),
                "evidence_refs": [f"intake:{card.get('id')}:source"] if card.get("source_text") else [],
                "status": "ready" if card.get("assignee_id") else "unassigned",
                "unassigned_reason": None if card.get("assignee_id") else "owner_not_in_evidence",
            }
            for card in normalized_cards
        ]
        if any(not task["acceptance_criteria"] for task in team_tasks):
            findings.append("团队任务缺少可由证据支持的验收条件，需要人工确认")
    return {"cards": normalized_cards, "team_tasks": team_tasks, "findings": findings}


def _is_advisory_support_card(
    card: dict[str, Any],
    all_cards: list[dict[str, Any]],
) -> bool:
    source = str(card.get("source_text", ""))
    title = str(card.get("title", ""))
    if not source.startswith(("老师提醒", "温馨提醒", "注意")):
        return False
    if card.get("deadline") or card.get("start_time") or card.get("location"):
        return False
    if not any(token in title for token in ("附件", "材料", "文件")):
        return False
    return any(
        other is not card
        and (
            other.get("deadline")
            or any(token in str(other.get("title", "")) for token in ("提交", "发送", "上传"))
        )
        for other in all_cards
    )


def build_intake_graph():
    graph = StateGraph(IntakeGraphState)
    graph.add_node("normalize", normalize_input)
    graph.add_node("classify", classify_input)
    graph.add_node("analyze", analyze)
    graph.add_node("finalize", finalize)
    graph.add_edge(START, "normalize")
    graph.add_edge("normalize", "classify")
    graph.add_conditional_edges(
        "classify",
        dispatch_analyzers,
        {"finalize": "finalize"},
    )
    graph.add_edge("analyze", "finalize")
    graph.add_edge("finalize", END)
    return graph.compile()


def merge_overlapping_lines(text: str) -> str:
    lines = [
        " ".join(line.split())
        for line in text.splitlines()
        if line.strip() and not _is_status_bar_line(line)
    ]
    result: list[str] = []
    for line in lines:
        if result and (line == result[-1] or line in result[-1]):
            continue
        if result and result[-1] in line:
            result[-1] = line
        else:
            result.append(line)
    return "\n".join(result)


def split_action_segments(text: str, *, split_commas: bool = False) -> list[str]:
    punctuation = r"[，,；;。]" if split_commas else r"[；;。]"
    chunks = re.split(rf"(?:\n+|{punctuation}\s*|(?=\d+[.、]\s*))", text)
    return [chunk.strip() for chunk in chunks if len(chunk.strip()) >= 2]


def contextualize_action_segments(
    segments: list[str],
    *,
    workspace_type: str,
) -> list[str]:
    """Attach nearby temporal and continuation evidence before extracting cards."""
    results: list[str] = []
    for index, segment in enumerate(segments):
        lowered = segment.lower()
        is_action = any(word.lower() in lowered for word in ACTION_WORDS) or (
            workspace_type == "team"
            and re.search(r"[\u4e00-\u9fffA-Za-z]{2,12}负责", segment) is not None
        )
        if not is_action:
            continue

        parts: list[str] = []
        if index > 0:
            previous = segments[index - 1]
            previous_is_action = any(
                word.lower() in previous.lower() for word in ACTION_WORDS
            )
            if TIME_PATTERN.search(previous) and not previous_is_action:
                parts.append(previous)
        parts.append(segment)

        combined = "\n".join(parts)
        if results and _segments_describe_same_action(results[-1], combined):
            results[-1] = f"{results[-1]}\n{segment}"
        else:
            results.append(combined)
    return list(dict.fromkeys(results))


def _segments_describe_same_action(left: str, right: str) -> bool:
    left_actor = _leading_actor(left.splitlines()[-1])
    right_actor = _leading_actor(right.splitlines()[-1])
    if left_actor and right_actor and left_actor != right_actor:
        return False
    shared_deliverable = any(
        term in left and term in right for term in DELIVERABLE_PATTERNS
    )
    right_is_submission_detail = any(
        platform in right for platform in PLATFORM_PATTERNS
    ) and any(
        marker in right for marker in ("提交至", "提交到", "发送至", "发送到", "上传至", "通过")
    )
    return shared_deliverable or right_is_submission_detail


def _leading_actor(segment: str) -> str | None:
    match = re.match(
        r"^(?P<actor>[\u4e00-\u9fffA-Za-z]{1,12})(?:[：:]|负责|需|应|本周|周|拿到)",
        segment.strip(),
    )
    return match.group("actor") if match else None


def normalize_ocr_spacing(text: str) -> str:
    normalized = text
    protected_terms = (
        *ACTION_WORDS,
        *ACTION_OBJECTS,
        *DELIVERABLE_PATTERNS,
        *PLATFORM_PATTERNS,
        "负责人",
        "截止",
    )
    for term in sorted(set(protected_terms), key=len, reverse=True):
        if term and all("\u4e00" <= char <= "\u9fff" for char in term):
            normalized = re.sub(r"\s*".join(map(re.escape, term)), term, normalized)
    return normalized


def _is_status_bar_line(line: str) -> bool:
    value = " ".join(line.split())
    has_device_noise = sum(
        token in value for token in ("5G", "4G", "电量", "信号", "WiFi", "返回", "首页")
    ) >= 2
    return has_device_noise and len(value) <= 48


def _enrich_card_from_segment(card: ActionCard, segment: str) -> ActionCard:
    updates: dict[str, Any] = {}
    if "答辩" in segment and card.card_type == "event":
        updates["title"] = "参加中期答辩" if "中期" in segment else "参加答辩"
    location = card.location
    if not location:
        platform = next((value for value in PLATFORM_PATTERNS if value in segment), None)
        room = re.search(
            r"(?:行政楼[一二三四五六七八九十\d]+层|[\u4e00-\u9fff]{2,10}[A-Z]\d{3}|[A-Z]\d{3}|"
            r"[\u4e00-\u9fff]{2,10}(?:教室|会议室|活动中心))",
            segment,
        )
        email = re.search(r"[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}", segment)
        location = (
            platform
            or (room.group(0) if room else None)
            or (email.group(0) if email else None)
        )
        if location:
            updates["location"] = location

    materials = list(card.materials)
    for value in DELIVERABLE_PATTERNS:
        if value in segment and value not in materials:
            materials.append(value)
    if materials:
        updates["materials"] = materials

    if not card.submit_method:
        email = re.search(r"[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}", segment)
        if email:
            updates["submit_method"] = f"发送到 {email.group(0)}"
        elif location in PLATFORM_PATTERNS:
            updates["submit_method"] = f"通过{location}提交"

    if card.title in {"相关日程", "提交材料", "处理截图事项"}:
        deliverable = next(
            (value for value in DELIVERABLE_PATTERNS if value in segment),
            None,
        )
        if deliverable:
            verb = (
                "提交"
                if any(value in segment for value in ("提交", "发送", "上传"))
                else "完成"
            )
            updates["title"] = f"{verb}{deliverable}"
    return card.model_copy(update=updates) if updates else card


def _participants(text: str) -> list[str]:
    names = re.findall(
        r"(?:(?:负责人|由|请)\s*)?([\u4e00-\u9fffA-Za-z]{2,12})(?:负责|完成|提交)",
        text,
    )
    return list(dict.fromkeys(names))[:20]


def _segment_assignee(segment: str) -> str | None:
    normalized = re.sub(r"^\d+[.、]\s*", "", segment.strip())
    leading = re.match(
        r"^(?P<person>[\u4e00-\u9fff]{2,4}?)(?=(?:本周|这周|下周|周[一二三四五六日天]|"
        r"今天|明天|负责|拿到|完成|提交|参加|制作|整理|评审))",
        normalized,
    )
    if leading:
        person = leading.group("person").removeprefix("请")
        if 2 <= len(person) <= 4:
            return person
    match = re.search(
        r"(?:^|[\uff1a:，,；;\s])(?:请)?(?P<person>[\u4e00-\u9fff]{2,4})(?:请)?"
        r"(?:负责|需|需要|于|在|完成|提交|参加|制作|整理)",
        normalized,
    )
    if not match:
        return None
    person = re.sub(r"(?:负责|需要|请|需)$", "", match.group("person"))
    return person if 2 <= len(person) <= 4 else None


def _specific_action_title(segment: str, current_title: str) -> str | None:
    generic_titles = {
        "提交材料",
        "处理截图事项",
        "相关日程",
        "参加会议",
        "准备会议材料",
    }
    if current_title not in generic_titles:
        return None
    patterns = (
        r"提交(?P<object>[^，。；;到至]{2,24})",
        r"把(?P<object>[^，。；;]{2,24}?)(?:发送|提交|上传)",
        r"进行(?P<object>[^，。；;并]{2,20})",
        r"准备(?P<object>[^，。；;]{2,16})",
        r"参加(?P<object>[^，。；;]{2,20})",
    )
    for pattern in patterns:
        match = re.search(pattern, segment)
        if not match:
            continue
        value = re.sub(r"\s+", " ", match.group("object")).strip(" ：:，,。；;")
        if len(value) >= 2:
            return value if value.startswith(("提交", "参加", "准备")) else f"完成{value}"
    return None
