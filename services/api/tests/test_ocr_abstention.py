from app.services.ocr_quality import adjudicate_candidates


def test_low_quality_ocr_abstains_with_explicit_reason() -> None:
    result = adjudicate_candidates([{"engine": "mlkit", "text": "\ufffd\ufffd 5G 12:04", "confidence": 0.99}])
    assert result.requires_review
    assert "low_ocr_quality" in result.review_reasons or "garbled_characters" in result.review_reasons


def test_critical_candidate_conflict_is_unconditional_review() -> None:
    result = adjudicate_candidates([
        {"engine": "a", "text": "submit report 6/10 18:00", "confidence": 1.0},
        {"engine": "b", "text": "submit report 6/11 18:00", "confidence": 0.1},
    ])
    assert result.requires_review
    assert result.critical_conflicts
