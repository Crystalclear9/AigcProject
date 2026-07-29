from __future__ import annotations

from datetime import datetime
from typing import Literal

from pydantic import BaseModel, Field

CardType = Literal["task", "event", "promise", "comparison", "collection"]
CardStatus = Literal["draft", "confirmed", "done", "archived"]
Priority = Literal["low", "normal", "high"]
WorkspaceType = Literal["personal", "team"]
PriorityMode = Literal["manual", "adaptive"]


class ReminderNode(BaseModel):
    id: str
    mode: Literal["relative", "absolute"] = "relative"
    absolute_time: str | None = None
    offset_minutes: int | None = Field(default=None, ge=0)
    enabled: bool = True
    source: Literal["user", "ai_suggestion", "migrated"] = "user"
    revision: int = Field(default=0, ge=0)
    legacy_label: str | None = None


class ActionCardBase(BaseModel):
    action_id: str | None = None
    dependencies: list[str] = Field(default_factory=list)
    evidence_summary: list[str] = Field(default_factory=list)
    card_type: CardType = "task"
    title: str
    summary: str = ""
    deadline: str | None = None
    start_time: str | None = None
    end_time: str | None = None
    location: str | None = None
    materials: list[str] = Field(default_factory=list)
    submit_method: str | None = None
    priority: Priority = "normal"
    priority_mode: PriorityMode = "adaptive"
    priority_score: float = Field(default=50, ge=0, le=100)
    priority_reason: str = ""
    priority_updated_at: str | None = None
    priority_locked: bool = False
    workspace_type: WorkspaceType = "personal"
    workspace_id: str = "personal"
    assignee_id: str | None = None
    participant_ids: list[str] = Field(default_factory=list)
    deliverables: list[str] = Field(default_factory=list)
    source_session_id: str | None = None
    tags: list[str] = Field(default_factory=list)
    reminders: list[str] = Field(default_factory=list)
    reminder_nodes: list[ReminderNode] = Field(default_factory=list)
    need_confirm: list[str] = Field(default_factory=list)
    status: CardStatus = "draft"
    source_text: str = ""


class ActionCardCreate(ActionCardBase):
    id: str | None = None


class ActionCardUpdate(BaseModel):
    action_id: str | None = None
    dependencies: list[str] | None = None
    evidence_summary: list[str] | None = None
    card_type: CardType | None = None
    title: str | None = None
    summary: str | None = None
    deadline: str | None = None
    start_time: str | None = None
    end_time: str | None = None
    location: str | None = None
    materials: list[str] | None = None
    submit_method: str | None = None
    priority: Priority | None = None
    priority_mode: PriorityMode | None = None
    priority_score: float | None = Field(default=None, ge=0, le=100)
    priority_reason: str | None = None
    priority_updated_at: str | None = None
    priority_locked: bool | None = None
    workspace_type: WorkspaceType | None = None
    workspace_id: str | None = None
    assignee_id: str | None = None
    participant_ids: list[str] | None = None
    deliverables: list[str] | None = None
    source_session_id: str | None = None
    tags: list[str] | None = None
    reminders: list[str] | None = None
    reminder_nodes: list[ReminderNode] | None = None
    need_confirm: list[str] | None = None
    status: CardStatus | None = None
    source_text: str | None = None


class ActionCard(ActionCardBase):
    id: str
    created_at: datetime


class AnalyzeScreenshotTextRequest(BaseModel):
    text: str = Field(min_length=1)
    screenshot_time: str | None = None


class AnalyzeScreenshotTextResponse(BaseModel):
    ocr_text: str
    cards: list[ActionCard]
    preview_actions: list[str]
    engine: str
    trace_id: str
    fallback_reason: str | None = None
    warnings: list[str] = Field(default_factory=list)
    run_id: str = ""
    workflow_status: str = "completed"
    pending_action: str | None = None
    node_trace: list[dict[str, object]] = Field(default_factory=list)
    confidence: dict[str, dict[str, float]] = Field(default_factory=dict)
    provenance: dict[str, dict[str, str]] = Field(default_factory=dict)
