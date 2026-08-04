package com.suishouban.app.data.repository

import com.suishouban.app.data.remote.ApiFactory
import com.suishouban.app.data.remote.OnboardingTurnRequestDto
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout

data class OnboardingChoice(
    val id: String,
    val label: String,
)

data class OnboardingFollowupQuestion(
    val id: String,
    val topic: String,
    val prompt: String,
    val choices: List<OnboardingChoice>,
)

data class OnboardingTurn(
    val assistantMessage: String,
    val nextQuestion: OnboardingFollowupQuestion? = null,
    val mood: String = "focus",
    val animationHint: String = "scan",
    val complete: Boolean = false,
    val enhancementStatus: String = "not_configured",
)

class OnboardingRepository {
    suspend fun requestTurn(
        settings: AppSettings,
        sessionId: String,
        phase: String,
        currentStep: String,
        answers: Map<String, String>,
        answeredFollowups: List<String>,
        timeoutMillis: Long = API_TIMEOUT_MILLIS,
    ): OnboardingTurn {
        val api = if (settings.preferCloudModel) {
            WorkflowUrlPolicy.normalize(settings.apiBaseUrl)?.let(ApiFactory::create)
        } else {
            null
        }
        if (api == null) {
            return localTurn(phase, currentStep, answers, answeredFollowups)
        }
        return runCatching {
            withTimeout(timeoutMillis.coerceIn(500L, API_TIMEOUT_MILLIS)) {
                api.onboardingTurn(
                    OnboardingTurnRequestDto(
                        sessionId = sessionId,
                        phase = phase,
                        currentStep = currentStep,
                        answers = answers,
                        answeredFollowups = answeredFollowups,
                        timezone = java.time.ZoneId.systemDefault().id,
                    ),
                )
            }
        }.map { response ->
            val question = response.nextQuestion?.let { remote ->
                require(remote.topic !in answeredFollowups) { "AI 重复了已回答的问题" }
                val allowed = ALLOWED_FOLLOWUPS[remote.topic]
                    ?: error("AI 返回了不受支持的问题")
                require(remote.options.size in 2..4) { "AI 问题选项数量无效" }
                require(remote.options.map { it.id }.distinct().size == remote.options.size) {
                    "AI 问题选项重复"
                }
                require(remote.options.all { it.id in allowed }) { "AI 问题选项越界" }
                OnboardingFollowupQuestion(
                    id = remote.id,
                    topic = remote.topic,
                    prompt = remote.prompt.take(80),
                    choices = remote.options.map {
                        OnboardingChoice(it.id, it.label.take(20))
                    },
                )
            }
            val fallbackMessage = localTurn(
                phase,
                currentStep,
                answers,
                answeredFollowups,
            ).assistantMessage
            OnboardingTurn(
                assistantMessage = safeAssistantMessage(
                    response.assistantMessage,
                    fallbackMessage,
                ),
                nextQuestion = question,
                mood = response.mood,
                animationHint = response.animationHint,
                complete = response.complete,
                enhancementStatus = response.enhancementStatus,
            )
        }.getOrElse {
            if (it is CancellationException) throw it
            localTurn(phase, currentStep, answers, answeredFollowups).copy(
                enhancementStatus = "degraded",
            )
        }
    }

    internal fun localTurn(
        phase: String,
        currentStep: String,
        answers: Map<String, String>,
        answeredFollowups: List<String>,
    ): OnboardingTurn {
        if (phase == "final_summary") {
            return OnboardingTurn(
                assistantMessage = "设置好了，之后也能随时修改。",
                mood = "complete",
                animationHint = "celebrate",
                complete = true,
            )
        }
        if (phase == "core_feedback") {
            val message = when (currentStep) {
                "scenario" -> "好，我会按这个场景安排。"
                "active_period" -> "好，任务会优先放在这个时段。"
                "planning_granularity" -> "好，步骤会保持这个详细程度。"
                else -> "好，提醒会按这个方式出现。"
            }
            return OnboardingTurn(message, mood = "confirm", animationHint = "peek")
        }
        val core = setOf(
            "scenario",
            "active_period",
            "planning_granularity",
            "reminder_style",
        )
        val nextTopic = FOLLOWUP_ORDER.firstOrNull {
            it !in answeredFollowups && it !in answers && answeredFollowups.size < MAX_FOLLOWUPS
        }
        if (!core.all(answers::containsKey) || nextTopic == null) {
            return OnboardingTurn(
                assistantMessage = "这些就够了，之后也能再改。",
                mood = "complete",
                animationHint = "celebrate",
                complete = true,
            )
        }
        val (prompt, choices) = LOCAL_FOLLOWUPS.getValue(nextTopic)
        return OnboardingTurn(
            assistantMessage = "再选一项就好。",
            nextQuestion = OnboardingFollowupQuestion(
                id = "local-${UUID.randomUUID()}",
                topic = nextTopic,
                prompt = prompt,
                choices = choices,
            ),
            mood = "focus",
            animationHint = "scan",
        )
    }

    companion object {
        private const val API_TIMEOUT_MILLIS = 8_000L
        private const val MAX_FOLLOWUPS = 3
        private val FOLLOWUP_ORDER = listOf(
            "work_rhythm",
            "buffer_preference",
            "weekend_policy",
            "assistant_tone",
        )
        private val ALLOWED_FOLLOWUPS = mapOf(
            "work_rhythm" to setOf("steady", "sprint", "adaptive"),
            "buffer_preference" to setOf("compact", "standard", "generous"),
            "weekend_policy" to setOf("avoid", "allow", "flexible"),
            "assistant_tone" to setOf("concise", "warm", "coach"),
        )
        private val LOCAL_FOLLOWUPS = mapOf(
            "work_rhythm" to (
                "你更习惯怎样推进一项重要任务？" to listOf(
                    OnboardingChoice("steady", "均匀推进"),
                    OnboardingChoice("sprint", "集中冲刺"),
                    OnboardingChoice("adaptive", "动态安排"),
                )
            ),
            "buffer_preference" to (
                "你希望在截止前预留多少缓冲？" to listOf(
                    OnboardingChoice("compact", "紧凑"),
                    OnboardingChoice("standard", "标准"),
                    OnboardingChoice("generous", "宽裕"),
                )
            ),
            "weekend_policy" to (
                "规划可以占用周末时间吗？" to listOf(
                    OnboardingChoice("avoid", "尽量避免"),
                    OnboardingChoice("allow", "可以安排"),
                    OnboardingChoice("flexible", "视任务而定"),
                )
            ),
            "assistant_tone" to (
                "你希望墨斐怎样陪你推进？" to listOf(
                    OnboardingChoice("concise", "简洁直接"),
                    OnboardingChoice("warm", "温暖克制"),
                    OnboardingChoice("coach", "教练式"),
                )
            ),
        )

        internal fun safeAssistantMessage(message: String, fallback: String): String {
            val normalized = message.trim().replace(Regex("\\s+"), " ")
            val banned = listOf(
                "AI",
                "ai",
                "智能",
                "模型",
                "画像",
                "个性化",
                "赋能",
                "理解你",
                "高效",
                "授权",
                "绝不泄露",
                "准备就绪",
            )
            return normalized.takeIf {
                it.isNotBlank() &&
                    it.length <= 24 &&
                    banned.none(it::contains) &&
                    it.codePoints().noneMatch { codePoint -> codePoint > 0xFFFF }
            } ?: fallback
        }
    }
}
