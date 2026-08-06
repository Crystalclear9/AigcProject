from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Callable

from pydantic import BaseModel

from app.schemas.agent_contracts import (
    AGENT_CONTRACT_VERSION,
    ActionBoundary,
    DependencyOutput,
    DependencySolverOutput,
    DuplicateOutput,
    EntityLinkerOutput,
    EntityOutput,
    EvidenceReference,
    FieldCoverage,
    HistoryRetrieverOutput,
    PrivacyRiskOutput,
    PersonalPlannerOutput,
    PlanningSuggestion,
    QualityVerifierOutput,
    RetrievalOutput,
    SemanticDecomposerOutput,
    TemporalConstraintOutput,
    TemporalSolverOutput,
    TeamCoordinatorOutput,
    WebRetrieverOutput,
)
from app.schemas.agent_workflow import AgentResult, ToolName


@dataclass(frozen=True)
class AgentContract:
    tool: ToolName
    output_type: str
    output_model: type[BaseModel]
    required_claim_types: frozenset[str]
    acceptance_criteria: tuple[str, ...]
    default_timeout_ms: int
    acceptance_validator: Callable[[BaseModel, AgentResult, dict[str, Any]], list[str]]


def _accept_evidence_items(items: list[Any], _: AgentResult, __: dict[str, Any]) -> list[str]:
    return [
        "acceptance:missing_evidence_span"
        for item in items
        if not item.evidence.source_text
        or item.evidence.start is None
        or item.evidence.end is None
    ]


def _accept_semantic(output: BaseModel, result: AgentResult, state: dict[str, Any]) -> list[str]:
    return _accept_evidence_items(output.actions, result, state)  # type: ignore[attr-defined]


def _accept_temporal(output: BaseModel, result: AgentResult, state: dict[str, Any]) -> list[str]:
    return _accept_evidence_items(output.constraints, result, state)  # type: ignore[attr-defined]


def _accept_entities(output: BaseModel, result: AgentResult, state: dict[str, Any]) -> list[str]:
    return _accept_evidence_items(output.entities, result, state)  # type: ignore[attr-defined]


def _accept_dependencies(output: BaseModel, _: AgentResult, __: dict[str, Any]) -> list[str]:
    return [
        "acceptance:self_dependency"
        for item in output.dependencies  # type: ignore[attr-defined]
        if item.source_action_id == item.target_action_id
    ]


def _accept_history(output: BaseModel, _: AgentResult, __: dict[str, Any]) -> list[str]:
    return [
        "acceptance:invalid_history_reference"
        for item in output.matches  # type: ignore[attr-defined]
        if not item.historical_card_id or item.similarity < 0.0
    ]


def _accept_privacy(_: BaseModel, __: AgentResult, ___: dict[str, Any]) -> list[str]:
    return []


def _accept_web(output: BaseModel, _: AgentResult, __: dict[str, Any]) -> list[str]:
    return [
        "acceptance:unsafe_retrieval_url"
        for item in output.sources  # type: ignore[attr-defined]
        if not item.url.startswith("https://")
    ]


def _accept_quality(output: BaseModel, _: AgentResult, state: dict[str, Any]) -> list[str]:
    if state.get("rule_cards") and not output.field_coverage:  # type: ignore[attr-defined]
        return ["acceptance:missing_field_coverage"]
    return []


def _accept_planning(output: BaseModel, _: AgentResult, state: dict[str, Any]) -> list[str]:
    verified = {str(item.get("id")) for item in state.get("evidence_spans", []) if item.get("id")}
    return [
        "acceptance:unknown_planning_evidence"
        for suggestion in output.suggestions  # type: ignore[attr-defined]
        if set(suggestion.evidence_refs) - verified
    ]


AGENT_CONTRACTS: dict[ToolName, AgentContract] = {
    "semantic_decomposer": AgentContract(
        "semantic_decomposer", "semantic_decomposition", SemanticDecomposerOutput,
        frozenset({"field"}),
        ("every action has a stable id and source span", "no final card is committed"),
        4500, _accept_semantic,
    ),
    "temporal_solver": AgentContract(
        "temporal_solver", "temporal_constraints", TemporalSolverOutput,
        frozenset({"constraint"}),
        ("every normalized time identifies its action, field, certainty, and evidence"),
        2500, _accept_temporal,
    ),
    "entity_linker": AgentContract(
        "entity_linker", "entities", EntityLinkerOutput,
        frozenset({"entity"}),
        ("every entity identifies its type, value, action, and evidence"),
        2500, _accept_entities,
    ),
    "dependency_solver": AgentContract(
        "dependency_solver", "dependencies", DependencySolverOutput,
        frozenset({"dependency"}),
        ("dependencies reference distinct actions and use an allowed relation"),
        2500, _accept_dependencies,
    ),
    "history_retriever": AgentContract(
        "history_retriever", "history_matches", HistoryRetrieverOutput,
        frozenset({"duplicate"}),
        ("duplicate matches include similarity and a read-only historical id"),
        2500, _accept_history,
    ),
    "privacy_risk_analyzer": AgentContract(
        "privacy_risk_analyzer", "privacy_risk", PrivacyRiskOutput,
        frozenset({"risk"}),
        ("risk output explicitly controls retrieval"),
        2500, _accept_privacy,
    ),
    "web_retriever": AgentContract(
        "web_retriever", "retrieval_sources", WebRetrieverOutput,
        frozenset({"retrieval"}),
        ("every source includes URL, query, retrieval time, summary, and confidence"),
        3500, _accept_web,
    ),
    "quality_verifier": AgentContract(
        "quality_verifier", "quality_verification", QualityVerifierOutput,
        frozenset({"quality"}),
        ("critical fields are evaluated from independent evidence groups", "constraints are explicit"),
        8000, _accept_quality,
    ),
    "personal_planner": AgentContract(
        "personal_planner", "personal_plan", PersonalPlannerOutput,
        frozenset(),
        ("planning suggestions preserve facts and require confirmation",),
        2500, _accept_planning,
    ),
    "team_coordinator": AgentContract(
        "team_coordinator", "team_coordination", TeamCoordinatorOutput,
        frozenset(),
        ("coordination suggestions preserve parent facts and require confirmation",),
        2500, _accept_planning,
    ),
}


def contract_for(tool: ToolName) -> AgentContract:
    return AGENT_CONTRACTS[tool]


def project_and_validate_result(
    result: AgentResult,
    state: dict[str, Any],
) -> AgentResult:
    contract = contract_for(result.tool)
    dependency_failures = _dependency_failures(result, state)
    output = _PROJECTORS[result.tool](result, state)
    errors: list[str] = []
    try:
        validated = contract.output_model.model_validate(output)
    except Exception as error:
        validated = None
        errors.append(f"schema_validation:{type(error).__name__}")
    if validated is not None:
        errors.extend(contract.acceptance_validator(validated, result, state))
    coverage = _evidence_coverage(result)
    if result.status == "completed" and result.claims and coverage < 1.0:
        errors.append("claims_missing_source_evidence")
    present_claim_types = {claim.claim_type for claim in result.claims}
    if (
        result.status == "completed"
        and result.claims
        and not present_claim_types.intersection(contract.required_claim_types)
    ):
        errors.append("required_claim_type_missing")
    if dependency_failures:
        errors.append("dependency_degraded")
    if errors and result.status == "completed":
        result.status = "degraded"
    result.contract_version = AGENT_CONTRACT_VERSION
    result.output_type = contract.output_type
    result.validated_output = validated.model_dump(mode="json") if validated else {}
    result.contract_errors = errors
    result.evidence_coverage = coverage
    result.dependency_failures = dependency_failures
    result.decision_summary = _decision_summary(result, errors)
    # Candidate actions are evidence, not mutable final-card projections.
    result.cards = []
    return result


def _reference(claim: Any) -> EvidenceReference:
    return EvidenceReference(
        source_text=claim.source_text,
        start=claim.start,
        end=claim.end,
        correlation_group=claim.correlation_group or claim.id,
    )


def _semantic(result: AgentResult, state: dict[str, Any]) -> dict[str, Any]:
    source = str(state.get("ocr_text", ""))
    action_fields: dict[str, dict[str, Any]] = {}
    for claim in result.claims:
        if claim.claim_type != "field" or not claim.action_id:
            continue
        action_fields.setdefault(claim.action_id, {})[str(claim.field)] = claim.value
    actions: list[ActionBoundary] = []
    for action_id, fields in action_fields.items():
        title = str(fields.get("title", "")).strip()
        if not title:
            continue
        start = source.find(title)
        ref = EvidenceReference(
            source_text=title if start >= 0 else "",
            start=start if start >= 0 else None,
            end=start + len(title) if start >= 0 else None,
            correlation_group=f"semantic:{action_id}",
        )
        actions.append(ActionBoundary(
            action_id=action_id,
            title=title,
            card_type=fields.get("card_type", "task"),
            summary=str(fields.get("summary", "")),
            evidence=ref,
            confidence=max(
                (claim.confidence for claim in result.claims if claim.action_id == action_id),
                default=0.5,
            ),
        ))
    return SemanticDecomposerOutput(actions=actions).model_dump(mode="json")


def _temporal(result: AgentResult, _: dict[str, Any]) -> dict[str, Any]:
    constraints = [
        TemporalConstraintOutput(
            action_id=claim.action_id or "unassigned",
            field=str(claim.field),
            normalized_value=str(claim.value),
            certainty="certain" if claim.confidence >= 0.8 else "uncertain",
            evidence=_reference(claim),
            confidence=claim.confidence,
        )
        for claim in result.claims
        if claim.claim_type == "constraint" and claim.field in {"deadline", "start_time", "end_time"}
    ]
    return TemporalSolverOutput(constraints=constraints, conflicts=result.findings).model_dump(mode="json")


def _entities(result: AgentResult, _: dict[str, Any]) -> dict[str, Any]:
    type_map = {"location": "location", "materials": "material", "submit_method": "platform"}
    entities = [
        EntityOutput(
            action_id=claim.action_id,
            entity_type=type_map.get(str(claim.field), "activity"),
            value=str(claim.value),
            evidence=_reference(claim),
            confidence=claim.confidence,
        )
        for claim in result.claims if claim.claim_type == "entity"
    ]
    return EntityLinkerOutput(entities=entities).model_dump(mode="json")


def _dependencies(result: AgentResult, _: dict[str, Any]) -> dict[str, Any]:
    items: list[DependencyOutput] = []
    for claim in result.claims:
        value = claim.value if isinstance(claim.value, dict) else {}
        if claim.claim_type != "dependency" or not value.get("source_card_id") or not value.get("target_card_id"):
            continue
        items.append(DependencyOutput(
            source_action_id=str(value["source_card_id"]),
            target_action_id=str(value["target_card_id"]),
            dependency_type=value.get("dependency_type", "same_matter"),
            confidence=claim.confidence,
            evidence_ids=[claim.id],
        ))
    return DependencySolverOutput(dependencies=items, conflicts=result.findings).model_dump(mode="json")


def _history(result: AgentResult, _: dict[str, Any]) -> dict[str, Any]:
    matches: list[DuplicateOutput] = []
    for claim in result.claims:
        value = claim.value if isinstance(claim.value, dict) else {}
        if claim.claim_type != "duplicate":
            continue
        matches.append(DuplicateOutput(
            current_action_id=str(value.get("current_card_id", "unassigned")),
            historical_card_id=str(value.get("historical_card_id", "")),
            historical_title=str(value.get("historical_title", "")),
            similarity=claim.confidence,
        ))
    return HistoryRetrieverOutput(matches=matches).model_dump(mode="json")


def _privacy(result: AgentResult, _: dict[str, Any]) -> dict[str, Any]:
    claim = next((item for item in result.claims if item.claim_type == "risk"), None)
    value = claim.value if claim and isinstance(claim.value, dict) else {}
    return PrivacyRiskOutput(
        risk_level=result.risk_level,
        retrieval_allowed=bool(value.get("retrieval_allowed", result.risk_level != "high")),
        sensitive_categories=[item for item in result.findings if item == "private_content"],
    ).model_dump(mode="json")


def _web(result: AgentResult, _: dict[str, Any]) -> dict[str, Any]:
    return WebRetrieverOutput(
        sources=[RetrievalOutput(**source.model_dump()) for source in result.retrieval_sources]
    ).model_dump(mode="json")


def _quality(result: AgentResult, state: dict[str, Any]) -> dict[str, Any]:
    errors = sorted(set(result.findings))
    claims = [
        claim
        for previous in state.get("agent_task_results", [])
        for claim in previous.get("claims", [])
    ]
    field_coverage = []
    for card in state.get("rule_cards", []):
        action_id = str(card.get("action_id") or card.get("id"))
        time_field = "deadline" if card.get("card_type") in {"task", "promise"} else "start_time"
        for field in ("title", time_field):
            if card.get(field) in (None, "", []):
                continue
            supporting = [
                claim
                for claim in claims
                if claim.get("action_id") == action_id
                and claim.get("field") == field
                and claim.get("source_text")
                and claim.get("start") is not None
                and claim.get("end") is not None
            ]
            groups = {
                str(claim.get("correlation_group") or claim.get("id"))
                for claim in supporting
            }
            field_coverage.append(
                FieldCoverage(
                    action_id=action_id,
                    field=field,
                    supported=bool(groups),
                    independent_groups=len(groups),
                    best_confidence=max(
                        (float(claim.get("confidence", 0)) for claim in supporting),
                        default=0,
                    ),
                    evidence_ids=[str(claim.get("id")) for claim in supporting],
                )
            )
    return QualityVerifierOutput(
        passed=not errors and all(item.supported for item in field_coverage),
        field_coverage=field_coverage,
        constraint_errors=errors,
        missing_evidence=[item for item in errors if item.startswith("missing_")],
        recommended_tools=[],
    ).model_dump(mode="json")


def _planning(result: AgentResult, state: dict[str, Any]) -> dict[str, Any]:
    evidence_by_card: dict[str, set[str]] = {}
    for item in state.get("field_evidence", []):
        card_id = str(item.get("field", "")).split(".", 1)[0]
        evidence_by_card.setdefault(card_id, set()).update(str(ref) for ref in item.get("evidence_refs", []))
    suggestion_type = "team_coordination" if result.tool == "team_coordinator" else "schedule"
    suggestions = [
        PlanningSuggestion(
            action_id=str(card.get("id")),
            suggestion_type=suggestion_type,
            value={
                key: card.get(key)
                for key in ("deadline", "priority", "assignee_id", "participant_ids")
                if card.get(key) not in (None, "", [])
            },
            evidence_refs=sorted(evidence_by_card.get(str(card.get("id")), set())),
            requires_confirmation=True,
        )
        for card in state.get("rule_cards", [])
    ]
    model = TeamCoordinatorOutput if result.tool == "team_coordinator" else PersonalPlannerOutput
    return model(suggestions=suggestions).model_dump(mode="json")


_PROJECTORS: dict[ToolName, Callable[[AgentResult, dict[str, Any]], dict[str, Any]]] = {
    "semantic_decomposer": _semantic,
    "temporal_solver": _temporal,
    "entity_linker": _entities,
    "dependency_solver": _dependencies,
    "history_retriever": _history,
    "privacy_risk_analyzer": _privacy,
    "web_retriever": _web,
    "quality_verifier": _quality,
    "personal_planner": _planning,
    "team_coordinator": _planning,
}


def _evidence_coverage(result: AgentResult) -> float:
    relevant = [claim for claim in result.claims if claim.claim_type in {"field", "constraint", "entity"}]
    if not relevant:
        return 1.0
    supported = sum(
        bool(claim.source_text) and claim.start is not None and claim.end is not None
        for claim in relevant
    )
    return round(supported / len(relevant), 3)


def _dependency_failures(result: AgentResult, state: dict[str, Any]) -> list[dict[str, str]]:
    task = next(
        (item for item in state.get("agent_plan", {}).get("tasks", []) if item.get("id") == result.task_id),
        {},
    )
    dependencies = set(task.get("depends_on", []))
    return [
        {
            "task_id": str(item.get("task_id")),
            "status": str(item.get("status")),
            "failure_type": str(item.get("failure_type") or "degraded"),
        }
        for item in state.get("agent_task_results", [])
        if item.get("task_id") in dependencies and item.get("status") != "completed"
    ]


def _decision_summary(result: AgentResult, errors: list[str]) -> str:
    if errors:
        return f"{result.tool} produced reviewable output with {len(errors)} contract warning(s)"[:240]
    return f"{result.tool} output passed {AGENT_CONTRACT_VERSION}"[:240]
