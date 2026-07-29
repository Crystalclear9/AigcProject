from __future__ import annotations

import asyncio
import uuid
from typing import Any

from pydantic import ValidationError

from app.core.config import settings
from app.schemas.onboarding import (
    ALLOWED_ANSWERS,
    OnboardingOption,
    OnboardingQuestion,
    OnboardingTurnRequest,
    OnboardingTurnResponse,
)
from app.services.llm_client import structured_completion
from app.services.provider_runtime import provider_usage_delta, runtime

FOLLOWUP_COPY: dict[str, tuple[str, list[tuple[str, str]]]] = {
    "work_rhythm": (
        "你更习惯怎样推进一项重要任务？",
        [("steady", "均匀推进"), ("sprint", "集中冲刺"), ("adaptive", "动态安排")],
    ),
    "buffer_preference": (
        "你希望在截止前预留多少缓冲？",
        [("compact", "紧凑"), ("standard", "标准"), ("generous", "宽裕")],
    ),
    "weekend_policy": (
        "规划可以占用周末时间吗？",
        [("avoid", "尽量避免"), ("allow", "可以安排"), ("flexible", "视任务而定")],
    ),
    "assistant_tone": (
        "你希望墨斐怎样陪你推进？",
        [("concise", "简洁直接"), ("warm", "温暖克制"), ("coach", "教练式")],
    ),
}

TURN_SCHEMA: dict[str, Any] = {
    "type": "object",
    "additionalProperties": False,
    "required": [
        "assistant_message",
        "next_topic",
        "prompt",
        "option_ids",
        "mood",
        "animation_hint",
        "complete",
    ],
    "properties": {
        "assistant_message": {"type": "string"},
        "next_topic": {
            "type": ["string", "null"],
            "enum": [
                "work_rhythm",
                "buffer_preference",
                "weekend_policy",
                "assistant_tone",
                None,
            ],
        },
        "prompt": {"type": "string"},
        "option_ids": {
            "type": "array",
            "minItems": 0,
            "maxItems": 4,
            "items": {"type": "string"},
        },
        "mood": {"type": "string", "enum": ["idle", "focus", "confirm", "complete"]},
        "animation_hint": {
            "type": "string",
            "enum": ["breathe", "scan", "peek", "celebrate"],
        },
        "complete": {"type": "boolean"},
    },
}

SYSTEM_PROMPT = """
你是随手办的首次使用引导助手“墨斐”。只返回 JSON，不输出思维链。
你可以用一句不超过 24 个汉字的日常口吻回应用户，并从白名单主题选择下一道结构化问题。
禁止询问身份、年龄、性别、职业、健康、联系方式、住址、收入或任何自由文本。
问题必须有 2 到 4 个白名单选项，不得重复已回答主题。核心问题尚未全部完成时不得追问。
不要提及 AI、模型、智能、画像、个性化、技术机制或不存在的能力，不使用 emoji。
语气自然克制，像手机应用里的简短反馈。
""".strip()

USER_COPY_BANNED_TERMS = (
    "AI",
    "ai",
    "智能",
    "模型",
    "画像",
    "个性化",
    "赋能",
    "理解你",
    "高效",
    "授权",
    "绝不泄露",
    "准备就绪",
)


async def run_onboarding_turn(
    request: OnboardingTurnRequest,
) -> OnboardingTurnResponse:
    request_id = str(uuid.uuid4())
    baseline = runtime.snapshot()
    if not settings.has_fast_model_config:
        return _fallback_response(request, request_id, baseline, "not_configured")
    try:
        result = await asyncio.wait_for(
            structured_completion(
                "fast_model",
                system_prompt=SYSTEM_PROMPT,
                input_payload={
                    "phase": request.phase,
                    "current_step": request.current_step,
                    "answers": request.answers,
                    "answered_followups": request.answered_followups,
                    "available_followups": _available_topics(request),
                    "max_followups": request.max_followups,
                    "locale": request.locale,
                    "timezone": request.timezone,
                },
                schema_name="onboarding_turn",
                schema=TURN_SCHEMA,
                max_tokens=420,
            ),
            timeout=8.0,
        )
        response = _validated_model_response(request, request_id, result)
        return response.model_copy(
            update={
                "provider_usage": provider_usage_delta(baseline),
                "enhancement_status": "succeeded",
            }
        )
    except (asyncio.TimeoutError, ValidationError, ValueError, KeyError, TypeError):
        return _fallback_response(request, request_id, baseline, "degraded")
    except Exception:
        return _fallback_response(request, request_id, baseline, "degraded")


def _validated_model_response(
    request: OnboardingTurnRequest,
    request_id: str,
    result: dict[str, Any],
) -> OnboardingTurnResponse:
    topic = result.get("next_topic")
    question: OnboardingQuestion | None = None
    available = _available_topics(request)
    if request.phase == "followup" and topic is not None:
        if topic not in available:
            raise ValueError("model selected an unavailable topic")
        canonical_prompt, canonical_options = FOLLOWUP_COPY[topic]
        requested = [str(value) for value in result.get("option_ids", [])]
        allowed = ALLOWED_ANSWERS[topic]
        selected_ids = [value for value in requested if value in allowed]
        if len(selected_ids) < 2:
            selected_ids = [value for value, _ in canonical_options]
        label_by_id = dict(canonical_options)
        question = OnboardingQuestion(
            id=f"followup-{topic}",
            topic=topic,
            # The model chooses a relevant topic, while user-facing copy remains reviewed.
            prompt=canonical_prompt,
            options=[
                OnboardingOption(id=value, label=label_by_id[value])
                for value in selected_ids[:4]
            ],
        )
    complete = bool(result.get("complete")) or (
        request.phase != "core_feedback" and question is None
    )
    message = _reviewed_assistant_message(request, topic, complete)
    return OnboardingTurnResponse(
        request_id=request_id,
        assistant_message=message,
        next_question=question,
        mood=str(result.get("mood", "focus")),
        animation_hint=str(result.get("animation_hint", "scan")),
        complete=complete,
        profile_patch=_profile_patch(request.answers),
    )


def _fallback_response(
    request: OnboardingTurnRequest,
    request_id: str,
    baseline: dict[str, dict[str, Any]],
    status: str,
) -> OnboardingTurnResponse:
    question: OnboardingQuestion | None = None
    if request.phase == "followup":
        topics = _available_topics(request)
        if topics:
            topic = topics[0]
            prompt, options = FOLLOWUP_COPY[topic]
            question = OnboardingQuestion(
                id=f"followup-{topic}",
                topic=topic,
                prompt=prompt,
                options=[
                    OnboardingOption(id=option_id, label=label)
                    for option_id, label in options
                ],
            )
    messages = {
        "scenario": "好，我会按这个场景安排。",
        "active_period": "好，任务会优先放在这个时段。",
        "planning_granularity": "好，步骤会保持这个详细程度。",
        "reminder_style": "好，提醒会按这个方式出现。",
    }
    complete = request.phase == "final_summary" or (
        request.phase == "followup" and question is None
    )
    return OnboardingTurnResponse(
        request_id=request_id,
        assistant_message=(
            "设置好了，之后也能随时修改。"
            if complete
            else messages.get(request.current_step, "再选一项就好。")
        ),
        next_question=question,
        mood="complete" if complete else "confirm",
        animation_hint="celebrate" if complete else "peek",
        complete=complete,
        profile_patch=_profile_patch(request.answers),
        provider_usage=provider_usage_delta(baseline),
        enhancement_status=status,
    )


def _available_topics(request: OnboardingTurnRequest) -> list[str]:
    core = {"scenario", "active_period", "planning_granularity", "reminder_style"}
    if not core.issubset(request.answers):
        return []
    answered = set(request.answered_followups) | (set(request.answers) - core)
    remaining = [topic for topic in FOLLOWUP_COPY if topic not in answered]
    limit = max(0, request.max_followups - len(request.answered_followups))
    return remaining[:limit]


def _profile_patch(answers: dict[str, str]) -> dict[str, str]:
    return {
        key: value
        for key, value in answers.items()
        if key in ALLOWED_ANSWERS and value in ALLOWED_ANSWERS[key]
    }


def _safe_assistant_message(
    request: OnboardingTurnRequest,
    raw_message: Any,
) -> str:
    message = " ".join(str(raw_message or "").split())
    fallback = {
        "scenario": "好，我会按这个场景安排。",
        "active_period": "好，任务会优先放在这个时段。",
        "planning_granularity": "好，步骤会保持这个详细程度。",
        "reminder_style": "好，提醒会按这个方式出现。",
    }.get(request.current_step, "再选一项就好。")
    if not message or len(message) > 24:
        return fallback
    if any(term in message for term in USER_COPY_BANNED_TERMS):
        return fallback
    if any(ord(char) > 0xFFFF for char in message):
        return fallback
    return message


def _reviewed_assistant_message(
    request: OnboardingTurnRequest,
    topic: str | None,
    complete: bool,
) -> str:
    if complete:
        return "设置好了，之后也能随时修改。"
    if request.phase == "core_feedback":
        return {
            "scenario": "好，我会按这个场景安排。",
            "active_period": "好，任务会优先放在这个时段。",
            "planning_granularity": "好，步骤会保持这个详细程度。",
            "reminder_style": "好，提醒会按这个方式出现。",
        }.get(request.current_step, "继续。")
    return {
        "work_rhythm": "再说说你通常怎么推进任务。",
        "buffer_preference": "再选一下你习惯的预留时间。",
        "weekend_policy": "周末要不要留给任务？",
        "assistant_tone": "最后，选一种你舒服的陪伴方式。",
    }.get(topic, "再选一项就好。")
