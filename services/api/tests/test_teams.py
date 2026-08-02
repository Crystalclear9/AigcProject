from __future__ import annotations

from collections.abc import Iterator
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.core.config import settings
from app.main import create_app


@pytest.fixture
def client(tmp_path: Path) -> Iterator[TestClient]:
    original_path = settings.database_path
    object.__setattr__(settings, "database_path", str(tmp_path / "teams.db"))
    try:
        with TestClient(create_app()) as test_client:
            yield test_client
    finally:
        object.__setattr__(settings, "database_path", original_path)


def _register(client: TestClient, user_id: str, nickname: str) -> None:
    response = client.post(
        "/api/users", json={"id": user_id, "nickname": nickname}
    )
    assert response.status_code == 200, response.text


def test_register_is_idempotent(client: TestClient) -> None:
    _register(client, "u-alice", "小李")
    response = client.post(
        "/api/users", json={"id": "u-alice", "nickname": "小李改名"}
    )
    assert response.status_code == 200
    assert response.json()["nickname"] == "小李改名"


def test_create_team_makes_caller_owner(client: TestClient) -> None:
    _register(client, "u-alice", "小李")
    response = client.post(
        "/api/teams", json={"name": "AIGC比赛队"}, headers={"X-User-Id": "u-alice"}
    )
    assert response.status_code == 200, response.text
    team = response.json()
    assert team["owner_id"] == "u-alice"
    assert len(team["invite_code"]) == 6
    assert team["members"][0]["role"] == "owner"


def test_join_by_invite_code(client: TestClient) -> None:
    _register(client, "u-alice", "小李")
    _register(client, "u-bob", "小王")
    team = client.post(
        "/api/teams", json={"name": "AIGC比赛队"}, headers={"X-User-Id": "u-alice"}
    ).json()

    response = client.post(
        "/api/teams/join",
        json={"invite_code": team["invite_code"].lower()},
        headers={"X-User-Id": "u-bob"},
    )
    assert response.status_code == 200
    roles = {m["user_id"]: m["role"] for m in response.json()["members"]}
    assert roles == {"u-alice": "owner", "u-bob": "member"}

    listed = client.get("/api/teams", headers={"X-User-Id": "u-bob"}).json()
    assert [t["id"] for t in listed] == [team["id"]]


def test_join_with_unknown_code_returns_404(client: TestClient) -> None:
    _register(client, "u-bob", "小王")
    response = client.post(
        "/api/teams/join",
        json={"invite_code": "ZZZZZZ"},
        headers={"X-User-Id": "u-bob"},
    )
    assert response.status_code == 404


def test_non_member_cannot_read_team(client: TestClient) -> None:
    _register(client, "u-alice", "小李")
    _register(client, "u-eve", "路人")
    team = client.post(
        "/api/teams", json={"name": "AIGC比赛队"}, headers={"X-User-Id": "u-alice"}
    ).json()

    response = client.get(
        f"/api/teams/{team['id']}", headers={"X-User-Id": "u-eve"}
    )
    assert response.status_code == 403


def test_member_cannot_rename_or_remove(client: TestClient) -> None:
    _register(client, "u-alice", "小李")
    _register(client, "u-bob", "小王")
    team = client.post(
        "/api/teams", json={"name": "AIGC比赛队"}, headers={"X-User-Id": "u-alice"}
    ).json()
    client.post(
        "/api/teams/join",
        json={"invite_code": team["invite_code"]},
        headers={"X-User-Id": "u-bob"},
    )

    rename = client.patch(
        f"/api/teams/{team['id']}",
        json={"name": "新队名"},
        headers={"X-User-Id": "u-bob"},
    )
    assert rename.status_code == 403

    removal = client.delete(
        f"/api/teams/{team['id']}/members/u-alice",
        headers={"X-User-Id": "u-bob"},
    )
    assert removal.status_code == 403


def test_owner_can_rename_remove_and_dissolve(client: TestClient) -> None:
    _register(client, "u-alice", "小李")
    _register(client, "u-bob", "小王")
    team = client.post(
        "/api/teams", json={"name": "AIGC比赛队"}, headers={"X-User-Id": "u-alice"}
    ).json()
    client.post(
        "/api/teams/join",
        json={"invite_code": team["invite_code"]},
        headers={"X-User-Id": "u-bob"},
    )
    owner_headers = {"X-User-Id": "u-alice"}

    renamed = client.patch(
        f"/api/teams/{team['id']}", json={"name": "新队名"}, headers=owner_headers
    )
    assert renamed.status_code == 200
    assert renamed.json()["name"] == "新队名"

    removed = client.delete(
        f"/api/teams/{team['id']}/members/u-bob", headers=owner_headers
    )
    assert removed.status_code == 200
    assert [m["user_id"] for m in removed.json()["members"]] == ["u-alice"]

    dissolved = client.delete(f"/api/teams/{team['id']}", headers=owner_headers)
    assert dissolved.status_code == 204
    assert (
        client.get(f"/api/teams/{team['id']}", headers=owner_headers).status_code
        == 404
    )


def test_owner_cannot_be_removed(client: TestClient) -> None:
    _register(client, "u-alice", "小李")
    team = client.post(
        "/api/teams", json={"name": "AIGC比赛队"}, headers={"X-User-Id": "u-alice"}
    ).json()
    response = client.delete(
        f"/api/teams/{team['id']}/members/u-alice",
        headers={"X-User-Id": "u-alice"},
    )
    assert response.status_code == 404


def test_team_endpoints_require_identity_header(client: TestClient) -> None:
    response = client.post("/api/teams", json={"name": "无名氏队"})
    assert response.status_code == 422


def _team_with_members(client: TestClient) -> dict:
    _register(client, "u-alice", "小李")
    _register(client, "u-bob", "小王")
    _register(client, "u-carol", "小张")
    team = client.post(
        "/api/teams", json={"name": "AIGC比赛队"}, headers={"X-User-Id": "u-alice"}
    ).json()
    for uid in ("u-bob", "u-carol"):
        client.post(
            "/api/teams/join",
            json={"invite_code": team["invite_code"]},
            headers={"X-User-Id": uid},
        )
    return team


def test_goal_decomposition_falls_back_to_template(client: TestClient) -> None:
    # conftest blanks provider credentials, so the LLM path is unavailable and
    # the deterministic template must produce the plan.
    team = _team_with_members(client)
    response = client.post(
        f"/api/teams/{team['id']}/goals",
        json={"title": "6月20日前完成AIGC比赛作品", "due_date": "2026-06-20"},
        headers={"X-User-Id": "u-alice"},
    )
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["goal"]["decompose_source"] == "template"
    assert len(body["goal"]["milestones"]) >= 2
    assert len(body["tasks"]) >= 3
    assignees = {t["assignee_id"] for t in body["tasks"]}
    assert assignees <= {"u-alice", "u-bob", "u-carol"}
    assert len(assignees) >= 2
    milestone_ids = {m["id"] for m in body["goal"]["milestones"]}
    for task in body["tasks"]:
        assert task["milestone_id"] in milestone_ids


def test_member_cannot_create_goal(client: TestClient) -> None:
    team = _team_with_members(client)
    response = client.post(
        f"/api/teams/{team['id']}/goals",
        json={"title": "小组周报"},
        headers={"X-User-Id": "u-bob"},
    )
    assert response.status_code == 403


def test_goal_confirm_creates_team_cards(client: TestClient) -> None:
    team = _team_with_members(client)
    preview = client.post(
        f"/api/teams/{team['id']}/goals",
        json={"title": "完成课程大作业", "due_date": "2026-06-30"},
        headers={"X-User-Id": "u-alice"},
    ).json()
    # Owner reassigns the first task before confirming.
    tasks = preview["tasks"]
    tasks[0]["assignee_id"] = "u-carol"
    response = client.post(
        f"/api/teams/{team['id']}/goals/{preview['goal']['id']}/confirm",
        json={"tasks": tasks},
        headers={"X-User-Id": "u-alice"},
    )
    assert response.status_code == 200, response.text
    cards = response.json()["cards"]
    assert len(cards) == len(tasks)
    first = cards[0]
    assert first["workspace_type"] == "team"
    assert first["workspace_id"] == team["id"]
    assert first["assignee_id"] == "u-carol"
    assert first["status"] == "confirmed"
    assert first["milestone_id"] is not None

    listed = client.get(f"/api/teams/{team['id']}/goals", headers={"X-User-Id": "u-bob"})
    assert listed.status_code == 200
    assert listed.json()[0]["id"] == preview["goal"]["id"]


def test_template_matches_goal_category() -> None:
    from app.services.team_goal_templates import match_template

    assert match_template("参加AIGC创新大赛").key == "competition"
    assert match_template("完成数据库课设").key == "coursework"
    assert match_template("撰写调研报告").key == "report"
    assert match_template("举办社团招新").key == "event"
    assert match_template("随便一个目标").key == "generic"


def test_summary_reports_progress_and_incremental_changes(client: TestClient) -> None:
    team = _team_with_members(client)
    owner = {"X-User-Id": "u-alice"}
    preview = client.post(
        f"/api/teams/{team['id']}/goals",
        json={"title": "参加AIGC创新大赛", "due_date": "2026-06-20"},
        headers=owner,
    ).json()
    confirmed = client.post(
        f"/api/teams/{team['id']}/goals/{preview['goal']['id']}/confirm",
        json={"tasks": preview["tasks"]},
        headers=owner,
    ).json()

    first = client.get(f"/api/teams/{team['id']}/summary", headers=owner)
    assert first.status_code == 200, first.text
    summary = first.json()
    total = len(confirmed["cards"])
    assert summary["goals"][0]["total"] == total
    assert summary["goals"][0]["done"] == 0
    assert sum(s["total"] for s in summary["member_stats"]) == total
    assert len(summary["changed_cards"]) == total
    cursor = summary["server_time"]

    # Nothing changed since the cursor.
    quiet = client.get(
        f"/api/teams/{team['id']}/summary", params={"since": cursor}, headers=owner
    ).json()
    assert quiet["changed_cards"] == []

    # A member completes one card; only that card comes back as changed.
    card_id = confirmed["cards"][0]["id"]
    done = client.post(f"/api/cards/{card_id}/complete")
    assert done.status_code == 200
    after = client.get(
        f"/api/teams/{team['id']}/summary", params={"since": cursor}, headers=owner
    ).json()
    assert [c["id"] for c in after["changed_cards"]] == [card_id]
    assert after["goals"][0]["done"] == 1
    milestone_done = [
        m for m in after["goals"][0]["milestones"] if m["done"] == 1
    ]
    assert len(milestone_done) == 1


def test_card_update_refreshes_updated_at_for_last_write_wins(
    client: TestClient,
) -> None:
    team = _team_with_members(client)
    owner = {"X-User-Id": "u-alice"}
    preview = client.post(
        f"/api/teams/{team['id']}/goals",
        json={"title": "撰写调研报告", "due_date": "2026-06-30"},
        headers=owner,
    ).json()
    cards = client.post(
        f"/api/teams/{team['id']}/goals/{preview['goal']['id']}/confirm",
        json={"tasks": preview["tasks"]},
        headers=owner,
    ).json()["cards"]
    card = cards[0]
    assert card["updated_at"] is not None

    first = client.patch(f"/api/cards/{card['id']}", json={"summary": "A 的修改"})
    second = client.patch(f"/api/cards/{card['id']}", json={"summary": "B 的修改"})
    assert first.status_code == second.status_code == 200
    assert second.json()["summary"] == "B 的修改"
    assert second.json()["updated_at"] >= first.json()["updated_at"]


def test_summary_requires_membership(client: TestClient) -> None:
    team = _team_with_members(client)
    _register(client, "u-eve", "路人")
    response = client.get(
        f"/api/teams/{team['id']}/summary", headers={"X-User-Id": "u-eve"}
    )
    assert response.status_code == 403
