from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field, field_validator

OnboardingPhase = Literal["core_feedback", "followup", "final_summary"]
QuestionTopic = Literal[
    "work_rhythm",
    "buffer_preference",
    "weekend_policy",
    "assistant_tone",
]
MascotMood = Literal["idle", "focus", "confirm", "complete"]
AnimationHint = Literal["breathe", "scan", "peek", "celebrate"]

ALLOWED_ANSWERS: dict[str, set[str]] = {
    "scenario": {"study", "office", "life", "mixed"},
    "active_period": {"morning", "afternoon", "evening", "flexible"},
    "planning_granularity": {"concise", "balanced", "detailed"},
    "reminder_style": {"key_only", "standard", "multi_stage"},
    "work_rhythm": {"steady", "sprint", "adaptive"},
    "buffer_preference": {"compact", "standard", "generous"},
    "weekend_policy": {"avoid", "allow", "flexible"},
    "assistant_tone": {"concise", "warm", "coach"},
}


class OnboardingOption(BaseModel):
    id: str = Field(min_length=1, max_length=40)
    label: str = Field(min_length=1, max_length=20)


class OnboardingQuestion(BaseModel):
    id: str = Field(min_length=1, max_length=60)
    topic: QuestionTopic
    prompt: str = Field(min_length=1, max_length=80)
    options: list[OnboardingOption] = Field(min_length=2, max_length=4)

    @field_validator("options")
    @classmethod
    def validate_options(
        cls,
        options: list[OnboardingOption],
        info,
    ) -> list[OnboardingOption]:
        topic = info.data.get("topic")
        allowed = ALLOWED_ANSWERS.get(str(topic), set())
        ids = [option.id for option in options]
        if len(ids) != len(set(ids)) or any(option_id not in allowed for option_id in ids):
            raise ValueError("question contains unsupported or duplicate options")
        return options


class OnboardingTurnRequest(BaseModel):
    session_id: str = Field(min_length=8, max_length=80)
    phase: OnboardingPhase
    current_step: str = Field(default="", max_length=60)
    answers: dict[str, str] = Field(default_factory=dict, max_length=12)
    answered_followups: list[str] = Field(default_factory=list, max_length=4)
    locale: str = Field(default="zh-CN", max_length=20)
    timezone: str = Field(default="Asia/Shanghai", max_length=80)
    max_followups: int = Field(default=3, ge=0, le=3)

    @field_validator("answers")
    @classmethod
    def validate_answers(cls, answers: dict[str, str]) -> dict[str, str]:
        safe: dict[str, str] = {}
        for key, value in answers.items():
            if key not in ALLOWED_ANSWERS or value not in ALLOWED_ANSWERS[key]:
                raise ValueError(f"unsupported onboarding answer: {key}")
            safe[key] = value
        return safe

    @field_validator("answered_followups")
    @classmethod
    def validate_followups(cls, topics: list[str]) -> list[str]:
        allowed = set(ALLOWED_ANSWERS) - {
            "scenario",
            "active_period",
            "planning_granularity",
            "reminder_style",
        }
        if any(topic not in allowed for topic in topics):
            raise ValueError("unsupported follow-up topic")
        return list(dict.fromkeys(topics))


class OnboardingTurnResponse(BaseModel):
    request_id: str
    assistant_message: str = Field(max_length=80)
    next_question: OnboardingQuestion | None = None
    mood: MascotMood = "focus"
    animation_hint: AnimationHint = "scan"
    complete: bool = False
    profile_patch: dict[str, str] = Field(default_factory=dict)
    provider_usage: dict[str, dict] = Field(default_factory=dict)
    enhancement_status: Literal["not_configured", "succeeded", "degraded"] = (
        "not_configured"
    )

