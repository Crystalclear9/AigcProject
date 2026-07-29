from __future__ import annotations

from datetime import datetime, timezone
from math import exp

from app.schemas.card import ActionCard
from app.schemas.intake import CardReplanRequest


def replan_priority(card: ActionCard, request: CardReplanRequest) -> ActionCard:
    mode = request.priority_mode or card.priority_mode
    now = datetime.now(timezone.utc)
    if mode == "manual":
        priority = request.manual_priority or card.priority
        score = {"low": 25.0, "normal": 50.0, "high": 85.0}[priority]
        return card.model_copy(
            update={
                "priority": priority,
                "priority_mode": "manual",
                "priority_score": score,
                "priority_reason": "用户手动设定，工作流不会自动覆盖",
                "priority_updated_at": now.isoformat(),
                "priority_locked": True,
            }
        )
    if card.priority_locked and request.priority_mode is None:
        return card

    deadline_signal = _deadline_signal(card.deadline or card.start_time, now)
    dependency_signal = min(1.0, (len(card.dependencies) + request.blocked_dependents * 2) / 6)
    workload_signal = min(1.0, (request.estimated_minutes or 60) / 480)
    weights = request.policy
    weighted = (
        deadline_signal * weights.deadline_weight
        + request.importance * weights.importance_weight
        + dependency_signal * weights.dependency_weight
        + request.team_impact * weights.team_impact_weight
        + workload_signal * weights.workload_weight
    )
    denominator = max(
        0.01,
        weights.deadline_weight
        + weights.importance_weight
        + weights.dependency_weight
        + weights.team_impact_weight
        + weights.workload_weight,
    )
    score = round(max(0.0, min(100.0, weighted / denominator * 100)), 1)
    priority = "high" if score >= 70 else "low" if score < 35 else "normal"
    reasons = []
    if deadline_signal >= 0.7:
        reasons.append("截止时间临近")
    if dependency_signal >= 0.5:
        reasons.append("存在依赖阻塞")
    if request.team_impact >= 0.6:
        reasons.append("影响团队交付")
    if request.importance >= 0.7:
        reasons.append("重要性较高")
    return card.model_copy(
        update={
            "priority": priority,
            "priority_mode": "adaptive",
            "priority_score": score,
            "priority_reason": "、".join(reasons) or "按时间、重要性与工作量综合计算",
            "priority_updated_at": now.isoformat(),
            "priority_locked": False,
        }
    )


def _deadline_signal(value: str | None, now: datetime) -> float:
    if not value:
        return 0.2
    try:
        deadline = datetime.fromisoformat(value.replace("Z", "+00:00"))
        if deadline.tzinfo is None:
            deadline = deadline.replace(tzinfo=timezone.utc)
    except ValueError:
        return 0.35
    hours = (deadline.astimezone(timezone.utc) - now).total_seconds() / 3600
    if hours <= 0:
        return 1.0
    return max(0.05, min(1.0, 2 / (1 + exp((hours - 48) / 24))))
