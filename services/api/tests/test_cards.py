from __future__ import annotations

import sqlite3
from collections.abc import Iterator
from pathlib import Path

import pytest

from app.core.config import settings
from app.db.connection import connect, ensure_schema
from app.repositories.cards import CardRepository
from app.schemas.card import ActionCardCreate, ActionCardUpdate


@pytest.fixture
def card_database(tmp_path: Path) -> Iterator[Path]:
    original_path = settings.database_path
    database_path = tmp_path / "cards.db"
    object.__setattr__(settings, "database_path", str(database_path))
    try:
        yield database_path
    finally:
        object.__setattr__(settings, "database_path", original_path)


def test_create_preserves_action_metadata(card_database: Path) -> None:
    card = CardRepository().create(
        ActionCardCreate(
            id="card-create",
            action_id="action-root",
            dependencies=["action-prerequisite"],
            evidence_summary=["群公告要求周五前提交"],
            title="提交实验报告",
        )
    )

    assert card.action_id == "action-root"
    assert card.dependencies == ["action-prerequisite"]
    assert card.evidence_summary == ["群公告要求周五前提交"]


def test_update_preserves_action_metadata(card_database: Path) -> None:
    repository = CardRepository()
    repository.create(ActionCardCreate(id="card-update", title="准备材料"))

    updated = repository.update(
        "card-update",
        ActionCardUpdate(
            action_id="action-updated",
            dependencies=["action-parent"],
            evidence_summary=["报名页列出了身份证明"],
        ),
    )

    assert updated.action_id == "action-updated"
    assert updated.dependencies == ["action-parent"]
    assert updated.evidence_summary == ["报名页列出了身份证明"]


def test_connect_migrates_legacy_card_table(card_database: Path) -> None:
    with sqlite3.connect(card_database) as legacy:
        legacy.execute(
            """
            CREATE TABLE cards (
                id TEXT PRIMARY KEY,
                card_type TEXT NOT NULL,
                title TEXT NOT NULL,
                summary TEXT NOT NULL DEFAULT '',
                deadline TEXT,
                start_time TEXT,
                end_time TEXT,
                location TEXT,
                materials TEXT NOT NULL DEFAULT '[]',
                submit_method TEXT,
                priority TEXT NOT NULL DEFAULT 'normal',
                tags TEXT NOT NULL DEFAULT '[]',
                reminders TEXT NOT NULL DEFAULT '[]',
                need_confirm TEXT NOT NULL DEFAULT '[]',
                status TEXT NOT NULL DEFAULT 'draft',
                source_text TEXT NOT NULL DEFAULT '',
                created_at TEXT NOT NULL
            )
            """
        )
        legacy.execute(
            """
            INSERT INTO cards (id, card_type, title, created_at)
            VALUES ('legacy-card', 'task', '旧卡片', '2026-07-23T00:00:00+00:00')
            """
        )

    migrated = connect()
    try:
        columns = {
            row["name"]
            for row in migrated.execute("PRAGMA table_info(cards)").fetchall()
        }
        row = migrated.execute(
            "SELECT title, action_id, dependencies, evidence_summary FROM cards WHERE id=?",
            ("legacy-card",),
        ).fetchone()
    finally:
        migrated.close()

    assert {"action_id", "dependencies", "evidence_summary"} <= columns
    assert dict(row) == {
        "title": "旧卡片",
        "action_id": None,
        "dependencies": "[]",
        "evidence_summary": "[]",
    }


def test_schema_migration_acquires_write_lock_before_inspection(tmp_path: Path) -> None:
    database = sqlite3.connect(tmp_path / "locked-migration.db")
    database.row_factory = sqlite3.Row
    statements: list[str] = []
    database.set_trace_callback(statements.append)
    try:
        ensure_schema(database)
    finally:
        database.close()

    assert any(
        statement.strip().upper() == "BEGIN IMMEDIATE"
        for statement in statements
    )
