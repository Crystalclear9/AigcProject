from __future__ import annotations

from datetime import datetime
from typing import Any, Literal

from pydantic import BaseModel, Field, model_validator

from app.schemas.action_graph import ActionGraph, ActionDependency, RiskLevel
from app.schemas.card import ActionCard
from app.schemas.agent_workflow import (
    AgentPlan,
    AgentResult,
    BudgetUsage,
    RetrievalSource,
    VerificationSummary,
)

WorkflowStatus = Literal[
    "queued",
    "running",
    "awaiting_client_ocr",
    "awaiting_review",
    "completed",
    "failed",
    "cancelled",
]
ResumeCommand = Literal["provide_ocr_text", "review_cards", "cancel"]
ResultStage = Literal["provisional", "enhanced", "final"]
WorkflowRoute = Literal["rules", "fast_model", "expert_model", "fast_and_expert", "supervisor_agents"]


class NodeTrace(BaseModel):
    node: str
    status: Literal["completed", "degraded", "failed", "interrupted"] = "completed"
    duration_ms: float = 0
    engine: str | None = None
    detail: str | None = None


class WorkflowStartTextRequest(BaseModel):
    text: str = Field(min_length=1)
    screenshot_time: str | None = None


class OcrCandidateRequest(BaseModel):
    text: str = Field(min_length=1)
    engine: str = "mlkit"
    confidence: float = Field(default=0.8, ge=0, le=1)


class DraftPatchRequest(BaseModel):
    base_revision: int = Field(ge=0)
    cards: list[ActionCard] | None = None
    locked_fields: dict[str, list[str]] = Field(default_factory=dict)
    operations: list["DraftFieldOperation"] = Field(default_factory=list)

    @model_validator(mode="after")
    def require_changes(self) -> "DraftPatchRequest":
        if self.cards is None and not self.operations and not self.locked_fields:
            raise ValueError("cards, operations or locked_fields is required")
        return self


class DraftFieldOperation(BaseModel):
    operation: Literal["set", "unset", "lock", "unlock"]
    card_id: str
    field: str
    value: Any = None
    base_field_version: int | None = Field(default=None, ge=0)


class ConfirmWorkflowRequest(BaseModel):
    revision: int = Field(ge=0)


class WorkflowResumeRequest(BaseModel):
    command: ResumeCommand
    ocr_text: str | None = None
    cards: list[ActionCard] | None = None

    @model_validator(mode="after")
    def validate_payload(self) -> "WorkflowResumeRequest":
        if self.command == "provide_ocr_text" and not (self.ocr_text or "").strip():
            raise ValueError("ocr_text is required for provide_ocr_text")
        if self.command == "review_cards" and self.cards is None:
            raise ValueError("cards are required for review_cards")
        return self


class WorkflowRunResponse(BaseModel):
    run_id: str
    trace_id: str
    workflow_status: WorkflowStatus
    pending_action: str | None = None
    ocr_text: str = ""
    cards: list[ActionCard] = Field(default_factory=list)
    preview_actions: list[str] = Field(default_factory=list)
    engine: str = ""
    fallback_reason: str | None = None
    warnings: list[str] = Field(default_factory=list)
    node_trace: list[NodeTrace] = Field(default_factory=list)
    confidence: dict[str, dict[str, float]] = Field(default_factory=dict)
    provenance: dict[str, dict[str, str]] = Field(default_factory=dict)
    validation_errors: list[str] = Field(default_factory=list)
    created_at: datetime
    updated_at: datetime
    error: str | None = None
    revision: int = 0
    result_stage: ResultStage = "provisional"
    overall_confidence: float = 0
    route: WorkflowRoute = "rules"
    cache_status: Literal["hit", "miss", "bypass"] = "bypass"
    time_to_first_draft_ms: float | None = None
    time_to_final_ms: float | None = None
    user_locked: dict[str, list[str]] = Field(default_factory=dict)
    suggestions: dict[str, dict[str, Any]] = Field(default_factory=dict)
    action_graph: ActionGraph = Field(default_factory=ActionGraph)
    dependencies: list[ActionDependency] = Field(default_factory=list)
    evidence_summary: list[str] = Field(default_factory=list)
    active_agents: list[str] = Field(default_factory=list)
    decision_reasons: list[str] = Field(default_factory=list)
    risk_level: RiskLevel = "low"
    field_versions: dict[str, dict[str, int]] = Field(default_factory=dict)
    field_conflicts: list[dict[str, Any]] = Field(default_factory=list)
    agent_plan: AgentPlan | None = None
    agent_tasks: list[AgentResult] = Field(default_factory=list)
    unresolved_evidence: list[str] = Field(default_factory=list)
    budget_usage: BudgetUsage = Field(default_factory=BudgetUsage)
    retrieval_sources: list[RetrievalSource] = Field(default_factory=list)
    verification_summary: VerificationSummary = Field(default_factory=VerificationSummary)
    replan_count: int = 0


class WorkflowEvent(BaseModel):
    id: int
    run_id: str
    event: Literal[
        "run_started",
        "node_started",
        "ocr_candidate",
        "draft_created",
        "draft_updated",
        "review_required",
        "agent_dispatched",
        "evidence_added",
        "action_graph_updated",
        "field_conflict",
        "suggestion_added",
        "decision_made",
        "plan_created",
        "task_scheduled",
        "tool_started",
        "tool_completed",
        "retrieval_source_added",
        "verification_failed",
        "plan_revised",
        "budget_exhausted",
        "completed",
        "failed",
    ]
    data: dict[str, Any] = Field(default_factory=dict)
    created_at: datetime


class WorkflowMetrics(BaseModel):
    total: int = 0
    completed: int = 0
    completion_rate: float = 0
    human_review_rate: float = 0
    ocr_fallback_rate: float = 0
    rules_fallback_rate: float = 0
    average_node_duration_ms: float = 0
    average_repair_count: float = 0
