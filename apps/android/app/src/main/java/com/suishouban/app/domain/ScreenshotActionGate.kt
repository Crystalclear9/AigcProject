package com.suishouban.app.domain

data class ScreenshotActionGateResult(
    val shouldPrompt: Boolean,
    val reason: String,
    val matchedSignals: List<String> = emptyList(),
    val suggestedTitle: String? = null,
    val deadlineHint: String? = null,
)

class ScreenshotActionGate {
    fun evaluate(
        ocrText: String,
        screenshotUri: String? = null,
        screenshotTimeMillis: Long = System.currentTimeMillis(),
    ): ScreenshotActionGateResult {
        val normalized = ocrText
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.length < MIN_TEXT_LENGTH) {
            return ScreenshotActionGateResult(false, "OCR 文本过短，未发现明确行动信号")
        }

        val actionHits = ACTION_SIGNALS.filter { it in normalized }
        val deadlineWordHits = DEADLINE_SIGNALS.filter { it in normalized }
        val domainHits = DOMAIN_SIGNALS.filter { it in normalized }
        val commitmentHits = COMMITMENT_SIGNALS.filter { it in normalized }
        val timeHits = TIME_PATTERNS.mapNotNull { pattern -> pattern.find(normalized)?.value }

        val matched = buildList {
            actionHits.take(3).forEach { add("行动词:$it") }
            deadlineWordHits.take(2).forEach { add("截止词:$it") }
            domainHits.take(3).forEach { add("事项:$it") }
            commitmentHits.take(2).forEach { add("承诺:$it") }
            timeHits.take(2).forEach { add("时间:$it") }
        }.distinct()

        val hasAction = actionHits.isNotEmpty() || commitmentHits.isNotEmpty()
        val hasDeadline = deadlineWordHits.isNotEmpty()
        val hasTime = hasDeadline || timeHits.isNotEmpty()
        val hasDomain = domainHits.isNotEmpty()
        val score =
            (if (hasAction) 2 else 0) +
                (if (hasDeadline) 3 else 0) +
                (if (timeHits.isNotEmpty()) 2 else 0) +
                (if (hasDomain) 1 else 0) +
                (if (commitmentHits.isNotEmpty()) 1 else 0)

        val title = suggestedTitle(normalized)
        val deadline = deadlineHint(normalized)
        if (looksLikeOwnAppUi(normalized)) {
            return ScreenshotActionGateResult(false, "随手办自身界面截图，跳过自动生成")
        }

        val rawShouldPrompt = score >= 4 && (
            hasAction && hasTime ||
                hasDeadline && hasDomain ||
                commitmentHits.isNotEmpty() && hasDomain
            )
        val shouldPrompt = rawShouldPrompt && (title != null || deadline != null)
        val reason = if (shouldPrompt) {
            buildString {
                append("命中行动信号")
                if (matched.isNotEmpty()) append("：").append(matched.take(4).joinToString("、"))
                deadline?.let { append("；候选截止 ").append(it) }
            }
        } else {
            "无明确行动信号"
        }

        return ScreenshotActionGateResult(
            shouldPrompt = shouldPrompt,
            reason = reason,
            matchedSignals = matched,
            suggestedTitle = title,
            deadlineHint = deadline,
        )
    }

    private fun suggestedTitle(text: String): String? {
        ACTION_TITLE_PATTERN.find(text)?.let { match ->
            return match.value.trim(*TRIM_CHARS).take(MAX_TITLE_LENGTH)
        }
        return when {
            "会议" in text || "开会" in text || "组会" in text -> "参加会议"
            "实验报告" in text -> "提交实验报告"
            "作业" in text -> "完成作业"
            "报名" in text -> "完成报名"
            "考试" in text -> "准备考试"
            "材料" in text -> "准备材料"
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

    companion object {
        private const val MIN_TEXT_LENGTH = 8
        private const val MAX_TITLE_LENGTH = 24
        private const val MAX_DEADLINE_HINT_LENGTH = 36

        private val TRIM_CHARS = charArrayOf(' ', '，', '。', '；', ';', '：', ':', '\n', '\r', '\t')
        private val ACTION_SIGNALS = listOf(
            "提交", "完成", "上传", "填写", "填报", "报名", "参加", "开会", "准备",
            "发送", "发给", "缴费", "签到", "领取", "复习", "考试", "交作业", "交报告",
            "确认", "办理", "预约",
        )
        private val DEADLINE_SIGNALS = listOf("截止", "截至", "截止时间", "之前", "前完成", "提醒")
        private val DOMAIN_SIGNALS = listOf(
            "作业", "实验报告", "报告", "会议", "组会", "课程", "考试", "报名", "通知",
            "材料", "表格", "申请", "项目", "比赛", "学习通", "邮箱", "老师",
        )
        private val COMMITMENT_SIGNALS = listOf("我来", "我会", "我负责", "帮我", "麻烦", "记得", "别忘")
        private val OWN_APP_UI_SIGNALS = listOf(
            "随手办", "截图导入", "动作预览", "卡片中心", "今日关注", "已创建提醒", "生成行动卡",
            "自动化偏好", "截图入口提示", "优先使用云端模型", "日历同步", "提醒策略", "会议事件",
            "截图来源", "当前版本支持截图监听", "文字识别结果",
        )
        private val TIME_PATTERNS = listOf(
            Regex("\\d{1,2}\\s*月\\s*\\d{1,2}\\s*日?"),
            Regex("\\d{1,2}\\s*[:：]\\s*\\d{2}"),
            Regex("周[一二三四五六日天]"),
            Regex("(今天|明天|后天|今晚|明晚|本周|下周|上午|下午|晚上)"),
            Regex("\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}"),
        )
        private val ACTION_TITLE_PATTERN = Regex(
            "(提交|完成|上传|填写|填报|报名|参加|准备|发送|发给|缴费|领取|复习|预约|办理)[^，。！？!?\n]{0,16}"
        )
        private val DEADLINE_HINT_PATTERNS = listOf(
            Regex("(截止|截至)[^，。！？!?\n]{0,24}"),
            Regex("\\d{1,2}\\s*月\\s*\\d{1,2}\\s*日?\\s*(\\d{1,2}\\s*[:：]\\s*\\d{2})?\\s*(前|之前)?"),
            Regex("(今天|明天|后天|今晚|明晚|本周|下周)[^，。！？!?\n]{0,16}(前|之前)?"),
            Regex("周[一二三四五六日天][^，。！？!?\n]{0,16}"),
        )
    }
}
