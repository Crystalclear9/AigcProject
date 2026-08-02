"""Team goal decomposition: fast model first, deterministic template fallback."""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Any

from app.core.config import settings
from app.services.llm_client import structured_completion
from app.services.team_goal_templates import decompose_with_template

DECOMPOSITION_SCHEMA: dict[str, Any] = {
    "type": "object",
    "properties": {
        "milestones": {
            "type": "array",
            "minItems": 1,
            "maxItems": 6,
            "items": {
                "type": "object",
                "properties": {
                    "title": {"type": "string"},
                    "due_date": {"type": ["string", "null"]},
                },
                "required": ["title", "due_date"],
                "additionalProperties": False,
            },
        },
        "tasks": {
            "type": "array",
            "minItems": 1,
            "maxItems": 20,
            "items": {
                "type": "object",
                "properties": {
                    "title": {"type": "string"},
                    "summary": {"type": "string"},
                    "assignee_id": {"type": ["string", "null"]},
                    "milestone_index": {"type": ["integer", "null"]},
                    "start_date": {"type": ["string", "null"]},
                    "due_date": {"type": ["string", "null"]},
                    "deliverables": {
                        "type": "array",
                        "items": {"type": "string"},
                    },
                },
                "required": [
                    "title",
                    "summary",
                    "assignee_id",
                    "milestone_index",
                    "start_date",
                    "due_date",
                    "deliverables",
                ],
                "additionalProperties": False,
            },
        },
    },
    "required": ["milestones", "tasks"],
    "additionalProperties": False,
}

_SYSTEM_PROMPT = (
    "你是一个学生团队的项目协调者。给定团队的共同目标、截止日期和成员名单，"
    "请把目标拆解为 2-4 个里程碑和一组可执行任务。要求："
    "任务标题动词开头、可直接执行；每个任务归属一个里程碑（milestone_index 从 0 开始）；"
    "assignee_id 必须从成员名单的 user_id 中选择并尽量均衡分配；"
    "日期使用 ISO 格式且不得晚于目标截止日期；deliverables 列出该任务的交付物名称。"
    "只输出符合 schema 的 JSON。"
)


def _sanitize(result: dict[str, Any], member_ids: list[str]) -> dict[str, Any]:
    milestones = [
        {"title": str(m.get("title", "")).strip() or "阶段", "due_date": m.get("due_date")}
        for m in result.get("milestones", [])
        if isinstance(m, dict)
    ]
    valid_ids = set(member_ids)
    tasks = []
    for task in result.get("tasks", []):
        if not isinstance(task, dict):
            continue
        title = str(task.get("title", "")).strip()
        if not title:
            continue
        index = task.get("milestone_index")
        if not isinstance(index, int) or not 0 <= index < len(milestones):
            index = None
        assignee = task.get("assignee_id")
        if assignee not in valid_ids:
            assignee = None
        tasks.append(
            {
                "title": title,
                "summary": str(task.get("summary", "")),
                "assignee_id": assignee,
                "milestone_index": index,
                "start_date": task.get("start_date"),
                "due_date": task.get("due_date"),
                "deliverables": [
                    str(item) for item in task.get("deliverables", []) if str(item).strip()
                ],
            }
        )
    if not milestones or not tasks:
        raise ValueError("model decomposition is empty")
    return {"milestones": milestones, "tasks": tasks}


async def decompose_goal(
    *,
    title: str,
    description: str = "",
    due_date: str | None = None,
    members: list[dict[str, str]],
    now: datetime | None = None,
) -> tuple[dict[str, Any], str, list[str]]:
    """Return (decomposition, source, warnings).

    decomposition keys: milestones [{title, due_date}], tasks [{title, summary,
    assignee_id, milestone_index, start_date, due_date, deliverables}].
    """
    member_ids = [member["user_id"] for member in members]
    warnings: list[str] = []
    if settings.has_fast_model_config:
        try:
            raw = await structured_completion(
                "fast_model",
                system_prompt=_SYSTEM_PROMPT,
                input_payload={
                    "goal": {
                        "title": title,
                        "description": description,
                        "due_date": due_date,
                    },
                    "members": members,
                    "current_time": (
                        now or datetime.now(timezone.utc)
                    ).isoformat(),
                },
                schema_name="team_goal_decomposition",
                schema=DECOMPOSITION_SCHEMA,
                max_tokens=2500,
            )
            return _sanitize(raw, member_ids), "llm", warnings
        except Exception as error:  # noqa: BLE001 - degrade, never fail the goal
            warnings.append(
                f"模型拆解不可用，已使用规则模板：{type(error).__name__}"
            )
    template_result = decompose_with_template(
        title=title,
        description=description,
        due_date=due_date,
        member_ids=member_ids,
        now=now,
    )
    template_result.pop("template_key", None)
    return template_result, "template", warnings
