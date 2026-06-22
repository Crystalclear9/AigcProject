package com.suishouban.app.domain

import com.suishouban.app.data.model.ActionCard
import com.suishouban.app.data.model.AnalyzeResult
import com.suishouban.app.data.model.CardTypes
import com.suishouban.app.data.model.Priority
import com.suishouban.app.domain.screenshot.OcrTextNormalizer
import java.time.DayOfWeek
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class LocalActionExtractor {
    private val textNormalizer = OcrTextNormalizer()

    fun extract(text: String): AnalyzeResult {
        val normalizedOcr = textNormalizer.normalize(text)
        val normalized = normalizedOcr.fullText.ifBlank {
            text.replace(Regex("\\s+"), " ").trim()
        }
        val segments = splitActionSegments(normalizedOcr.lines, normalized)
        val rawCards = segments
            .flatMap { segment -> extractCardsFromSegment(segment) }
            .ifEmpty { extractCardsFromSegment(normalized) }
        val cards = mergeSimilarCandidates(rawCards + extractEvidenceBackfillCards(normalized, rawCards))
        return AnalyzeResult(
            ocrText = normalized,
            cards = cards,
            previewActions = previewActions(cards),
            engine = "local-rules",
        )
    }

    private fun extractCardsFromSegment(segment: String): List<ActionCard> {
        val text = segment.trim()
        if (!isActionableText(text)) return emptyList()
        val clauses = splitAtomicActionClauses(text)
        if (clauses.size > 1) {
            return clauses.flatMap { clause -> extractCardsFromAtomicSegment(clause) }
        }
        return extractCardsFromAtomicSegment(text)
    }

    private fun extractCardsFromAtomicSegment(text: String): List<ActionCard> {
        if (hasMeetingPreparation(text)) {
            return listOf(
                buildCard(text, CardTypes.EVENT, title = if ("组会" in text) "参加组会" else "参加会议"),
                buildCard(text, CardTypes.TASK, title = if ("汇报" in text) "准备进展汇报" else "准备会议材料"),
            )
        }
        val cardType = classify(text) ?: return emptyList()
        return listOf(buildCard(text, cardType))
    }

    private fun extractEvidenceBackfillCards(text: String, existingCards: List<ActionCard>): List<ActionCard> {
        val cards = mutableListOf<ActionCard>()
        val hasRegistration = existingCards.any { "报名" in it.title || "报名表" in it.title }
        val hasRegistrationTiming = hasTimeSignal(text) ||
            Regex("\\d").containsMatchIn(text) ||
            listOf("截止", "逾期", "前").any { it in text }
        val hasRegistrationEvidence = "报名表" in text ||
            Regex("报名\\s*(表|材料|信息)").containsMatchIn(text) ||
            ("报名" in text && listOf("邮箱", "发到", "发送", "提交", "逾期").any { it in text })
        if (!hasRegistration && hasRegistrationEvidence && hasRegistrationTiming) {
            val registrationWindow = focusedEvidenceWindow(
                text,
                if ("报名表" in text) "报名表" else "报名",
            )
            cards += buildCard(
                registrationWindow,
                CardTypes.TASK,
                title = if (listOf("邮箱", "发到", "发送").any { it in registrationWindow }) "发送报名表" else "提交报名表",
            )
        }
        return cards
    }

    private fun focusedEvidenceWindow(text: String, keyword: String): String {
        val index = text.indexOf(keyword).takeIf { it >= 0 } ?: return text.take(120)
        val start = index
        val end = (index + 96).coerceAtMost(text.length)
        return text.substring(start, end).trim()
    }

    private fun splitAtomicActionClauses(text: String): List<String> {
        return insertActionBreaks(text)
            .split(Regex("[\\n；;。]+"))
            .map { it.trim(' ', '，', ',', '-', '—') }
            .filter { it.length >= 6 }
            .filter { isActionableText(it) }
            .distinctBy { it.normalizedKey() }
    }

    private fun mergeSimilarCandidates(cards: List<ActionCard>): List<ActionCard> {
        val merged = mutableListOf<ActionCard>()
        cards.sortedByDescending(::cardEvidenceScore).forEach { candidate ->
            val existingIndex = merged.indexOfFirst { existing -> areSameAction(existing, candidate) }
            if (existingIndex < 0) {
                merged += candidate
            } else if (cardEvidenceScore(candidate) > cardEvidenceScore(merged[existingIndex])) {
                merged[existingIndex] = candidate
            }
        }
        return merged
    }

    private fun areSameAction(left: ActionCard, right: ActionCard): Boolean {
        if (left.cardType != right.cardType) return false
        val leftSignals = titleActionSignals(left.title)
        val rightSignals = titleActionSignals(right.title)
        if (leftSignals.isNotEmpty() && rightSignals.isNotEmpty() && leftSignals.intersect(rightSignals).isEmpty()) {
            return false
        }
        val sameTime = left.primaryTimeKey().isNotBlank() && left.primaryTimeKey() == right.primaryTimeKey()
        val sharedMaterials = left.materials.intersect(right.materials.toSet()).isNotEmpty()
        val sameSubmitMethod = left.submitMethod != null && left.submitMethod == right.submitMethod
        val sourceOverlap = textOverlap(left.sourceText, right.sourceText) >= 0.48
        val titleOverlap = textOverlap(left.title, right.title) >= 0.58
        return sourceOverlap && (sameTime || sharedMaterials || sameSubmitMethod || titleOverlap)
    }

    private fun titleActionSignals(title: String): Set<String> {
        return buildSet {
            if ("实验报告" in title) add("lab_report")
            if ("报名" in title || "报名表" in title) add("registration")
            if ("会议" in title) add("meeting")
            if ("汇报" in title || "PPT" in title) add("report")
        }
    }

    private fun cardEvidenceScore(card: ActionCard): Int {
        var score = card.sourceText.length.coerceAtMost(160) / 10
        if (!card.deadline.isNullOrBlank() || !card.startTime.isNullOrBlank()) score += 12
        if (card.materials.isNotEmpty()) score += 8 + card.materials.size
        if (!card.submitMethod.isNullOrBlank()) score += 6
        if (!card.location.isNullOrBlank()) score += 4
        if (card.title !in setOf("处理截图事项", "提交材料")) score += 4
        score += card.evidenceSummary.size
        return score
    }

    private fun textOverlap(left: String, right: String): Double {
        val a = left.normalizedKey()
        val b = right.normalizedKey()
        if (a.isBlank() || b.isBlank()) return 0.0
        if (a.contains(b) || b.contains(a)) return 1.0
        val gramsA = a.charNgrams()
        val gramsB = b.charNgrams()
        if (gramsA.isEmpty() || gramsB.isEmpty()) return 0.0
        val shared = gramsA.intersect(gramsB).size
        return shared.toDouble() / minOf(gramsA.size, gramsB.size)
    }

    private fun splitActionSegments(lines: List<String>, fullText: String): List<String> {
        val candidates = mutableListOf<String>()
        val usefulLines = lines.filter { line -> line.length >= 4 && !looksLikeChromeOnly(line) }
        usefulLines.forEachIndexed { index, line ->
            val previous = usefulLines.getOrNull(index - 1)
            val next = usefulLines.getOrNull(index + 1)
            if (previous != null && hasTimeSignal(previous) && isActionableText("$previous $line")) {
                candidates += "$previous $line"
            }
            if (next != null && hasTimeSignal(line) && isActionableText("$line $next")) {
                candidates += "$line $next"
            }
            val contextWindow = usefulLines
                .subList((index - 1).coerceAtLeast(0), (index + 3).coerceAtMost(usefulLines.size))
                .joinToString(" ")
            when {
                isActionableText(contextWindow) -> candidates += contextWindow
                isActionableText(line) -> {
                    val following = next
                        ?.takeIf { !isActionableText(it) && hasKeySignal("$line $it") }
                    candidates += listOfNotNull(line, following).joinToString(" ")
                }
            }
        }

        val splitText = insertActionBreaks(fullText)
        splitText
            .split(Regex("[\\n；;。]+"))
            .map { it.trim(' ', '，', ',', '-', '—') }
            .filter { it.length >= 6 }
            .filter { isActionableText(it) }
            .forEach { candidates += it }

        if (candidates.isEmpty() && isActionableText(fullText)) candidates += fullText
        return candidates
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotBlank() }
            .sortedByDescending { segmentEvidenceScore(it) }
            .distinctBy { it.normalizedKey() }
            .take(MAX_SEGMENTS)
    }

    private fun insertActionBreaks(text: String): String {
        return text
            .replace(Regex("([①②③④⑤⑥⑦⑧⑨])"), "\n$1")
            .replace(Regex("(?<!\\d)([1-9][、.．)]\\s*)"), "\n$1")
            .replace(Regex("(另外|同时|还有|并且|以及|请各位|各组需|注意[:：])"), "\n$1")
            .replace(Regex("\\s+(?=(?:请在\\s*)?\\d{1,2}\\s*月\\s*\\d{1,2}\\s*[日号]?\\s*(?:\\d{1,2}[:：]\\d{2})?\\s*(?:前)?\\s*(?:提交|参加|报名|发送|发到|准备))"), "\n")
            .replace(Regex("\\s+(?=(?:报名表|实验报告|项目\\s*PPT|PPT)\\s*\\d{1,2}\\s*月\\s*\\d{1,2})"), "\n")
    }

    private fun segmentEvidenceScore(text: String): Int {
        var score = text.length.coerceAtMost(120) / 12
        if (hasTimeSignal(text)) score += 6
        if (extractMaterials(text).isNotEmpty()) score += 5
        if (extractSubmitMethod(text) != null) score += 4
        if (extractLocation(text) != null) score += 3
        score += taskWords.count { it in text } * 2
        score += eventWords.count { it in text } * 2
        return score
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
            Regex("\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}"),
            Regex("本月底|月底|近期|近日|\\d{1,2}\\s*月\\s*(上旬|中旬|下旬)"),
        ).any { it.containsMatchIn(text) }
    }

    private fun hasMeetingPreparation(text: String): Boolean {
        return listOf("组会", "开会", "会议").any { it in text } && listOf("准备", "汇报").any { it in text }
    }

    private fun looksLikeChromeOnly(text: String): Boolean {
        val chromeHits = listOf("首页", "返回", "设置", "消息", "我的", "搜索", "5G", "WiFi", "电量").count { it in text }
        val actionHits = taskWords.count { it in text } + eventWords.count { it in text } + promiseWords.count { it in text }
        return chromeHits >= 3 && actionHits == 0
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
        val submitMethod = if (cardType == CardTypes.EVENT) null else extractSubmitMethod(text)
        val needConfirm = buildList {
            if (time.fuzzy && cardType != CardTypes.TASK) add("时间")
            if (cardType != CardTypes.EVENT && ("指定邮箱" in text || "指定平台" in text) && submitMethod == null) add("提交方式")
            if (
                cardType == CardTypes.EVENT &&
                location == null &&
                "会议号" !in text &&
                "腾讯会议" !in text &&
                "线上" !in text
            ) add("地点")
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
            evidenceSummary = evidenceSummary(text, time.value, location, submitMethod),
        )
    }

    private fun evidenceSummary(
        text: String,
        time: String?,
        location: String?,
        submitMethod: String?,
    ): List<String> {
        return buildList {
            time?.let { add("时间：$it") }
            location?.let { add("地点/平台：$it") }
            submitMethod?.let { add("方式：$it") }
            extractMaterials(text).takeIf { it.isNotEmpty() }?.let { add("材料：${it.joinToString("、")}") }
            add("片段：${text.take(80)}")
        }.distinct().take(5)
    }

    private fun inferTitle(text: String, cardType: String): String = when {
        "实验报告" in text -> "提交实验报告"
        "报名表" in text && ("邮箱" in text || "发送" in text || "发到" in text) -> "发送报名表"
        "进展汇报" in text && ("准备" in text || "PPT" in text) -> "准备进展汇报"
        "腾讯会议" in text || ("会议" in text && "参加" in text) -> "参加会议"
        Regex("(提交|上传|完成|填写|报名|准备|参加|发送|缴费|复习|整理)[^，。；;！？!?\n]{2,20}").find(text) != null ->
            Regex("(提交|上传|完成|填写|报名|准备|参加|发送|缴费|复习|整理)[^，。；;！？!?\n]{2,20}")
                .find(text)!!.value.trim().take(28)
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
        return listOf("报名表", "作品说明书", "团队信息表", "实验报告", "进展汇报", "表格", "材料", "证件", "文件", "PPT", "简历")
            .filter { it in text }
    }

    private fun extractLocation(text: String): String? {
        val direct = Regex("地点[:：]\\s*([^，。；\\n]{2,32})").find(text)?.groupValues?.get(1)
        if (!direct.isNullOrBlank()) return direct
        val inline = Regex("在([^，。；\\n]{2,24})(集合|开会|参加|签到|考试)").find(text)?.groupValues?.get(1)
        if (!inline.isNullOrBlank()) return inline
        return when {
            "学习通" in text -> "学习通"
            "腾讯会议" in text -> "腾讯会议"
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

        Regex("(20\\d{2})[-/.](\\d{1,2})[-/.](\\d{1,2})").find(text)?.let {
            val year = it.groupValues[1].toInt()
            val month = it.groupValues[2].toInt()
            val day = it.groupValues[3].toInt()
            return TimeGuess(
                OffsetDateTime.of(year, month, day, hourGuess.hour, hourGuess.minute, 0, 0, now.offset).toString(),
                hourGuess.fuzzy,
            )
        }

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
        Regex("(\\d{1,2})\\s*[:：]\\s*(\\d{2})").find(text)?.let {
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
        const val MAX_SEGMENTS = 8
        val taskWords = listOf("提交", "报名", "上传", "填写", "截止", "截至", "DDL", "deadline", "作业", "报告", "发送", "准备", "完成", "整理")
        val eventWords = listOf("开会", "会议", "组会", "讲座", "集合", "活动", "考试", "面试", "召开", "举行", "参加")
        val promiseWords = listOf("帮我", "帮你", "答应", "可以，我", "我来", "没问题", "承诺", "说好了")
        val comparisonWords = listOf("对比", "比较", "区别", "选哪个", "哪款", "哪个更", "还是", "vs", "VS")
        val objectWords = listOf("老师", "同学", "同学们", "各组", "全体", "负责人", "报名表", "作品说明书", "实验报告", "进展汇报", "PPT", "商业计划书", "团队信息表", "表格", "材料", "证件", "文件")
    }
}

private fun String.normalizedKey(): String =
    lowercase().replace(Regex("[^a-z0-9\\u4e00-\\u9fff]+"), "")

private fun String.charNgrams(size: Int = 3): Set<String> {
    if (length <= size) return if (isBlank()) emptySet() else setOf(this)
    return windowed(size).toSet()
}

private fun ActionCard.primaryTimeKey(): String =
    (startTime ?: deadline ?: "").take(16)
