from __future__ import annotations

import hashlib
import json
import re
import sqlite3
from datetime import datetime, timedelta, timezone
from pathlib import Path
from threading import Condition, RLock
from typing import Any

from app.core.config import settings
from app.schemas.workflow import WorkflowEvent, WorkflowRunResponse

_schema_lock = RLock()
_connection_lock = RLock()
_schema_ready = False
_connection: sqlite3.Connection | None = None
_event_condition = Condition()


def _connect() -> sqlite3.Connection:
    global _schema_ready, _connection
    if _connection is not None:
        return _connection
    path = Path(settings.workflow_database_path)
    if path.parent != Path("."):
        path.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(path, timeout=5, check_same_thread=False)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA synchronous=NORMAL")
    conn.execute("PRAGMA busy_timeout=5000")
    if not _schema_ready:
        with _schema_lock:
            if not _schema_ready:
                _ensure_schema(conn)
                _schema_ready = True
    _connection = conn
    return _connection


def _ensure_schema(conn: sqlite3.Connection) -> None:
    conn.executescript(
        """
        CREATE TABLE IF NOT EXISTS workflow_runs (
            run_id TEXT PRIMARY KEY,
            status TEXT NOT NULL,
            pending_action TEXT,
            state_json TEXT NOT NULL,
            error TEXT,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS workflow_events (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            run_id TEXT NOT NULL,
            event TEXT NOT NULL,
            data_json TEXT NOT NULL DEFAULT '{}',
            idempotency_key TEXT,
            created_at TEXT NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_workflow_events_run_id_id
            ON workflow_events(run_id, id);
        CREATE TABLE IF NOT EXISTS workflow_cache (
            cache_key TEXT PRIMARY KEY,
            result_json TEXT NOT NULL,
            model_signature TEXT NOT NULL,
            created_at TEXT NOT NULL,
            expires_at TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS workflow_jobs (
            run_id TEXT PRIMARY KEY,
            lease_owner TEXT,
            lease_expires_at TEXT,
            heartbeat_at TEXT,
            attempts INTEGER NOT NULL DEFAULT 0,
            input_path TEXT,
            environment TEXT NOT NULL DEFAULT 'development'
        );
        CREATE TABLE IF NOT EXISTS workflow_agent_tasks (
            run_id TEXT NOT NULL,
            task_id TEXT NOT NULL,
            idempotency_key TEXT NOT NULL,
            tool TEXT NOT NULL,
            status TEXT NOT NULL,
            attempt INTEGER NOT NULL DEFAULT 1,
            task_json TEXT NOT NULL DEFAULT '{}',
            result_json TEXT NOT NULL DEFAULT '{}',
            updated_at TEXT NOT NULL,
            PRIMARY KEY (run_id, task_id)
        );
        CREATE UNIQUE INDEX IF NOT EXISTS idx_workflow_agent_task_idempotency
            ON workflow_agent_tasks(run_id, idempotency_key);
        """
    )
    event_columns = {row[1] for row in conn.execute("PRAGMA table_info(workflow_events)").fetchall()}
    if "idempotency_key" not in event_columns:
        conn.execute("ALTER TABLE workflow_events ADD COLUMN idempotency_key TEXT")
    conn.execute(
        """
        CREATE UNIQUE INDEX IF NOT EXISTS idx_workflow_events_idempotency
        ON workflow_events(run_id, idempotency_key)
        WHERE idempotency_key IS NOT NULL
        """
    )


def _now() -> datetime:
    return datetime.now(timezone.utc)


def normalized_text(text: str) -> str:
    return re.sub(r"\s+", " ", text).strip().lower()


def cache_key(text: str, model_signature: str, prompt_version: str = "adaptive-v1") -> str:
    payload = f"{prompt_version}\0{model_signature}\0{normalized_text(text)}"
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


class WorkflowRepository:
    def create_run(
        self,
        run_id: str,
        state: dict[str, Any],
        input_path: str | None = None,
        lease_owner: str | None = None,
        lease_seconds: int = 30,
    ) -> None:
        created = _now()
        now = created.isoformat()
        lease_expires = (
            (created + timedelta(seconds=lease_seconds)).isoformat()
            if lease_owner
            else None
        )
        payload = json.dumps(state, ensure_ascii=False, default=str)
        with _connection_lock, _connect() as conn:
            conn.execute(
                """
                INSERT INTO workflow_runs
                    (run_id, status, pending_action, state_json, error, created_at, updated_at)
                VALUES (?, ?, ?, ?, NULL, ?, ?)
                """,
                (
                    run_id,
                    state.get("workflow_status", "queued"),
                    state.get("pending_action"),
                    payload,
                    now,
                    now,
                ),
            )
            conn.execute(
                """
                INSERT INTO workflow_jobs
                    (run_id, lease_owner, lease_expires_at, heartbeat_at, attempts, input_path, environment)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    run_id,
                    lease_owner,
                    lease_expires,
                    now if lease_owner else None,
                    1 if lease_owner else 0,
                    input_path,
                    settings.workflow_environment,
                ),
            )
            conn.execute(
                """
                INSERT INTO workflow_events(run_id, event, data_json, idempotency_key, created_at)
                VALUES (?, 'run_started', ?, 'run-started', ?)
                """,
                (
                    run_id,
                    json.dumps({"input_kind": state.get("input_kind")}, ensure_ascii=False),
                    now,
                ),
            )
        with _event_condition:
            _event_condition.notify_all()

    def create(self, run_id: str, state: dict[str, Any]) -> None:
        now = _now().isoformat()
        payload = json.dumps(state, ensure_ascii=False, default=str)
        with _connection_lock, _connect() as conn:
            conn.execute(
                """
                INSERT INTO workflow_runs
                    (run_id, status, pending_action, state_json, error, created_at, updated_at)
                VALUES (?, ?, ?, ?, NULL, ?, ?)
                """,
                (run_id, state.get("workflow_status", "queued"), state.get("pending_action"), payload, now, now),
            )

    def save(self, run_id: str, state: dict[str, Any], error: str | None = None) -> None:
        now = _now().isoformat()
        payload = json.dumps(state, ensure_ascii=False, default=str)
        with _connection_lock, _connect() as conn:
            cursor = conn.execute(
                """
                UPDATE workflow_runs
                SET status=?, pending_action=?, state_json=?, error=?, updated_at=?
                WHERE run_id=?
                """,
                (
                    state.get("workflow_status", "running"),
                    state.get("pending_action"),
                    payload,
                    error,
                    now,
                    run_id,
                ),
            )
            if cursor.rowcount == 0:
                now = _now().isoformat()
                conn.execute(
                    """
                    INSERT INTO workflow_runs
                        (run_id, status, pending_action, state_json, error, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        run_id,
                        state.get("workflow_status", "running"),
                        state.get("pending_action"),
                        payload,
                        error,
                        now,
                        now,
                    ),
                )

    def save_with_events(
        self,
        run_id: str,
        state: dict[str, Any],
        events: list[tuple[str, dict[str, Any], str | None]],
        error: str | None = None,
    ) -> None:
        now = _now().isoformat()
        payload = json.dumps(state, ensure_ascii=False, default=str)
        with _connection_lock, _connect() as conn:
            conn.execute(
                """
                UPDATE workflow_runs
                SET status=?, pending_action=?, state_json=?, error=?, updated_at=?
                WHERE run_id=?
                """,
                (
                    state.get("workflow_status", "running"),
                    state.get("pending_action"),
                    payload,
                    error,
                    now,
                    run_id,
                ),
            )
            for event, data, idempotency_key in events:
                conn.execute(
                    """
                    INSERT OR IGNORE INTO workflow_events
                        (run_id, event, data_json, idempotency_key, created_at)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    (
                        run_id,
                        event,
                        json.dumps(data, ensure_ascii=False, default=str),
                        idempotency_key,
                        now,
                    ),
                )
        if events:
            with _event_condition:
                _event_condition.notify_all()

    def append_event(
        self,
        run_id: str,
        event: str,
        data: dict[str, Any] | None = None,
        idempotency_key: str | None = None,
    ) -> int:
        with _connection_lock, _connect() as conn:
            cursor = conn.execute(
                """
                INSERT OR IGNORE INTO workflow_events
                    (run_id, event, data_json, idempotency_key, created_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                (
                    run_id,
                    event,
                    json.dumps(data or {}, ensure_ascii=False, default=str),
                    idempotency_key,
                    _now().isoformat(),
                ),
            )
            if cursor.rowcount:
                with _event_condition:
                    _event_condition.notify_all()
                return int(cursor.lastrowid)
            row = conn.execute(
                "SELECT id FROM workflow_events WHERE run_id=? AND idempotency_key=?",
                (run_id, idempotency_key),
            ).fetchone()
            return int(row["id"]) if row else 0

    def save_agent_task(
        self,
        run_id: str,
        task: dict[str, Any],
        result: dict[str, Any],
    ) -> None:
        with _connection_lock, _connect() as conn:
            conn.execute(
                """
                INSERT INTO workflow_agent_tasks
                    (run_id, task_id, idempotency_key, tool, status, attempt,
                     task_json, result_json, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(run_id, task_id) DO UPDATE SET
                    status=excluded.status,
                    attempt=excluded.attempt,
                    result_json=excluded.result_json,
                    updated_at=excluded.updated_at
                """,
                (
                    run_id,
                    result.get("task_id") or task.get("id"),
                    result.get("idempotency_key") or task.get("idempotency_key"),
                    result.get("tool") or task.get("tool"),
                    result.get("status", "failed"),
                    int(result.get("attempt", 1)),
                    json.dumps(task, ensure_ascii=False, default=str),
                    json.dumps(result, ensure_ascii=False, default=str),
                    _now().isoformat(),
                ),
            )

    def wait_for_events(self, timeout: float = 15.0) -> None:
        with _event_condition:
            _event_condition.wait(timeout=timeout)

    def create_job(self, run_id: str, input_path: str | None = None) -> None:
        with _connection_lock, _connect() as conn:
            conn.execute(
                """
                INSERT OR REPLACE INTO workflow_jobs
                    (run_id, lease_owner, lease_expires_at, heartbeat_at, attempts, input_path, environment)
                VALUES (?, NULL, NULL, NULL, 0, ?, ?)
                """,
                (run_id, input_path, settings.workflow_environment),
            )

    def claim_job(self, run_id: str, owner: str, lease_seconds: int) -> bool:
        now = _now()
        expires = now + timedelta(seconds=lease_seconds)
        with _connection_lock, _connect() as conn:
            cursor = conn.execute(
                """
                UPDATE workflow_jobs
                SET lease_owner=?, lease_expires_at=?, heartbeat_at=?, attempts=attempts+1
                WHERE run_id=?
                  AND (lease_owner IS NULL OR lease_expires_at IS NULL OR lease_expires_at<=? OR lease_owner=?)
                """,
                (owner, expires.isoformat(), now.isoformat(), run_id, now.isoformat(), owner),
            )
            return cursor.rowcount == 1

    def heartbeat_job(self, run_id: str, owner: str, lease_seconds: int) -> None:
        now = _now()
        expires = now + timedelta(seconds=lease_seconds)
        with _connection_lock, _connect() as conn:
            conn.execute(
                """
                UPDATE workflow_jobs SET heartbeat_at=?, lease_expires_at=?
                WHERE run_id=? AND lease_owner=?
                """,
                (now.isoformat(), expires.isoformat(), run_id, owner),
            )

    def release_job(self, run_id: str, owner: str) -> None:
        with _connection_lock, _connect() as conn:
            conn.execute(
                """
                UPDATE workflow_jobs SET lease_owner=NULL, lease_expires_at=NULL
                WHERE run_id=? AND lease_owner=?
                """,
                (run_id, owner),
            )

    def recoverable_jobs(self) -> list[tuple[str, str | None]]:
        now = _now().isoformat()
        with _connection_lock, _connect() as conn:
            rows = conn.execute(
                """
                SELECT jobs.run_id, jobs.input_path
                FROM workflow_jobs jobs
                JOIN workflow_runs runs ON runs.run_id=jobs.run_id
                WHERE runs.status IN ('queued', 'running')
                  AND (jobs.lease_expires_at IS NULL OR jobs.lease_expires_at<=?)
                """,
                (now,),
            ).fetchall()
        return [(str(row["run_id"]), row["input_path"]) for row in rows]

    def events_after(self, run_id: str, after_id: int = 0, limit: int = 100) -> list[WorkflowEvent]:
        with _connection_lock, _connect() as conn:
            rows = conn.execute(
                """
                SELECT id, run_id, event, data_json, created_at
                FROM workflow_events
                WHERE run_id=? AND id>?
                ORDER BY id ASC LIMIT ?
                """,
                (run_id, after_id, limit),
            ).fetchall()
        return [
            WorkflowEvent(
                id=row["id"],
                run_id=row["run_id"],
                event=row["event"],
                data=json.loads(row["data_json"]),
                created_at=row["created_at"],
            )
            for row in rows
        ]

    def get_state(self, run_id: str) -> dict[str, Any]:
        with _connection_lock, _connect() as conn:
            row = conn.execute("SELECT * FROM workflow_runs WHERE run_id = ?", (run_id,)).fetchone()
        if row is None:
            raise KeyError(run_id)
        state = json.loads(row["state_json"])
        state["_created_at"] = row["created_at"]
        state["_updated_at"] = row["updated_at"]
        state["_error"] = row["error"]
        return state

    def response(self, run_id: str) -> WorkflowRunResponse:
        state = self.get_state(run_id)
        return WorkflowRunResponse(
            run_id=run_id,
            trace_id=run_id,
            workflow_status=state.get("workflow_status", "failed"),
            pending_action=state.get("pending_action"),
            ocr_text=state.get("ocr_text", ""),
            cards=state.get("cards", []),
            preview_actions=state.get("preview_actions", []),
            engine=state.get("engine", ""),
            fallback_reason=state.get("fallback_reason"),
            warnings=state.get("warnings", []),
            node_trace=state.get("node_trace", []),
            confidence=state.get("confidence", {}),
            provenance=state.get("provenance", {}),
            validation_errors=state.get("validation_errors", []),
            created_at=state["_created_at"],
            updated_at=state["_updated_at"],
            error=state["_error"],
            revision=int(state.get("revision", 0)),
            result_stage=state.get("result_stage", "provisional"),
            overall_confidence=float(state.get("overall_confidence", 0)),
            route=state.get("route", "rules"),
            cache_status=state.get("cache_status", "bypass"),
            time_to_first_draft_ms=state.get("time_to_first_draft_ms"),
            time_to_final_ms=state.get("time_to_final_ms"),
            user_locked=state.get("user_locked", {}),
            suggestions=state.get("suggestions", {}),
            action_graph=state.get("action_graph", {}),
            dependencies=state.get("action_graph", {}).get("dependencies", []),
            evidence_summary=state.get("evidence_summary", []),
            active_agents=state.get("active_agents", []),
            decision_reasons=state.get("decision_reasons", []),
            risk_level=state.get("risk_level", "low"),
            field_versions=state.get("field_versions", {}),
            field_conflicts=state.get("field_conflicts", []),
            agent_plan=state.get("agent_plan"),
            agent_tasks=state.get("agent_task_results", []),
            unresolved_evidence=state.get("unresolved_evidence", []),
            budget_usage=state.get("budget_usage", {}),
            retrieval_sources=state.get("retrieval_sources", []),
            verification_summary=state.get("verification_summary", {}),
            replan_count=int(state.get("replan_count", 0)),
        )

    def get_cache(self, key: str) -> dict[str, Any] | None:
        with _connection_lock, _connect() as conn:
            row = conn.execute(
                "SELECT result_json, expires_at FROM workflow_cache WHERE cache_key=?",
                (key,),
            ).fetchone()
            if row is None:
                return None
            if datetime.fromisoformat(row["expires_at"]) <= _now():
                conn.execute("DELETE FROM workflow_cache WHERE cache_key=?", (key,))
                return None
        return json.loads(row["result_json"])

    def put_cache(self, key: str, result: dict[str, Any], model_signature: str) -> None:
        created = _now()
        expires = created + timedelta(seconds=settings.workflow_cache_ttl_seconds)
        with _connection_lock, _connect() as conn:
            conn.execute(
                """
                INSERT INTO workflow_cache(cache_key, result_json, model_signature, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(cache_key) DO UPDATE SET
                    result_json=excluded.result_json,
                    model_signature=excluded.model_signature,
                    created_at=excluded.created_at,
                    expires_at=excluded.expires_at
                """,
                (
                    key,
                    json.dumps(result, ensure_ascii=False, default=str),
                    model_signature,
                    created.isoformat(),
                    expires.isoformat(),
                ),
            )

    def metrics(self) -> dict[str, object]:
        with _connection_lock, _connect() as conn:
            rows = conn.execute(
                """
                SELECT runs.state_json
                FROM workflow_runs runs
                LEFT JOIN workflow_jobs jobs ON jobs.run_id=runs.run_id
                WHERE COALESCE(jobs.environment, 'development')=?
                """,
                (settings.workflow_environment,),
            ).fetchall()
        states = [json.loads(row["state_json"]) for row in rows]
        total = len(states)
        if not total:
            return {
                "total": 0,
                "completed": 0,
                "completion_rate": 0,
                "human_review_rate": 0,
                "ocr_fallback_rate": 0,
                "rules_fallback_rate": 0,
                "average_node_duration_ms": 0,
                "average_repair_count": 0,
                "p50_first_draft_ms": 0,
                "p95_first_draft_ms": 0,
                "p50_final_ms": 0,
                "p95_final_ms": 0,
                "cache_hit_rate": 0,
                "route_counts": {},
            }

        def percentile(values: list[float], p: float) -> float:
            if not values:
                return 0
            ordered = sorted(values)
            return round(ordered[min(len(ordered) - 1, int((len(ordered) - 1) * p))], 2)

        completed = sum(state.get("workflow_status") == "completed" for state in states)
        traces = [trace for state in states for trace in state.get("node_trace", [])]
        durations = [float(trace.get("duration_ms", 0)) for trace in traces]
        first_drafts = [float(v) for state in states if (v := state.get("time_to_first_draft_ms")) is not None]
        finals = [float(v) for state in states if (v := state.get("time_to_final_ms")) is not None]
        route_counts: dict[str, int] = {}
        for state in states:
            route = state.get("route", "rules")
            route_counts[route] = route_counts.get(route, 0) + 1
        return {
            "total": total,
            "completed": completed,
            "completion_rate": round(completed / total, 4),
            "human_review_rate": round(sum(bool(s.get("review_requested")) for s in states) / total, 4),
            "ocr_fallback_rate": round(sum(s.get("ocr_engine") == "client-ocr" for s in states) / total, 4),
            "rules_fallback_rate": round(sum(s.get("route") == "rules" for s in states) / total, 4),
            "average_node_duration_ms": round(sum(durations) / len(durations), 2) if durations else 0,
            "average_repair_count": round(sum(int(s.get("repair_count", 0)) for s in states) / total, 2),
            "p50_first_draft_ms": percentile(first_drafts, 0.5),
            "p95_first_draft_ms": percentile(first_drafts, 0.95),
            "p50_final_ms": percentile(finals, 0.5),
            "p95_final_ms": percentile(finals, 0.95),
            "cache_hit_rate": round(sum(s.get("cache_status") == "hit" for s in states) / total, 4),
            "route_counts": route_counts,
        }
