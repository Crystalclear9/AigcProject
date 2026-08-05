from __future__ import annotations

from typing import Any

from app.schemas.agent_workflow import TeamTask, TeamWorkflowReview


def validate_team_tasks(tasks: list[TeamTask | dict[str, Any]]) -> TeamWorkflowReview:
    """Validate the team plan before any task or reminder side effect."""
    normalized = [item if isinstance(item, TeamTask) else TeamTask(**item) for item in tasks]
    ids = {item.task_id for item in normalized}
    reasons: list[str] = []
    conflicts: list[dict[str, Any]] = []
    for task in normalized:
        missing = sorted(set(task.dependency_ids) - ids)
        if missing:
            conflicts.append({"task_id": task.task_id, "kind": "unknown_dependency", "values": missing})
        if not task.owner_id:
            reasons.append(f"unassigned:{task.task_id}")
            if not task.unassigned_reason:
                conflicts.append({"task_id": task.task_id, "kind": "missing_owner"})
        if not task.deliverables:
            reasons.append(f"missing_deliverable:{task.task_id}")
        if not task.acceptance_criteria:
            reasons.append(f"missing_acceptance:{task.task_id}")

    graph = {task.task_id: [dep for dep in task.dependency_ids if dep in ids] for task in normalized}
    visiting: set[str] = set()
    visited: set[str] = set()

    def visit(node: str) -> None:
        if node in visiting:
            conflicts.append({"task_id": node, "kind": "dependency_cycle"})
            return
        if node in visited:
            return
        visiting.add(node)
        for dependency in graph.get(node, []):
            visit(dependency)
        visiting.remove(node)
        visited.add(node)

    for task_id in graph:
        visit(task_id)
    reasons.extend(
        f"{item['kind']}:{item.get('task_id', '')}"
        for item in conflicts
        if item["kind"] in {"unknown_dependency", "dependency_cycle"}
    )
    return TeamWorkflowReview(
        required=bool(reasons or conflicts),
        reasons=list(dict.fromkeys(reasons)),
        tasks=normalized,
        conflicts=conflicts,
    )


def team_metrics(tasks: list[TeamTask | dict[str, Any]]) -> dict[str, float]:
    normalized = [item if isinstance(item, TeamTask) else TeamTask(**item) for item in tasks]
    count = max(1, len(normalized))
    return {
        "owner_coverage": sum(bool(item.owner_id) for item in normalized) / count,
        "deliverable_coverage": sum(bool(item.deliverables) for item in normalized) / count,
        "acceptance_criterion_coverage": sum(bool(item.acceptance_criteria) for item in normalized) / count,
        "dependency_validity": sum(all(dep in {candidate.task_id for candidate in normalized} for dep in item.dependency_ids) for item in normalized) / count,
    }
