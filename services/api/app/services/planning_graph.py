from __future__ import annotations

from typing import Any, TypedDict

from langgraph.graph import END, START, StateGraph

from app.schemas.card import ActionCard
from app.schemas.card_refinement import (
    CardRefinementPlan,
    RefinementOptions,
    UserProfileContext,
)
from app.schemas.intake import CardReplanRequest
from app.services.card_refinement_graph import deterministic_plan, validate_plan
from app.services.priority_engine import replan_priority


class PlanningState(TypedDict, total=False):
    card: ActionCard
    request: CardReplanRequest
    profile: UserProfileContext | None
    priority_card: ActionCard
    plan: CardRefinementPlan
    calendar_actions: list[dict[str, Any]]
    verification_summary: str
    warnings: list[str]


def calculate_priority(state: PlanningState) -> dict[str, Any]:
    return {"priority_card": replan_priority(state["card"], state["request"])}


def create_plan(state: PlanningState) -> dict[str, Any]:
    card = state.get("priority_card", state["card"])
    profile = state.get("profile")
    plan = deterministic_plan(
        card,
        options=RefinementOptions(
            granularity=profile.planning_granularity if profile else "balanced",
            use_profile=profile is not None,
        ),
        profile=profile,
        evidence_summary=card.evidence_summary,
        instruction="根据卡片变化进行轻量重规划",
    )
    return {"plan": plan}


def propose_device_actions(state: PlanningState) -> dict[str, Any]:
    card = state.get("priority_card", state["card"])
    plan = state["plan"]
    actions: list[dict[str, Any]] = []
    for item in plan.items:
        event_time = item.start_time or item.deadline
        if not event_time:
            continue
        if item.kind == "work_block":
            actions.append(
                {
                    "id": f"calendar:{item.id}",
                    "type": "calendar_event",
                    "title": item.title,
                    "start_time": event_time,
                    "location": card.location,
                    "description": item.description,
                    "requires_confirmation": True,
                }
            )
        elif item.kind == "milestone" and item.reminder_enabled:
            actions.append(
                {
                    "id": f"reminder:{item.id}",
                    "type": "app_reminder",
                    "title": item.title,
                    "start_time": event_time,
                    "description": item.description,
                    "requires_confirmation": True,
                }
            )
    return {"calendar_actions": actions}


def verify(state: PlanningState) -> dict[str, Any]:
    card = state.get("priority_card", state["card"])
    plan = state["plan"]
    errors = validate_plan(plan, card, state.get("profile"))
    summary = (
        "约束通过，所有设备动作仍需用户确认"
        if not errors
        else f"发现 {len(errors)} 项约束冲突，禁止静默应用"
    )
    return {
        "plan": plan.model_copy(update={"constraint_errors": errors}),
        "verification_summary": summary,
        "warnings": errors,
    }


def build_planning_graph():
    graph = StateGraph(PlanningState)
    graph.add_node("priority", calculate_priority)
    graph.add_node("plan", create_plan)
    graph.add_node("device_actions", propose_device_actions)
    graph.add_node("verify", verify)
    graph.add_edge(START, "priority")
    graph.add_edge("priority", "plan")
    graph.add_edge("plan", "device_actions")
    graph.add_edge("device_actions", "verify")
    graph.add_edge("verify", END)
    return graph.compile()
