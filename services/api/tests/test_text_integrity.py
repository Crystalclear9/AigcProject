from app.services.extraction_context import preprocess_ocr_text
from app.services.text_integrity import (
    choose_better_summary,
    compose_evidence_summary,
    evaluate_summary_quality,
    evaluate_text_integrity,
)


def test_random_identifier_and_ui_chrome_are_not_accepted_as_summary() -> None:
    value = "22:29 未分类 欢迎来到原子笔记 V2-CR22k3zM_OVq7CS"
    report = evaluate_summary_quality(value)
    assert report.acceptable is False
    assert "random_identifier" in report.reasons
    assert "ui_noise" in report.reasons


def test_evidence_summary_is_composed_from_fields_not_raw_ocr() -> None:
    summary = compose_evidence_summary(
        title="提交实验报告",
        deadline="2026-08-12T22:00:00+08:00",
        location="学习通",
        materials=["实验报告"],
        evidence_spans=["请在8月12日22:00前通过学习通提交实验报告"],
    )
    assert "提交实验报告" in summary
    assert "学习通" in summary
    assert "欢迎来到" not in summary


def test_clean_incoming_summary_replaces_garbled_local_summary() -> None:
    selected = choose_better_summary(
        "锟斤拷 22:29 V2-CR22k3zM_OVq7CS",
        "8月12日22:00前提交实验报告至学习通",
    )
    assert selected == "8月12日22:00前提交实验报告至学习通"


def test_reversible_repair_never_changes_digits() -> None:
    report = evaluate_text_integrity("鎻愪氦 2026-08-12 22:01")
    assert "2026-08-12" in report.text
    assert "22:01" in report.text


def test_chat_action_and_submit_channel_are_not_removed_as_ui_noise() -> None:
    text = "我会在今晚22:00前发送实验报告到群文件。\n发送\n输入消息"

    cleaned = preprocess_ocr_text(text)

    assert "发送实验报告到群文件" in cleaned
    assert "输入消息" not in cleaned
