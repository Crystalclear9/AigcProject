package com.suishouban.app.domain

import java.text.Normalizer

data class TextIntegrityReport(
    val text: String,
    val score: Double,
    val reasons: List<String>,
    val garbledRatio: Double,
    val noiseRatio: Double,
) {
    val reliable: Boolean
        get() = score >= 0.78 && reasons.none {
            it == "mojibake" || it == "random_identifier" || it == "invalid_unicode"
        }
}

data class SummaryQualityReport(
    val text: String,
    val score: Double,
    val reasons: List<String>,
) {
    val acceptable: Boolean
        get() = score >= 0.72 && reasons.none {
            it == "mojibake" || it == "random_identifier" ||
                it == "ui_noise" || it == "empty_summary"
        }
}

object TextIntegrity {
    private val conservativeOcrCorrections = linkedMapOf(
        "实验根告" to "实验报告",
        "学刁通" to "学习通",
        "学习迥" to "学习通",
        "腾汛会议" to "腾讯会议",
        "邮葙" to "邮箱",
        "谍程群公告" to "课程群公告",
        "井准备" to "并准备",
        "⑨报各表" to "③报名表",
        "报各表" to "报名表",
    )
    private val mojibake = Regex("(锟斤拷|鏃堕棿|鎻愪氦|璇峰湪|Ã.|Â.|â€|ä½|å¥|�|□|■)")
    private val randomToken = Regex("(?<![A-Za-z0-9])[A-Za-z0-9_-]{12,}(?![A-Za-z0-9])")
    private val uiNoise = Regex(
        "(欢迎来到|功能介绍|未分类|清除全部|通知中心|返回|首页|设置|消息|我的|" +
            "^\\s*\\d{1,2}:\\d{2}\\s*$|4G|5G|Wi-?Fi|电量\\s*\\d*%?|KB/s)",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
    )
    private val actionOrTime = Regex(
        "(提交|完成|参加|报名|发送|准备|汇报|会议|考试|截止|截至|" +
            "\\d{1,2}\\s*月\\s*\\d{1,2}|\\d{1,2}\\s*[:：]\\s*\\d{2})",
    )
    private val safeLatin = Regex("^(PPTX?|PDF|DOCX?|XLSX?|TXT|MD|DDL|AI|AIGC|URL|ID|[A-Z]\\d{2,5})$", RegexOption.IGNORE_CASE)

    fun evaluate(value: String): TextIntegrityReport {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lineSequence()
            .map { it.replace(Regex("[ \\t]+"), " ").trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
        val markerChars = mojibake.findAll(normalized).sumOf { it.value.length }
        val invalidChars = normalized.count { char ->
            val type = Character.getType(char)
            type == Character.CONTROL.toInt() || type == Character.PRIVATE_USE.toInt() ||
                type == Character.SURROGATE.toInt() || type == Character.UNASSIGNED.toInt()
        }
        val lines = normalized.lines().filter { it.isNotBlank() }
        val noisyLines = lines.count { uiNoise.containsMatchIn(it) }
        val length = normalized.count { it != '\n' }.coerceAtLeast(1)
        val garbledRatio = ((markerChars + invalidChars).toDouble() / length).coerceIn(0.0, 1.0)
        val noiseRatio = noisyLines.toDouble() / lines.size.coerceAtLeast(1)
        val hasRandomToken = Regex("[\\u4e00-\\u9fff]").containsMatchIn(normalized) &&
            randomToken.findAll(normalized).any { match ->
                val token = match.value
                !token.startsWith("http", ignoreCase = true) && '@' !in token && !safeLatin.matches(token)
            }
        val reasons = buildList {
            if (markerChars > 0) add("mojibake")
            if (hasRandomToken) add("random_identifier")
            if (noisyLines > 0) add("ui_noise")
            if (invalidChars > 0) add("invalid_unicode")
            if (normalized.length < 4) add("too_short")
        }
        var score = 1.0
        score -= (garbledRatio * 8).coerceAtMost(0.62)
        if (hasRandomToken) score -= 0.28
        score -= (noiseRatio * 0.35).coerceAtMost(0.30)
        if (normalized.length < 4) score -= 0.35
        return TextIntegrityReport(
            text = normalized,
            score = score.coerceIn(0.0, 1.0),
            reasons = reasons.distinct(),
            garbledRatio = garbledRatio,
            noiseRatio = noiseRatio,
        )
    }

    /**
     * Produces an explicit review suggestion for a small set of high-precision OCR confusions.
     * It never changes digits or time expressions and callers must still ask the user to apply it.
     */
    fun suggestOcrCorrection(value: String): String? {
        var corrected = value
        conservativeOcrCorrections.forEach { (wrong, expected) ->
            corrected = corrected.replace(wrong, expected)
        }
        corrected = corrected
            .replace("::", ":")
            .replace(Regex("(?<=\\d)[：](?=\\d{2})"), ":")
        return corrected.takeIf { it != value }
    }

    fun sanitizeSummary(value: String): String {
        val report = evaluate(value)
        val withoutTokens = randomToken.replace(report.text, "")
        val useful = withoutTokens.split(Regex("[\\n。；;]+"))
            .map { it.replace(Regex("\\s+"), " ").trim(' ', ',', '，', '。', ':', '：', ';', '；') }
            .filter { it.isNotBlank() && !uiNoise.containsMatchIn(it) }
        val selected = useful.filter { actionOrTime.containsMatchIn(it) }.ifEmpty { useful }
        return selected.take(2).joinToString("；").take(100).trim(' ', ',', '，', '。', '；')
    }

    fun summaryQuality(value: String): SummaryQualityReport {
        val sanitized = sanitizeSummary(value)
        val integrity = evaluate(value)
        val reasons = integrity.reasons.toMutableList()
        if (sanitized != value.trim(' ', ',', '，', '。', '；')) reasons += "summary_requires_sanitization"
        if (sanitized.isBlank() || sanitized == "摘要待复核") reasons += "empty_summary"
        return SummaryQualityReport(
            text = sanitized,
            score = if (sanitized.isBlank() || sanitized == "摘要待复核") 0.0 else integrity.score,
            reasons = reasons.distinct(),
        )
    }

    fun chooseBetterSummary(current: String, incoming: String): String {
        val currentQuality = summaryQuality(current)
        val incomingQuality = summaryQuality(incoming)
        return when {
            incomingQuality.acceptable &&
                (!currentQuality.acceptable || incomingQuality.score >= currentQuality.score + 0.08) -> incomingQuality.text
            currentQuality.acceptable -> currentQuality.text
            incomingQuality.acceptable -> incomingQuality.text
            else -> "摘要待复核"
        }
    }
}

object EvidenceSummaryComposer {
    fun compose(
        title: String,
        deadline: String?,
        startTime: String?,
        location: String?,
        materials: List<String>,
        submitMethod: String?,
    ): String {
        val safeTitle = TextIntegrity.sanitizeSummary(title)
        if (safeTitle.isBlank() || !TextIntegrity.evaluate(safeTitle).reliable) return "摘要待复核"
        val parts = buildList {
            add(safeTitle)
            (deadline ?: startTime)?.let { add("时间：${humanTime(it)}") }
            location?.let { TextIntegrity.sanitizeSummary(it).takeIf(String::isNotBlank)?.let { value -> add("地点/平台：$value") } }
            materials.map(TextIntegrity::sanitizeSummary).filter(String::isNotBlank).take(3)
                .takeIf { it.isNotEmpty() }?.let { add("材料：${it.joinToString("、")}") }
            submitMethod?.let { TextIntegrity.sanitizeSummary(it).takeIf(String::isNotBlank)?.let { value -> add("方式：$value") } }
        }
        val summary = parts.joinToString("；").take(100)
        return if (TextIntegrity.summaryQuality(summary).acceptable) summary else "摘要待复核"
    }

    private fun humanTime(value: String): String = value
        .replace('T', ' ')
        .removeSuffix("+08:00")
        .removeSuffix("Z")
        .trim()
}
