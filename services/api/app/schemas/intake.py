from __future__ import annotations

from datetime import datetime
from typing import Literal

from pydantic import BaseModel, Field

from app.schemas.action_graph import ActionGraph
from app.schemas.card import ActionCard
from app.schemas.workflow import WorkflowRunResponse
from app.schemas.card_refinement import (
    AttachmentDescriptor,
    CardRefinementPlan,
    RefinementOptions,
    UserProfileContext,
)

ContentClassification = Literal["noise", "informational", "actionable", "mixed", "uncertain"]
SourceKind = Literal[
    "text",
    "screenshot",
    "long_screenshot",
    "chat",
    "document",
    "mixed",
]
RoleTemplate = Literal["action_analyst", "personal_planner", "team_coordinator"]


class PromptEnvelope(BaseModel):
    version: str = "prompt-envelope-v2"
    role_template: RoleTemplate = "action_analyst"
    role_instruction: str
    user_policy: str = ""
    source_contract: str
    fact_protection: str = ""
    output_policy: str = ""
    character_count: int = Field(ge=0, le=1200)


class IntakeSessionResponse(BaseModel):
    session_id: str
    workflow_run_id: str | None = None
    source_kind: SourceKind
    workspace_type: Literal["personal", "team"] = "personal"
    classification: ContentClassification
    classification_confidence: float = Field(default=0, ge=0, le=1)
    should_create_cards: bool = False
    canonical_text: str = ""
    cards: list[ActionCard] = Field(default_factory=list)
    action_graph: ActionGraph = Field(default_factory=ActionGraph)
    prompt_envelope: PromptEnvelope
    warnings: list[str] = Field(default_factory=list)
    attachments: list[AttachmentDescriptor] = Field(default_factory=list)
    refinement_run_id: str | None = None
    workflow: WorkflowRunResponse | None = None
    created_at: datetime
    updated_at: datetime


class IntakeRefineRequest(BaseModel):
    card_id: str
    options: RefinementOptions = Field(default_factory=RefinementOptions)
    profile_context: UserProfileContext | None = None
    instruction: str = Field(default="", max_length=600)


class IntakeConfirmRequest(BaseModel):
    revision: int = Field(ge=1)
    selected_card_ids: list[str] = Field(min_length=1, max_length=40)


class PriorityPolicy(BaseModel):
    deadline_weight: float = Field(default=0.35, ge=0, le=1)
    importance_weight: float = Field(default=0.25, ge=0, le=1)
    dependency_weight: float = Field(default=0.2, ge=0, le=1)
    team_impact_weight: float = Field(default=0.15, ge=0, le=1)
    workload_weight: float = Field(default=0.05, ge=0, le=1)


class CardReplanRequest(BaseModel):
    changed_fields: list[str] = Field(default_factory=list, max_length=30)
    priority_mode: Literal["manual", "adaptive"] | None = None
    manual_priority: Literal["low", "normal", "high"] | None = None
    importance: float = Field(default=0.5, ge=0, le=1)
    estimated_minutes: int | None = Field(default=None, ge=1, le=10080)
    blocked_dependents: int = Field(default=0, ge=0, le=100)
    team_impact: float = Field(default=0, ge=0, le=1)
    policy: PriorityPolicy = Field(default_factory=PriorityPolicy)
    profile_context: UserProfileContext | None = None


class CardReplanResponse(BaseModel):
    card: ActionCard
    changed: bool
    plan: CardRefinementPlan | None = None
    calendar_actions: list[dict[str, object]] = Field(default_factory=list)
    verification_summary: str = ""
    warnings: list[str] = Field(default_factory=list)
