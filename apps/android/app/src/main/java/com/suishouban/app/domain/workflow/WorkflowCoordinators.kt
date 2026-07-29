package com.suishouban.app.domain.workflow

import com.suishouban.app.data.model.ActionCard
import com.suishouban.app.data.model.PriorityModes
import com.suishouban.app.data.model.ReminderModes
import com.suishouban.app.data.model.effectiveReminderNodes
import com.suishouban.app.domain.ocr.OcrArbitrationResult
import com.suishouban.app.domain.ocr.OcrCandidate
import com.suishouban.app.domain.ocr.OcrRaceController
import java.time.OffsetDateTime

object OcrCoordinator {
    fun adjudicate(candidates: List<OcrCandidate>): OcrArbitrationResult? =
        OcrRaceController.arbitrate(candidates)
}

object TemporalCoordinator {
    fun validationErrors(card: ActionCard, now: OffsetDateTime = OffsetDateTime.now()): List<String> {
        val start = parse(card.startTime)
        val end = parse(card.endTime)
        val deadline = parse(card.deadline)
        return buildList {
            if (start != null && end != null && !end.isAfter(start)) {
                add("结束时间必须晚于开始时间")
            }
            card.effectiveReminderNodes().filter { it.enabled }.forEach { node ->
                if (node.mode == ReminderModes.ABSOLUTE) {
                    val instant = parse(node.absoluteTime)
                    if (instant == null) add("存在无法解析的提醒时间")
                    else {
                        if (!instant.isAfter(now)) add("提醒时间必须晚于当前时间")
                        if (deadline != null && instant.isAfter(deadline)) {
                            add("提醒时间不能晚于截止时间")
                        }
                    }
                } else if ((node.offsetMinutes ?: 0L) <= 0L) {
                    add("截止前提醒至少提前 1 分钟")
                }
            }
        }.distinct()
    }

    private fun parse(value: String?): OffsetDateTime? =
        value?.takeIf(String::isNotBlank)?.let {
            runCatching { OffsetDateTime.parse(it) }.getOrNull()
        }
}

object PriorityCoordinator {
    fun mergeRemote(local: ActionCard, remote: ActionCard): ActionCard =
        if (local.priorityMode == PriorityModes.MANUAL || local.priorityLocked) {
            remote.copy(
                priority = local.priority,
                priorityMode = PriorityModes.MANUAL,
                priorityScore = local.priorityScore,
                priorityReason = local.priorityReason,
                priorityUpdatedAt = local.priorityUpdatedAt,
                priorityLocked = true,
            )
        } else {
            remote
        }
}

object ConfirmationCoordinator {
    fun blockingReasons(cards: List<ActionCard>): List<String> =
        cards.flatMap { card ->
            buildList {
                if (card.title.isBlank()) add("存在标题为空的行动卡，请先补全标题")
                if (card.title in genericTitles) {
                    add("存在标题过于泛化的行动卡，请先让 AI 继续完善或手动修改")
                }
                if (card.needConfirm.isNotEmpty()) {
                    add("仍有字段需要确认：${card.needConfirm.joinToString("、")}")
                }
                if (
                    card.cardType == "promise" &&
                    card.deadline.isNullOrBlank() &&
                    card.startTime.isNullOrBlank()
                ) {
                    add("承诺类行动卡需要补全执行时间后才能创建")
                }
                addAll(TemporalCoordinator.validationErrors(card))
            }
        }.distinct()

    private val genericTitles = setOf("相关日程", "待办事项", "相关事项", "日程提醒", "行动事项")
}
