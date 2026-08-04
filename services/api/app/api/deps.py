from __future__ import annotations

from app.repositories.cards import CardRepository
from app.repositories.teams import TeamRepository

_card_repository = CardRepository()
_team_repository = TeamRepository()


def get_card_repository() -> CardRepository:
    return _card_repository


def get_team_repository() -> TeamRepository:
    return _team_repository
