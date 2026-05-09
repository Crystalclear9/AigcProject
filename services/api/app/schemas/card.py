from __future__ import annotations

from datetime import datetime
from typing import Literal

from pydantic import BaseModel, Field

CardType = Literal["task", "event", "promise", "note"]
CardStatus = Literal["draft", "confirmed", "done", "archived"]
Priority = Literal["low", "normal", "high"]


class ActionCardBase(BaseModel):
    card_type: CardType = "task"
    title: str
    summary: str = ""
    deadline: str | None = None
    start_time: str | None = None
    end_time: str | None = None
    location: str | None = None
    materials: list[str] = Field(default_factory=list)
    submit_method: str | None = None
    priority: Priority = "normal"
    tags: list[str] = Field(default_factory=list)
    reminders: list[str] = Field(default_factory=list)
    need_confirm: list[str] = Field(default_factory=list)
    status: CardStatus = "draft"
    source_text: str = ""


class ActionCardCreate(ActionCardBase):
    id: str | None = None


class ActionCardUpdate(BaseModel):
    card_type: CardType | None = None
    title: str | None = None
    summary: str | None = None
    deadline: str | None = None
    start_time: str | None = None
    end_time: str | None = None
    location: str | None = None
    materials: list[str] | None = None
    submit_method: str | None = None
    priority: Priority | None = None
    tags: list[str] | None = None
    reminders: list[str] | None = None
    need_confirm: list[str] | None = None
    status: CardStatus | None = None
    source_text: str | None = None


class ActionCard(ActionCardBase):
    id: str
    created_at: datetime


class AnalyzeScreenshotTextRequest(BaseModel):
    text: str = Field(min_length=1)
    screenshot_time: str | None = None


class AnalyzeScreenshotTextResponse(BaseModel):
    ocr_text: str
    cards: list[ActionCard]
    preview_actions: list[str]
    engine: Literal["lanxin", "rules"]
