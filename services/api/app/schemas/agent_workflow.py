from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, Field, model_validator


ToolName = Literal[
    "semantic_decomposer",
    "temporal_solver",
    "entity_linker",
    "dependency_solver",
    "history_retriever",
    "privacy_risk_analyzer",
    "web_retriever",
    "quality_verifier",
]
ModelTier = Literal["none", "fast_model", "expert_model"]
TaskStatus = Literal["pending", "running", "completed", "degraded", "failed", "skipped"]


class AgentTask(BaseModel):
    id: str
    objective: str
    tool: ToolName
    depends_on: list[str] = Field(default_factory=list)
    expected_evidence: list[str] = Field(default_factory=list)
    acceptance_criteria: list[str] = Field(default_factory=list)
    priority: int = Field(default=50, ge=0, le=100)
    model_tier: ModelTier = "none"
    timeout_ms: int = Field(default=2500, ge=100, le=15000)
    max_attempts: int = Field(default=1, ge=1, le=2)
    round: int = Field(default=0, ge=0, le=2)
    idempotency_key: str
    arguments: dict[str, Any] = Field(default_factory=dict)

    @model_validator(mode="after")
    def no_self_dependency(self) -> "AgentTask":
        if self.id in self.depends_on:
            raise ValueError("task cannot depend on itself")
        return self


class AgentPlan(BaseModel):
    id: str
    objective: str
    tasks: list[AgentTask] = Field(default_factory=list, max_length=8)
    reasons: list[str] = Field(default_factory=list)
    created_by: Literal["deterministic", "fast_model"] = "deterministic"
    round: int = Field(default=0, ge=0, le=2)
    max_tasks: int = Field(default=8, ge=1, le=8)
    max_replans: int = Field(default=2, ge=0, le=2)
    deadline_ms: int = Field(default=15000, ge=1000, le=30000)

    @model_validator(mode="after")
    def validate_dependencies(self) -> "AgentPlan":
        task_ids = {task.id for task in self.tasks}
        if len(task_ids) != len(self.tasks):
            raise ValueError("task ids must be unique")
        for task in self.tasks:
            unknown = set(task.depends_on) - task_ids
            if unknown:
                raise ValueError(f"unknown task dependencies: {sorted(unknown)}")
        return self


class ToolClaim(BaseModel):
    id: str
    claim_type: Literal[
        "field",
        "entity",
        "dependency",
        "constraint",
        "risk",
        "duplicate",
        "quality",
        "retrieval",
    ]
    action_id: str | None = None
    field: str | None = None
    value: Any = None
    confidence: float = Field(default=0.5, ge=0, le=1)
    source_text: str = ""
    start: int | None = None
    end: int | None = None
    citation_url: str | None = None
    citation_title: str | None = None
    correlation_group: str = ""
    derived_from: list[str] = Field(default_factory=list)
    rationale: str = ""


class RetrievalSource(BaseModel):
    url: str
    title: str = ""
    summary: str = ""
    retrieved_at: str
    query: str
    confidence: float = Field(default=0.5, ge=0, le=1)


class AgentResult(BaseModel):
    task_id: str
    tool: ToolName
    status: TaskStatus
    claims: list[ToolClaim] = Field(default_factory=list)
    cards: list[dict[str, Any]] = Field(default_factory=list)
    findings: list[str] = Field(default_factory=list)
    retrieval_sources: list[RetrievalSource] = Field(default_factory=list)
    risk_level: Literal["low", "medium", "high"] = "low"
    failure_type: str | None = None
    duration_ms: float = Field(default=0, ge=0)
    attempt: int = Field(default=1, ge=1, le=2)
    model_tier: ModelTier = "none"
    idempotency_key: str


class BudgetUsage(BaseModel):
    task_limit: int = 8
    tasks_scheduled: int = 0
    tasks_completed: int = 0
    tasks_failed: int = 0
    replan_limit: int = 2
    replans_used: int = 0
    deadline_ms: int = 15000
    elapsed_ms: float = 0
    exhausted: bool = False
    exhaustion_reason: str | None = None
    fast_model_calls: int = 0
    expert_model_calls: int = 0
    web_requests: int = 0


class VerificationSummary(BaseModel):
    passed: bool = False
    evidence_coverage: float = Field(default=0, ge=0, le=1)
    constraint_errors: list[str] = Field(default_factory=list)
    unresolved_evidence: list[str] = Field(default_factory=list)
    recommended_tasks: list[ToolName] = Field(default_factory=list)
    requires_review: bool = False
    reason: str = ""
