package com.suishouban.app.domain.ocr

import com.suishouban.app.domain.TextIntegrity
import kotlin.math.min

data class OcrCandidate(
    val engine: String,
    val text: String,
    val blocks: Int = text.lines().count { it.isNotBlank() },
    val evidenceBlocks: List<OcrEvidenceBlock> = emptyList(),
    val arrivedAtMs: Long = System.currentTimeMillis(),
    val qualityReport: OcrQualityReport = OcrQualityScorer.evaluate(text, blocks, evidenceBlocks),
    val qualityScore: Double = qualityReport.qualityScore,
)

data class OcrEvidenceBlock(
    val text: String,
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
    val readingOrder: Int,
)

data class OcrQualityReport(
    val qualityScore: Double,
    val garbledRatio: Double,
    val duplicateRatio: Double,
    val noiseRatio: Double,
    val layoutScore: Double,
    val hasActionEvidence: Boolean,
    val hasTimeEvidence: Boolean,
    val reasons: List<String>,
)

data class OcrArbitrationResult(
    val firstCandidate: OcrCandidate,
    val selectedCandidate: OcrCandidate,
    val reason: String,
    val lateCandidates: List<OcrCandidate> = emptyList(),
    val requiresReview: Boolean = false,
    val reviewReasons: List<String> = emptyList(),
)

object OcrRaceController {
    private const val QUALITY_MARGIN = 0.12
    private val fusionSignalPattern = Regex(
        "(提交|完成|上传|填写|报名|参加|开会|准备|发送|考试|汇报|答辩|" +
            "作业|报告|会议|PPT|材料|学习通|腾讯会议|邮箱|" +
            "\\d{1,2}\\s*月\\s*\\d{1,2}|\\d{1,2}[:：]\\d{2}|截止|截至)",
    )

    fun arbitrate(candidates: List<OcrCandidate>): OcrArbitrationResult? {
        val usable = candidates
            .filter { it.text.isNotBlank() }
            .sortedWith(compareBy<OcrCandidate> { it.arrivedAtMs }.thenByDescending { it.qualityScore })
        if (usable.isEmpty()) return null
        val first = usable.first()
        val best = usable.maxWith(compareBy<OcrCandidate> { it.qualityScore }.thenBy { it.arrivedAtMs })
        val baseSelected = if (best.qualityScore - first.qualityScore >= QUALITY_MARGIN) best else first
        val criticalConflict = hasCriticalTimeConflict(usable)
        val selected = if (!criticalConflict) fuseCompatibleCandidates(baseSelected, usable) else baseSelected
        val reason = when {
            selected.text != baseSelected.text -> "block_evidence_fusion"
            baseSelected == first && best != first -> "first_candidate_within_quality_margin"
            baseSelected == first -> "first_candidate"
            else -> "higher_quality_candidate"
        }
        val reviewReasons = buildList {
            addAll(selected.qualityReport.reasons)
            if (selected.qualityScore < 0.72) add("low_ocr_quality")
            if (criticalConflict) add("critical_field_conflict")
        }
        return OcrArbitrationResult(
            firstCandidate = first,
            selectedCandidate = selected,
            reason = reason,
            lateCandidates = usable.drop(1),
            requiresReview = selected.qualityScore < 0.72 ||
                "garbled_characters" in selected.qualityReport.reasons ||
                criticalConflict,
            reviewReasons = reviewReasons.distinct(),
        )
    }

    private fun fuseCompatibleCandidates(selected: OcrCandidate, candidates: List<OcrCandidate>): OcrCandidate {
        val lines = selected.text.lines().map(String::trim).filter(String::isNotBlank).toMutableList()
        candidates.filter { it !== selected && it.qualityScore >= 0.60 }.forEach { candidate ->
            candidate.text.lines().map(String::trim).filter(String::isNotBlank).forEach { line ->
                val compact = line.replace(Regex("\\s+"), "")
                val duplicate = lines.any { existing ->
                    val other = existing.replace(Regex("\\s+"), "")
                    compact == other || compact.contains(other) || other.contains(compact)
                }
                val useful = fusionSignalPattern.containsMatchIn(line)
                if (!duplicate && useful && TextIntegrity.evaluate(line).reliable) lines += line
            }
        }
        val fused = lines.joinToString("\n")
        return if (fused == selected.text) selected else OcrCandidate(
            engine = "${selected.engine}+fused",
            text = fused,
            blocks = lines.size,
            arrivedAtMs = selected.arrivedAtMs,
        )
    }

    private fun hasCriticalTimeConflict(candidates: List<OcrCandidate>): Boolean {
        if (candidates.size < 2) return false
        val values = candidates.take(3).map { candidate ->
            Regex("""(?:\d{1,2}\s*月\s*\d{1,2}\s*[日号]?|\d{1,2}\s*[:：]\s*\d{2}|周[一二三四五六日天])""")
                .findAll(candidate.text)
                .map { it.value.replace(Regex("\\s+"), "") }
                .toSet()
        }.filter { it.isNotEmpty() }
        return values.size >= 2 && values.indices.any { left ->
            values.indices.any { right -> left < right && values[left].intersect(values[right]).isEmpty() }
        }
    }
}

object OcrQualityScorer {
    private val timePattern = Regex("(\\d{1,2}\\s*月\\s*\\d{1,2}\\s*[日号]?|\\d{1,2}[:：]\\d{2}|周[一二三四五六日天]|今天|明天|后天|今晚|DDL|ddl|截止|截至)")
    private val actionPattern = Regex("(提交|完成|上传|填写|报名|参加|开会|准备|发送|缴费|签到|考试|提醒|汇报|答辩)")
    private val objectPattern = Regex("(作业|实验报告|报告|会议|组会|课程|考试|报名表|作品说明书|PPT|材料|学习通|腾讯会议|邮箱|官网|教室)")
    private val garbledPattern = Regex("[�□■]|锟斤拷|烫烫烫|屯屯屯|鈻|鏃堕棿|鎻愪氦")
    private val chromeWords = listOf("5G", "WiFi", "电量", "首页", "返回", "设置", "消息", "我的")

    fun score(text: String, blocks: Int = text.lines().count { it.isNotBlank() }): Double =
        evaluate(text, blocks, emptyList()).qualityScore

    fun evaluate(
        text: String,
        blocks: Int = text.lines().count { it.isNotBlank() },
        evidenceBlocks: List<OcrEvidenceBlock> = emptyList(),
    ): OcrQualityReport {
        val compact = text.replace(Regex("\\s+"), " ").trim()
        if (compact.isBlank()) {
            return OcrQualityReport(0.0, 0.0, 0.0, 0.0, 0.0, false, false, listOf("empty_text"))
        }
        val lengthScore = min(compact.length / 180.0, 1.0) * 0.18
        val layoutScore = layoutScore(evidenceBlocks)
        val blockScore = (0.05 + min(blocks / 8.0, 1.0) * 0.06 + layoutScore * 0.05).coerceAtMost(0.16)
        val timeCount = timePattern.findAll(compact).count()
        val actionCount = actionPattern.findAll(compact).count()
        val timeScore = min(timeCount / 2.0, 1.0) * 0.22
        val actionScore = min(actionCount / 3.0, 1.0) * 0.2
        val objectScore = min(objectPattern.findAll(compact).count() / 3.0, 1.0) * 0.18
        val integrity = TextIntegrity.evaluate(text)
        val garbledRatio = maxOf(
            garbledPattern.findAll(compact).sumOf { it.value.length } / compact.length.toDouble(),
            integrity.garbledRatio,
        )
        val garbledPenalty = min(garbledRatio / 0.08, 1.0) * 0.34
        val noiseRatio = maxOf(
            min(chromeWords.count { it in compact } / 5.0, 1.0),
            integrity.noiseRatio,
        )
        val chromePenalty = noiseRatio * 0.08
        val duplicateRatio = repeatedLineRatio(text)
        val repeatPenalty = duplicateRatio * 0.12
        val integrityPenalty = (1.0 - integrity.score) * 0.24
        val score = (0.18 + lengthScore + blockScore + timeScore + actionScore + objectScore - garbledPenalty - chromePenalty - repeatPenalty - integrityPenalty)
            .coerceIn(0.0, 1.0)
        val reasons = buildList {
            if (compact.length < 8) add("text_too_short")
            if (garbledRatio >= 0.08) add("garbled_characters")
            if (duplicateRatio >= 0.35) add("duplicate_blocks")
            if (noiseRatio >= 0.6) add("chrome_noise")
            if (actionCount == 0) add("no_action_evidence")
            if (actionCount > 0 && timeCount == 0 && objectPattern.find(compact) == null) {
                add("incomplete_action_evidence")
            }
            addAll(integrity.reasons)
        }
        return OcrQualityReport(
            qualityScore = score,
            garbledRatio = garbledRatio,
            duplicateRatio = duplicateRatio,
            noiseRatio = noiseRatio,
            layoutScore = layoutScore,
            hasActionEvidence = actionCount > 0,
            hasTimeEvidence = timeCount > 0,
            reasons = reasons,
        )
    }

    private fun layoutScore(blocks: List<OcrEvidenceBlock>): Double {
        if (blocks.isEmpty()) return 0.70
        if (blocks.size == 1) return 0.65
        val ordered = blocks.zipWithNext().count { (left, right) ->
            left.top < right.top || (left.top == right.top && left.left <= right.left)
        }
        return 0.55 + 0.45 * ordered.toDouble() / (blocks.size - 1)
    }

    private fun repeatedLineRatio(text: String): Double {
        val lines = text.lines().map { it.trim() }.filter { it.length >= 2 }
        if (lines.size < 4) return 0.0
        val duplicated = lines.groupingBy { it }.eachCount().values.count { it > 1 }
        return min(duplicated / lines.size.toDouble(), 1.0)
    }
}
