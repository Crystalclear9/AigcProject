package com.suishouban.app.domain

import com.suishouban.app.data.model.ActionCard
import com.suishouban.app.data.model.AnalyzeResult
import com.suishouban.app.data.model.CardTypes
import com.suishouban.app.data.model.Priority
import java.time.DayOfWeek
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class LocalActionExtractor {
    fun extract(text: String): AnalyzeResult {
        val normalized = text.replace(Regex("\\s+"), " ").trim()
        val cards = if (!isActionableText(normalized)) {
            emptyList()
        } else if (hasMeetingPreparation(normalized)) {
            listOf(
                buildCard(normalized, CardTypes.EVENT, title = if ("组会" in normalized) "参加组会" else "参加会议"),
                buildCard(normalized, CardTypes.TASK, title = if ("汇报" in normalized) "准备进展汇报" else "准备会议材料"),
            )
        } else {
            val cardType = classify(normalized)
            if (cardType == null) emptyList() else listOf(buildCard(normalized, cardType))
        }
        return AnalyzeResult(
            ocrText = normalized,
            cards = cards,
            previewActions = previewActions(cards),
            engine = "local-rules",
        )
    }

    private fun isActionableText(text: String): Boolean {
        if (text.length < 4) return false
        val hasActionSignal = taskWords.any { it in text } ||
            eventWords.any { it in text } ||
            promiseWords.any { it in text } ||
            comparisonWords.any { it in text }
        return hasActionSignal && hasKeySignal(text)
    }

    private fun hasKeySignal(text: String): Boolean {
        // 平衡策略：行动词必须搭配一个时间、地点、提交物、平台、对象或对比选项锚点。
        return hasTimeSignal(text) ||
            extractMaterials(text).isNotEmpty() ||
            extractLocation(text) != null ||
            extractSubmitMethod(text) != null ||
            objectWords.any { it in text } ||
            Regex("(A|B|方案|选项|¥|￥|\\d+\\s*元)").containsMatchIn(text)
    }

    private fun hasTimeSignal(text: String): Boolean {
        return listOf(
            Regex("\\d{1,2}\\s*月\\s*\\d{1,2}\\s*[日号]?"),
            Regex("(本周|这周|下周|周|星期)[一二三四五六日天]"),
            Regex("(今天|明天|后天|今晚|上午|早上|中午|下午|晚上)"),
            Regex("\\d{1,2}[:：]\\d{2}"),
            Regex("\\d{1,2}\\s*点"),
            Regex("本月底|月底|近期|近日|\\d{1,2}\\s*月\\s*(上旬|中旬|下旬)"),
        ).any { it.containsMatchIn(text) }
    }

    private fun hasMeetingPreparation(text: String): Boolean {
        return listOf("组会", "开会", "会议").any { it in text } && listOf("准备", "汇报").any { it in text }
    }

    private fun classify(text: String): String? = when {
        isComparisonText(text) -> CardTypes.COMPARISON
        promiseWords.any { it in text } -> CardTypes.PROMISE
        eventWords.any { it in text } -> CardTypes.EVENT
        taskWords.any { it in text } -> CardTypes.TASK
        else -> null
    }

    private fun isComparisonText(text: String): Boolean {
        val hasComparisonWord = comparisonWords.any { it in text }
        val hasOptionOrPrice = Regex("(A|B|方案|选项|¥|￥|\\d+\\s*元)").containsMatchIn(text)
        return hasComparisonWord && hasOptionOrPrice
    }

    private fun buildCard(text: String, cardType: String, title: String? = null): ActionCard {
        val time = extractTime(text)
        val hasTime = time.value != null
        val priority = if (listOf("截止", "逾期", "前提交", "考试", "报名").any { it in text }) Priority.HIGH else Priority.NORMAL
        val location = extractLocation(text)
        val submitMethod = extractSubmitMethod(text)
        val needConfirm = buildList {
            if (time.fuzzy) add("时间")
            if ("指定邮箱" in text || "指定平台" in text) add("提交方式")
            if (cardType == CardTypes.EVENT && location == null) add("地点")
            if ("表格" in text && "位置" !in text) add("表格位置")
        }
        return ActionCard(
            id = UUID.randomUUID().toString(),
            cardType = cardType,
            title = title ?: inferTitle(text, cardType),
            summary = text.take(120),
            deadline = if (cardType == CardTypes.TASK || cardType == CardTypes.PROMISE) time.value else null,
            startTime = if (cardType == CardTypes.EVENT) time.value else null,
            endTime = null,
            location = location,
            materials = extractMaterials(text),
            submitMethod = submitMethod,
            priority = priority,
            tags = inferTags(text, cardType),
            reminders = ReminderPolicy.recommend(cardType, priority, hasTime),
            needConfirm = needConfirm,
            sourceText = text,
        )
    }

    private fun inferTitle(text: String, cardType: String): String = when {
        "实验报告" in text -> "提交实验报告"
        "AIGC" in text -> "完成 AIGC 创新赛报名"
        "比赛" in text && "报名" in text -> "完成比赛报名"
        "组会" in text -> "参加组会"
        "进展汇报" in text -> "准备进展汇报"
        "社团" in text || "集合" in text -> "社团活动集合"
        "表格" in text && "老师" in text -> "帮同学把表格发给老师"
        "提交" in text -> "提交材料"
        "开会" in text || "会议" in text -> "参加会议"
        cardType == CardTypes.COMPARISON -> "整理对比信息"
        else -> "处理截图事项"
    }

    private fun inferTags(text: String, cardType: String): List<String> {
        val tags = linkedSetOf<String>()
        mapOf(
            "课程" to "课程",
            "实验报告" to "课程作业",
            "比赛" to "比赛",
            "AIGC" to "比赛",
            "社团" to "社团",
            "组会" to "会议",
            "会议" to "会议",
            "考试" to "考试",
            "报名" to "报名",
        ).forEach { (keyword, tag) -> if (keyword in text) tags.add(tag) }
        if (tags.isEmpty()) {
            tags.add(
                when (cardType) {
                    CardTypes.EVENT -> "日程"
                    CardTypes.PROMISE -> "承诺"
                    CardTypes.COMPARISON -> "对比"
                    else -> "任务"
                }
            )
        }
        return tags.toList()
    }

    private fun extractMaterials(text: String): List<String> {
        return listOf("报名表", "作品说明书", "实验报告", "进展汇报", "表格", "材料", "证件", "文件")
            .filter { it in text }
    }

    private fun extractLocation(text: String): String? {
        val direct = Regex("地点[:：]\\s*([^，。；\\n]{2,32})").find(text)?.groupValues?.get(1)
        if (!direct.isNullOrBlank()) return direct
        val inline = Regex("在([^，。；\\n]{2,24})(集合|开会|参加|签到|考试)").find(text)?.groupValues?.get(1)
        if (!inline.isNullOrBlank()) return inline
        return when {
            "学习通" in text -> "学习通"
            "官网" in text -> "官网"
            else -> null
        }
    }

    private fun extractSubmitMethod(text: String): String? = when {
        "学习通" in text -> "提交至学习通"
        "指定邮箱" in text -> "发送至指定邮箱"
        "邮箱" in text -> "发送至邮箱"
        "官网" in text || "报名链接" in text -> "官网报名链接"
        else -> null
    }

    private fun previewActions(cards: List<ActionCard>): List<String> = cards.flatMap { card ->
        buildList {
            add(
                when (card.cardType) {
                    CardTypes.EVENT -> "创建日历事件：${card.title}"
                    CardTypes.PROMISE -> "创建承诺提醒：${card.title}"
                    CardTypes.COMPARISON -> "生成对比卡：${card.title}"
                    else -> "创建待办任务：${card.title}"
                }
            )
            if (card.reminders.isNotEmpty()) add("设置提醒：${card.reminders.joinToString("、")}")
            if (card.needConfirm.isNotEmpty()) add("需要确认：${card.needConfirm.joinToString("、")}")
        }
    }

    private data class TimeGuess(val value: String?, val fuzzy: Boolean)

    private fun extractTime(text: String): TimeGuess {
        val now = OffsetDateTime.now(ZoneOffset.ofHours(8))
        val hourGuess = extractHour(text)

        Regex("(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*[日号]?").find(text)?.let {
            val month = it.groupValues[1].toInt()
            val day = it.groupValues[2].toInt()
            var date = OffsetDateTime.of(
                now.year,
                month,
                day,
                hourGuess.hour,
                hourGuess.minute,
                0,
                0,
                now.offset,
            )
            if (date.isBefore(now.minusDays(1))) date = date.plusYears(1)
            return TimeGuess(date.toString(), hourGuess.fuzzy)
        }

        if ("明天" in text) {
            return TimeGuess(now.plusDays(1).withHour(hourGuess.hour).withMinute(hourGuess.minute).withSecond(0).withNano(0).toString(), true)
        }
        if ("今天" in text || "今晚" in text) {
            return TimeGuess(now.withHour(hourGuess.hour).withMinute(hourGuess.minute).withSecond(0).withNano(0).toString(), hourGuess.fuzzy)
        }

        val weekMatch = Regex("(本周|这周|下周|周|星期)([一二三四五六日天])").find(text)
        if (weekMatch != null) {
            val day = when (weekMatch.groupValues[2]) {
                "一" -> DayOfWeek.MONDAY
                "二" -> DayOfWeek.TUESDAY
                "三" -> DayOfWeek.WEDNESDAY
                "四" -> DayOfWeek.THURSDAY
                "五" -> DayOfWeek.FRIDAY
                "六" -> DayOfWeek.SATURDAY
                else -> DayOfWeek.SUNDAY
            }
            var date = now
            while (date.dayOfWeek != day) date = date.plusDays(1)
            if (weekMatch.groupValues[1] == "下周") date = date.plusWeeks(1)
            return TimeGuess(date.withHour(hourGuess.hour).withMinute(hourGuess.minute).withSecond(0).withNano(0).toString(), hourGuess.fuzzy)
        }

        return TimeGuess(null, false)
    }

    private data class HourGuess(val hour: Int, val minute: Int, val fuzzy: Boolean)

    private fun extractHour(text: String): HourGuess {
        Regex("(\\d{1,2})[:：](\\d{2})").find(text)?.let {
            return HourGuess(it.groupValues[1].toInt(), it.groupValues[2].toInt(), false)
        }
        Regex("(上午|早上|中午|下午|晚上|今晚|晚)?\\s*(\\d{1,2})\\s*点\\s*(\\d{1,2})?分?").find(text)?.let {
            val prefix = it.groupValues[1]
            var hour = it.groupValues[2].toInt()
            val minute = it.groupValues.getOrNull(3)?.takeIf { value -> value.isNotBlank() }?.toIntOrNull() ?: 0
            if (prefix in setOf("下午", "晚上", "今晚", "晚") && hour < 12) hour += 12
            if (prefix == "中午" && hour < 11) hour += 12
            return HourGuess(hour, minute, false)
        }
        return when {
            "上午" in text || "早上" in text -> HourGuess(9, 0, true)
            "中午" in text -> HourGuess(12, 0, true)
            "下午" in text -> HourGuess(15, 0, true)
            "晚上" in text || "今晚" in text -> HourGuess(20, 0, true)
            else -> HourGuess(9, 0, true)
        }
    }

    private companion object {
        val taskWords = listOf("提交", "报名", "上传", "填写", "截止", "作业", "报告", "发送", "准备", "完成", "整理")
        val eventWords = listOf("开会", "会议", "组会", "讲座", "集合", "活动", "考试", "面试", "召开", "举行", "参加")
        val promiseWords = listOf("帮我", "帮你", "答应", "可以，我", "我来", "没问题", "承诺", "说好了")
        val comparisonWords = listOf("对比", "比较", "区别", "选哪个", "哪款", "哪个更", "还是", "vs", "VS")
        val objectWords = listOf("老师", "同学", "同学们", "各组", "全体", "负责人", "报名表", "作品说明书", "实验报告", "进展汇报", "PPT", "商业计划书", "团队信息表", "表格", "材料", "证件", "文件")
    }
}
