from __future__ import annotations

import asyncio
import hashlib
import re
import time
from datetime import datetime, timezone
from difflib import SequenceMatcher
from typing import Any, Awaitable, Callable
from urllib.parse import urlparse

from app.core.config import settings
from app.repositories.cards import CardRepository
from app.schemas.agent_workflow import (
    AgentPlan,
    AgentResult,
    AgentTask,
    RetrievalSource,
    ToolClaim,
    ToolName,
    VerificationSummary,
)
from app.services.llm_client import extract_cards_with_model, structured_completion
from app.services.provider_runtime import runtime

ToolHandler = Callable[[AgentTask, dict[str, Any]], Awaitable[AgentResult]]
PRIVATE_PATTERNS = [
    re.compile(r"1[3-9]\d{9}"),
    re.compile(r"\b\d{15,18}[0-9Xx]\b"),
    re.compile(r"\b[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}\b"),
    re.compile(r"\b(?:\d[ -]*?){13,19}\b"),
]
SENSITIVE_WORDS = ("密码", "身份证", "银行卡", "验证码", "账号", "住址", "手机号")
PUBLIC_ENTITY_PATTERN = re.compile(
    r"[\u4e00-\u9fffA-Za-z0-9][\u4e00-\u9fffA-Za-z0-9·._-]{1,30}"
    r"(?:会议|活动|比赛|考试|课程|讲座|展览|地点|中心|大学|学院|公司|组织)"
)


def stable_id(prefix: str, *parts: object) -> str:
    raw = "\0".join(str(part) for part in parts)
    return f"{prefix}-{hashlib.sha1(raw.encode('utf-8')).hexdigest()[:16]}"


def _task(
    run_id: str,
    round_number: int,
    tool: ToolName,
    objective: str,
    *,
    depends_on: list[str] | None = None,
    expected: list[str] | None = None,
    model_tier: str = "none",
    priority: int = 50,
    timeout_ms: int = 2500,
    arguments: dict[str, Any] | None = None,
) -> AgentTask:
    task_id = stable_id("task", run_id, round_number, tool, objective)
    return AgentTask(
        id=task_id,
        objective=objective,
        tool=tool,
        depends_on=depends_on or [],
        expected_evidence=expected or [],
        acceptance_criteria=[f"produce typed {item}" for item in (expected or ["findings"])],
        priority=priority,
        model_tier=model_tier,
        timeout_ms=timeout_ms,
        round=round_number,
        idempotency_key=stable_id("task-key", run_id, round_number, tool, objective),
        arguments=arguments or {},
    )


def create_plan(state: dict[str, Any], recommended: list[str] | None = None) -> AgentPlan:
    run_id = str(state.get("run_id", "workflow"))
    round_number = int(state.get("replan_count", 0))
    cards = state.get("rule_cards", [])
    reasons = set(state.get("complexity_reasons", []))
    requested = set(recommended or [])
    tasks: list[AgentTask] = []
    if (
        not requested
        and float(state.get("overall_confidence", 0)) >= 0.85
        and not reasons
        and not state.get("has_fast_model")
        and not state.get("has_expert_model")
    ):
        return AgentPlan(
            id=stable_id("plan", run_id, round_number, "rules-only"),
            objective="Accept high-confidence deterministic evidence",
            tasks=[],
            reasons=["high-confidence deterministic extraction"],
            created_by="deterministic",
            round=round_number,
            max_tasks=settings.workflow_agent_max_tasks,
            max_replans=settings.workflow_agent_max_replans,
            deadline_ms=int(settings.workflow_agent_deadline_seconds * 1000),
        )

    semantic_needed = (
        "semantic_decomposer" in requested
        or "multiple_cards" in reasons
        or "long_text" in reasons
        or float(state.get("overall_confidence", 0)) < 0.72
    )
    if semantic_needed:
        tasks.append(
            _task(
                run_id,
                round_number,
                "semantic_decomposer",
                "Separate independent actions, goals, participants, and subtasks",
                expected=["action candidates", "goals", "participants"],
                model_tier="fast_model" if state.get("has_fast_model") else "none",
                priority=95,
                timeout_ms=4500,
            )
        )
    if (
        "temporal_solver" in requested
        or any(card.get("deadline") or card.get("start_time") or card.get("need_confirm") for card in cards)
    ):
        tasks.append(
            _task(
                run_id,
                round_number,
                "temporal_solver",
                "Validate temporal fields and ordering constraints",
                expected=["time constraints", "time conflicts"],
                priority=90,
            )
        )
    if (
        "entity_linker" in requested
        or any(card.get("location") or card.get("materials") or card.get("submit_method") for card in cards)
    ):
        tasks.append(
            _task(
                run_id,
                round_number,
                "entity_linker",
                "Extract people, organizations, locations, materials, and public entities",
                expected=["entities", "resource fields"],
                priority=70,
            )
        )
    risk_task = _task(
        run_id,
        round_number,
        "privacy_risk_analyzer",
        "Classify private data, commitment risk, and retrieval safety",
        expected=["risk classification", "safe retrieval query"],
        priority=100,
    )
    tasks.append(risk_task)

    history_task = _task(
        run_id,
        round_number,
        "history_retriever",
        "Find duplicate or conflicting historical cards",
        expected=["duplicate candidates", "history conflicts"],
        priority=45,
    )
    tasks.append(history_task)

    dependency_prerequisites = [task.id for task in tasks if task.tool in {"semantic_decomposer", "temporal_solver"}]
    if len(cards) > 1 or semantic_needed or "dependency_solver" in requested:
        tasks.append(
            _task(
                run_id,
                round_number,
                "dependency_solver",
                "Build and validate action dependencies and resource conflicts",
                depends_on=dependency_prerequisites,
                expected=["dependencies", "cycle checks", "resource conflicts"],
                priority=75,
            )
        )

    public_entities = _public_entities(str(state.get("ocr_text", "")))
    if settings.web_retrieval_enabled and public_entities and "private_content" not in reasons:
        tasks.append(
            _task(
                run_id,
                round_number,
                "web_retriever",
                "Retrieve public context for explicitly named public entities",
                depends_on=[risk_task.id],
                expected=["cited public sources"],
                priority=35,
                timeout_ms=3500,
                arguments={"entities": public_entities[:2]},
            )
        )

    quality_dependencies = [task.id for task in tasks]
    use_expert = (
        round_number > 0
        or "quality_verifier" in requested
        or float(state.get("overall_confidence", 0)) < 0.6
        or "promise" in reasons
    )
    tasks.append(
        _task(
            run_id,
            round_number,
            "quality_verifier",
            "Verify evidence coverage, graph constraints, and completion safety",
            depends_on=quality_dependencies,
            expected=["verification decision", "missing evidence"],
            model_tier="expert_model" if use_expert and state.get("has_expert_model") else "none",
            priority=10,
            timeout_ms=8000 if use_expert else 2500,
        )
    )

    completed_keys = {
        str(result.get("idempotency_key"))
        for result in state.get("agent_task_results", [])
        if result.get("status") == "completed"
    }
    completed_tools = {
        str(result.get("tool"))
        for result in state.get("agent_task_results", [])
        if result.get("status") == "completed"
    }
    unique: list[AgentTask] = []
    requested_tools = set(recommended or [])
    ordered = sorted(tasks, key=lambda item: item.priority, reverse=True)
    quality = next((task for task in ordered if task.tool == "quality_verifier"), None)
    ordered = [task for task in ordered if task.tool != "quality_verifier"]
    allowed_count = settings.workflow_agent_max_tasks if round_number else max(
        1, settings.workflow_agent_max_tasks - 2
    )
    for task in ordered:
        if (
            task.idempotency_key in completed_keys
            or task.tool in {item.tool for item in unique}
            or (task.tool in completed_tools and task.tool not in requested_tools)
        ):
            continue
        task.depends_on = [dep for dep in task.depends_on if dep in {item.id for item in tasks}]
        unique.append(task)
        if len(unique) >= max(0, allowed_count - (1 if quality else 0)):
            break
    if quality and (
        quality.tool not in completed_tools
        or quality.tool in requested_tools
        or round_number > 0
    ):
        unique.append(quality)
    allowed_ids = {task.id for task in unique}
    for task in unique:
        task.depends_on = [dep for dep in task.depends_on if dep in allowed_ids]
    return AgentPlan(
        id=stable_id("plan", run_id, round_number, *(task.id for task in unique)),
        objective="Turn screenshot evidence into a validated ActionGraph",
        tasks=unique,
        reasons=sorted(reasons) or ["independent evidence verification"],
        created_by="deterministic",
        round=round_number,
        max_tasks=settings.workflow_agent_max_tasks,
        max_replans=settings.workflow_agent_max_replans,
        deadline_ms=int(settings.workflow_agent_deadline_seconds * 1000),
    )


async def create_plan_with_model(state: dict[str, Any]) -> AgentPlan:
    baseline = create_plan(state)
    if not baseline.tasks or not state.get("has_fast_model"):
        return baseline
    schema = {
        "type": "object",
        "additionalProperties": False,
        "required": ["tools", "reasons"],
        "properties": {
            "tools": {
                "type": "array",
                "maxItems": 6,
                "items": {
                    "type": "string",
                    "enum": list(TOOL_REGISTRY),
                },
            },
            "reasons": {"type": "array", "items": {"type": "string"}},
        },
    }
    safe_cards = [
        {
            "card_type": card.get("card_type"),
            "has_title": bool(card.get("title")),
            "has_time": bool(card.get("deadline") or card.get("start_time")),
            "has_location": bool(card.get("location")),
            "material_count": len(card.get("materials") or []),
            "needs_confirmation": list(card.get("need_confirm") or []),
        }
        for card in state.get("rule_cards", [])
    ]
    try:
        recommendation = await structured_completion(
            "fast_model",
            system_prompt=(
                "Select only the minimum useful tools for a screenshot-to-action evidence "
                "investigation. Never request code execution or external writes."
            ),
            input_payload={
                "complexity_reasons": state.get("complexity_reasons", []),
                "overall_confidence": state.get("overall_confidence", 0),
                "cards": safe_cards,
                "allowed_tools": list(TOOL_REGISTRY),
                "task_budget": settings.workflow_agent_max_tasks,
            },
            schema_name="agent_tool_plan",
            schema=schema,
            max_tokens=700,
        )
        requested = [
            tool for tool in recommendation.get("tools", [])
            if tool in TOOL_REGISTRY
        ]
        plan = create_plan(state, requested)
        plan.created_by = "fast_model"
        plan.reasons = list(dict.fromkeys(plan.reasons + recommendation.get("reasons", [])))
        return plan
    except Exception:
        baseline.reasons.append("fast-model planner degraded to deterministic policy")
        return baseline


def _public_entities(text: str) -> list[str]:
    redacted = redact_private_text(text)
    return list(dict.fromkeys(match.group(0).strip() for match in PUBLIC_ENTITY_PATTERN.finditer(redacted)))


def redact_private_text(text: str) -> str:
    result = text
    for pattern in PRIVATE_PATTERNS:
        result = pattern.sub("[REDACTED]", result)
    for word in SENSITIVE_WORDS:
        result = re.sub(rf"{re.escape(word)}\s*[:：]?\s*\S+", f"{word} [REDACTED]", result)
    return result


def safe_retrieval_query(entity: str) -> str | None:
    value = redact_private_text(entity).strip()
    if "[REDACTED]" in value or len(value) < 2 or len(value) > 80:
        return None
    if any(pattern.search(value) for pattern in PRIVATE_PATTERNS):
        return None
    return value


def _claim(
    task: AgentTask,
    claim_type: str,
    *,
    value: Any = None,
    field: str | None = None,
    action_id: str | None = None,
    confidence: float = 0.6,
    source_text: str = "",
    rationale: str = "",
    citation_url: str | None = None,
    citation_title: str | None = None,
    derived_from: list[str] | None = None,
    correlation_group: str | None = None,
) -> ToolClaim:
    start = source_text.find(str(value)) if value not in (None, "", []) else -1
    return ToolClaim(
        id=stable_id("claim", task.id, claim_type, field, value),
        claim_type=claim_type,
        action_id=action_id,
        field=field,
        value=value,
        confidence=confidence,
        source_text=str(value) if start >= 0 else "",
        start=start if start >= 0 else None,
        end=(start + len(str(value))) if start >= 0 else None,
        citation_url=citation_url,
        citation_title=citation_title,
        correlation_group=correlation_group or f"{task.tool}:{task.id}",
        derived_from=derived_from or [],
        rationale=rationale,
    )


async def execute_task(task: AgentTask, state: dict[str, Any]) -> AgentResult:
    handler = TOOL_REGISTRY[task.tool]
    started = time.perf_counter()
    try:
        result = await asyncio.wait_for(
            handler(task, state),
            timeout=min(task.timeout_ms / 1000, settings.workflow_agent_deadline_seconds),
        )
    except asyncio.TimeoutError:
        result = AgentResult(
            task_id=task.id,
            tool=task.tool,
            status="failed",
            failure_type="timeout",
            findings=["tool deadline exceeded"],
            idempotency_key=task.idempotency_key,
            model_tier=task.model_tier,
        )
    except Exception as error:
        result = AgentResult(
            task_id=task.id,
            tool=task.tool,
            status="failed",
            failure_type=type(error).__name__,
            findings=["tool execution degraded"],
            idempotency_key=task.idempotency_key,
            model_tier=task.model_tier,
        )
    result.duration_ms = round((time.perf_counter() - started) * 1000, 2)
    return result


async def semantic_decomposer(task: AgentTask, state: dict[str, Any]) -> AgentResult:
    text = str(state.get("ocr_text", ""))
    cards = [dict(card) for card in state.get("rule_cards", [])]
    findings: list[str] = []
    status = "completed"
    if task.model_tier in {"fast_model", "expert_model"}:
        try:
            model_cards = await extract_cards_with_model(
                text,
                task.model_tier,
                state.get("screenshot_time"),
                state.get("validation_errors"),
            )
            cards = [card.model_dump(mode="json") for card in model_cards]
        except Exception as error:
            status = "degraded"
            findings.append(f"model_fallback:{type(error).__name__}")
    if not cards:
        status = "degraded"
        findings.append("no_action_candidates")
    claims: list[ToolClaim] = []
    for card in cards:
        action_id = str(card.get("action_id") or stable_id("action", card.get("id"), card.get("title")))
        for field in ("card_type", "title", "summary"):
            if card.get(field) not in (None, "", []):
                claims.append(
                    _claim(
                        task,
                        "field",
                        action_id=action_id,
                        field=field,
                        value=card[field],
                        confidence=0.8 if not findings else 0.62,
                        source_text=text,
                        rationale="semantic action decomposition",
                        correlation_group=f"semantic:{action_id}",
                    )
                )
    return AgentResult(
        task_id=task.id,
        tool=task.tool,
        status=status,
        claims=claims,
        cards=cards,
        findings=findings,
        idempotency_key=task.idempotency_key,
        model_tier=task.model_tier,
    )


async def temporal_solver(task: AgentTask, state: dict[str, Any]) -> AgentResult:
    claims: list[ToolClaim] = []
    findings: list[str] = []
    for card in state.get("rule_cards", []):
        action_id = str(card.get("action_id") or stable_id("action", card.get("id"), card.get("title")))
        for field in ("deadline", "start_time", "end_time"):
            value = card.get(field)
            if value:
                claims.append(
                    _claim(
                        task,
                        "constraint",
                        action_id=action_id,
                        field=field,
                        value=value,
                        confidence=0.84 if field not in set(card.get("need_confirm", [])) else 0.55,
                        source_text=str(state.get("ocr_text", "")),
                        rationale="deterministic temporal parse",
                    )
                )
        if card.get("start_time") and card.get("end_time") and str(card["end_time"]) < str(card["start_time"]):
            findings.append(f"time_order:{card.get('id')}")
    return AgentResult(
        task_id=task.id,
        tool=task.tool,
        status="completed",
        claims=claims,
        findings=findings,
        idempotency_key=task.idempotency_key,
    )


async def entity_linker(task: AgentTask, state: dict[str, Any]) -> AgentResult:
    claims: list[ToolClaim] = []
    text = str(state.get("ocr_text", ""))
    for card in state.get("rule_cards", []):
        action_id = str(card.get("action_id") or stable_id("action", card.get("id"), card.get("title")))
        for field in ("location", "materials", "submit_method"):
            values = card.get(field) or []
            values = values if isinstance(values, list) else [values]
            for value in values:
                claims.append(
                    _claim(
                        task,
                        "entity",
                        action_id=action_id,
                        field=field,
                        value=value,
                        confidence=0.76,
                        source_text=text,
                        rationale="entity and resource extraction",
                    )
                )
    return AgentResult(
        task_id=task.id,
        tool=task.tool,
        status="completed",
        claims=claims,
        idempotency_key=task.idempotency_key,
    )


async def dependency_solver(task: AgentTask, state: dict[str, Any]) -> AgentResult:
    cards = [dict(card) for card in state.get("rule_cards", [])]
    claims: list[ToolClaim] = []
    findings: list[str] = []
    prepare_words = ("准备", "材料", "报名", "提交", "完成", "复习")
    for left in cards:
        for right in cards:
            if left.get("id") == right.get("id"):
                continue
            if left.get("card_type") == "task" and right.get("card_type") == "event":
                if any(word in str(left.get("title", "")) for word in prepare_words):
                    value = {
                        "source_card_id": left.get("id"),
                        "target_card_id": right.get("id"),
                        "dependency_type": "prerequisite",
                    }
                    claims.append(
                        _claim(
                            task,
                            "dependency",
                            value=value,
                            confidence=0.74,
                            rationale="task semantics indicate event prerequisite",
                        )
                    )
            left_time = left.get("start_time") or left.get("deadline")
            right_time = right.get("start_time") or right.get("deadline")
            if left_time and right_time and left_time == right_time and left.get("location") != right.get("location"):
                findings.append(f"resource_or_time_conflict:{left.get('id')}:{right.get('id')}")
    return AgentResult(
        task_id=task.id,
        tool=task.tool,
        status="completed",
        claims=claims,
        findings=sorted(set(findings)),
        idempotency_key=task.idempotency_key,
    )


async def history_retriever(task: AgentTask, state: dict[str, Any]) -> AgentResult:
    history = await asyncio.to_thread(CardRepository().list)
    claims: list[ToolClaim] = []
    for current in state.get("rule_cards", []):
        current_title = _normalize(str(current.get("title", "")))
        for previous in history[:100]:
            ratio = SequenceMatcher(None, current_title, _normalize(previous.title)).ratio()
            if ratio >= 0.86:
                claims.append(
                    _claim(
                        task,
                        "duplicate",
                        value={
                            "current_card_id": current.get("id"),
                            "historical_card_id": previous.id,
                            "historical_title": previous.title,
                        },
                        confidence=min(0.95, ratio),
                        rationale="normalized title similarity",
                        correlation_group=f"history:{previous.id}",
                    )
                )
    return AgentResult(
        task_id=task.id,
        tool=task.tool,
        status="completed",
        claims=claims,
        idempotency_key=task.idempotency_key,
    )


async def privacy_risk_analyzer(task: AgentTask, state: dict[str, Any]) -> AgentResult:
    text = str(state.get("ocr_text", ""))
    private = any(pattern.search(text) for pattern in PRIVATE_PATTERNS) or any(word in text for word in SENSITIVE_WORDS)
    promise = any(card.get("card_type") == "promise" for card in state.get("rule_cards", []))
    risk = "high" if private else ("medium" if promise else "low")
    findings = ["private_content"] if private else []
    if promise:
        findings.append("user_commitment")
    claim = _claim(
        task,
        "risk",
        value={"risk_level": risk, "retrieval_allowed": not private},
        confidence=0.94,
        rationale="local privacy policy classification",
    )
    return AgentResult(
        task_id=task.id,
        tool=task.tool,
        status="completed",
        claims=[claim],
        findings=findings,
        risk_level=risk,
        idempotency_key=task.idempotency_key,
    )


async def web_retriever(task: AgentTask, state: dict[str, Any]) -> AgentResult:
    privacy_results = [
        result for result in state.get("agent_task_results", [])
        if result.get("tool") == "privacy_risk_analyzer"
    ]
    if any(result.get("risk_level") == "high" for result in privacy_results):
        return AgentResult(
            task_id=task.id,
            tool=task.tool,
            status="skipped",
            findings=["retrieval_blocked_by_privacy_policy"],
            idempotency_key=task.idempotency_key,
        )
    sources: list[RetrievalSource] = []
    claims: list[ToolClaim] = []
    for raw_entity in task.arguments.get("entities", [])[:2]:
        query = safe_retrieval_query(str(raw_entity))
        if not query:
            continue
        params = {
            "action": "query",
            "list": "search",
            "srsearch": query,
            "format": "json",
            "utf8": 1,
            "srlimit": 3,
        }
        async with runtime.semaphores["web"]:
            response = await runtime.client.get(
                settings.web_retrieval_base_url,
                params=params,
                timeout=settings.web_retrieval_timeout_seconds,
            )
            response.raise_for_status()
        host = urlparse(str(response.url)).hostname or "wikipedia.org"
        for item in response.json().get("query", {}).get("search", [])[:3]:
            title = str(item.get("title", ""))
            summary = re.sub(r"<[^>]+>", "", str(item.get("snippet", "")))
            url = f"https://{host}/wiki/{title.replace(' ', '_')}"
            source = RetrievalSource(
                url=url,
                title=title,
                summary=summary[:300],
                retrieved_at=datetime.now(timezone.utc).isoformat(),
                query=query,
                confidence=0.62,
            )
            sources.append(source)
            claims.append(
                _claim(
                    task,
                    "retrieval",
                    value={"entity": query, "title": title, "summary": summary[:300]},
                    confidence=0.62,
                    citation_url=url,
                    citation_title=title,
                    rationale="public entity search result; not authoritative for critical fields",
                    correlation_group=f"web:{host}:{query}",
                )
            )
    return AgentResult(
        task_id=task.id,
        tool=task.tool,
        status="completed",
        claims=claims,
        retrieval_sources=sources,
        idempotency_key=task.idempotency_key,
    )


async def quality_verifier(task: AgentTask, state: dict[str, Any]) -> AgentResult:
    findings: list[str] = []
    for card in state.get("rule_cards", []):
        if not str(card.get("title", "")).strip():
            findings.append(f"missing_title:{card.get('id')}")
        if card.get("card_type") == "promise" and not (card.get("deadline") or card.get("start_time")):
            findings.append(f"missing_execution_time:{card.get('id')}")
    for result in state.get("agent_task_results", []):
        findings.extend(
            item for item in result.get("findings", [])
            if item.startswith(("time_order:", "resource_or_time_conflict:"))
        )
    model_claims: list[ToolClaim] = []
    if task.model_tier == "expert_model":
        try:
            expert_cards = await extract_cards_with_model(
                str(state.get("ocr_text", "")),
                "expert_model",
                state.get("screenshot_time"),
                findings,
            )
            expected_titles = {
                _normalize(str(card.get("title", "")))
                for card in state.get("rule_cards", [])
                if card.get("title")
            }
            expert_titles = {_normalize(card.title) for card in expert_cards}
            if expected_titles and expert_titles and not expected_titles.intersection(expert_titles):
                findings.append("expert_disagrees_on_action_identity")
            model_claims.append(
                _claim(
                    task,
                    "quality",
                    value={"expert_card_count": len(expert_cards)},
                    confidence=0.86,
                    rationale="expert-model independent structured extraction",
                    correlation_group=f"expert-verifier:{task.id}",
                )
            )
        except Exception as error:
            findings.append(f"expert_verifier_degraded:{type(error).__name__}")
    return AgentResult(
        task_id=task.id,
        tool=task.tool,
        status="completed" if not findings else "degraded",
        claims=[
            _claim(
                task,
                "quality",
                value={"passed": not findings, "errors": sorted(set(findings))},
                confidence=0.9,
                rationale="independent completion gate",
                derived_from=[
                    claim.get("id")
                    for result in state.get("agent_task_results", [])
                    for claim in result.get("claims", [])
                ][:40],
            ),
            *model_claims,
        ],
        findings=sorted(set(findings)),
        idempotency_key=task.idempotency_key,
        model_tier=task.model_tier,
    )


def verify_results(state: dict[str, Any]) -> VerificationSummary:
    results = state.get("agent_task_results", [])
    if (
        not results
        and state.get("route") == "rules"
        and float(state.get("overall_confidence", 0)) >= 0.85
        and not state.get("validation_errors")
    ):
        return VerificationSummary(
            passed=True,
            evidence_coverage=1.0,
            reason="high-confidence deterministic evidence passed the fast-path gate",
        )
    completed_tools = {
        result.get("tool")
        for result in results
        if result.get("status") in {"completed", "degraded", "skipped"}
    }
    errors = sorted(
        {
            finding
            for result in results
            for finding in result.get("findings", [])
            if finding.startswith(("missing_", "time_order:", "resource_or_time_conflict:"))
        }
    )
    required = {"privacy_risk_analyzer", "quality_verifier"}
    missing_tools = required - completed_tools
    claims = [claim for result in results for claim in result.get("claims", [])]
    critical_cards = state.get("rule_cards", [])
    critical_total = max(1, len(critical_cards) * 2)
    covered = sum(
        1
        for card in critical_cards
        for field in ("title", "deadline" if card.get("card_type") in {"task", "promise"} else "start_time")
        if card.get(field)
    )
    coverage = min(1.0, covered / critical_total)
    unresolved = list(errors)
    if not critical_cards:
        unresolved.append("no_action_candidates")
    if missing_tools:
        unresolved.extend(f"missing_tool:{tool}" for tool in sorted(missing_tools))
    if not claims:
        unresolved.append("no_independent_evidence")
    recommendations: list[ToolName] = []
    if any("time" in item for item in unresolved):
        recommendations.append("temporal_solver")
    if any("resource" in item for item in unresolved):
        recommendations.append("dependency_solver")
    if coverage < 0.6:
        recommendations.append("semantic_decomposer")
    passed = not unresolved and coverage >= 0.5
    return VerificationSummary(
        passed=passed,
        evidence_coverage=round(coverage, 3),
        constraint_errors=errors,
        unresolved_evidence=sorted(set(unresolved)),
        recommended_tasks=list(dict.fromkeys(recommendations)),
        requires_review=not passed,
        reason="evidence and constraints passed" if passed else "additional evidence or user review required",
    )


def _normalize(value: str) -> str:
    return re.sub(r"[^\w\u4e00-\u9fff]+", "", value.lower())


TOOL_REGISTRY: dict[ToolName, ToolHandler] = {
    "semantic_decomposer": semantic_decomposer,
    "temporal_solver": temporal_solver,
    "entity_linker": entity_linker,
    "dependency_solver": dependency_solver,
    "history_retriever": history_retriever,
    "privacy_risk_analyzer": privacy_risk_analyzer,
    "web_retriever": web_retriever,
    "quality_verifier": quality_verifier,
}
