from __future__ import annotations

from datetime import datetime
from typing import Literal

from pydantic import BaseModel, Field

from app.schemas.card import ActionCard

TeamRole = Literal["owner", "member"]
GoalStatus = Literal["active", "done", "archived"]
DecomposeSource = Literal["llm", "template"]


class UserCreate(BaseModel):
    id: str = Field(min_length=1, max_length=64)
    nickname: str = Field(min_length=1, max_length=24)
    avatar_color: str = "blue"


class UserUpdate(BaseModel):
    nickname: str | None = Field(default=None, min_length=1, max_length=24)
    avatar_color: str | None = None


class User(BaseModel):
    id: str
    nickname: str
    avatar_color: str
    created_at: datetime


class TeamCreate(BaseModel):
    name: str = Field(min_length=1, max_length=32)


class TeamRename(BaseModel):
    name: str = Field(min_length=1, max_length=32)


class TeamJoinRequest(BaseModel):
    invite_code: str = Field(min_length=6, max_length=6)


class TeamMember(BaseModel):
    user_id: str
    nickname: str
    avatar_color: str
    role: TeamRole
    joined_at: datetime


class Team(BaseModel):
    id: str
    name: str
    invite_code: str
    owner_id: str
    created_at: datetime
    updated_at: datetime
    members: list[TeamMember] = Field(default_factory=list)


class Milestone(BaseModel):
    id: str
    goal_id: str
    title: str
    due_date: str | None = None
    sort_order: int = 0


class TeamGoal(BaseModel):
    id: str
    team_id: str
    title: str
    description: str = ""
    due_date: str | None = None
    status: GoalStatus = "active"
    decompose_source: DecomposeSource = "template"
    created_by: str
    created_at: datetime
    updated_at: datetime
    milestones: list[Milestone] = Field(default_factory=list)


class TeamGoalCreate(BaseModel):
    title: str = Field(min_length=1, max_length=80)
    description: str = ""
    due_date: str | None = None


class ProposedTask(BaseModel):
    title: str = Field(min_length=1)
    summary: str = ""
    assignee_id: str | None = None
    milestone_id: str | None = None
    start_time: str | None = None
    deadline: str | None = None
    deliverables: list[str] = Field(default_factory=list)


class GoalDecomposition(BaseModel):
    goal: TeamGoal
    tasks: list[ProposedTask] = Field(default_factory=list)
    warnings: list[str] = Field(default_factory=list)


class GoalConfirmRequest(BaseModel):
    tasks: list[ProposedTask] = Field(default_factory=list)


class GoalConfirmResponse(BaseModel):
    goal: TeamGoal
    cards: list[ActionCard] = Field(default_factory=list)


class MilestoneProgress(BaseModel):
    milestone: Milestone
    done: int = 0
    total: int = 0


class GoalProgress(BaseModel):
    goal: TeamGoal
    done: int = 0
    total: int = 0
    milestones: list[MilestoneProgress] = Field(default_factory=list)


class MemberStat(BaseModel):
    user_id: str
    nickname: str
    avatar_color: str = "blue"
    role: TeamRole = "member"
    done: int = 0
    total: int = 0


class TeamSummary(BaseModel):
    team: Team
    goals: list[GoalProgress] = Field(default_factory=list)
    member_stats: list[MemberStat] = Field(default_factory=list)
    changed_cards: list[ActionCard] = Field(default_factory=list)
    server_time: str
