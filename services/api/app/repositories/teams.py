from __future__ import annotations

import secrets
import sqlite3
import uuid
import json
from datetime import datetime, timezone
from typing import Any

from app.db.connection import connect
from app.schemas.team import (
    Milestone,
    Team,
    TeamGoal,
    TeamGoalCreate,
    TeamMember,
    User,
    UserCreate,
    UserUpdate,
)

# Unambiguous alphabet: no 0/O/1/I so codes survive being read aloud or
# copied from a projected screen during the demo.
_INVITE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def _generate_invite_code() -> str:
    return "".join(secrets.choice(_INVITE_ALPHABET) for _ in range(6))


def _row_to_user(row: sqlite3.Row) -> User:
    data = dict(row)
    data["created_at"] = datetime.fromisoformat(data["created_at"])
    return User(**data)


def _row_to_member(row: sqlite3.Row) -> TeamMember:
    return TeamMember(
        user_id=row["user_id"],
        nickname=row["nickname"],
        avatar_color=row["avatar_color"],
        role=row["role"],
        joined_at=datetime.fromisoformat(row["joined_at"]),
    )


class TeamRepository:
    # --- users -----------------------------------------------------------

    def upsert_user(self, user: UserCreate) -> User:
        now = utc_now().isoformat()
        with connect() as conn:
            conn.execute(
                """
                INSERT INTO users (id, nickname, avatar_color, created_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    nickname = excluded.nickname,
                    avatar_color = excluded.avatar_color
                """,
                (user.id, user.nickname, user.avatar_color, now),
            )
        return self.get_user(user.id)

    def get_user(self, user_id: str) -> User:
        with connect() as conn:
            row = conn.execute(
                "SELECT * FROM users WHERE id = ?", (user_id,)
            ).fetchone()
        if row is None:
            raise KeyError(user_id)
        return _row_to_user(row)

    def update_user(self, user_id: str, patch: UserUpdate) -> User:
        values = patch.model_dump(exclude_unset=True, exclude_none=True)
        if not values:
            return self.get_user(user_id)
        assignments = ", ".join(f"{field} = ?" for field in values)
        params = [*values.values(), user_id]
        with connect() as conn:
            cursor = conn.execute(
                f"UPDATE users SET {assignments} WHERE id = ?", params
            )
        if cursor.rowcount == 0:
            raise KeyError(user_id)
        return self.get_user(user_id)

    # --- teams -----------------------------------------------------------

    def create_team(self, name: str, owner_id: str) -> Team:
        self.get_user(owner_id)
        team_id = str(uuid.uuid4())
        now = utc_now().isoformat()
        with connect() as conn:
            for _ in range(20):
                code = _generate_invite_code()
                try:
                    conn.execute(
                        """
                        INSERT INTO teams
                            (id, name, invite_code, owner_id, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                        (team_id, name, code, owner_id, now, now),
                    )
                    break
                except sqlite3.IntegrityError:
                    continue
            else:
                raise RuntimeError("could not allocate a unique invite code")
            conn.execute(
                """
                INSERT INTO team_members (team_id, user_id, role, joined_at)
                VALUES (?, ?, 'owner', ?)
                """,
                (team_id, owner_id, now),
            )
        return self.get_team(team_id)

    def join_team(self, invite_code: str, user_id: str) -> Team:
        self.get_user(user_id)
        code = invite_code.strip().upper()
        with connect() as conn:
            row = conn.execute(
                "SELECT id FROM teams WHERE invite_code = ?", (code,)
            ).fetchone()
            if row is None:
                raise KeyError(code)
            team_id = row["id"]
            conn.execute(
                """
                INSERT INTO team_members (team_id, user_id, role, joined_at)
                VALUES (?, ?, 'member', ?)
                ON CONFLICT(team_id, user_id) DO NOTHING
                """,
                (team_id, user_id, utc_now().isoformat()),
            )
            self._touch_team(conn, team_id)
        return self.get_team(team_id)

    def list_teams(self, user_id: str) -> list[Team]:
        with connect() as conn:
            rows = conn.execute(
                """
                SELECT t.id FROM teams t
                JOIN team_members m ON m.team_id = t.id
                WHERE m.user_id = ?
                ORDER BY t.created_at DESC
                """,
                (user_id,),
            ).fetchall()
        return [self.get_team(row["id"]) for row in rows]

    def get_team(self, team_id: str) -> Team:
        with connect() as conn:
            row = conn.execute(
                "SELECT * FROM teams WHERE id = ?", (team_id,)
            ).fetchone()
            if row is None:
                raise KeyError(team_id)
            member_rows = conn.execute(
                """
                SELECT m.user_id, m.role, m.joined_at, u.nickname, u.avatar_color
                FROM team_members m
                JOIN users u ON u.id = m.user_id
                WHERE m.team_id = ?
                ORDER BY m.joined_at
                """,
                (team_id,),
            ).fetchall()
        data = dict(row)
        data["created_at"] = datetime.fromisoformat(data["created_at"])
        data["updated_at"] = datetime.fromisoformat(data["updated_at"])
        data["revision"] = int(data.get("revision") or 0)
        data["members"] = [_row_to_member(m) for m in member_rows]
        return Team(**data)

    def get_role(self, team_id: str, user_id: str) -> str | None:
        with connect() as conn:
            row = conn.execute(
                "SELECT role FROM team_members WHERE team_id = ? AND user_id = ?",
                (team_id, user_id),
            ).fetchone()
        return row["role"] if row else None

    def require_member(self, team_id: str, user_id: str) -> str:
        role = self.get_role(team_id, user_id)
        if role is None:
            raise PermissionError("not a member of this team")
        return role

    def require_owner(self, team_id: str, user_id: str) -> None:
        if self.get_role(team_id, user_id) != "owner":
            raise PermissionError("owner role required")

    def rename_team(self, team_id: str, name: str) -> Team:
        with connect() as conn:
            cursor = conn.execute(
                "UPDATE teams SET name = ?, updated_at = ? WHERE id = ?",
                (name, utc_now().isoformat(), team_id),
            )
        if cursor.rowcount == 0:
            raise KeyError(team_id)
        return self.get_team(team_id)

    def delete_team(self, team_id: str) -> None:
        with connect() as conn:
            cursor = conn.execute("DELETE FROM teams WHERE id = ?", (team_id,))
            if cursor.rowcount == 0:
                raise KeyError(team_id)
            conn.execute("DELETE FROM team_members WHERE team_id = ?", (team_id,))
            goal_ids = [
                row["id"]
                for row in conn.execute(
                    "SELECT id FROM team_goals WHERE team_id = ?", (team_id,)
                ).fetchall()
            ]
            if goal_ids:
                marks = ", ".join("?" for _ in goal_ids)
                conn.execute(
                    f"DELETE FROM milestones WHERE goal_id IN ({marks})", goal_ids
                )
            conn.execute("DELETE FROM team_goals WHERE team_id = ?", (team_id,))

    def remove_member(self, team_id: str, user_id: str) -> Team:
        with connect() as conn:
            cursor = conn.execute(
                """
                DELETE FROM team_members
                WHERE team_id = ? AND user_id = ? AND role != 'owner'
                """,
                (team_id, user_id),
            )
            if cursor.rowcount == 0:
                raise KeyError(user_id)
            self._touch_team(conn, team_id)
        return self.get_team(team_id)

    @staticmethod
    def _touch_team(conn: sqlite3.Connection, team_id: str) -> None:
        now = utc_now().isoformat()
        conn.execute(
            "UPDATE teams SET updated_at = ?, revision = revision + 1 WHERE id = ?",
            (now, team_id),
        )

    def list_events(self, team_id: str, after_revision: int = 0, limit: int = 200) -> list[dict]:
        with connect() as conn:
            rows = conn.execute(
                "SELECT * FROM team_events WHERE team_id=? AND revision>? ORDER BY revision LIMIT ?",
                (team_id, after_revision, limit),
            ).fetchall()
        return [
            {**dict(row), "payload": json.loads(row["payload"] or "{}"),
             "created_at": datetime.fromisoformat(row["created_at"])}
            for row in rows
        ]

    def execute_command(self, team_id: str, user_id: str, operation: str, payload: dict,
                        base_revision: int, idempotency_key: str) -> dict:
        self.require_member(team_id, user_id)
        with connect() as conn:
            existing = conn.execute(
                "SELECT * FROM team_commands WHERE idempotency_key=?", (idempotency_key,)
            ).fetchone()
            if existing:
                team = self.get_team(team_id)
                return {"command_id": existing["command_id"], "team_id": team_id,
                        "status": existing["status"],
                        "revision": int(existing["revision"] if existing["revision"] is not None else team.revision),
                        "result": json.loads(existing["result"] or "{}")}
            row = conn.execute("SELECT revision FROM teams WHERE id=?", (team_id,)).fetchone()
            if row is None:
                raise KeyError(team_id)
            if int(row["revision"]) != base_revision:
                raise ValueError(f"revision_conflict:{base_revision}:{row['revision']}")
            command_id = str(uuid.uuid4())
            now = utc_now().isoformat()
            if operation == "rename_team":
                name = str(payload.get("name", "")).strip()
                if not name:
                    raise ValueError("name is required")
                self.require_owner(team_id, user_id)
                conn.execute("UPDATE teams SET name=?, updated_at=? WHERE id=?", (name, now, team_id))
                self._touch_team(conn, team_id)
                result = {"name": name}
            elif operation in {
                "create_task", "update_task", "delete_task", "assign_owner",
                "update_deadline", "set_dependencies", "set_deliverables",
                "set_acceptance_criteria", "update_status",
            }:
                task_id = str(payload.get("task_id") or uuid.uuid4())
                if operation == "create_task":
                    title = str(payload.get("title", "")).strip()
                    if not title:
                        raise ValueError("title is required")
                    owner_id = payload.get("owner_id")
                    if owner_id:
                        self.require_member(team_id, str(owner_id))
                    dependencies = [str(item) for item in payload.get("dependency_ids", [])]
                    existing = {str(row["id"]) for row in conn.execute(
                        "SELECT id FROM cards WHERE workspace_id=?", (team_id,)
                    ).fetchall()}
                    if set(dependencies) - existing:
                        raise ValueError("unknown dependency")
                    conn.execute(
                        """INSERT INTO cards
                        (id,card_type,title,summary,deadline,location,materials,need_confirm,status,source_text,
                         workspace_type,workspace_id,assignee_id,participant_ids,deliverables,acceptance_criteria,created_at,updated_at)
                        VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                        (task_id, "task", title, str(payload.get("summary", "")), payload.get("deadline"),
                         payload.get("location"), json.dumps(payload.get("materials", [])),
                         json.dumps(payload.get("need_confirm", [])), payload.get("status", "draft"),
                         str(payload.get("source_text", "")), "team", team_id, owner_id,
                         json.dumps(payload.get("participant_ids", [])), json.dumps(payload.get("deliverables", [])),
                         json.dumps(payload.get("acceptance_criteria", [])), now, now),
                    )
                    result = {"task_id": task_id, "status": "created"}
                else:
                    row = conn.execute(
                        "SELECT * FROM cards WHERE id=? AND workspace_id=?", (task_id, team_id)
                    ).fetchone()
                    if row is None:
                        raise KeyError(task_id)
                    if operation == "delete_task":
                        conn.execute("DELETE FROM cards WHERE id=? AND workspace_id=?", (task_id, team_id))
                    else:
                        updates: dict[str, Any] = {}
                        if operation == "update_task":
                            updates = {key: payload[key] for key in {
                                "title", "summary", "deadline", "location", "status", "assignee_id",
                            } if key in payload}
                        elif operation == "assign_owner":
                            owner = payload.get("owner_id")
                            if owner:
                                self.require_member(team_id, str(owner))
                            updates = {"assignee_id": owner}
                        elif operation == "update_deadline":
                            updates = {"deadline": payload.get("deadline")}
                        elif operation == "set_dependencies":
                            dependencies = [str(item) for item in payload.get("dependency_ids", [])]
                            if task_id in dependencies:
                                raise ValueError("dependency_cycle:self")
                            known = {str(item["id"]) for item in conn.execute(
                                "SELECT id FROM cards WHERE workspace_id=?", (team_id,)
                            ).fetchall()}
                            if set(dependencies) - known:
                                raise ValueError("unknown dependency")
                            graph = {
                                str(item["id"]): set(json.loads(item["dependencies"] or "[]"))
                                for item in conn.execute(
                                    "SELECT id, dependencies FROM cards WHERE workspace_id=?", (team_id,)
                                ).fetchall()
                            }
                            graph[task_id] = set(dependencies)
                            visiting: set[str] = set()
                            visited: set[str] = set()
                            def has_cycle(node: str) -> bool:
                                if node in visiting:
                                    return True
                                if node in visited:
                                    return False
                                visiting.add(node)
                                if any(has_cycle(child) for child in graph.get(node, set())):
                                    return True
                                visiting.remove(node)
                                visited.add(node)
                                return False
                            if any(has_cycle(node) for node in graph):
                                raise ValueError("dependency_cycle")
                            updates = {"dependencies": json.dumps(dependencies)}
                        elif operation == "set_deliverables":
                            values = [str(item).strip() for item in payload.get("deliverables", []) if str(item).strip()]
                            if not values:
                                raise ValueError("deliverables are required")
                            updates = {"deliverables": json.dumps(values)}
                        elif operation == "set_acceptance_criteria":
                            values = [str(item).strip() for item in payload.get("acceptance_criteria", []) if str(item).strip()]
                            if not values:
                                raise ValueError("acceptance criteria are required")
                            updates = {"acceptance_criteria": json.dumps(values)}
                        elif operation == "update_status":
                            status = str(payload.get("status", "draft"))
                            if status not in {"draft", "confirmed", "done", "archived"}:
                                raise ValueError("invalid task status")
                            updates = {"status": status}
                        if updates:
                            assignments = ", ".join(f"{key}=?" for key in updates)
                            conn.execute(
                                f"UPDATE cards SET {assignments}, updated_at=? WHERE id=? AND workspace_id=?",
                                [*updates.values(), now, task_id, team_id],
                            )
                    result = {"task_id": task_id, "status": "deleted" if operation == "delete_task" else "updated"}
                self._touch_team(conn, team_id)
            else:
                raise ValueError(f"unsupported_operation:{operation}")
            current_revision = int(conn.execute("SELECT revision FROM teams WHERE id=?", (team_id,)).fetchone()["revision"])
            conn.execute(
                "INSERT INTO team_commands(command_id,team_id,idempotency_key,operation,status,revision,result,created_at) VALUES(?,?,?,?,?,?,?,?)",
                (command_id, team_id, idempotency_key, operation, "completed", current_revision, json.dumps(result), now),
            )
            conn.execute(
                "INSERT INTO team_events(event_id,team_id,revision,event_type,payload,created_at) VALUES(?,?,?,?,?,?)",
                (str(uuid.uuid4()), team_id, current_revision, operation, json.dumps(result), now),
            )
            return {"command_id": command_id, "team_id": team_id, "status": "completed",
                    "revision": current_revision, "result": result}

    # --- goals -------------------------------------------------------------

    def create_goal(
        self,
        team_id: str,
        payload: TeamGoalCreate,
        created_by: str,
        milestones: list[dict],
        decompose_source: str,
    ) -> TeamGoal:
        goal_id = str(uuid.uuid4())
        now = utc_now().isoformat()
        with connect() as conn:
            conn.execute(
                """
                INSERT INTO team_goals
                    (id, team_id, title, description, due_date, status,
                     decompose_source, created_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'active', ?, ?, ?, ?)
                """,
                (
                    goal_id,
                    team_id,
                    payload.title,
                    payload.description,
                    payload.due_date,
                    decompose_source,
                    created_by,
                    now,
                    now,
                ),
            )
            for order, milestone in enumerate(milestones):
                conn.execute(
                    """
                    INSERT INTO milestones (id, goal_id, title, due_date, sort_order)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    (
                        str(uuid.uuid4()),
                        goal_id,
                        milestone["title"],
                        milestone.get("due_date"),
                        order,
                    ),
                )
            self._touch_team(conn, team_id)
        return self.get_goal(goal_id)

    def get_goal(self, goal_id: str) -> TeamGoal:
        with connect() as conn:
            row = conn.execute(
                "SELECT * FROM team_goals WHERE id = ?", (goal_id,)
            ).fetchone()
            if row is None:
                raise KeyError(goal_id)
            milestone_rows = conn.execute(
                "SELECT * FROM milestones WHERE goal_id = ? ORDER BY sort_order",
                (goal_id,),
            ).fetchall()
        data = dict(row)
        data["created_at"] = datetime.fromisoformat(data["created_at"])
        data["updated_at"] = datetime.fromisoformat(data["updated_at"])
        data["milestones"] = [Milestone(**dict(m)) for m in milestone_rows]
        return TeamGoal(**data)

    def list_goals(self, team_id: str) -> list[TeamGoal]:
        with connect() as conn:
            rows = conn.execute(
                "SELECT id FROM team_goals WHERE team_id = ? ORDER BY created_at DESC",
                (team_id,),
            ).fetchall()
        return [self.get_goal(row["id"]) for row in rows]

    def set_goal_status(self, goal_id: str, status: str) -> TeamGoal:
        with connect() as conn:
            cursor = conn.execute(
                "UPDATE team_goals SET status = ?, updated_at = ? WHERE id = ?",
                (status, utc_now().isoformat(), goal_id),
            )
        if cursor.rowcount == 0:
            raise KeyError(goal_id)
        return self.get_goal(goal_id)
