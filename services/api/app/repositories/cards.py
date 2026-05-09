from __future__ import annotations

import json
import sqlite3
import uuid
from datetime import datetime, timezone
from typing import Any

from app.db.connection import connect
from app.schemas.card import ActionCard, ActionCardCreate, ActionCardUpdate

ARRAY_FIELDS = {"materials", "tags", "reminders", "need_confirm"}


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def _encode(value: Any, field: str) -> Any:
    if field in ARRAY_FIELDS:
        return json.dumps(value or [], ensure_ascii=False)
    if isinstance(value, datetime):
        return value.isoformat()
    return value


def _row_to_card(row: sqlite3.Row) -> ActionCard:
    data = dict(row)
    for field in ARRAY_FIELDS:
        raw = data.get(field) or "[]"
        data[field] = json.loads(raw)
    data["created_at"] = datetime.fromisoformat(data["created_at"])
    return ActionCard(**data)


class CardRepository:
    def create(self, card: ActionCardCreate) -> ActionCard:
        card_id = card.id or str(uuid.uuid4())
        created_at = utc_now()
        payload = card.model_dump()
        payload["id"] = card_id
        payload["created_at"] = created_at.isoformat()

        fields = [
            "id",
            "card_type",
            "title",
            "summary",
            "deadline",
            "start_time",
            "end_time",
            "location",
            "materials",
            "submit_method",
            "priority",
            "tags",
            "reminders",
            "need_confirm",
            "status",
            "source_text",
            "created_at",
        ]
        values = [_encode(payload.get(field), field) for field in fields]
        placeholders = ", ".join("?" for _ in fields)
        with connect() as conn:
            conn.execute(
                f"INSERT INTO cards ({', '.join(fields)}) VALUES ({placeholders})",
                values,
            )
        return self.get(card_id)

    def list(
        self,
        card_type: str | None = None,
        status: str | None = None,
        q: str | None = None,
    ) -> list[ActionCard]:
        clauses: list[str] = []
        values: list[Any] = []
        if card_type:
            clauses.append("card_type = ?")
            values.append(card_type)
        if status:
            clauses.append("status = ?")
            values.append(status)
        if q:
            clauses.append("(title LIKE ? OR summary LIKE ? OR source_text LIKE ?)")
            like = f"%{q}%"
            values.extend([like, like, like])
        where = f"WHERE {' AND '.join(clauses)}" if clauses else ""
        with connect() as conn:
            rows = conn.execute(
                f"SELECT * FROM cards {where} ORDER BY created_at DESC",
                values,
            ).fetchall()
        return [_row_to_card(row) for row in rows]

    def get(self, card_id: str) -> ActionCard:
        with connect() as conn:
            row = conn.execute("SELECT * FROM cards WHERE id = ?", (card_id,)).fetchone()
        if row is None:
            raise KeyError(card_id)
        return _row_to_card(row)

    def update(self, card_id: str, patch: ActionCardUpdate) -> ActionCard:
        values = patch.model_dump(exclude_unset=True)
        if not values:
            return self.get(card_id)
        assignments = ", ".join(f"{field} = ?" for field in values)
        encoded = [_encode(value, field) for field, value in values.items()]
        encoded.append(card_id)
        with connect() as conn:
            cursor = conn.execute(f"UPDATE cards SET {assignments} WHERE id = ?", encoded)
        if cursor.rowcount == 0:
            raise KeyError(card_id)
        return self.get(card_id)

    def complete(self, card_id: str) -> ActionCard:
        return self.update(card_id, ActionCardUpdate(status="done"))
