from __future__ import annotations

from app.repositories.cards import CardRepository

_card_repository = CardRepository()


def get_card_repository() -> CardRepository:
    return _card_repository
