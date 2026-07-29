from __future__ import annotations

import asyncio

import pytest
from pydantic import ValidationError

from app.schemas.onboarding import OnboardingTurnRequest
from app.services.onboarding_service import (
    _safe_assistant_message,
    _validated_model_response,
    run_onboarding_turn,
)


def _request(**updates) -> OnboardingTurnRequest:
    payload = {
        "session_id": "session-12345678",
        "phase": "followup",
        "current_step": "reminder_style",
        "answers": {
            "scenario": "study",
            "active_period": "evening",
            "planning_granularity": "detailed",
            "reminder_style": "standard",
        },
        "answered_followups": [],
        "max_followups": 3,
    }
    payload.update(updates)
    return OnboardingTurnRequest(**payload)


def test_onboarding_rejects_sensitive_or_unknown_answer_fields() -> None:
    with pytest.raises(ValidationError):
        _request(answers={"age": "18"})


def test_model_followup_is_constrained_to_topic_and_option_whitelists() -> None:
    response = _validated_model_response(
        _request(),
        "request-1",
        {
            "assistant_message": "我会把重要节点安排得更从容。",
            "next_topic": "buffer_preference",
            "prompt": "希望预留多少提交缓冲？",
            "option_ids": ["compact", "standard", "generous"],
            "mood": "confirm",
            "animation_hint": "peek",
            "complete": False,
        },
    )

    assert response.next_question is not None
    assert response.next_question.topic == "buffer_preference"
    assert 2 <= len(response.next_question.options) <= 4


def test_model_cannot_repeat_an_answered_followup() -> None:
    with pytest.raises(ValueError):
        _validated_model_response(
            _request(answered_followups=["work_rhythm"]),
            "request-2",
            {
                "assistant_message": "继续。",
                "next_topic": "work_rhythm",
                "prompt": "怎样推进？",
                "option_ids": ["steady", "sprint"],
                "mood": "focus",
                "animation_hint": "scan",
                "complete": False,
            },
        )


def test_model_copy_is_replaced_when_it_exposes_ai_language_or_emoji() -> None:
    request = _request(phase="core_feedback", current_step="scenario")

    assert _safe_assistant_message(request, "AI 已理解你，会智能规划 🤖") == (
        "好，我会按这个场景安排。"
    )


def test_model_cannot_invent_user_facing_followup_copy() -> None:
    response = _validated_model_response(
        _request(),
        "request-copy-guard",
        {
            "assistant_message": "那就再选一项。",
            "next_topic": "buffer_preference",
            "prompt": "让 AI 猜猜你要留几天？",
            "option_ids": ["compact", "standard", "generous"],
            "mood": "focus",
            "animation_hint": "scan",
            "complete": False,
        },
    )

    assert response.next_question is not None
    assert response.next_question.prompt == "你希望在截止前预留多少缓冲？"
    assert response.assistant_message == "再选一下你习惯的预留时间。"


def test_unconfigured_model_uses_deterministic_structured_fallback() -> None:
    response = asyncio.run(run_onboarding_turn(_request()))

    assert response.next_question is not None
    assert response.next_question.topic == "work_rhythm"
    assert response.enhancement_status in {"not_configured", "degraded"}
    assert "request_count" not in str(response.profile_patch)
