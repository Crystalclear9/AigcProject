from app.services.ocr_quality import adjudicate_candidates, evaluate_candidate


def test_garbled_ocr_is_scored_below_complete_action_text() -> None:
    garbled = evaluate_candidate(
        {
            "engine": "mlkit",
            "confidence": 0.99,
            "text": "提□交□□报�告 6月?? 22:00 学□通",
        }
    )
    complete = evaluate_candidate(
        {
            "engine": "vivo-ocr",
            "confidence": 0.5,
            "text": "请在6月10日22:00前通过学习通提交实验报告",
        }
    )
    assert complete["quality_score"] > garbled["quality_score"]
    assert "garbled_characters" in garbled["quality_report"]["reasons"]


def test_provider_confidence_cannot_override_measured_quality() -> None:
    result = adjudicate_candidates(
        [
            {
                "engine": "mlkit",
                "confidence": 0.99,
                "text": "□�□�□",
            },
            {
                "engine": "vivo-ocr",
                "confidence": 0.4,
                "text": "周五18:00前发送报名表到team@example.com",
            },
        ]
    )
    assert result.selected["engine"] == "vivo-ocr"


def test_conflicting_critical_times_require_review() -> None:
    result = adjudicate_candidates(
        [
            {
                "engine": "mlkit",
                "text": "请在6月10日22:00前通过学习通提交实验报告",
                "confidence": 0.8,
            },
            {
                "engine": "vivo-ocr",
                "text": "请在6月11日22:00前通过学习通提交实验报告",
                "confidence": 0.8,
            },
        ]
    )
    assert result.requires_review is True
    assert result.critical_conflicts
    assert "critical_field_conflict" in result.review_reasons


def test_similar_candidates_can_contribute_complementary_evidence() -> None:
    result = adjudicate_candidates(
        [
            {
                "engine": "mlkit",
                "text": "请在6月10日22:00前提交实验报告",
                "confidence": 0.8,
            },
            {
                "engine": "vivo-ocr",
                "text": "请在6月10日22:00前提交实验报告\n提交平台：学习通",
                "confidence": 0.8,
            },
        ]
    )
    assert "学习通" in result.merged_text
    assert not result.critical_conflicts


def test_absolute_android_block_coordinates_are_normalized() -> None:
    result = evaluate_candidate(
        {
            "engine": "mlkit:contrast",
            "text": "6月10日22:01前提交实验报告\n平台：学习通",
            "image_width": 1080,
            "image_height": 2400,
            "blocks": [
                {"text": "6月10日22:01前提交实验报告", "left": 120, "top": 800, "right": 920, "bottom": 900},
                {"text": "平台：学习通", "left": 120, "top": 940, "right": 520, "bottom": 1010},
            ],
        }
    )
    assert result["blocks"][0]["top"] == 800 / 2400
    assert result["blocks"][1]["left"] == 120 / 1080
    assert result["quality_report"]["layout_score"] > 0.6
