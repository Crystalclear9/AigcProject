from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, Field


EvidenceSource = Literal[
    "ocr",
    "rules",
    "semantic_agent",
    "temporal_agent",
    "entity_agent",
    "risk_agent",
    "duplicate_agent",
    "quality_agent",
    "fast_model",
    "expert_model",
    "user",
]
DependencyType = Literal[
    "prerequisite",
    "subtask",
    "same_matter",
    "time_conflict",
    "resource_dependency",
]
RiskLevel = Literal["low", "medium", "high"]


class EvidenceItem(BaseModel):
    id: str
    source: EvidenceSource
    action_id: str | None = None
    field: str | None = None
    value: Any = None
    text: str = ""
    start: int | None = None
    end: int | None = None
    confidence: float = Field(default=0.5, ge=0, le=1)
    engine: str = ""
    version: int = 1


class ActionNode(BaseModel):
    id: str
    card_id: str
    card_type: str
    title: str
    field_values: dict[str, Any] = Field(default_factory=dict)
    field_evidence: dict[str, list[str]] = Field(default_factory=dict)


class EntityNode(BaseModel):
    id: str
    entity_type: Literal["person", "location", "material", "organization", "other"]
    name: str
    evidence_ids: list[str] = Field(default_factory=list)


class ActionConstraint(BaseModel):
    id: str
    action_id: str
    constraint_type: Literal["deadline", "start_time", "end_time", "required_field", "policy"]
    value: Any = None
    satisfied: bool = True
    evidence_ids: list[str] = Field(default_factory=list)


class ActionDependency(BaseModel):
    id: str
    source_action_id: str
    target_action_id: str
    dependency_type: DependencyType
    confidence: float = Field(default=0.5, ge=0, le=1)
    evidence_ids: list[str] = Field(default_factory=list)


class ActionConflict(BaseModel):
    id: str
    action_id: str | None = None
    field: str | None = None
    kind: Literal["value", "time", "identity", "policy", "revision"]
    severity: RiskLevel = "medium"
    candidate_values: list[Any] = Field(default_factory=list)
    evidence_ids: list[str] = Field(default_factory=list)
    resolved: bool = False
    resolution: Any = None


class ActionSuggestion(BaseModel):
    id: str
    action_id: str
    field: str
    value: Any = None
    reason: str = ""
    evidence_ids: list[str] = Field(default_factory=list)


class ActionGraph(BaseModel):
    actions: list[ActionNode] = Field(default_factory=list)
    entities: list[EntityNode] = Field(default_factory=list)
    constraints: list[ActionConstraint] = Field(default_factory=list)
    dependencies: list[ActionDependency] = Field(default_factory=list)
    evidence: list[EvidenceItem] = Field(default_factory=list)
    conflicts: list[ActionConflict] = Field(default_factory=list)
    suggestions: list[ActionSuggestion] = Field(default_factory=list)
    version: int = 1

