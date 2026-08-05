from __future__ import annotations

import secrets
import sqlite3
import uuid
from datetime import datetime, timezone

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
        conn.execute(
            "UPDATE teams SET updated_at = ? WHERE id = ?",
            (utc_now().isoformat(), team_id),
        )

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
