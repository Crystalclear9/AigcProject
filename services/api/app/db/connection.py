from __future__ import annotations

import sqlite3
from pathlib import Path

from app.core.config import settings


def connect() -> sqlite3.Connection:
    db_path = Path(settings.database_path)
    if db_path.parent != Path("."):
        db_path.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(db_path)
    conn.row_factory = sqlite3.Row
    ensure_schema(conn)
    return conn


def ensure_schema(conn: sqlite3.Connection) -> None:
    # Serialize inspection and ALTER statements across multiple server workers.
    conn.execute("BEGIN IMMEDIATE")
    try:
        _ensure_schema_locked(conn)
    except Exception:
        conn.rollback()
        raise
    else:
        conn.commit()


def _ensure_schema_locked(conn: sqlite3.Connection) -> None:
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS cards (
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
    existing_columns = {
        str(row["name"])
        for row in conn.execute("PRAGMA table_info(cards)").fetchall()
    }
    # Existing installations predate workflow action metadata. Add only
    # missing columns so startup remains idempotent and preserves all rows.
    migrations = {
        "action_id": "ALTER TABLE cards ADD COLUMN action_id TEXT",
        "dependencies": (
            "ALTER TABLE cards ADD COLUMN dependencies TEXT NOT NULL DEFAULT '[]'"
        ),
        "evidence_summary": (
            "ALTER TABLE cards ADD COLUMN evidence_summary TEXT NOT NULL DEFAULT '[]'"
        ),
    }
    for column, statement in migrations.items():
        if column not in existing_columns:
            conn.execute(statement)


def init_db() -> None:
    with connect():
        pass
