from __future__ import annotations

import unittest

from app.services.rule_extractor import extract_cards_with_rules


def labeled_corpus() -> list[dict[str, object]]:
    task_objects = ["实验报告", "课程作业", "报名表", "作品说明书", "申请材料"]
    task_patterns = [
        "请在6月10日22:00前提交{item}。",
        "务必于6月11日18:00前上传{item}。",
        "{item}截止时间为6月12日20:00，请及时提交。",
        "请完成{item}并在6月13日17:00前提交。",
        "6月14日19:00前填写并提交{item}。",
    ]
    event_names = ["课程会议", "社团活动", "专题讲座", "项目组会", "期末考试"]
    event_patterns = [
        "本周六下午3点在大学生活动中心参加{name}。",
        "下周一上午9点在教学楼召开{name}。",
        "请于本周三下午2点到会议室参加{name}。",
        "{name}安排在下周五上午10点，请准时参加。",
        "本周日下午4点在报告厅举行{name}。",
    ]
    promise_objects = ["表格", "报告", "文件", "材料", "作业"]
    promise_patterns = [
        "可以，我明天上午把{item}发给老师。",
        "我答应明天下午提交{item}。",
        "可以，我明天晚上把{item}发送到邮箱。",
        "我承诺明天上午完成并发送{item}。",
        "可以，我明天下午把{item}交给负责人。",
    ]
    note_objects = ["课程复习资料", "阅读清单", "知识点摘要", "参考文献", "学习笔记"]
    note_patterns = [
        "这是{item}，请保存，之后查看。",
        "收藏这份{item}，以后复习使用。",
        "{item}仅供查阅，请妥善保存。",
        "整理并保存{item}，方便以后查找。",
        "这份{item}很有用，先保存下来。",
    ]

    samples: list[dict[str, object]] = []
    for pattern in task_patterns:
        for item in task_objects:
            samples.append({"text": pattern.format(item=item), "type": "task", "time_field": "deadline"})
    for pattern in event_patterns:
        for name in event_names:
            samples.append({"text": pattern.format(name=name), "type": "event", "time_field": "start_time"})
    for pattern in promise_patterns:
        for item in promise_objects:
            samples.append({"text": pattern.format(item=item), "type": "promise", "time_field": "deadline"})
    for pattern in note_patterns:
        for item in note_objects:
            samples.append({"text": pattern.format(item=item), "type": "note", "time_field": None})
    return samples


class QualityBenchmarkTest(unittest.TestCase):
    def test_hundred_distinct_labeled_examples_meet_quality_floor(self) -> None:
        corpus = labeled_corpus()
        type_correct = 0
        key_field_correct = 0
        for sample in corpus:
            card = extract_cards_with_rules(
                str(sample["text"]),
                "2026-06-07T10:00:00+08:00",
            )[0]
            type_correct += card.card_type == sample["type"]
            time_field = sample["time_field"]
            key_field_correct += bool(getattr(card, str(time_field))) if time_field else bool(card.title)

        type_f1 = type_correct / len(corpus)
        key_field_accuracy = key_field_correct / len(corpus)
        self.assertEqual(len(corpus), 100)
        self.assertEqual(len({str(sample["text"]) for sample in corpus}), 100)
        self.assertGreaterEqual(type_f1, 0.90)
        self.assertGreaterEqual(key_field_accuracy, 0.85)


if __name__ == "__main__":
    unittest.main()
