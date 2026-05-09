from __future__ import annotations

from fastapi import APIRouter

from app.services.demo_scenarios import evaluate_demo_scenarios, scenario_catalog

router = APIRouter()


@router.get("/scenarios", summary="List built-in demo scenarios")
def list_demo_scenarios() -> list[dict[str, object]]:
    return scenario_catalog()


@router.get("/evaluate", summary="Evaluate built-in demo scenarios")
def evaluate_demo() -> dict[str, object]:
    return evaluate_demo_scenarios()
