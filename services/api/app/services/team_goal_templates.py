"""Deterministic team-goal decomposition skeletons.

Mirrors the rule/LLM dual-track architecture used elsewhere: when the fast
model is unavailable or fails, these templates guarantee that a goal always
decomposes into a usable milestone + task plan.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date, datetime, timedelta, timezone


@dataclass(frozen=True)
class TemplateTask:
    title: str
    deliverables: list[str] = field(default_factory=list)


@dataclass(frozen=True)
class TemplateMilestone:
    title: str
    # Fraction of the goal timespan at which this milestone is due (0..1].
    fraction: float
    tasks: list[TemplateTask] = field(default_factory=list)


@dataclass(frozen=True)
class GoalTemplate:
    key: str
    keywords: tuple[str, ...]
    milestones: list[TemplateMilestone] = field(default_factory=list)


_COMPETITION = GoalTemplate(
    key="competition",
    keywords=("比赛", "竞赛", "大赛", "赛事", "参赛"),
    milestones=[
        TemplateMilestone(
            "方案与分工",
            0.25,
            [
                TemplateTask("确定选题与整体方案", ["方案说明"]),
                TemplateTask("拆分模块并认领分工", ["分工表"]),
            ],
        ),
        TemplateMilestone(
            "作品制作",
            0.7,
            [
                TemplateTask("完成核心功能/作品主体", ["作品初版"]),
                TemplateTask("准备演示材料与讲稿", ["演示文稿"]),
            ],
        ),
        TemplateMilestone(
            "打磨与提交",
            1.0,
            [
                TemplateTask("联调排练并修复问题", ["排练记录"]),
                TemplateTask("按要求完成正式提交", ["提交回执"]),
            ],
        ),
    ],
)

_COURSEWORK = GoalTemplate(
    key="coursework",
    keywords=("大作业", "课设", "课程设计", "作业", "项目答辩"),
    milestones=[
        TemplateMilestone(
            "选题与调研",
            0.3,
            [
                TemplateTask("确认题目与需求范围", ["需求说明"]),
                TemplateTask("收集参考资料", ["资料清单"]),
            ],
        ),
        TemplateMilestone(
            "实现与撰写",
            0.8,
            [
                TemplateTask("完成主体实现/初稿", ["初稿"]),
                TemplateTask("交叉检查与修改", ["修改记录"]),
            ],
        ),
        TemplateMilestone(
            "定稿提交",
            1.0,
            [TemplateTask("整理终稿并提交", ["终稿"])],
        ),
    ],
)

_REPORT = GoalTemplate(
    key="report",
    keywords=("报告", "论文", "综述", "文档"),
    milestones=[
        TemplateMilestone(
            "提纲与分工",
            0.25,
            [TemplateTask("确定提纲并分配章节", ["提纲"])],
        ),
        TemplateMilestone(
            "初稿完成",
            0.7,
            [
                TemplateTask("各自完成负责章节", ["章节初稿"]),
                TemplateTask("统稿并统一格式", ["合并稿"]),
            ],
        ),
        TemplateMilestone(
            "定稿",
            1.0,
            [TemplateTask("校对定稿并提交", ["终稿"])],
        ),
    ],
)

_EVENT = GoalTemplate(
    key="event",
    keywords=("活动", "晚会", "招新", "讲座", "聚会"),
    milestones=[
        TemplateMilestone(
            "策划筹备",
            0.4,
            [
                TemplateTask("确定活动方案与预算", ["活动方案"]),
                TemplateTask("预订场地与物资", ["物资清单"]),
            ],
        ),
        TemplateMilestone(
            "宣传与报名",
            0.75,
            [TemplateTask("制作宣传物料并发布", ["宣传图文"])],
        ),
        TemplateMilestone(
            "执行收尾",
            1.0,
            [
                TemplateTask("现场执行与协调", []),
                TemplateTask("整理复盘与致谢", ["复盘记录"]),
            ],
        ),
    ],
)

_GENERIC = GoalTemplate(
    key="generic",
    keywords=(),
    milestones=[
        TemplateMilestone(
            "拆解与分工",
            0.25,
            [TemplateTask("明确范围并分配任务", ["分工表"])],
        ),
        TemplateMilestone(
            "推进执行",
            0.75,
            [
                TemplateTask("完成各自负责部分", []),
                TemplateTask("中期同步进度", ["进度记录"]),
            ],
        ),
        TemplateMilestone(
            "收尾交付",
            1.0,
            [TemplateTask("汇总检查并交付", ["交付物"])],
        ),
    ],
)

TEMPLATES = [_COMPETITION, _COURSEWORK, _REPORT, _EVENT]


def match_template(title: str, description: str = "") -> GoalTemplate:
    text = f"{title} {description}"
    for template in TEMPLATES:
        if any(keyword in text for keyword in template.keywords):
            return template
    return _GENERIC


def _parse_due(due_date: str | None, today: date) -> date:
    if due_date:
        try:
            return datetime.fromisoformat(due_date).date()
        except ValueError:
            pass
    return today + timedelta(days=14)


def decompose_with_template(
    *,
    title: str,
    description: str = "",
    due_date: str | None = None,
    member_ids: list[str],
    now: datetime | None = None,
) -> dict:
    """Return a plain decomposition dict shaped like the LLM schema output.

    Keys: template_key, milestones [{title, due_date}], tasks [{title, summary,
    assignee_id, milestone_index, start_date, due_date, deliverables}].
    """
    template = match_template(title, description)
    today = (now or datetime.now(timezone.utc)).date()
    end = _parse_due(due_date, today)
    if end <= today:
        end = today + timedelta(days=1)
    span_days = (end - today).days

    milestones: list[dict] = []
    tasks: list[dict] = []
    assignees = member_ids or [""]
    assign_index = 0
    previous_due = today
    for milestone_index, milestone in enumerate(template.milestones):
        milestone_due = today + timedelta(days=round(span_days * milestone.fraction))
        if milestone_due <= previous_due:
            milestone_due = previous_due + timedelta(days=1)
        milestone_due = min(milestone_due, end) if milestone.fraction < 1 else end
        milestones.append(
            {"title": milestone.title, "due_date": milestone_due.isoformat()}
        )
        for task in milestone.tasks:
            assignee = assignees[assign_index % len(assignees)] or None
            assign_index += 1
            tasks.append(
                {
                    "title": task.title,
                    "summary": f"来自团队目标「{title}」",
                    "assignee_id": assignee,
                    "milestone_index": milestone_index,
                    "start_date": previous_due.isoformat(),
                    "due_date": milestone_due.isoformat(),
                    "deliverables": list(task.deliverables),
                }
            )
        previous_due = milestone_due
    return {"template_key": template.key, "milestones": milestones, "tasks": tasks}
