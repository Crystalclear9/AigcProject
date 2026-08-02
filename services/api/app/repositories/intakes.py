from __future__ import annotations

import json
from datetime import datetime, timedelta, timezone
from typing import Any

from app.db.connection import connect
from app.core.config import settings


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
        state = json.loads(str(row["state_json"]))
        created = _parse_datetime(state.get("created_at"))
        if created and datetime.now(timezone.utc) - created >= timedelta(
            hours=max(1, settings.intake_sensitive_ttl_hours)
        ):
            state = _redact_sensitive_state(state)
            self.save(session_id, state)
        return state

    def redact_sensitive(self, session_id: str) -> dict[str, Any]:
        state = _redact_sensitive_state(self.get(session_id))
        self.save(session_id, state)
        return state


def _parse_datetime(value: Any) -> datetime | None:
    try:
        parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
        return parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)
    except (TypeError, ValueError):
        return None


def _redact_sensitive_state(state: dict[str, Any]) -> dict[str, Any]:
    redacted = dict(state)
    redacted["canonical_text"] = ""
    for key in ("cards", "intake_evidence_cards"):
        redacted[key] = [
            {**dict(card), "source_text": ""}
            for card in redacted.get(key, [])
        ]
    redacted["sensitive_content_redacted"] = True
    redacted["updated_at"] = datetime.now(timezone.utc).isoformat()
    return redacted
