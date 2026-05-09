from __future__ import annotations

from app.schemas.card import ActionCard
from app.services.demo_scenarios import evaluate_demo_scenarios


def build_summary(cards: list[ActionCard]) -> dict[str, object]:
    active_cards = [card for card in cards if card.status not in {"done", "archived"}]
    confirmed_cards = [card for card in cards if card.status == "confirmed"]
    reminders = sum(len(card.reminders) for card in cards)
    need_confirm = sum(len(card.need_confirm) for card in cards)
    card_types = {card.card_type for card in cards}

    return {
        "cards_total": len(cards),
        "cards_active": len(active_cards),
        "cards_confirmed": len(confirmed_cards),
        "reminders_total": reminders,
        "need_confirm_fields": need_confirm,
        "card_type_coverage": sorted(card_types),
        "estimated_minutes_saved": round(len(cards) * 2.5, 1),
        "demo_eval": evaluate_demo_scenarios(),
    }
