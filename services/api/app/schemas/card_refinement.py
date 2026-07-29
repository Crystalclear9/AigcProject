from __future__ import annotations

from datetime import datetime
from typing import Any, Literal

from pydantic import BaseModel, Field, model_validator

from app.schemas.card import ActionCard

PlanItemKind = Literal["milestone", "work_block", "step"]
PlanItemStatus = Literal["proposed", "accepted", "done", "skipped"]
RefinementStatus = Literal[
    "queued",
    "running",
    "awaiting_review",
    "completed",
    "failed",
    "cancelled",
]


class UserProfileContext(BaseModel):
    version: int = Field(default=1, ge=1)
    scenario: Literal[
        "unspecified",
        "study",
        "office",
        "freelance",
        "life",
        "mixed",
    ] = "unspecified"
    active_period: Literal[
        "unspecified",
        "morning",
        "afternoon",
        "daytime",
        "evening",
        "flexible",
    ] = "unspecified"
    planning_granularity: Literal["concise", "balanced", "detailed"] = "balanced"
    reminder_style: Literal[
        "gentle",
        "light",
        "key_only",
        "standard",
        "multi",
        "multi_stage",
    ] = "standard"
    work_rhythm: Literal["steady", "sprint", "adaptive"] = "adaptive"
    buffer_preference: Literal["compact", "standard", "generous"] = "standard"
    weekend_policy: Literal["avoid", "allow", "flexible"] = "flexible"
    assistant_tone: Literal["concise", "warm", "coach"] = "warm"
    timezone: str = "UTC"


class RefinementOptions(BaseModel):
    granularity: Literal["concise", "balanced", "detailed"] = "balanced"
    include_milestones: bool = True
    include_work_blocks: bool = True
    milestone_reminders: bool = True
    use_profile: bool = True


class AttachmentDescriptor(BaseModel):
    id: str
    name: str
    mime_type: str
    size_bytes: int = Field(ge=0)
    sha256: str
    extraction_status: Literal[
        "pending",
        "succeeded",
        "degraded",
        "unsupported",
        "too_large",
        "password_protected",
        "failed",
    ] = "pending"
    page_count: int | None = Field(default=None, ge=0)
    extracted_characters: int = Field(default=0, ge=0)
    warning: str | None = None


class PlanItem(BaseModel):
    id: str
    parent_id: str | None = None
    kind: PlanItemKind
    title: str = Field(min_length=1, max_length=160)
    description: str = Field(default="", max_length=1200)
    order: int = Field(default=0, ge=0)
    start_time: str | None = None
    deadline: str | None = None
    estimated_minutes: int | None = Field(default=None, ge=1, le=10080)
    importance: Literal["low", "normal", "high"] = "normal"
    dependencies: list[str] = Field(default_factory=list)
    reminder_enabled: bool = False
    confidence: float = Field(default=0.5, ge=0, le=1)
    evidence_refs: list[str] = Field(default_factory=list)
    need_confirm: list[str] = Field(default_factory=list)
    status: PlanItemStatus = "proposed"


class CardRefinementPlan(BaseModel):
    id: str
    parent_card_id: str
    revision: int = Field(default=1, ge=1)
    objective: str = ""
    items: list[PlanItem] = Field(default_factory=list)
    assumptions: list[str] = Field(default_factory=list)
    evidence_summary: list[str] = Field(default_factory=list)
    warnings: list[str] = Field(default_factory=list)
    generated_by: str = "rules"
    profile_version: int | None = None
    quality_score: float = Field(default=0.0, ge=0, le=1)
    constraint_errors: list[str] = Field(default_factory=list)
    profile_effects: list[str] = Field(default_factory=list)
    verification_summary: str = ""
    status: Literal["draft", "accepted"] = "draft"


class CardRefinementRunResponse(BaseModel):
    run_id: str
    trace_id: str
    status: RefinementStatus
    pending_action: str | None = None
    plan: CardRefinementPlan | None = None
    attachments: list[AttachmentDescriptor] = Field(default_factory=list)
    warnings: list[str] = Field(default_factory=list)
    validation_errors: list[str] = Field(default_factory=list)
    provider_usage: dict[str, dict[str, Any]] = Field(default_factory=dict)
    model_enhancement_status: Literal[
        "not_configured",
        "attempted",
        "succeeded",
        "degraded",
    ] = "not_configured"
    revision: int = 0
    created_at: datetime
    updated_at: datetime
    error: str | None = None


class CardRefinementReactRequest(BaseModel):
    base_revision: int = Field(ge=0)
    instruction: str = Field(min_length=1, max_length=600)
    selected_item_ids: list[str] = Field(default_factory=list, max_length=40)


class CardRefinementConfirmRequest(BaseModel):
    revision: int = Field(ge=1)
    selected_item_ids: list[str] = Field(default_factory=list, max_length=80)
    items: list[PlanItem] | None = None

    @model_validator(mode="after")
    def require_items(self) -> "CardRefinementConfirmRequest":
        if not self.selected_item_ids and not self.items:
            raise ValueError("selected_item_ids or items is required")
        return self


class CardRefinementStartPayload(BaseModel):
    card: ActionCard
    options: RefinementOptions = Field(default_factory=RefinementOptions)
    profile_context: UserProfileContext | None = None
    instruction: str = Field(default="", max_length=600)
