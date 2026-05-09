from __future__ import annotations

from app.db.session import CardRepository

_card_repository = CardRepository()


def get_card_repository() -> CardRepository:
    return _card_repository
