from __future__ import annotations

import asyncio
import os
import statistics
import tempfile
import time
import unittest
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import patch

from fastapi.testclient import TestClient

from app.main import app
from app.core.config import settings
from app.schemas.workflow import (
    ConfirmWorkflowRequest,
    DraftPatchRequest,
    OcrCandidateRequest,
    WorkflowResumeRequest,
    WorkflowReactRequest,
)
from app.schemas.card import ActionCard
from app.services.react_refiner import refine_state_with_react
from app.services.workflow_service import (
    _can_complete_rules_inline,
    close_workflow_runtime,
    confirm_workflow,
    get_workflow,
    initialize_workflow_runtime,
    patch_draft,
    refine_workflow_with_react,
    resume_workflow,
    start_image_workflow,
    start_text_workflow,
    submit_ocr_candidate,
    wait_for_result,
)
from app.services.vivo_ocr import VivoOcrError
from app.services.autonomous_agents import create_plan
from app.services.workflow_graph import dispatch_ready_tasks
from app.services.workflow_agents import build_action_graph
from app.repositories.workflows import (
    WorkflowRepository,
    cache_key,
    close_workflow_repository,
)


class WorkflowLifecycleTest(unittest.IsolatedAsyncioTestCase):
    async def asyncTearDown(self) -> None:
        await close_workflow_runtime()

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

    async def test_react_refinement_preserves_locked_fields_and_requires_review(self) -> None:
        started = await start_text_workflow(
            "请在6月10日22:00前提交实验报告，提交至学习通。",
            "2026-06-07T10:00:00+08:00",
        )
        draft = await wait_for_result(started.run_id, timeout=1, accept_provisional=True)
        edited = draft.cards[0].model_copy(update={"title": "用户锁定标题"})
        patched = patch_draft(
            started.run_id,
            DraftPatchRequest(
                base_revision=draft.revision,
                cards=[edited],
                locked_fields={edited.id: ["title"]},
            ),
        )

        refined = await refine_workflow_with_react(
            started.run_id,
            WorkflowReactRequest(
                base_revision=patched.revision,
                instruction="重写标题更具体，并检查提交方式",
                selected_card_ids=[edited.id],
            ),
        )

        self.assertEqual(refined.workflow_status, "awaiting_review")
        self.assertIn("react_refiner", refined.active_agents)
        self.assertTrue(refined.react_suggestions)
        self.assertEqual(refined.cards[0].title, "用户锁定标题")
        self.assertIn("react", refined.engine)

    async def test_react_refinement_does_not_overwrite_unselected_cards(self) -> None:
        selected = ActionCard(
            id="selected-card",
            created_at=datetime.now(timezone.utc),
            title="提交实验报告",
            deadline="2026-06-10T22:00:00+08:00",
            submit_method=None,
            source_text="请提交实验报告",
        )
        unselected = ActionCard(
            id="unselected-card",
            created_at=datetime.now(timezone.utc),
            title="进展汇报",
            deadline=None,
            submit_method=None,
            source_text="参加进展汇报",
        )
        incoming = ActionCard(
            id="incoming-card",
            created_at=datetime.now(timezone.utc),
            title="进展汇报",
            deadline="2026-06-11T14:30:00+08:00",
            submit_method="腾讯会议",
            source_text="参加进展汇报，腾讯会议",
        )

        with patch("app.services.react_refiner.extract_cards_with_rules", return_value=[incoming]):
            refined = await refine_state_with_react(
                {
                    "cards": [
                        selected.model_dump(mode="json"),
                        unselected.model_dump(mode="json"),
                    ],
                    "ocr_text": "请提交实验报告；参加进展汇报，腾讯会议。",
                    "has_fast_model": False,
                    "has_expert_model": False,
                },
                instruction="只完善选中的实验报告",
                selected_card_ids=[selected.id],
            )

        cards = {card["id"]: card for card in refined["cards"]}
        self.assertIsNone(cards[unselected.id]["deadline"])
        self.assertIsNone(cards[unselected.id]["submit_method"])
        self.assertEqual(refined["suggestions"][unselected.id]["deadline"], incoming.deadline)
        self.assertEqual(refined["suggestions"][unselected.id]["submit_method"], incoming.submit_method)

    async def test_react_refinement_blocks_empty_selection(self) -> None:
        current = ActionCard(
            id="current-card",
            created_at=datetime.now(timezone.utc),
            title="Submit lab report",
            deadline="2026-06-10T22:00:00+08:00",
            submit_method=None,
            source_text="Submit lab report before June 10 22:00",
        )

        with patch(
            "app.services.react_refiner.extract_cards_with_rules",
            side_effect=AssertionError("empty selection must not run tools"),
        ):
            refined = await refine_state_with_react(
                {
                    "cards": [current.model_dump(mode="json")],
                    "ocr_text": current.source_text,
                    "has_fast_model": False,
                    "has_expert_model": False,
                },
                instruction="continue refining",
                selected_card_ids=[],
            )

        self.assertEqual(refined["cards"][0]["title"], current.title)
        self.assertEqual(refined["react_session"]["status"], "failed")
        self.assertEqual(refined["react_session"]["failure_type"], "empty_selection")
        self.assertIn("selected_card_ids is required for ReAct refinement", refined["validation_errors"])

    async def test_field_operations_use_independent_versions(self) -> None:
        started = await start_text_workflow(
            "请在6月10日22:00前提交实验报告。",
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

    async def test_confirm_rejects_unresolved_need_confirm_fields(self) -> None:
        started = await start_text_workflow(
            "请在6月10日22:00前提交实验报告。",
            "2026-06-07T10:00:00+08:00",
        )
        draft = await wait_for_result(started.run_id, timeout=1, accept_provisional=True)
        card = draft.cards[0].model_copy(update={"need_confirm": ["deadline"]})
        patched = patch_draft(
            started.run_id,
            DraftPatchRequest(base_revision=draft.revision, cards=[card]),
        )

        with self.assertRaisesRegex(ValueError, "unresolved confirmation fields"):
            confirm_workflow(started.run_id, ConfirmWorkflowRequest(revision=patched.revision))

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

    async def test_late_ocr_conflict_is_preserved_for_review(self) -> None:
        started = await start_text_workflow("提交实验报告")
        submit_ocr_candidate(
            started.run_id,
            OcrCandidateRequest(text="明天十点提交实验报告", engine="mlkit", confidence=0.84),
        )
        updated = submit_ocr_candidate(
            started.run_id,
            OcrCandidateRequest(text="下周五下午参加篮球比赛", engine="vivo-ocr", confidence=0.9),
        )
        self.assertGreaterEqual(len(WorkflowRepository().get_state(started.run_id)["ocr_candidates"]), 2)
        self.assertTrue(any("OCR candidates conflict" in warning for warning in updated.warnings))

    async def test_multi_card_text_still_uses_full_workflow_path(self) -> None:
        started = await start_text_workflow(
            "6月10日22:00前提交实验报告；6月11日10:00参加组会并准备进展汇报",
            "2026-06-07T10:00:00+08:00",
        )
        initial_state = WorkflowRepository().get_state(started.run_id)
        self.assertEqual(initial_state["workflow_status"], "queued")
        self.assertIn("multiple_cards", initial_state.get("complexity_reasons", []))

        completed = await wait_for_result(started.run_id, timeout=2, accept_provisional=False)

        self.assertEqual(completed.workflow_status, "awaiting_review")
        self.assertGreaterEqual(len(completed.cards), 2)
        self.assertNotIn("rules-fast-path", [trace.engine for trace in completed.node_trace])

    async def test_complex_announcement_keeps_rule_cards_when_agents_degrade(self) -> None:
        started = await start_text_workflow(
            "请在7月3日22:00前提交《实验报告》到学习通。"
            "请在7月4日14:30参加项目进展汇报，地点会议室203。"
            "请在7月5日20:00前把报名表发到指定邮箱。",
            "2026-06-28T10:00:00+08:00",
        )
        state = WorkflowRepository().get_state(started.run_id)
        titles = [card["title"] for card in state.get("rule_cards", [])]

        self.assertGreaterEqual(len(titles), 3)
        self.assertTrue(any("实验报告" in title for title in titles))
        self.assertTrue(any("汇报" in title for title in titles))
        self.assertTrue(any("报名表" in title or "报名" in title for title in titles))

    async def test_inline_rules_fast_path_is_disabled_when_cloud_models_are_configured(self) -> None:
        state = {
            "input_kind": "text",
            "rule_cards": [{"title": "提交实验报告"}],
            "overall_confidence": 0.95,
            "complexity_reasons": [],
        }
        original_fast_key = settings.fast_model_api_key
        original_expert_key = settings.expert_model_api_key
        try:
            object.__setattr__(settings, "fast_model_api_key", "configured-on-server")
            object.__setattr__(settings, "expert_model_api_key", "")
            self.assertFalse(_can_complete_rules_inline(state))
        finally:
            object.__setattr__(settings, "fast_model_api_key", original_fast_key)
            object.__setattr__(settings, "expert_model_api_key", original_expert_key)


class PerformanceWorkflowTest(unittest.TestCase):
    def test_twenty_concurrent_rule_workflows_meet_local_budget(self) -> None:
        async def one(index: int) -> tuple[float, float]:
            started_at = time.perf_counter()
            run = await start_text_workflow(
                f"请在6月10日22:00前提交实验报告 {index}",
                "2026-06-07T10:00:00+08:00",
            )
            completed = await wait_for_result(run.run_id, timeout=2, accept_provisional=False)
            self.assertEqual(completed.workflow_status, "awaiting_review")
            self.assertEqual(completed.route, "rules")
            return (
                (time.perf_counter() - started_at) * 1000,
                completed.time_to_first_draft_ms or 9999,
            )

        async def benchmark() -> list[tuple[float, float]]:
            original_database = settings.workflow_database_path
            original_checkpoint_database = settings.workflow_checkpoint_database_path
            temporary_directory = tempfile.TemporaryDirectory()
            object.__setattr__(
                settings,
                "workflow_database_path",
                str(Path(temporary_directory.name) / "workflow.db"),
            )
            object.__setattr__(
                settings,
                "workflow_checkpoint_database_path",
                str(Path(temporary_directory.name) / "workflow_checkpoint.db"),
            )
            close_workflow_repository()
            try:
                self.assertTrue(WorkflowRepository().healthcheck())
                await initialize_workflow_runtime()
                warmup = await start_text_workflow(
                    "请在6月10日22:00前提交实验报告 warmup",
                    "2026-06-07T10:00:00+08:00",
                )
                await wait_for_result(warmup.run_id, timeout=2, accept_provisional=False)
                return await asyncio.gather(*(one(index) for index in range(20)))
            finally:
                await close_workflow_runtime()
                close_workflow_repository()
                object.__setattr__(settings, "workflow_database_path", original_database)
                object.__setattr__(
                    settings,
                    "workflow_checkpoint_database_path",
                    original_checkpoint_database,
                )
                temporary_directory.cleanup()

        results = asyncio.run(benchmark())
        durations = [result[0] for result in results]
        first_draft_durations = [result[1] for result in results]
        is_ci = os.getenv("CI", "").lower() in {"1", "true", "yes"}
        p95_budget_ms = float(
            os.getenv("WORKFLOW_TEST_P95_BUDGET_MS", "600" if is_ci else "450")
        )
        mean_budget_ms = float(
            os.getenv("WORKFLOW_TEST_MEAN_BUDGET_MS", "500" if is_ci else "400")
        )
        p95_ms = sorted(durations)[18]
        mean_ms = statistics.mean(durations)
        first_draft_p95_ms = sorted(first_draft_durations)[18]
        first_draft_budget_ms = float(
            os.getenv(
                "WORKFLOW_TEST_FIRST_DRAFT_P95_BUDGET_MS",
                "400" if is_ci else "300",
            )
        )
        self.assertLess(
            first_draft_p95_ms,
            first_draft_budget_ms,
            (
                f"first_draft_p95={first_draft_p95_ms:.1f}ms "
                f"durations={sorted(round(value, 1) for value in first_draft_durations)}"
            ),
        )
        self.assertLess(
            p95_ms,
            p95_budget_ms,
            f"p95={p95_ms:.1f}ms durations={sorted(round(value, 1) for value in durations)}",
        )
        self.assertLess(
            mean_ms,
            mean_budget_ms,
            f"mean={mean_ms:.1f}ms durations={sorted(round(value, 1) for value in durations)}",
        )


class SupervisorAgentTest(unittest.TestCase):
    def test_supervisor_uses_rules_for_simple_input_and_agents_for_complex_input(self) -> None:
        simple = {
            "overall_confidence": 0.9,
            "complexity_reasons": [],
            "rule_cards": [{"title": "Submit report", "card_type": "task"}],
        }
        simple["run_id"] = "simple"
        simple["agent_task_results"] = []
        simple["replan_count"] = 0
        self.assertEqual(create_plan(simple).tasks, [])
        complex_state = {
            "overall_confidence": 0.55,
            "complexity_reasons": ["multiple_cards", "uncertain_fields"],
            "rule_cards": [
                {"title": "Prepare report", "card_type": "task", "deadline": "2026-06-10T09:00:00Z"},
                {"title": "Attend meeting", "card_type": "event", "start_time": "2026-06-10T10:00:00Z"},
            ],
        }
        complex_state["run_id"] = "complex"
        complex_state["ocr_text"] = "Prepare report before attending the meeting"
        complex_state["agent_task_results"] = []
        complex_state["replan_count"] = 0
        tools = {task.tool for task in create_plan(complex_state).tasks}
        self.assertIn("semantic_decomposer", tools)
        self.assertIn("temporal_solver", tools)
        self.assertIn("quality_verifier", tools)

    def test_dispatch_creates_real_send_branches(self) -> None:
        plan = create_plan(
            {
                "run_id": "parallel",
                "overall_confidence": 0.5,
                "complexity_reasons": ["multiple_cards"],
                "ocr_text": "text",
                "rule_cards": [],
                "agent_task_results": [],
                "replan_count": 0,
            }
        )
        sends = dispatch_ready_tasks(
            {
                "run_id": "parallel",
                "agent_plan": plan.model_dump(mode="json"),
                "agent_task_results": [],
                "workflow_deadline_at": time.time() + 15,
            }
        )
        self.assertIsInstance(sends, list)
        self.assertGreaterEqual(len(sends), 2)
        self.assertEqual({send.node for send in sends}, {"run_agent_task"})

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
            self.assertIn('"snapshot"', text)
            self.assertIn('"cache_status"', text)

    def test_health_reports_durable_runtime_ready(self) -> None:
        with TestClient(app) as client:
            health = client.get("/health").json()
            ready = client.get("/ready").json()
        self.assertEqual(health["status"], "ok")
        self.assertTrue(health["sqlite_checkpointer_available"])
        self.assertIn("chat_configured", health)
        self.assertIn("ocr_configured", health)
        self.assertIn("image_generation_configured", health)
        self.assertTrue(ready["ready"])


if __name__ == "__main__":
    unittest.main()
