from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator


AGENT_CONTRACT_VERSION = "agent-contract-v2"


class AgentContractModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class EvidenceReference(AgentContractModel):
    source_text: str = ""
    start: int | None = Field(default=None, ge=0)
    end: int | None = Field(default=None, ge=0)
    block_id: str | None = None
    correlation_group: str

    @model_validator(mode="after")
    def validate_span(self) -> "EvidenceReference":
        if (self.start is None) != (self.end is None):
            raise ValueError("start and end must be provided together")
        if self.start is not None and self.end is not None and self.end <= self.start:
            raise ValueError("evidence end must be greater than start")
        return self


class ActionBoundary(AgentContractModel):
    action_id: str
    title: str = Field(min_length=1, max_length=120)
    card_type: Literal["task", "event", "promise", "habit", "note"] = "task"
    summary: str = Field(default="", max_length=500)
    participants: list[str] = Field(default_factory=list, max_length=20)
    evidence: EvidenceReference
    confidence: float = Field(ge=0, le=1)


class SemanticDecomposerOutput(AgentContractModel):
    output_type: Literal["semantic_decomposition"] = "semantic_decomposition"
    actions: list[ActionBoundary] = Field(default_factory=list, max_length=20)
    informational_spans: list[EvidenceReference] = Field(default_factory=list)
    unassigned_spans: list[EvidenceReference] = Field(default_factory=list)


class TemporalConstraintOutput(AgentContractModel):
    action_id: str
    field: Literal["deadline", "start_time", "end_time"]
    normalized_value: str
    certainty: Literal["certain", "inferred", "uncertain"]
    evidence: EvidenceReference
    confidence: float = Field(ge=0, le=1)


class TemporalSolverOutput(AgentContractModel):
    output_type: Literal["temporal_constraints"] = "temporal_constraints"
    constraints: list[TemporalConstraintOutput] = Field(default_factory=list)
    conflicts: list[str] = Field(default_factory=list)


class EntityOutput(AgentContractModel):
    action_id: str | None = None
    entity_type: Literal["person", "organization", "location", "material", "platform", "activity"]
    value: str
    evidence: EvidenceReference
    confidence: float = Field(ge=0, le=1)


class EntityLinkerOutput(AgentContractModel):
    output_type: Literal["entities"] = "entities"
    entities: list[EntityOutput] = Field(default_factory=list)


class DependencyOutput(AgentContractModel):
    source_action_id: str
    target_action_id: str
    dependency_type: Literal[
        "prerequisite",
        "subtask",
        "same_matter",
        "time_conflict",
        "resource_dependency",
    ]
    confidence: float = Field(ge=0, le=1)
    evidence_ids: list[str] = Field(default_factory=list)


class DependencySolverOutput(AgentContractModel):
    output_type: Literal["dependencies"] = "dependencies"
    dependencies: list[DependencyOutput] = Field(default_factory=list)
    conflicts: list[str] = Field(default_factory=list)


class DuplicateOutput(AgentContractModel):
    current_action_id: str
    historical_card_id: str
    similarity: float = Field(ge=0, le=1)
    historical_title: str = ""


class HistoryRetrieverOutput(AgentContractModel):
    output_type: Literal["history_matches"] = "history_matches"
    matches: list[DuplicateOutput] = Field(default_factory=list)


class PrivacyRiskOutput(AgentContractModel):
    output_type: Literal["privacy_risk"] = "privacy_risk"
    risk_level: Literal["low", "medium", "high"]
    retrieval_allowed: bool
    sensitive_categories: list[str] = Field(default_factory=list)


class RetrievalOutput(AgentContractModel):
    url: str
    title: str = ""
    summary: str = ""
    retrieved_at: str
    query: str
    confidence: float = Field(ge=0, le=1)


class WebRetrieverOutput(AgentContractModel):
    output_type: Literal["retrieval_sources"] = "retrieval_sources"
    sources: list[RetrievalOutput] = Field(default_factory=list)


class FieldCoverage(AgentContractModel):
    action_id: str
    field: str
    supported: bool
    independent_groups: int = Field(default=0, ge=0)
    best_confidence: float = Field(default=0, ge=0, le=1)
    evidence_ids: list[str] = Field(default_factory=list)


class QualityVerifierOutput(AgentContractModel):
    output_type: Literal["quality_verification"] = "quality_verification"
    passed: bool
    field_coverage: list[FieldCoverage] = Field(default_factory=list)
    constraint_errors: list[str] = Field(default_factory=list)
    missing_evidence: list[str] = Field(default_factory=list)
    recommended_tools: list[str] = Field(default_factory=list)


AgentValidatedOutput = (
    SemanticDecomposerOutput
    | TemporalSolverOutput
    | EntityLinkerOutput
    | DependencySolverOutput
    | HistoryRetrieverOutput
    | PrivacyRiskOutput
    | WebRetrieverOutput
    | QualityVerifierOutput
)
