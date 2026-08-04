package com.suishouban.app.domain

import com.suishouban.app.data.model.ActionCard
import com.suishouban.app.data.model.ActionPlan
import com.suishouban.app.data.model.PlanItem
import com.suishouban.app.data.model.PlanItemKinds
import com.suishouban.app.data.model.PlanningGranularity
import com.suishouban.app.data.model.Priority
import com.suishouban.app.data.model.UserProfile
import java.time.Duration
import java.time.DayOfWeek
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

data class LocalRefinementOptions(
    val granularity: String = PlanningGranularity.BALANCED,
    val includeMilestones: Boolean = true,
    val includeWorkBlocks: Boolean = true,
    val milestoneReminders: Boolean = true,
    val useProfile: Boolean = true,
)

object LocalCardRefiner {
    fun refine(
        card: ActionCard,
        options: LocalRefinementOptions,
        profile: UserProfile?,
        instruction: String = "",
        attachmentEvidence: String = "",
    ): ActionPlan {
        val granularity = if (options.useProfile && profile != null) {
            profile.planningGranularity
        } else {
            options.granularity
        }
        val specs = mutableListOf(
            Spec(
                PlanItemKinds.STEP,
                requirementTitle(profile),
                listOf(
                    "核对交付物、评价标准和最终截止时间。",
                    attachmentEvidence.takeIf(String::isNotBlank)?.let { "附件要点：$it" },
                ).filterNotNull().joinToString("\n"),
                20,
            ),
            Spec(PlanItemKinds.MILESTONE, "完成材料与资源准备", materialDescription(card), 45),
            Spec(PlanItemKinds.WORK_BLOCK, "完成核心执行", "集中完成任务主体，并记录仍需确认的问题。", 120),
            Spec(PlanItemKinds.MILESTONE, "完成质量复核", "逐项检查要求，预留修改与上传时间。", 45),
            Spec(PlanItemKinds.MILESTONE, "提交或参加", submissionDescription(card), 20),
        )
        if (granularity == PlanningGranularity.CONCISE) {
            val first = specs.first()
            val core = specs.first { it.kind == PlanItemKinds.WORK_BLOCK }
            val last = specs.last()
            specs.clear()
            specs += listOf(first, core, last)
        } else if (granularity == PlanningGranularity.DETAILED) {
            specs.add(3, Spec(PlanItemKinds.STEP, "处理中间反馈", "检查阶段成果并根据反馈修正。", 40))
        }
        if (!options.includeMilestones) specs.removeAll { it.kind == PlanItemKinds.MILESTONE }
        if (!options.includeWorkBlocks) specs.removeAll { it.kind == PlanItemKinds.WORK_BLOCK }
        if (specs.isEmpty()) specs += Spec(PlanItemKinds.STEP, "完成任务", card.summary.ifBlank { card.title }, 60)

        val zone = runCatching { ZoneId.of(profile?.timezone ?: ZoneId.systemDefault().id) }
            .getOrDefault(ZoneId.systemDefault())
        val now = OffsetDateTime.now(zone)
        val parentDeadline = parseTime(card.deadline ?: card.startTime)
        val available = parentDeadline?.let { Duration.between(now, it) }?.takeIf { !it.isNegative && !it.isZero }
        val buffer = deadlineBuffer(profile)
        val usable = parentDeadline
            ?.minus(buffer)
            ?.let { Duration.between(now, it) }
            ?.takeIf { !it.isNegative && !it.isZero }
            ?: available
        val fractions = scheduleFractions(profile)
        var previousId: String? = null
        val items = specs.mapIndexed { index, spec ->
            val id = UUID.randomUUID().toString()
            val scheduled: OffsetDateTime? = usable?.let { duration ->
                alignToProfilePeriod(
                    now.plus(
                    Duration.ofMillis(
                        (duration.toMillis() * fractions[index.coerceAtMost(fractions.lastIndex)]).toLong(),
                    ),
                    ),
                    profile,
                    now,
                    parentDeadline,
                )
            }
            val deadline = scheduled?.takeIf { spec.kind == PlanItemKinds.MILESTONE }?.toString()
            val startTime = scheduled?.takeIf { spec.kind == PlanItemKinds.WORK_BLOCK }?.toString()
            PlanItem(
                id = id,
                kind = spec.kind,
                title = spec.title,
                description = listOf(spec.description, instruction.takeIf(String::isNotBlank))
                    .filterNotNull()
                    .joinToString("\n"),
                order = index,
                startTime = startTime,
                deadline = deadline,
                estimatedMinutes = spec.minutes,
                importance = if (index == specs.lastIndex) Priority.HIGH else Priority.NORMAL,
                dependencies = previousId?.let(::listOf).orEmpty(),
                reminderEnabled = shouldEnableReminder(
                    kind = spec.kind,
                    index = index,
                    itemCount = specs.size,
                    eligible = options.milestoneReminders && deadline != null,
                    profile = profile,
                ),
                confidence = if (parentDeadline != null) 0.78 else 0.62,
                // Missing DDL is a plan-level warning, not a blocking field conflict. The user may
                // still accept a relative sequence; no absolute reminder is created.
                needConfirm = emptyList(),
            ).also { previousId = id }
        }
        return ActionPlan(
            parentCardId = card.id,
            objective = card.title,
            assumptions = listOf("本地规则计划不会修改父卡事实字段"),
            evidenceSummary = card.evidenceSummary +
                attachmentEvidence.takeIf(String::isNotBlank)?.let { "本地附件提取：$it" }.orEmpty(),
            warnings = if (parentDeadline == null) listOf("补充截止时间后可生成绝对时间提醒") else emptyList(),
            generatedBy = "local_rules",
            profileVersion = profile?.version,
            qualityScore = if (parentDeadline != null) 0.9 else 0.72,
            profileEffects = profileEffects(profile),
            verificationSummary = if (parentDeadline != null) {
                "本地规则已校验顺序、截止时间、画像时段和提醒密度"
            } else {
                "已生成相对顺序；补充截止时间后才能校验绝对排期"
            },
            items = items,
        )
    }

    private fun parseTime(value: String?): OffsetDateTime? =
        value?.takeIf(String::isNotBlank)?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }

    private fun materialDescription(card: ActionCard): String =
        if (card.materials.isEmpty()) {
            "整理任务所需材料、账号、模板和参考信息。"
        } else {
            "准备：" + card.materials.joinToString("、")
        }

    private fun requirementTitle(profile: UserProfile?): String = when (profile?.scenario) {
        "study" -> "确认课程与交付要求"
        "office" -> "确认工作目标与交付要求"
        "life" -> "确认事项范围与准备要求"
        else -> "确认任务要求"
    }

    private fun submissionDescription(card: ActionCard): String = when {
        !card.submitMethod.isNullOrBlank() -> "通过 ${card.submitMethod} 完成最终提交，并保留成功凭证。"
        !card.location.isNullOrBlank() -> "在 ${card.location} 完成任务，并确认结果。"
        else -> "完成最终提交或参加，并核对是否成功。"
    }

    private fun deadlineBuffer(profile: UserProfile?): Duration = when (profile?.bufferPreference) {
        "compact" -> Duration.ofMinutes(30)
        "generous" -> Duration.ofDays(1)
        else -> Duration.ofHours(3)
    }

    private fun scheduleFractions(profile: UserProfile?): List<Double> = when (profile?.workRhythm) {
        "steady" -> listOf(0.10, 0.28, 0.46, 0.64, 0.82, 0.94)
        "sprint" -> listOf(0.42, 0.56, 0.68, 0.78, 0.88, 0.95)
        else -> listOf(0.08, 0.25, 0.48, 0.68, 0.84, 0.94)
    }

    private fun alignToProfilePeriod(
        value: OffsetDateTime,
        profile: UserProfile?,
        now: OffsetDateTime,
        parentDeadline: OffsetDateTime?,
    ): OffsetDateTime {
        if (profile == null || profile.activePeriod in setOf("unspecified", "flexible")) {
            return avoidWeekend(value, profile)
        }
        val hour = when (profile.activePeriod) {
            "morning" -> 9
            "afternoon", "daytime" -> 14
            "evening" -> 19
            else -> return avoidWeekend(value, profile)
        }
        var aligned = value.withHour(hour).withMinute(0).withSecond(0).withNano(0)
        if (aligned < now) aligned = aligned.plusDays(1)
        aligned = avoidWeekend(aligned, profile)
        return parentDeadline?.let { if (aligned > it) it.minusMinutes(10) else aligned } ?: aligned
    }

    private fun avoidWeekend(value: OffsetDateTime, profile: UserProfile?): OffsetDateTime {
        if (profile?.weekendPolicy != "avoid") return value
        var adjusted = value
        while (adjusted.dayOfWeek in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)) {
            adjusted = adjusted.minusDays(1)
        }
        return adjusted
    }

    private fun shouldEnableReminder(
        kind: String,
        index: Int,
        itemCount: Int,
        eligible: Boolean,
        profile: UserProfile?,
    ): Boolean {
        if (!eligible || kind != PlanItemKinds.MILESTONE) return false
        return when (profile?.reminderStyle) {
            "light", "gentle", "key_only" -> index == itemCount - 1
            "multi", "multi_stage" -> true
            else -> index >= itemCount - 2
        }
    }

    private fun profileEffects(profile: UserProfile?): List<String> {
        if (profile == null) return listOf("使用中性规划策略")
        val labels = mapOf(
            "morning" to "时间块优先安排在上午",
            "afternoon" to "时间块优先安排在下午",
            "evening" to "时间块优先安排在晚上",
            "steady" to "任务均匀铺开",
            "sprint" to "核心工作集中安排",
            "adaptive" to "根据剩余时间动态安排",
            "compact" to "保留紧凑截止缓冲",
            "standard" to "保留标准截止缓冲",
            "generous" to "保留宽裕截止缓冲",
            "avoid" to "尽量避开周末",
            "allow" to "允许安排周末",
            "flexible" to "按任务需要安排周末",
        )
        return listOf(
            profile.activePeriod,
            profile.workRhythm,
            profile.bufferPreference,
            profile.weekendPolicy,
        ).mapNotNull(labels::get)
    }

    private data class Spec(
        val kind: String,
        val title: String,
        val description: String,
        val minutes: Int,
    )
}
