package com.suishouban.app.domain.screenshot

data class ScreenshotActionGateResult(
    val shouldPrompt: Boolean,
    val reason: String,
    val matchedSignals: List<String> = emptyList(),
    val suggestedTitle: String? = null,
    val deadlineHint: String? = null,
    val confidence: Double = 0.0,
    val evidenceExcerpt: String? = null,
    val promptSummary: String? = null,
    val confidenceBand: String = ConfidenceBands.LOW,
    val scenarioType: String = ScenarioTypes.UNKNOWN,
    val primaryEvidence: List<String> = emptyList(),
    val negativeSignals: List<String> = emptyList(),
)

class ScreenshotActionGate(
    private val normalizer: OcrTextNormalizer = OcrTextNormalizer(),
) {
    fun evaluate(
        ocrText: String,
        screenshotUri: String? = null,
        screenshotTimeMillis: Long = System.currentTimeMillis(),
    ): ScreenshotActionGateResult {
        val normalized = normalizer.normalize(ocrText)
        if (normalized.fullText.length < MIN_TEXT_LENGTH) {
            return ScreenshotActionGateResult(
                shouldPrompt = false,
                reason = "OCR 文本过短，未发现明确行动信号",
                scenarioType = ScenarioTypes.UNKNOWN,
            )
        }
        if (looksLikeOwnAppUi(normalized.fullText)) {
            return ScreenshotActionGateResult(
                shouldPrompt = false,
                reason = "随手办自身界面截图，跳过自动生成",
                scenarioType = ScenarioTypes.OWN_APP,
                negativeSignals = listOf("own_app_ui"),
            )
        }

        val scoredWindows = normalized.windows.map { scoreWindow(it) }.sortedByDescending { it.score }
        val best = scoredWindows.firstOrNull() ?: scoreWindow(TextWindow(normalized.fullText))
        val confidence = (best.score / 12.0).coerceIn(0.0, 1.0)
        val title = suggestedTitle(best.text) ?: suggestedTitle(normalized.fullText)
        val deadline = deadlineHint(best.text) ?: deadlineHint(normalized.fullText)
        val scenario = scenarioType(best.text, normalized.fullText, best)
        val shouldPrompt = best.isActionable &&
            confidence >= 0.58 &&
            (title != null || deadline != null) &&
            scenario !in setOf(ScenarioTypes.NOISE, ScenarioTypes.OWN_APP)
        val promptSummary = promptSummary(title, deadline)

        val reason = if (shouldPrompt) {
            buildString {
                append("命中行动证据")
                if (best.signals.isNotEmpty()) append("：").append(best.signals.take(5).joinToString("、"))
                deadline?.let { append("；候选截止 ").append(it) }
            }
        } else {
            if (best.negativeSignals.isNotEmpty()) {
                "命中干扰或广告信号，未提示"
            } else {
                "无明确行动信号"
            }
        }

        return ScreenshotActionGateResult(
            shouldPrompt = shouldPrompt,
            reason = reason,
            matchedSignals = best.signals,
            suggestedTitle = title,
            deadlineHint = deadline,
            confidence = confidence,
            evidenceExcerpt = best.text.take(MAX_EVIDENCE_LENGTH),
            promptSummary = promptSummary,
            confidenceBand = confidenceBand(confidence),
            scenarioType = scenario,
            primaryEvidence = primaryEvidence(best, title, deadline),
            negativeSignals = best.negativeSignals,
        )
    }

    private fun scoreWindow(window: TextWindow): WindowScore {
        val text = window.text
        val actionHits = ACTION_SIGNALS.filter { it in text }
        val deadlineHits = DEADLINE_SIGNALS.filter { it in text }
        val domainHits = DOMAIN_SIGNALS.filter { it in text }
        val commitmentHits = COMMITMENT_SIGNALS.filter { it in text }
        val locationHits = LOCATION_SIGNALS.filter { it in text }
        val materialHits = MATERIAL_SIGNALS.filter { it in text }
        val timeHits = TIME_PATTERNS.mapNotNull { pattern -> pattern.find(text)?.value }
        val negativeHits = NEGATIVE_SIGNALS.filter { it in text }

        val hasAction = actionHits.isNotEmpty() || commitmentHits.isNotEmpty()
        val hasDeadlineOrTime = deadlineHits.isNotEmpty() || timeHits.isNotEmpty()
        val hasObject = domainHits.isNotEmpty() || materialHits.isNotEmpty() || locationHits.isNotEmpty()
        val hasTaskCore = hasAction && hasDeadlineOrTime && hasObject
        val hasDeadlineTask = deadlineHits.isNotEmpty() && hasObject && (actionHits.isNotEmpty() || materialHits.isNotEmpty())
        val hasPromise = commitmentHits.isNotEmpty() && (hasDeadlineOrTime || materialHits.isNotEmpty()) && hasAction

        val negativePenalty = negativeHits.size * 3 + if (looksLikeChromeOnly(text)) 3 else 0
        val score =
            actionHits.size.coerceAtMost(3) * 2 +
                deadlineHits.size.coerceAtMost(2) * 3 +
                timeHits.size.coerceAtMost(2) * 2 +
                domainHits.size.coerceAtMost(3) +
                materialHits.size.coerceAtMost(2) +
                locationHits.size.coerceAtMost(1) +
                commitmentHits.size.coerceAtMost(2) * 2 -
                negativePenalty

        val signals = buildList {
            actionHits.take(3).forEach { add("行动:$it") }
            deadlineHits.take(2).forEach { add("截止:$it") }
            timeHits.take(2).forEach { add("时间:$it") }
            domainHits.take(3).forEach { add("事项:$it") }
            materialHits.take(2).forEach { add("材料:$it") }
            locationHits.take(1).forEach { add("地点:$it") }
            commitmentHits.take(2).forEach { add("承诺:$it") }
        }.distinct()

        return WindowScore(
            text = text,
            score = score,
            signals = signals,
            negativeSignals = negativeHits,
            isActionable = score >= 7 && (hasTaskCore || hasDeadlineTask || hasPromise),
        )
    }

    private fun promptSummary(title: String?, deadline: String?): String? {
        val cleanTitle = title?.takeIf { it.isNotBlank() }
        val cleanDeadline = deadline?.takeIf { it.isNotBlank() }
        return when {
            cleanTitle != null && cleanDeadline != null -> "$cleanTitle · $cleanDeadline"
            cleanTitle != null -> cleanTitle
            cleanDeadline != null -> "可能的行动事项 · $cleanDeadline"
            else -> null
        }?.take(MAX_PROMPT_SUMMARY_LENGTH)
    }

    private fun primaryEvidence(best: WindowScore, title: String?, deadline: String?): List<String> {
        return buildList {
            title?.let { add("候选事项：$it") }
            deadline?.let { add("候选时间：$it") }
            best.signals.take(4).forEach { add(it) }
            best.text.take(MAX_EVIDENCE_LENGTH).takeIf { it.isNotBlank() }?.let { add("片段：$it") }
        }.distinct().take(6)
    }

    private fun confidenceBand(confidence: Double): String = when {
        confidence >= 0.82 -> ConfidenceBands.HIGH
        confidence >= 0.58 -> ConfidenceBands.MEDIUM
        else -> ConfidenceBands.LOW
    }

    private fun scenarioType(text: String, fullText: String, best: WindowScore): String {
        val merged = "$text $fullText"
        return when {
            best.negativeSignals.size >= 2 || looksLikeChromeOnly(text) -> ScenarioTypes.NOISE
            COMMITMENT_SIGNALS.any { it in merged } -> ScenarioTypes.CHAT_PROMISE
            listOf("报名", "比赛", "官网", "报名表", "作品说明书").any { it in merged } -> ScenarioTypes.REGISTRATION
            listOf("会议", "组会", "腾讯会议", "开会", "汇报").any { it in merged } -> ScenarioTypes.MEETING
            listOf("课程", "作业", "实验报告", "学习通", "老师").any { it in merged } -> ScenarioTypes.COURSE_NOTICE
            else -> ScenarioTypes.UNKNOWN
        }
    }

    private fun suggestedTitle(text: String): String? {
        ACTION_TITLE_PATTERN.find(text)?.let { match ->
            return match.value.trim(*TRIM_CHARS).take(MAX_TITLE_LENGTH)
        }
        return when {
            "实验报告" in text -> "提交实验报告"
            "作业" in text -> "完成作业"
            "报名" in text -> "完成报名"
            "考试" in text -> "准备考试"
            "组会" in text -> "参加组会"
            "会议" in text || "开会" in text -> "参加会议"
            "材料" in text || "文件" in text -> "准备材料"
            "PPT" in text || "汇报" in text -> "准备汇报"
            else -> null
        }
    }

    private fun deadlineHint(text: String): String? {
        DEADLINE_HINT_PATTERNS.forEach { pattern ->
            val value = pattern.find(text)?.value?.trim(*TRIM_CHARS)
            if (!value.isNullOrBlank()) return value.take(MAX_DEADLINE_HINT_LENGTH)
        }
        return null
    }

    private fun looksLikeOwnAppUi(text: String): Boolean {
        return OWN_APP_UI_SIGNALS.any { it in text }
    }

    private fun looksLikeChromeOnly(text: String): Boolean {
        val chromeHits = CHROME_SIGNALS.count { it in text }
        val actionableHits = ACTION_SIGNALS.count { it in text } + DEADLINE_SIGNALS.count { it in text }
        return chromeHits >= 4 && actionableHits == 0
    }

    private data class WindowScore(
        val text: String,
        val score: Int,
        val signals: List<String>,
        val negativeSignals: List<String>,
        val isActionable: Boolean,
    )

    companion object {
        private const val MIN_TEXT_LENGTH = 8
        private const val MAX_TITLE_LENGTH = 24
        private const val MAX_DEADLINE_HINT_LENGTH = 36
        private const val MAX_EVIDENCE_LENGTH = 160
        private const val MAX_PROMPT_SUMMARY_LENGTH = 56

        private val TRIM_CHARS = charArrayOf(' ', '，', '。', '；', ';', '：', ':', '\n', '\r', '\t', '-', '—')
        private val ACTION_SIGNALS = listOf(
            "提交", "完成", "上传", "填写", "填报", "报名", "参加", "开会", "准备",
            "发送", "发给", "缴费", "签到", "领取", "复习", "考试", "交作业", "交报告",
            "确认", "办理", "预约", "到场", "集合", "汇报", "答辩", "提交到", "提交至",
        )
        private val DEADLINE_SIGNALS = listOf(
            "截止", "截至", "截止时间", "截止日期", "之前", "前完成", "前提交", "逾期", "ddl", "DDL",
            "deadline", "Deadline", "due", "Due",
        )
        private val DOMAIN_SIGNALS = listOf(
            "作业", "实验报告", "报告", "会议", "组会", "课程", "考试", "报名", "通知",
            "申请", "项目", "比赛", "学习通", "邮箱", "老师", "讲座", "面试", "答辩",
        )
        private val MATERIAL_SIGNALS = listOf(
            "材料", "文件", "附件", "报名表", "作品说明书", "PPT", "汇报", "表格", "证件", "截图",
            "实验报告", "论文", "简历", "名单",
        )
        private val LOCATION_SIGNALS = listOf("教室", "会议室", "线上", "腾讯会议", "飞书", "钉钉", "学习通", "邮箱", "官网")
        private val COMMITMENT_SIGNALS = listOf("我来", "我会", "我负责", "帮我", "帮你", "麻烦", "记得", "别忘", "提醒我", "说好了")
        private val NEGATIVE_SIGNALS = listOf(
            "优惠券", "秒杀", "抢购", "满减", "直播间", "购物车", "下单", "广告", "游戏", "皮肤", "金币",
            "领券", "折扣", "特价", "爆款", "包邮", "到手价", "清仓", "主播", "种草",
        )
        private val CHROME_SIGNALS = listOf(
            "5G", "WiFi", "电量", "今日", "导入", "卡片", "日历", "设置", "首页", "消息", "我的", "返回",
        )
        private val OWN_APP_UI_SIGNALS = listOf(
            "随手办", "截图导入", "动作预览", "卡片中心", "今日关注", "已创建提醒", "生成行动卡",
            "自动化偏好", "截图入口提示", "优先使用云端模型", "日历同步", "提醒策略", "会议事件",
            "截图来源", "当前版本支持截图监听", "文字识别结果", "云端增强",
        )
        private val TIME_PATTERNS = listOf(
            Regex("\\d{1,2}\\s*月\\s*\\d{1,2}\\s*[日号]?"),
            Regex("\\d{1,2}\\s*[:：]\\s*\\d{2}"),
            Regex("\\d{1,2}\\s*点\\s*(\\d{1,2}\\s*分?)?"),
            Regex("周[一二三四五六日天]"),
            Regex("(今天|明天|后天|今晚|明晚|本周|下周|上午|下午|晚上|明早|今晚)"),
            Regex("\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}"),
            Regex("\\d{1,2}/\\d{1,2}"),
        )
        private val ACTION_TITLE_PATTERN = Regex(
            "(提交|完成|上传|填写|填报|报名|参加|准备|发送|发给|缴费|领取|复习|预约|办理|汇报|答辩)[^，。！？!?\n]{0,18}"
        )
        private val DEADLINE_HINT_PATTERNS = listOf(
            Regex("(截止|截至|deadline|Deadline|due|Due|DDL|ddl)[^，。！？!?\n]{0,28}"),
            Regex("\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}\\s*(\\d{1,2}\\s*[:：]\\s*\\d{2})?\\s*(前|之前)?"),
            Regex("\\d{1,2}\\s*月\\s*\\d{1,2}\\s*[日号]?\\s*(\\d{1,2}\\s*[:：]\\s*\\d{2})?\\s*(前|之前)?"),
            Regex("(今天|明天|后天|今晚|明晚|本周|下周|明早)[^，。！？!?\n]{0,18}(前|之前)?"),
            Regex("周[一二三四五六日天][^，。！？!?\n]{0,18}"),
        )
    }
}

object ScenarioTypes {
    const val COURSE_NOTICE = "course_notice"
    const val CHAT_PROMISE = "chat_promise"
    const val REGISTRATION = "registration"
    const val MEETING = "meeting"
    const val NOISE = "noise"
    const val OWN_APP = "own_app"
    const val UNKNOWN = "unknown"
}

object ConfidenceBands {
    const val LOW = "low"
    const val MEDIUM = "medium"
    const val HIGH = "high"
}

enum class ScreenshotWorkflowStage {
    OCR_DETECTED,
    GATE_PASSED,
    PROMPT_SHOWN,
    DRAFT_READY,
    REVIEWING,
    CONFIRMED,
    IGNORED,
}

data class TextWindow(val text: String)

class OcrTextNormalizer {
    fun normalize(raw: String): NormalizedOcrText {
        val converted = raw
            .map(::toHalfWidth)
            .joinToString("")
            .replace('\u00A0', ' ')
            .replace(Regex("[\\u200B-\\u200D\\uFEFF]"), "")
            .replace(Regex("[|｜•·●◆◇★☆✨※→←↑↓✓✔✦✧]+"), " ")
            .replace(Regex("[ \t]+"), " ")
            .replaceKnownSeparatedWords()
            .normalizeDateAndTimeSeparators()

        val lines = converted
            .split(Regex("[\\r\\n]+"))
            .map { it.trim() }
            .filter { it.length >= 2 }
            .filterNot(::isLowValueChromeLine)
            .take(MAX_LINES)

        val compactText = lines.joinToString(" ").replace(Regex("\\s+"), " ").trim()
        val windows = buildWindows(lines).ifEmpty { listOf(TextWindow(compactText)) }
        return NormalizedOcrText(fullText = compactText, lines = lines, windows = windows)
    }

    private fun buildWindows(lines: List<String>): List<TextWindow> {
        if (lines.isEmpty()) return emptyList()
        val windows = mutableListOf<TextWindow>()
        lines.forEach { windows += TextWindow(it) }
        for (index in lines.indices) {
            val maxEnd = (index + 3).coerceAtMost(lines.size)
            for (end in index + 2..maxEnd) {
                windows += TextWindow(lines.subList(index, end).joinToString(" "))
            }
        }
        windows += TextWindow(lines.joinToString(" "))
        return windows.distinctBy { it.text }
    }

    private fun isLowValueChromeLine(line: String): Boolean {
        if (STATUS_BAR_PATTERN.matches(line)) return true
        if (line.length <= 8 && CHROME_ONLY_WORDS.any { it == line }) return true
        val chromeCount = CHROME_ONLY_WORDS.count { it in line }
        val actionCount = ACTION_WORDS.count { it in line }
        return chromeCount >= 3 && actionCount == 0
    }

    private fun toHalfWidth(char: Char): Char {
        return when (char.code) {
            0x3000 -> ' '
            in 0xFF01..0xFF5E -> (char.code - 0xFEE0).toChar()
            else -> char
        }
    }

    private fun String.replaceKnownSeparatedWords(): String {
        return this
            .mergeSeparatedKeyword("截止")
            .mergeSeparatedKeyword("截至")
            .mergeSeparatedKeyword("提交")
            .mergeSeparatedKeyword("报名")
            .mergeSeparatedKeyword("上传")
            .mergeSeparatedKeyword("完成")
            .mergeSeparatedKeyword("准备")
            .mergeSeparatedKeyword("会议")
            .mergeSeparatedKeyword("组会")
            .mergeSeparatedKeyword("开会")
            .mergeSeparatedKeyword("实验报告")
            .mergeSeparatedKeyword("学习通")
            .mergeSeparatedKeyword("腾讯会议")
            .mergeSeparatedKeyword("作品说明书")
            .mergeSeparatedKeyword("团队信息表")
            .mergeSeparatedKeyword("报名表")
            .mergeSeparatedKeyword("商业计划书")
            .mergeSeparatedKeyword("进展汇报")
            .mergeSeparatedKeyword("截止时间")
            .mergeSeparatedKeyword("报名通道")
            .replace(Regex("截\\s*[止至]"), "截止")
            .replace(Regex("报\\s*名"), "报名")
            .replace(Regex("学\\s*习\\s*通"), "学习通")
            .replace(Regex("实\\s*验\\s*报\\s*告"), "实验报告")
            .replace(Regex("腾\\s*讯\\s*会\\s*议"), "腾讯会议")
            .replace(Regex("P\\s*P\\s*T", RegexOption.IGNORE_CASE), "PPT")
            .replace(Regex("D\\s*D\\s*L", RegexOption.IGNORE_CASE), "DDL")
    }

    private fun String.mergeSeparatedKeyword(keyword: String): String {
        if (keyword.length < 2) return this
        val pattern = keyword.map { Regex.escape(it.toString()) }.joinToString("\\s*")
        return replace(Regex(pattern), keyword)
    }

    private fun String.normalizeDateAndTimeSeparators(): String {
        return this
            .replace(Regex("(20\\d{2})\\s*[-/.]\\s*(\\d{1,2})\\s*[-/.]\\s*(\\d{1,2})")) {
                "${it.groupValues[1]}/${it.groupValues[2]}/${it.groupValues[3]}"
            }
            .replace(Regex("(\\d{1,2})\\s*[:：]\\s*(\\d{2})")) {
                "${it.groupValues[1]}:${it.groupValues[2]}"
            }
            .replace(Regex("(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*[日号]?")) {
                "${it.groupValues[1]}月${it.groupValues[2]}日"
            }
    }

    companion object {
        private const val MAX_LINES = 80
        private val STATUS_BAR_PATTERN = Regex("^[\\d:：\\sGgWIFIwifi%电量\\-_/\\.]+$")
        private val CHROME_ONLY_WORDS = listOf(
            "首页", "消息", "我的", "返回", "搜索", "设置", "发现", "通讯录", "动态", "分享", "评论",
            "点赞", "收藏", "今日", "导入", "卡片", "日历",
        )
        private val ACTION_WORDS = listOf("提交", "完成", "截止", "报名", "会议", "作业", "报告", "考试", "准备")
    }
}

data class NormalizedOcrText(
    val fullText: String,
    val lines: List<String>,
    val windows: List<TextWindow>,
)
