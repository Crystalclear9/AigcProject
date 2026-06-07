from __future__ import annotations

import asyncio
import statistics
import time
import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient

from app.main import app
from app.schemas.workflow import (
    ConfirmWorkflowRequest,
    DraftPatchRequest,
    OcrCandidateRequest,
    WorkflowResumeRequest,
)
from app.services.workflow_service import (
    confirm_workflow,
    get_workflow,
    patch_draft,
    resume_workflow,
    start_image_workflow,
    start_text_workflow,
    submit_ocr_candidate,
    wait_for_result,
)
from app.services.vivo_ocr import VivoOcrError
from app.services.workflow_graph import choose_route, dispatch_experts
from app.services.workflow_agents import build_action_graph, plan_agents
from app.repositories.workflows import WorkflowRepository, cache_key


class WorkflowLifecycleTest(unittest.IsolatedAsyncioTestCase):
    async def test_text_workflow_returns_provisional_then_requires_confirmation(self) -> None:
        started = await start_text_workflow(
            "请在6月10日22:00前提交实验报告，提交至学习通。",
            "2026-06-07T10:00:00+08:00",
        )
        self.assertEqual(started.workflow_status, "queued")
        provisional = await wait_for_result(started.run_id, timeout=1, accept_provisional=True)
        self.assertGreaterEqual(provisional.revision, 1)
        self.assertTrue(provisional.cards)
        self.assertLess(provisional.time_to_first_draft_ms or 9999, 150)
        enhanced = await wait_for_result(started.run_id, timeout=2, accept_provisional=False)
        self.assertEqual(enhanced.workflow_status, "awaiting_review")
        self.assertEqual(enhanced.pending_action, "confirm")
        self.assertTrue(enhanced.action_graph.actions)
        completed = confirm_workflow(
            started.run_id,
            ConfirmWorkflowRequest(revision=enhanced.revision),
        )
        self.assertEqual(completed.workflow_status, "completed")

    async def test_image_ocr_candidate_races_failed_cloud_ocr(self) -> None:
        async def failing_ocr(*args, **kwargs):
            await asyncio.sleep(0.03)
            raise VivoOcrError("offline")

        with patch("app.services.workflow_graph.VivoOcrClient.recognize", failing_ocr):
            started = await start_image_workflow(b"image")
            submit_ocr_candidate(
                started.run_id,
                OcrCandidateRequest(
                    text="请在6月10日22:00前提交实验报告，提交至学习通。",
                    engine="mlkit",
                    confidence=0.82,
                ),
            )
            completed = await wait_for_result(started.run_id, timeout=2, accept_provisional=False)
        self.assertEqual(completed.workflow_status, "awaiting_review")
        self.assertTrue(completed.engine.startswith("mlkit+"))

    async def test_user_locked_draft_cannot_be_overwritten(self) -> None:
        started = await start_text_workflow(
            "可以，我明天上午把表格发给老师。",
            "2026-06-07T10:00:00+08:00",
        )
        provisional = await wait_for_result(started.run_id, timeout=1, accept_provisional=True)
        edited = provisional.cards[0].model_copy(update={"title": "用户锁定标题", "need_confirm": []})
        patched = patch_draft(
            started.run_id,
            DraftPatchRequest(
                base_revision=provisional.revision,
                cards=[edited],
                locked_fields={edited.id: ["title"]},
            ),
        )
        confirm_workflow(started.run_id, ConfirmWorkflowRequest(revision=patched.revision))
        await asyncio.sleep(0.1)
        final = get_workflow(started.run_id)
        self.assertEqual(final.cards[0].title, "用户锁定标题")

    async def test_field_operations_use_independent_versions(self) -> None:
        started = await start_text_workflow(
            "Please submit the report by June 10 at 22:00.",
            "2026-06-07T10:00:00+08:00",
        )
        provisional = await wait_for_result(started.run_id, timeout=1, accept_provisional=True)
        card = provisional.cards[0]
        version = provisional.field_versions[card.id]["title"]
        patched = patch_draft(
            started.run_id,
            DraftPatchRequest(
                base_revision=provisional.revision,
                operations=[
                    {
                        "operation": "set",
                        "card_id": card.id,
                        "field": "title",
                        "value": "User title",
                        "base_field_version": version,
                    },
                    {
                        "operation": "lock",
                        "card_id": card.id,
                        "field": "title",
                    },
                ],
            ),
        )
        self.assertEqual(patched.cards[0].title, "User title")
        self.assertIn("title", patched.user_locked[card.id])
        with self.assertRaisesRegex(ValueError, "field conflicts"):
            patch_draft(
                started.run_id,
                DraftPatchRequest(
                    base_revision=provisional.revision,
                    operations=[
                        {
                            "operation": "set",
                            "card_id": card.id,
                            "field": "title",
                            "value": "Stale title",
                            "base_field_version": version,
                        }
                    ],
                ),
            )

    async def test_resume_compatibility_and_cancel(self) -> None:
        async def failing_ocr(*args, **kwargs):
            raise VivoOcrError("offline")

        with patch("app.services.workflow_graph.VivoOcrClient.recognize", failing_ocr):
            started = await start_image_workflow(b"image")
            await resume_workflow(
                started.run_id,
                WorkflowResumeRequest(
                    command="provide_ocr_text",
                    ocr_text="请在6月10日22:00前提交实验报告。",
                ),
            )
            completed = await wait_for_result(started.run_id, timeout=2, accept_provisional=False)
        self.assertEqual(completed.workflow_status, "awaiting_review")

        other = await start_text_workflow("整理截图信息")
        cancelled = await resume_workflow(other.run_id, WorkflowResumeRequest(command="cancel"))
        self.assertEqual(cancelled.workflow_status, "cancelled")


class PerformanceWorkflowTest(unittest.TestCase):
    def test_twenty_concurrent_rule_workflows_meet_local_budget(self) -> None:
        async def one(index: int) -> float:
            started_at = time.perf_counter()
            run = await start_text_workflow(f"请在6月10日22:00前提交实验报告 {index}")
            completed = await wait_for_result(run.run_id, timeout=2, accept_provisional=False)
            self.assertEqual(completed.workflow_status, "awaiting_review")
            return (time.perf_counter() - started_at) * 1000

        async def benchmark() -> list[float]:
            return await asyncio.gather(*(one(index) for index in range(20)))

        durations = asyncio.run(benchmark())
        self.assertLess(sorted(durations)[18], 200)
        self.assertLess(statistics.mean(durations), 180)


class SupervisorAgentTest(unittest.TestCase):
    def test_supervisor_uses_rules_for_simple_input_and_agents_for_complex_input(self) -> None:
        simple = {
            "overall_confidence": 0.9,
            "complexity_reasons": [],
            "rule_cards": [{"title": "Submit report", "card_type": "task"}],
        }
        self.assertEqual(plan_agents(simple)[0], [])
        complex_state = {
            "overall_confidence": 0.55,
            "complexity_reasons": ["multiple_cards", "uncertain_fields"],
            "rule_cards": [
                {"title": "Prepare report", "card_type": "task", "deadline": "2026-06-10T09:00:00Z"},
                {"title": "Attend meeting", "card_type": "event", "start_time": "2026-06-10T10:00:00Z"},
            ],
        }
        agents, _ = plan_agents(complex_state)
        self.assertIn("semantic_agent", agents)
        self.assertIn("temporal_agent", agents)
        self.assertIn("quality_agent", agents)
        routed = choose_route(complex_state)
        self.assertEqual(routed["route"], "supervisor_agents")

    def test_dispatch_creates_real_send_branches(self) -> None:
        sends = dispatch_experts(
            {
                "run_id": "parallel",
                "active_agents": ["temporal_agent", "quality_agent"],
                "ocr_text": "text",
                "rule_cards": [],
            }
        )
        self.assertEqual(len(sends), 2)
        self.assertEqual({send.node for send in sends}, {"run_expert"})

    def test_action_graph_projects_prerequisite(self) -> None:
        cards = [
            {
                "id": "task-1",
                "card_type": "task",
                "title": "准备会议材料",
                "summary": "Prepare materials before meeting",
                "source_text": "Prepare materials before meeting",
            },
            {
                "id": "event-1",
                "card_type": "event",
                "title": "Attend meeting",
                "summary": "Prepare materials before meeting",
                "source_text": "Prepare materials before meeting",
            },
        ]
        graph = build_action_graph(cards, [], cards[0]["source_text"], [])
        self.assertEqual(len(graph.actions), 2)
        self.assertTrue(any(dep.dependency_type == "prerequisite" for dep in graph.dependencies))

    def test_model_cache_round_trip(self) -> None:
        repo = WorkflowRepository()
        key = cache_key("提交实验报告", "fast:test")
        repo.put_cache(key, {"cards": [{"title": "提交实验报告"}]}, "fast:test")
        self.assertEqual(repo.get_cache(key)["cards"][0]["title"], "提交实验报告")


class WorkflowApiTest(unittest.TestCase):
    def test_start_query_events_and_revision_conflict(self) -> None:
        with TestClient(app) as client:
            response = client.post(
                "/api/workflows/screenshot-text",
                json={"text": "请在6月10日22:00前提交实验报告。"},
            )
            self.assertEqual(response.status_code, 202)
            run_id = response.json()["run_id"]
            deadline = time.time() + 2
            body = response.json()
            while body["revision"] == 0 and time.time() < deadline:
                time.sleep(0.02)
                body = client.get(f"/api/workflows/{run_id}").json()
            self.assertGreaterEqual(body["revision"], 1)
            conflict = client.patch(
                f"/api/workflows/{run_id}/draft",
                json={"base_revision": 999, "cards": body["cards"], "locked_fields": {}},
            )
            self.assertEqual(conflict.status_code, 409)
            deadline = time.time() + 2
            while body["workflow_status"] != "awaiting_review" and time.time() < deadline:
                time.sleep(0.02)
                body = client.get(f"/api/workflows/{run_id}").json()
            confirmed = client.post(
                f"/api/workflows/{run_id}/confirm",
                json={"revision": body["revision"]},
            )
            self.assertEqual(confirmed.status_code, 200)
            with client.stream("GET", f"/api/workflows/{run_id}/events") as stream:
                text = "".join(stream.iter_text())
            self.assertIn("event: run_started", text)
            self.assertIn("event: draft_created", text)
            self.assertIn("event: action_graph_updated", text)
            self.assertIn("event: completed", text)


if __name__ == "__main__":
    unittest.main()
