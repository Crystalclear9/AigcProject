from __future__ import annotations

import hashlib
import uuid
from datetime import datetime, timedelta, timezone
from typing import Any, TypedDict
from zoneinfo import ZoneInfo

from langgraph.graph import END, START, StateGraph

from app.core.config import settings
from app.schemas.card import ActionCard
from app.schemas.card_refinement import (
    CardRefinementPlan,
    PlanItem,
    RefinementOptions,
    UserProfileContext,
)
from app.services.llm_client import structured_completion
from app.services.prompt_envelope import compile_profile_policy, render_system_prompt

PLAN_SCHEMA: dict[str, Any] = {
    "type": "object",
    "properties": {
        "objective": {"type": "string"},
        "assumptions": {"type": "array", "items": {"type": "string"}},
        "items": {
            "type": "array",
            "maxItems": 40,
            "items": {
                "type": "object",
                "properties": {
                    "id": {"type": "string"},
                    "parent_id": {"type": ["string", "null"]},
                    "kind": {"type": "string", "enum": ["milestone", "work_block", "step"]},
                    "title": {"type": "string"},
                    "description": {"type": "string"},
                    "order": {"type": "integer"},
                    "start_time": {"type": ["string", "null"]},
                    "deadline": {"type": ["string", "null"]},
                    "estimated_minutes": {"type": ["integer", "null"]},
                    "importance": {"type": "string", "enum": ["low", "normal", "high"]},
                    "dependencies": {"type": "array", "items": {"type": "string"}},
                    "reminder_enabled": {"type": "boolean"},
                    "confidence": {"type": "number"},
                    "evidence_refs": {"type": "array", "items": {"type": "string"}},
                    "need_confirm": {"type": "array", "items": {"type": "string"}},
                },
                "required": [
                    "id",
                    "parent_id",
                    "kind",
                    "title",
                    "description",
                    "order",
                    "start_time",
                    "deadline",
                    "estimated_minutes",
                    "importance",
                    "dependencies",
                    "reminder_enabled",
                    "confidence",
                    "evidence_refs",
                    "need_confirm",
                ],
                "additionalProperties": False,
            },
        },
    },
    "required": ["objective", "assumptions", "items"],
    "additionalProperties": False,
}

SYSTEM_PROMPT = """
你是行动规划器。你只能细化给定父卡，不能改写父卡标题、DDL、地点或提交方式。
根据证据生成里程碑、工作时间块和执行步骤。DDL 是硬约束，任何计划时间不得晚于它。
证据冲突时写入 need_confirm，不得猜测。用户画像只影响粒度、工作时段和提醒风格。
输出必须符合 JSON Schema，不要输出解释或思维链。
""".strip()


class CardRefinementState(TypedDict, total=False):
    run_id: str
    card: dict[str, Any]
    options: dict[str, Any]
    profile_context: dict[str, Any] | None
    instruction: str
    documents: list[dict[str, str]]
    evidence_summary: list[str]
    rule_plan: dict[str, Any]
    model_plan: dict[str, Any] | None
    plan: dict[str, Any]
    warnings: list[str]
    validation_errors: list[str]
    generated_by: str


def build_card_refinement_graph():
    builder = StateGraph(CardRefinementState)
    builder.add_node("prepare_evidence", prepare_evidence)
    builder.add_node("rules_plan", create_rule_plan)
    builder.add_node("model_plan", create_model_plan)
    builder.add_node("verify_plan", verify_plan)
    builder.add_edge(START, "prepare_evidence")
    builder.add_edge("prepare_evidence", "rules_plan")
    builder.add_conditional_edges(
        "rules_plan",
        should_use_model,
        {"model": "model_plan", "verify": "verify_plan"},
    )
    builder.add_edge("model_plan", "verify_plan")
    builder.add_edge("verify_plan", END)
    return builder.compile()


def prepare_evidence(state: CardRefinementState) -> dict[str, Any]:
    card = ActionCard(**state["card"])
    evidence: list[str] = []
    for value in [
        card.summary,
        card.location or "",
        card.submit_method or "",
        *card.materials,
        *card.evidence_summary,
    ]:
        cleaned = " ".join(value.split())
        if cleaned and cleaned not in evidence:
            evidence.append(cleaned[:220])
    for index, document in enumerate(state.get("documents", []), start=1):
        text = str(document.get("text", "")).strip()
        if not text:
            continue
        snippets = _evidence_snippets(text, limit=3)
        evidence.extend(f"附件{index}：{snippet}" for snippet in snippets)
    return {"evidence_summary": evidence[:16]}


def create_rule_plan(state: CardRefinementState) -> dict[str, Any]:
    card = ActionCard(**state["card"])
    options = RefinementOptions(**state.get("options", {}))
    profile = (
        UserProfileContext(**state["profile_context"])
        if state.get("profile_context") and options.use_profile
        else None
    )
    plan = deterministic_plan(
        card,
        options=options,
        profile=profile,
        evidence_summary=state.get("evidence_summary", []),
        instruction=state.get("instruction", ""),
    )
    return {"rule_plan": plan.model_dump(mode="json"), "generated_by": "rules"}


def should_use_model(state: CardRefinementState) -> str:
    return "model" if settings.has_fast_model_config else "verify"


async def create_model_plan(state: CardRefinementState) -> dict[str, Any]:
    card = ActionCard(**state["card"])
    options = RefinementOptions(**state.get("options", {}))
    profile = (
        UserProfileContext(**state["profile_context"])
        if state.get("profile_context") and options.use_profile
        else None
    )
    envelope = compile_profile_policy(
        "team_coordinator" if card.workspace_type == "team" else "personal_planner",
        profile,
    )
    try:
        result = await structured_completion(
            "fast_model",
            system_prompt=render_system_prompt(envelope),
            input_payload={
                "parent_card": {
                    "id": card.id,
                    "card_type": card.card_type,
                    "title": card.title,
                    "summary": card.summary,
                    "deadline": card.deadline,
                    "start_time": card.start_time,
                    "end_time": card.end_time,
                    "location": card.location,
                    "materials": card.materials,
                    "submit_method": card.submit_method,
                },
                "options": options.model_dump(),
                "profile_policy": envelope.user_policy,
                "prompt_version": envelope.version,
                "instruction": state.get("instruction", ""),
                "evidence": state.get("evidence_summary", []),
                "current_time": datetime.now(timezone.utc).isoformat(),
            },
            schema_name="card_refinement_plan",
            schema=PLAN_SCHEMA,
            max_tokens=3000,
        )
        plan = _model_result_to_plan(
            result,
            card=card,
            evidence_summary=state.get("evidence_summary", []),
            profile_version=(state.get("profile_context") or {}).get("version"),
        )
        return {"model_plan": plan.model_dump(mode="json"), "generated_by": "fast_model"}
    except Exception as error:
        return {
            "model_plan": None,
            "warnings": [
                *state.get("warnings", []),
                f"模型细化不可用，已保留规则计划：{type(error).__name__}",
            ],
            "generated_by": "rules",
        }


async def verify_plan(state: CardRefinementState) -> dict[str, Any]:
    card = ActionCard(**state["card"])
    profile = (
        UserProfileContext(**state["profile_context"])
        if state.get("profile_context")
        else None
    )
    candidate = state.get("model_plan") or state["rule_plan"]
    plan = CardRefinementPlan(**candidate)
    errors = validate_plan(plan, card, profile)
    if errors and state.get("model_plan") and settings.has_fast_model_config:
        repaired = await _repair_plan_once(
            plan,
            card=card,
            profile=profile,
            errors=errors,
            evidence_summary=state.get("evidence_summary", []),
        )
        repaired_errors = validate_plan(repaired, card, profile) if repaired else errors
        if repaired is not None and not repaired_errors:
            plan = repaired
            errors = []
    if errors and state.get("model_plan"):
        rule_plan = CardRefinementPlan(**state["rule_plan"])
        rule_errors = validate_plan(rule_plan, card, profile)
        if not rule_errors:
            rule_plan = _with_verification(rule_plan, card, profile, [])
            return {
                "plan": rule_plan.model_dump(mode="json"),
                "generated_by": "rules",
                "validation_errors": [],
                "warnings": [
                    *state.get("warnings", []),
                    "模型计划未通过时间与结构校验，已使用规则计划",
                ],
            }
    verified = _with_verification(plan, card, profile, errors)
    return {
        "plan": verified.model_dump(mode="json"),
        "validation_errors": errors,
        "warnings": state.get("warnings", []),
    }


def deterministic_plan(
    card: ActionCard,
    *,
    options: RefinementOptions,
    profile: UserProfileContext | None,
    evidence_summary: list[str],
    instruction: str = "",
) -> CardRefinementPlan:
    now = datetime.now(timezone.utc)
    deadline = _parse_time(card.deadline or card.start_time)
    granularity = profile.planning_granularity if profile and options.use_profile else options.granularity
    item_specs: list[tuple[str, str, str, int, str]] = [
        ("step", "确认任务要求", "核对交付物、评价标准和最终截止时间。", 20, "high"),
        ("milestone", "完成材料与资源准备", _material_description(card), 45, "normal"),
        ("work_block", "完成核心执行", "集中完成任务主体，并记录仍需确认的问题。", 120, "high"),
        ("milestone", "完成质量复核", "按要求逐项检查，预留修改与上传时间。", 45, "high"),
        ("milestone", "提交或参加", _submission_description(card), 20, "high"),
    ]
    if granularity == "concise":
        item_specs = [item_specs[0], item_specs[2], item_specs[-1]]
    elif granularity == "detailed":
        item_specs.insert(
            3,
            ("step", "处理中间反馈", "检查阶段成果并根据反馈修正。", 40, "normal"),
        )
    if not options.include_work_blocks:
        item_specs = [item for item in item_specs if item[0] != "work_block"]
    if not options.include_milestones:
        item_specs = [item for item in item_specs if item[0] != "milestone"]
    if not item_specs:
        item_specs = [("step", "完成任务", card.summary or card.title, 60, "high")]

    plan_id = _stable_id("plan", card.id, instruction, granularity)
    available = (deadline - now) if deadline and deadline > now else None
    buffer = _deadline_buffer(profile)
    usable_end = (
        deadline - buffer
        if deadline and deadline > now + buffer
        else deadline
    )
    usable = (usable_end - now) if usable_end and usable_end > now else available
    fractions = _schedule_fractions(profile)
    items: list[PlanItem] = []
    previous_id: str | None = None
    for index, (kind, title, description, minutes, importance) in enumerate(item_specs):
        item_id = _stable_id("item", plan_id, index, title)
        scheduled: datetime | None = None
        if usable:
            fraction = fractions[min(index, len(fractions) - 1)]
            scheduled = _align_to_profile_period(now + usable * fraction, profile)
            if usable_end and scheduled > usable_end:
                scheduled = usable_end
        item_deadline = scheduled.isoformat() if scheduled and kind == "milestone" else None
        start_time = scheduled.isoformat() if scheduled and kind == "work_block" else None
        # Missing DDL still permits a relative plan. Keep it as a plan warning and do not
        # manufacture absolute times or block confirmation.
        need_confirm: list[str] = []
        items.append(
            PlanItem(
                id=item_id,
                kind=kind,
                title=title,
                description=description,
                order=index,
                start_time=start_time,
                deadline=item_deadline,
                estimated_minutes=minutes,
                importance=importance,
                dependencies=[previous_id] if previous_id else [],
                reminder_enabled=_should_enable_reminder(
                    kind,
                    index,
                    len(item_specs),
                    bool(options.milestone_reminders and item_deadline),
                    profile,
                ),
                confidence=0.78 if deadline else 0.62,
                evidence_refs=[f"evidence-{min(index, max(0, len(evidence_summary) - 1))}"]
                if evidence_summary
                else [],
                need_confirm=need_confirm,
            )
        )
        previous_id = item_id
    warnings: list[str] = []
    if deadline is None:
        warnings.append("缺少明确截止时间，当前仅生成相对顺序，不创建绝对时间提醒")
    if deadline and deadline <= now:
        warnings.append("父卡时间已过期，请确认新的截止时间后再启用提醒")
        items = [
            item.model_copy(
                update={
                    "start_time": None,
                    "deadline": None,
                    "reminder_enabled": False,
                    "need_confirm": ["父卡截止时间已过期"],
                }
            )
            for item in items
        ]
    draft = CardRefinementPlan(
        id=plan_id,
        parent_card_id=card.id,
        objective=card.title,
        items=items,
        assumptions=["计划不会修改父卡事实字段"],
        evidence_summary=evidence_summary[:16],
        warnings=warnings,
        generated_by="rules",
        profile_version=profile.version if profile else None,
        profile_effects=_profile_effects(profile),
    )
    return _with_verification(
        draft,
        card,
        profile,
        validate_plan(draft, card, profile),
    )


def validate_plan(
    plan: CardRefinementPlan,
    card: ActionCard,
    profile: UserProfileContext | None = None,
) -> list[str]:
    errors: list[str] = []
    if plan.parent_card_id != card.id:
        errors.append("计划父卡不匹配")
    if not plan.items:
        errors.append("细化计划不能为空")
    ids = {item.id for item in plan.items}
    if len(ids) != len(plan.items):
        errors.append("计划项 ID 重复")
    parent_deadline = _parse_time(card.deadline or card.start_time)
    now = datetime.now(timezone.utc)
    order_by_id = {item.id: item.order for item in plan.items}
    dependency_graph = {item.id: list(item.dependencies) for item in plan.items}
    if _has_dependency_cycle(dependency_graph):
        errors.append("计划包含循环依赖")
    total_minutes = sum(item.estimated_minutes or 0 for item in plan.items)
    if parent_deadline and parent_deadline > now:
        available_minutes = int((parent_deadline - now).total_seconds() // 60)
        if total_minutes > available_minutes:
            errors.append("预计工作量超过截止前可用时间")
    parsed_intervals: list[tuple[datetime, datetime, str]] = []
    for item in plan.items:
        unknown = set(item.dependencies) - ids
        if unknown:
            errors.append(f"{item.title} 包含未知依赖")
        if any(order_by_id.get(dependency, -1) >= item.order for dependency in item.dependencies):
            errors.append(f"{item.title} 的依赖顺序倒置")
        for value in [item.start_time, item.deadline]:
            parsed = _parse_time(value)
            if value and parsed is None:
                errors.append(f"{item.title} 时间格式无效")
            if parsed and parent_deadline and parsed > parent_deadline:
                errors.append(f"{item.title} 晚于父卡截止时间")
            if parsed and parsed < now - timedelta(minutes=5):
                errors.append(f"{item.title} 早于当前时间")
        if item.kind == "work_block" and item.start_time and item.estimated_minutes:
            start = _parse_time(item.start_time)
            if start:
                parsed_intervals.append(
                    (start, start + timedelta(minutes=item.estimated_minutes), item.title)
                )
                if profile and not _matches_profile_period(start, profile):
                    errors.append(f"{item.title} 不符合用户常用处理时段")
        if item.reminder_enabled and not item.deadline:
            errors.append(f"{item.title} 缺少提醒时间")
        if item.reminder_enabled and item.kind != "milestone":
            errors.append(f"{item.title} 不是里程碑，不能创建节点提醒")
    parsed_intervals.sort(key=lambda value: value[0])
    for previous, current in zip(parsed_intervals, parsed_intervals[1:]):
        if current[0] < previous[1]:
            errors.append(f"{previous[2]} 与 {current[2]} 时间重叠")
    if parent_deadline:
        latest = max(
            (
                parsed
                for item in plan.items
                for parsed in [_parse_time(item.deadline or item.start_time)]
                if parsed
            ),
            default=None,
        )
        required_buffer = _deadline_buffer(profile)
        if latest and parent_deadline - latest < required_buffer:
            errors.append("计划未保留足够的截止前缓冲")
    reminder_count = sum(item.reminder_enabled for item in plan.items)
    if reminder_count > _max_reminders(profile):
        errors.append("里程碑提醒数量超过用户偏好")
    if plan.evidence_summary:
        covered = sum(bool(item.evidence_refs) for item in plan.items)
        if covered / max(1, len(plan.items)) < 0.4:
            errors.append("计划项的证据覆盖不足")
    if plan.objective.strip() != card.title.strip():
        errors.append("计划修改了父卡标题事实")
    return list(dict.fromkeys(errors))


async def _repair_plan_once(
    plan: CardRefinementPlan,
    *,
    card: ActionCard,
    profile: UserProfileContext | None,
    errors: list[str],
    evidence_summary: list[str],
) -> CardRefinementPlan | None:
    role = "expert_model" if settings.has_expert_model_config else "fast_model"
    role_template = (
        "team_coordinator" if card.workspace_type == "team" else "personal_planner"
    )
    envelope = compile_profile_policy(role_template, profile)
    try:
        result = await structured_completion(
            role,
            system_prompt=(
                render_system_prompt(envelope)
                + "\n这是唯一一次修复。必须逐项消除 validation_errors，保持父卡事实不变。"
            ),
            input_payload={
                "parent_card": card.model_dump(mode="json"),
                "candidate_plan": plan.model_dump(mode="json"),
                "validation_errors": errors,
                "profile_policy": envelope.user_policy,
                "prompt_version": envelope.version,
                "evidence": evidence_summary,
            },
            schema_name="card_refinement_plan_repair",
            schema=PLAN_SCHEMA,
            max_tokens=3000,
        )
        repaired = _model_result_to_plan(
            result,
            card=card,
            evidence_summary=evidence_summary,
            profile_version=profile.version if profile else None,
        )
        return repaired.model_copy(
            update={"generated_by": f"{plan.generated_by}+repair"}
        )
    except Exception:
        return None


def _with_verification(
    plan: CardRefinementPlan,
    card: ActionCard,
    profile: UserProfileContext | None,
    errors: list[str],
) -> CardRefinementPlan:
    checks = 8
    score = max(0.0, min(1.0, 1.0 - len(set(errors)) / checks))
    if not plan.items:
        score = 0.0
    summary = (
        f"已通过计划约束检查，质量分 {score:.2f}"
        if not errors
        else f"发现 {len(set(errors))} 项约束问题，修正前不可应用"
    )
    return plan.model_copy(
        update={
            "quality_score": round(score, 3),
            "constraint_errors": list(dict.fromkeys(errors)),
            "profile_effects": plan.profile_effects or _profile_effects(profile),
            "verification_summary": summary,
        }
    )


def _profile_effects(profile: UserProfileContext | None) -> list[str]:
    if profile is None:
        return ["使用中性规划策略"]
    labels = {
        "morning": "时间块优先安排在上午",
        "afternoon": "时间块优先安排在下午",
        "daytime": "时间块优先安排在白天",
        "evening": "时间块优先安排在晚上",
        "steady": "任务均匀铺开",
        "sprint": "核心工作集中安排",
        "adaptive": "根据剩余时间动态安排",
        "compact": "保留紧凑截止缓冲",
        "standard": "保留标准截止缓冲",
        "generous": "保留宽裕截止缓冲",
        "avoid": "尽量避开周末",
        "allow": "允许安排周末",
        "flexible": "按任务需要安排周末",
    }
    values = [
        profile.active_period,
        profile.work_rhythm,
        profile.buffer_preference,
        profile.weekend_policy,
    ]
    return [labels[value] for value in values if value in labels]


def _deadline_buffer(profile: UserProfileContext | None) -> timedelta:
    preference = profile.buffer_preference if profile else "standard"
    return {
        "compact": timedelta(minutes=30),
        "standard": timedelta(hours=3),
        "generous": timedelta(days=1),
    }.get(preference, timedelta(hours=3))


def _schedule_fractions(profile: UserProfileContext | None) -> list[float]:
    rhythm = profile.work_rhythm if profile else "adaptive"
    if rhythm == "steady":
        return [0.10, 0.28, 0.46, 0.64, 0.82, 0.94]
    if rhythm == "sprint":
        return [0.42, 0.56, 0.68, 0.78, 0.88, 0.95]
    return [0.08, 0.25, 0.48, 0.68, 0.84, 0.94]


def _align_to_profile_period(
    value: datetime,
    profile: UserProfileContext | None,
) -> datetime:
    if profile is None or profile.active_period in {"unspecified", "flexible"}:
        return _avoid_weekend(value, profile)
    try:
        zone = ZoneInfo(profile.timezone)
    except Exception:
        zone = timezone.utc
    local = value.astimezone(zone)
    target_hour = {
        "morning": 9,
        "afternoon": 14,
        "daytime": 14,
        "evening": 19,
    }.get(profile.active_period)
    if target_hour is not None:
        local = local.replace(hour=target_hour, minute=0, second=0, microsecond=0)
    return _avoid_weekend(local.astimezone(value.tzinfo or timezone.utc), profile)


def _avoid_weekend(
    value: datetime,
    profile: UserProfileContext | None,
) -> datetime:
    if profile is None or profile.weekend_policy != "avoid":
        return value
    while value.weekday() >= 5:
        value -= timedelta(days=1)
    return value


def _matches_profile_period(
    value: datetime,
    profile: UserProfileContext,
) -> bool:
    if profile.active_period in {"unspecified", "flexible"}:
        return True
    try:
        local = value.astimezone(ZoneInfo(profile.timezone))
    except Exception:
        local = value
    if profile.weekend_policy == "avoid" and local.weekday() >= 5:
        return False
    hour = local.hour
    return {
        "morning": 7 <= hour < 12,
        "afternoon": 12 <= hour < 18,
        "daytime": 8 <= hour < 18,
        "evening": 18 <= hour < 23,
    }.get(profile.active_period, True)


def _should_enable_reminder(
    kind: str,
    index: int,
    item_count: int,
    eligible: bool,
    profile: UserProfileContext | None,
) -> bool:
    if not eligible or kind != "milestone":
        return False
    style = profile.reminder_style if profile else "standard"
    if style in {"gentle", "light", "key_only"}:
        return index == item_count - 1
    if style in {"multi", "multi_stage"}:
        return True
    return index >= item_count - 2


def _max_reminders(profile: UserProfileContext | None) -> int:
    style = profile.reminder_style if profile else "standard"
    if style in {"gentle", "light", "key_only"}:
        return 1
    if style in {"multi", "multi_stage"}:
        return 4
    return 2


def _has_dependency_cycle(graph: dict[str, list[str]]) -> bool:
    visiting: set[str] = set()
    visited: set[str] = set()

    def visit(node: str) -> bool:
        if node in visiting:
            return True
        if node in visited:
            return False
        visiting.add(node)
        if any(visit(dependency) for dependency in graph.get(node, [])):
            return True
        visiting.remove(node)
        visited.add(node)
        return False

    return any(visit(node) for node in graph)


def _model_result_to_plan(
    result: dict[str, Any],
    *,
    card: ActionCard,
    evidence_summary: list[str],
    profile_version: int | None,
) -> CardRefinementPlan:
    plan_id = _stable_id("plan", card.id, "model", result.get("objective", ""))
    items: list[PlanItem] = []
    raw_items = result.get("items") if isinstance(result.get("items"), list) else []
    id_map: dict[str, str] = {}
    for index, raw in enumerate(raw_items):
        if not isinstance(raw, dict):
            continue
        raw_id = str(raw.get("id") or index)
        id_map[raw_id] = _stable_id("item", plan_id, raw_id, raw.get("title", ""))
    for index, raw in enumerate(raw_items):
        if not isinstance(raw, dict) or not str(raw.get("title", "")).strip():
            continue
        raw_id = str(raw.get("id") or index)
        raw_parent = raw.get("parent_id")
        dependencies = [
            id_map[value]
            for value in map(str, raw.get("dependencies") or [])
            if value in id_map
        ]
        items.append(
            PlanItem(
                id=id_map[raw_id],
                parent_id=id_map.get(str(raw_parent)) if raw_parent else None,
                kind=raw.get("kind", "step"),
                title=str(raw["title"]).strip(),
                description=str(raw.get("description", "")).strip(),
                order=index,
                start_time=raw.get("start_time"),
                deadline=raw.get("deadline"),
                estimated_minutes=raw.get("estimated_minutes"),
                importance=raw.get("importance", "normal"),
                dependencies=dependencies,
                reminder_enabled=bool(raw.get("reminder_enabled", False)),
                confidence=float(raw.get("confidence", 0.7)),
                evidence_refs=[str(value) for value in raw.get("evidence_refs") or []],
                need_confirm=[str(value) for value in raw.get("need_confirm") or []],
            )
        )
    return CardRefinementPlan(
        id=plan_id,
        parent_card_id=card.id,
        objective=str(result.get("objective") or card.title),
        items=items,
        assumptions=[str(value) for value in result.get("assumptions") or []],
        evidence_summary=evidence_summary[:16],
        generated_by="fast_model",
        profile_version=profile_version,
    )


def _evidence_snippets(text: str, limit: int) -> list[str]:
    lines = [" ".join(line.split()) for line in text.splitlines() if line.strip()]
    ranked = sorted(
        lines,
        key=lambda line: (
            any(token in line for token in ["截止", "提交", "要求", "时间", "材料", "地点", "会议"]),
            min(len(line), 180),
        ),
        reverse=True,
    )
    return [line[:180] for line in ranked[:limit]]


def _parse_time(value: str | None) -> datetime | None:
    if not value:
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    if parsed.tzinfo is None:
        return parsed.replace(tzinfo=timezone.utc)
    return parsed


def _stable_id(prefix: str, *parts: object) -> str:
    payload = "\0".join(str(part) for part in parts)
    return f"{prefix}-{hashlib.sha1(payload.encode('utf-8')).hexdigest()[:16]}"


def _material_description(card: ActionCard) -> str:
    if card.materials:
        return "准备：" + "、".join(card.materials)
    return "整理任务所需材料、账号、模板和参考信息。"


def _submission_description(card: ActionCard) -> str:
    if card.submit_method:
        return f"通过 {card.submit_method} 完成最终提交，并保留成功凭证。"
    if card.location:
        return f"在 {card.location} 完成任务，并确认结果。"
    return "完成最终提交或参加，并核对是否成功。"
