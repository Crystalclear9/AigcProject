from app.services.team_workflow import validate_team_tasks


def test_team_plan_requires_review_for_missing_owner_and_acceptance() -> None:
    review = validate_team_tasks([{"task_id": "a", "title": "Prepare data"}])
    assert review.required
    assert any("unassigned" in reason for reason in review.reasons)


def test_team_plan_rejects_dependency_cycles() -> None:
    review = validate_team_tasks([
        {"task_id": "a", "title": "A", "owner_id": "u1", "dependency_ids": ["b"], "deliverables": ["x"], "acceptance_criteria": [{"id": "c1", "description": "check"}]},
        {"task_id": "b", "title": "B", "owner_id": "u2", "dependency_ids": ["a"], "deliverables": ["y"], "acceptance_criteria": [{"id": "c2", "description": "check"}]},
    ])
    assert review.required
    assert any(item["kind"] == "dependency_cycle" for item in review.conflicts)
