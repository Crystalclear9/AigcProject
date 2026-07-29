from __future__ import annotations

import json
from datetime import datetime, timezone
from typing import Any

from app.db.connection import connect


class IntakeRepository:
    def save(self, session_id: str, state: dict[str, Any]) -> None:
        now = datetime.now(timezone.utc).isoformat()
        created_at = str(state.get("created_at") or now)
        payload = json.dumps(state, ensure_ascii=False, default=str)
        with connect() as connection:
            connection.execute(
                """
                INSERT INTO intake_sessions (
                    id, workflow_run_id, source_kind, workspace_type,
                    classification, should_create_cards, state_json,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    workflow_run_id=excluded.workflow_run_id,
                    classification=excluded.classification,
                    should_create_cards=excluded.should_create_cards,
                    state_json=excluded.state_json,
                    updated_at=excluded.updated_at
                """,
                (
                    session_id,
                    state.get("workflow_run_id"),
                    state.get("source_kind", "text"),
                    state.get("workspace_type", "personal"),
                    state.get("classification", "informational"),
                    int(bool(state.get("should_create_cards"))),
                    payload,
                    created_at,
                    now,
                ),
            )

    def get(self, session_id: str) -> dict[str, Any]:
        with connect() as connection:
            row = connection.execute(
                "SELECT state_json FROM intake_sessions WHERE id = ?",
                (session_id,),
            ).fetchone()
        if row is None:
            raise KeyError(session_id)
        return json.loads(str(row["state_json"]))
