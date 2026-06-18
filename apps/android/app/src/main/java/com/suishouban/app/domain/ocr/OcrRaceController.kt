package com.suishouban.app.domain.ocr

import kotlin.math.min

data class OcrCandidate(
    val engine: String,
    val text: String,
    val blocks: Int = text.lines().count { it.isNotBlank() },
    val arrivedAtMs: Long = System.currentTimeMillis(),
    val qualityScore: Double = OcrQualityScorer.score(text, blocks),
)

data class OcrArbitrationResult(
    val firstCandidate: OcrCandidate,
    val selectedCandidate: OcrCandidate,
    val reason: String,
    val lateCandidates: List<OcrCandidate> = emptyList(),
)

object OcrRaceController {
    private const val QUALITY_MARGIN = 0.12

    fun arbitrate(candidates: List<OcrCandidate>): OcrArbitrationResult? {
        val usable = candidates
            .filter { it.text.isNotBlank() }
            .sortedWith(compareBy<OcrCandidate> { it.arrivedAtMs }.thenByDescending { it.qualityScore })
        if (usable.isEmpty()) return null
        val first = usable.first()
        val best = usable.maxWith(compareBy<OcrCandidate> { it.qualityScore }.thenBy { it.arrivedAtMs })
        val selected = if (best.qualityScore - first.qualityScore >= QUALITY_MARGIN) best else first
        val reason = when {
            selected == first && best != first -> "first_candidate_within_quality_margin"
            selected == first -> "first_candidate"
            else -> "higher_quality_candidate"
        }
        return OcrArbitrationResult(
            firstCandidate = first,
            selectedCandidate = selected,
            reason = reason,
            lateCandidates = usable.drop(1),
        )
    }
}

object OcrQualityScorer {
    private val timePattern = Regex("(\\d{1,2}\\s*月\\s*\\d{1,2}\\s*[日号]?|\\d{1,2}[:：]\\d{2}|周[一二三四五六日天]|今天|明天|后天|今晚|DDL|ddl|截止|截至)")
    private val actionPattern = Regex("(提交|完成|上传|填写|报名|参加|开会|准备|发送|缴费|签到|考试|提醒|汇报|答辩)")
    private val objectPattern = Regex("(作业|实验报告|报告|会议|组会|课程|考试|报名表|作品说明书|PPT|材料|学习通|腾讯会议|邮箱|官网|教室)")
    private val garbledPattern = Regex("[�□■�]")
    private val chromeWords = listOf("5G", "WiFi", "电量", "首页", "返回", "设置", "消息", "我的")

    fun score(text: String, blocks: Int = text.lines().count { it.isNotBlank() }): Double {
        val compact = text.replace(Regex("\\s+"), " ").trim()
        if (compact.isBlank()) return 0.0
        val lengthScore = min(compact.length / 180.0, 1.0) * 0.18
        val blockScore = min(blocks / 8.0, 1.0) * 0.12
        val timeScore = min(timePattern.findAll(compact).count() / 2.0, 1.0) * 0.22
        val actionScore = min(actionPattern.findAll(compact).count() / 3.0, 1.0) * 0.2
        val objectScore = min(objectPattern.findAll(compact).count() / 3.0, 1.0) * 0.18
        val garbledPenalty = min(garbledPattern.findAll(compact).count() / 3.0, 1.0) * 0.16
        val chromePenalty = min(chromeWords.count { it in compact } / 5.0, 1.0) * 0.08
        val repeatPenalty = repeatedLinePenalty(text)
        return (0.24 + lengthScore + blockScore + timeScore + actionScore + objectScore - garbledPenalty - chromePenalty - repeatPenalty)
            .coerceIn(0.0, 1.0)
    }

    private fun repeatedLinePenalty(text: String): Double {
        val lines = text.lines().map { it.trim() }.filter { it.length >= 2 }
        if (lines.size < 4) return 0.0
        val duplicated = lines.groupingBy { it }.eachCount().values.count { it > 1 }
        return min(duplicated / lines.size.toDouble(), 1.0) * 0.12
    }
}
