from __future__ import annotations

import unittest

from pydantic import ValidationError

from app.schemas.agent_workflow import AgentPlan, AgentTask
from app.services.autonomous_agents import (
    create_plan,
    execute_task,
    redact_private_text,
    safe_retrieval_query,
    verify_results,
)


class AutonomousPlannerTest(unittest.TestCase):
    def test_complex_input_creates_bounded_dependency_dag(self) -> None:
        state = {
            "run_id": "complex-run",
            "overall_confidence": 0.51,
            "complexity_reasons": ["multiple_cards", "uncertain_fields"],
            "ocr_text": "准备材料后参加人工智能讲座，并在周五提交报告。",
            "rule_cards": [
                {
                    "id": "task-1",
                    "card_type": "task",
                    "title": "准备材料",
                    "deadline": "2026-06-12T09:00:00+08:00",
                    "materials": ["报告"],
                },
                {
                    "id": "event-1",
                    "card_type": "event",
                    "title": "参加人工智能讲座",
                    "start_time": "2026-06-12T10:00:00+08:00",
                },
            ],
            "has_fast_model": True,
            "has_expert_model": True,
            "agent_task_results": [],
            "replan_count": 0,
        }
        plan = create_plan(state)
        tools = {task.tool for task in plan.tasks}
        self.assertLessEqual(len(plan.tasks), 6)
        self.assertIn("semantic_decomposer", tools)
        self.assertIn("dependency_solver", tools)
        self.assertIn("privacy_risk_analyzer", tools)
        self.assertIn("quality_verifier", tools)
        dependency_task = next(task for task in plan.tasks if task.tool == "dependency_solver")
        self.assertTrue(dependency_task.depends_on)
        self.assertEqual(plan.max_tasks, 8)
        self.assertEqual(plan.max_replans, 2)
        self.assertEqual(plan.deadline_ms, 15000)

    def test_high_confidence_input_uses_empty_fast_path_plan(self) -> None:
        plan = create_plan(
            {
                "run_id": "simple-run",
                "overall_confidence": 0.95,
                "complexity_reasons": [],
                "rule_cards": [{"id": "1", "title": "提交报告", "deadline": "2026-06-10"}],
                "agent_task_results": [],
                "replan_count": 0,
            }
        )
        self.assertEqual(plan.tasks, [])
        self.assertIn("high-confidence", plan.reasons[0])

    def test_plan_rejects_unknown_dependencies(self) -> None:
        task = AgentTask(
            id="quality",
            objective="verify",
            tool="quality_verifier",
            depends_on=["missing"],
            idempotency_key="quality-key",
        )
        with self.assertRaises(ValidationError):
            AgentPlan(id="invalid", objective="invalid", tasks=[task])


class PrivacyToolTest(unittest.IsolatedAsyncioTestCase):
    async def test_private_content_blocks_web_task(self) -> None:
        privacy_task = AgentTask(
            id="privacy",
            objective="classify",
            tool="privacy_risk_analyzer",
            idempotency_key="privacy-key",
        )
        state = {
            "ocr_text": "身份证：110101199001011234，查询人工智能讲座",
            "rule_cards": [],
        }
        privacy_result = await execute_task(privacy_task, state)
        self.assertEqual(privacy_result.risk_level, "high")

        web_task = AgentTask(
            id="web",
            objective="retrieve",
            tool="web_retriever",
            depends_on=["privacy"],
            arguments={"entities": ["人工智能讲座"]},
            idempotency_key="web-key",
        )
        web_result = await execute_task(
            web_task,
            {
                **state,
                "agent_task_results": [privacy_result.model_dump(mode="json")],
            },
        )
        self.assertEqual(web_result.status, "skipped")
        self.assertIn("retrieval_blocked_by_privacy_policy", web_result.findings)

    def test_queries_are_redacted_before_retrieval(self) -> None:
        redacted = redact_private_text("电话 13800138000，参加人工智能讲座")
        self.assertNotIn("13800138000", redacted)
        self.assertIsNone(safe_retrieval_query("13800138000"))
        self.assertEqual(safe_retrieval_query("人工智能讲座"), "人工智能讲座")


class AutonomousVerificationTest(unittest.TestCase):
    def test_missing_time_requests_targeted_replan(self) -> None:
        summary = verify_results(
            {
                "route": "supervisor_agents",
                "overall_confidence": 0.4,
                "rule_cards": [
                    {
                        "id": "promise-1",
                        "card_type": "promise",
                        "title": "发送材料",
                        "deadline": None,
                    }
                ],
                "agent_task_results": [
                    {
                        "task_id": "privacy",
                        "tool": "privacy_risk_analyzer",
                        "status": "completed",
                        "claims": [],
                        "findings": [],
                        "risk_level": "medium",
                        "idempotency_key": "privacy",
                    },
                    {
                        "task_id": "quality",
                        "tool": "quality_verifier",
                        "status": "degraded",
                        "claims": [],
                        "findings": ["missing_execution_time:promise-1"],
                        "risk_level": "low",
                        "idempotency_key": "quality",
                    },
                ],
            }
        )
        self.assertFalse(summary.passed)
        self.assertIn("temporal_solver", summary.recommended_tasks)
        self.assertTrue(summary.requires_review)


if __name__ == "__main__":
    unittest.main()
