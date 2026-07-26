from __future__ import annotations

import asyncio
import os
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

from app.core.config import settings
from app.repositories.workflows import WorkflowRepository, close_workflow_repository
from app.schemas.workflow import WorkflowResumeRequest
from app.services import workflow_service


class WorkflowInputCleanupTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name)
        self.original_database = settings.workflow_database_path
        self.original_input_directory = settings.workflow_input_directory
        object.__setattr__(
            settings,
            "workflow_database_path",
            str(self.root / "workflow.db"),
        )
        object.__setattr__(
            settings,
            "workflow_input_directory",
            str(self.root / "inputs"),
        )
        close_workflow_repository()
        self.repository = WorkflowRepository()

    def tearDown(self) -> None:
        close_workflow_repository()
        object.__setattr__(
            settings,
            "workflow_database_path",
            self.original_database,
        )
        object.__setattr__(
            settings,
            "workflow_input_directory",
            self.original_input_directory,
        )
        self.temporary_directory.cleanup()

    def _create_run(self, run_id: str, status: str, path: Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(b"image")
        self.repository.create_run(
            run_id,
            {"workflow_status": status},
            str(path.resolve()),
        )

    def test_terminal_input_is_deleted_and_database_path_is_cleared(self) -> None:
        image_path = self.root / "inputs" / "terminal.bin"
        self._create_run("terminal", "completed", image_path)

        cleaned = workflow_service.cleanup_workflow_input("terminal")

        self.assertTrue(cleaned)
        self.assertFalse(image_path.exists())
        self.assertIsNone(self.repository.input_path_for_run("terminal"))

    def test_non_terminal_input_is_retained(self) -> None:
        image_path = self.root / "inputs" / "active.bin"
        self._create_run("active", "awaiting_review", image_path)

        cleaned = workflow_service.cleanup_workflow_input("active")

        self.assertFalse(cleaned)
        self.assertTrue(image_path.exists())
        self.assertEqual(
            self.repository.input_path_for_run("active"),
            str(image_path.resolve()),
        )

    def test_startup_cleanup_removes_terminal_and_stale_orphan_inputs(self) -> None:
        input_directory = self.root / "inputs"
        terminal_path = input_directory / "terminal.bin"
        active_path = input_directory / "active.bin"
        stale_orphan = input_directory / "stale-orphan.bin"
        recent_orphan = input_directory / "recent-orphan.bin"
        unrelated = input_directory / "keep.txt"
        self._create_run("terminal", "failed", terminal_path)
        self._create_run("active", "awaiting_review", active_path)
        stale_orphan.write_bytes(b"stale")
        recent_orphan.write_bytes(b"recent")
        unrelated.write_text("keep", encoding="utf-8")
        old_timestamp = time.time() - (25 * 60 * 60)
        os.utime(stale_orphan, (old_timestamp, old_timestamp))
        os.utime(unrelated, (old_timestamp, old_timestamp))

        cleaned = workflow_service.cleanup_stale_workflow_inputs(
            max_orphan_age_seconds=24 * 60 * 60,
        )

        self.assertEqual(cleaned, 2)
        self.assertFalse(terminal_path.exists())
        self.assertFalse(stale_orphan.exists())
        self.assertTrue(active_path.exists())
        self.assertTrue(recent_orphan.exists())
        self.assertTrue(unrelated.exists())


class WorkflowInputCancellationTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name)
        self.original_database = settings.workflow_database_path
        self.original_checkpoint_database = settings.workflow_checkpoint_database_path
        self.original_input_directory = settings.workflow_input_directory
        object.__setattr__(
            settings,
            "workflow_database_path",
            str(self.root / "workflow.db"),
        )
        object.__setattr__(
            settings,
            "workflow_checkpoint_database_path",
            str(self.root / "checkpoint.db"),
        )
        object.__setattr__(
            settings,
            "workflow_input_directory",
            str(self.root / "inputs"),
        )
        close_workflow_repository()

    async def asyncTearDown(self) -> None:
        await workflow_service.close_workflow_runtime()
        close_workflow_repository()
        object.__setattr__(
            settings,
            "workflow_database_path",
            self.original_database,
        )
        object.__setattr__(
            settings,
            "workflow_checkpoint_database_path",
            self.original_checkpoint_database,
        )
        object.__setattr__(
            settings,
            "workflow_input_directory",
            self.original_input_directory,
        )
        self.temporary_directory.cleanup()

    async def test_cancelled_workflow_deletes_its_input(self) -> None:
        async def slow_ocr(*args, **kwargs):
            await asyncio.sleep(60)

        with patch(
            "app.services.workflow_graph.VivoOcrClient.recognize",
            slow_ocr,
        ):
            started = await workflow_service.start_image_workflow(b"image")
            repository = WorkflowRepository()
            image_path = Path(repository.input_path_for_run(started.run_id))
            self.assertTrue(image_path.exists())

            cancelled = await workflow_service.resume_workflow(
                started.run_id,
                WorkflowResumeRequest(command="cancel"),
            )

        self.assertEqual(cancelled.workflow_status, "cancelled")
        self.assertFalse(image_path.exists())
        self.assertIsNone(repository.input_path_for_run(started.run_id))
