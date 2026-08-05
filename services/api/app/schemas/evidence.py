from __future__ import annotations

from datetime import datetime
from typing import Any, Literal

from pydantic import BaseModel, Field


EvidenceSourceType = Literal["text", "ocr", "attachment", "user_edit", "provider"]
EvidenceTrustStatus = Literal["trusted", "review_required", "user_verified"]


class EvidenceEnvelope(BaseModel):
    source_id: str
    source_type: EvidenceSourceType
    version: int = Field(default=1, ge=1)
    raw_text: str = ""
    blocks: list[dict[str, Any]] = Field(default_factory=list)
    spans: list[dict[str, Any]] = Field(default_factory=list)
    quality_report: dict[str, Any] = Field(default_factory=dict)
    trust_status: EvidenceTrustStatus = "review_required"
    conflicts: list[str] = Field(default_factory=list)
    created_at: datetime


class FieldEvidence(BaseModel):
    field: str
    value: Any = None
    evidence_refs: list[str] = Field(default_factory=list)
    confidence: float = Field(default=0, ge=0, le=1)
    source_version: int = Field(default=1, ge=1)
    locked: bool = False
    needs_confirmation: bool = False
