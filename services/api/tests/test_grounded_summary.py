from app.services.text_integrity import evaluate_summary_quality


def test_summary_rejects_unsupported_fact_when_evidence_is_available() -> None:
    report = evaluate_summary_quality("Submit the report tomorrow", evidence_spans=["Submit the report by Friday"])
    assert report.evidence_coverage == 0
    assert "unsupported_summary" in report.reasons


def test_ui_noise_is_rejected_from_summary() -> None:
    report = evaluate_summary_quality("5G 12:04 Submit report", evidence_spans=["Submit report"])
    assert "ui_noise" in report.reasons
