package com.suishouban.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.suishouban.app.data.repository.AppSettings
import com.suishouban.app.data.repository.OnboardingChoice
import com.suishouban.app.data.repository.OnboardingFollowupQuestion
import com.suishouban.app.data.repository.OnboardingRepository
import com.suishouban.app.mascot.MascotAnimationHint
import com.suishouban.app.mascot.MascotColorRole
import com.suishouban.app.mascot.MascotCompanion
import com.suishouban.app.mascot.MascotMood
import com.suishouban.app.mascot.MascotState
import com.suishouban.app.ui.theme.BrandBlue
import com.suishouban.app.ui.theme.DS
import com.suishouban.app.ui.theme.Ink
import com.suishouban.app.ui.theme.Muted
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SCREEN_WELCOME = "welcome"
private const val SCREEN_CORE = "core"
private const val SCREEN_FOLLOWUP = "followup"
private const val SCREEN_CONSENT = "consent"
private const val SCREEN_COMPLETE = "complete"

private data class CoreQuestion(
    val id: String,
    val prompt: String,
    val choices: List<OnboardingChoice>,
)

private val coreQuestions = listOf(
    CoreQuestion(
        "scenario",
        "你主要用随手办处理什么？",
        listOf(
            OnboardingChoice("study", "学习"),
            OnboardingChoice("office", "办公"),
            OnboardingChoice("life", "生活"),
            OnboardingChoice("mixed", "混合"),
        ),
    ),
    CoreQuestion(
        "active_period",
        "你通常在哪个时段处理任务？",
        listOf(
            OnboardingChoice("morning", "上午"),
            OnboardingChoice("afternoon", "下午"),
            OnboardingChoice("evening", "晚上"),
            OnboardingChoice("flexible", "灵活"),
        ),
    ),
    CoreQuestion(
        "planning_granularity",
        "你希望计划有多细？",
        listOf(
            OnboardingChoice("concise", "里程碑"),
            OnboardingChoice("balanced", "均衡"),
            OnboardingChoice("detailed", "详细"),
        ),
    ),
    CoreQuestion(
        "reminder_style",
        "怎样的提醒密度更适合你？",
        listOf(
            OnboardingChoice("key_only", "关键节点"),
            OnboardingChoice("standard", "标准"),
            OnboardingChoice("multi_stage", "多阶段"),
        ),
    ),
)

@Composable
fun OnboardingScreen(
    settings: AppSettings,
    reduceMotion: Boolean,
    onSkip: () -> Unit,
    onComplete: (
        scenario: String,
        activePeriod: String,
        granularity: String,
        reminderStyle: String,
        workRhythm: String,
        bufferPreference: String,
        weekendPolicy: String,
        assistantTone: String,
        learningConsent: Boolean,
        explicitFields: Set<String>,
    ) -> Unit,
) {
    val repository = remember { OnboardingRepository() }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val sessionId = rememberSaveable { UUID.randomUUID().toString() }
    var screen by rememberSaveable { mutableStateOf(SCREEN_WELCOME) }
    var coreIndex by rememberSaveable { mutableIntStateOf(0) }
    var turnRevision by remember { mutableIntStateOf(0) }
    var answerBlob by rememberSaveable { mutableStateOf("") }
    var answeredFollowupBlob by rememberSaveable { mutableStateOf("") }
    var followupTopic by rememberSaveable { mutableStateOf("") }
    var followupPrompt by rememberSaveable { mutableStateOf("") }
    var followupChoicesBlob by rememberSaveable { mutableStateOf("") }
    var assistantMessage by rememberSaveable {
        mutableStateOf("你好，我是墨斐。")
    }
    var assistantMood by rememberSaveable { mutableStateOf("idle") }
    var assistantAnimation by rememberSaveable { mutableStateOf("breathe") }
    var learningConsent by rememberSaveable { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    val answers = remember(answerBlob) { decodeAnswers(answerBlob) }
    val answeredFollowups = remember(answeredFollowupBlob) {
        answeredFollowupBlob.split(",").filter(String::isNotBlank)
    }
    val followupQuestion = remember(followupTopic, followupPrompt, followupChoicesBlob) {
        followupTopic.takeIf(String::isNotBlank)?.let {
            OnboardingFollowupQuestion(
                id = "followup-$it",
                topic = it,
                prompt = followupPrompt,
                choices = decodeChoices(followupChoicesBlob),
            )
        }
    }

    fun saveAnswer(key: String, value: String) {
        answerBlob = encodeAnswers(answers + (key to value))
        if (!reduceMotion) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    fun showFollowup(question: OnboardingFollowupQuestion?) {
        followupTopic = question?.topic.orEmpty()
        followupPrompt = question?.prompt.orEmpty()
        followupChoicesBlob = encodeChoices(question?.choices.orEmpty())
        screen = if (question == null) SCREEN_CONSENT else SCREEN_FOLLOWUP
    }

    fun applyTurn(turn: com.suishouban.app.data.repository.OnboardingTurn) {
        assistantMessage = turn.assistantMessage
        assistantMood = turn.mood
        assistantAnimation = turn.animationHint
    }

    fun requestFollowup(updatedAnswers: Map<String, String>, completed: List<String>) {
        val localTurn = repository.localTurn(
            phase = "followup",
            currentStep = completed.lastOrNull().orEmpty(),
            answers = updatedAnswers,
            answeredFollowups = completed,
        )
        applyTurn(localTurn)
        showFollowup(localTurn.nextQuestion)
        turnRevision += 1
        val revision = turnRevision
        val expectedScreen = screen
        scope.launch {
            val enhancedTurn = repository.requestTurn(
                settings = settings,
                sessionId = sessionId,
                phase = "followup",
                currentStep = completed.lastOrNull().orEmpty(),
                answers = updatedAnswers,
                answeredFollowups = completed,
                timeoutMillis = 8_000L,
            )
            if (revision == turnRevision && screen == expectedScreen) {
                applyTurn(enhancedTurn)
            }
        }
    }

    fun continueCore(value: String?) {
        val question = coreQuestions[coreIndex]
        val updated = if (value == null) {
            (answers - question.id).also { answerBlob = encodeAnswers(it) }
        } else {
            saveAnswer(question.id, value)
            answers + (question.id to value)
        }
        val isLastCoreQuestion = coreIndex == coreQuestions.lastIndex
        turnRevision += 1
        val revision = turnRevision
        if (!isLastCoreQuestion) coreIndex += 1
        if (!isLastCoreQuestion) {
            scope.launch {
                val feedback = repository.requestTurn(
                    settings = settings,
                    sessionId = sessionId,
                    phase = "core_feedback",
                    currentStep = question.id,
                    answers = updated,
                    answeredFollowups = answeredFollowups,
                    timeoutMillis = 8_000L,
                )
                if (revision == turnRevision) applyTurn(feedback)
            }
        }
        if (isLastCoreQuestion) {
            requestFollowup(updated, answeredFollowups)
        }
    }

    fun continueFollowup(value: String?) {
        val question = followupQuestion ?: return
        val updatedAnswers = if (value == null) {
            (answers - question.topic).also { answerBlob = encodeAnswers(it) }
        } else {
            saveAnswer(question.topic, value)
            answers + (question.topic to value)
        }
        val completed = (answeredFollowups + question.topic).distinct().take(3)
        answeredFollowupBlob = completed.joinToString(",")
        requestFollowup(updatedAnswers, completed)
    }

    LaunchedEffect(screen) {
        if (screen != SCREEN_COMPLETE) return@LaunchedEffect
        if (!reduceMotion) delay(900)
        val final = decodeAnswers(answerBlob)
        onComplete(
            final["scenario"] ?: "unspecified",
            final["active_period"] ?: "unspecified",
            final["planning_granularity"] ?: "balanced",
            final["reminder_style"] ?: "standard",
            final["work_rhythm"] ?: "adaptive",
            final["buffer_preference"] ?: "standard",
            final["weekend_policy"] ?: "flexible",
            final["assistant_tone"] ?: "warm",
            learningConsent,
            final.keys,
        )
    }

    val selected = when (screen) {
        SCREEN_CORE -> answers[coreQuestions[coreIndex].id]
        SCREEN_FOLLOWUP -> followupQuestion?.let { answers[it.topic] }
        else -> null
    }
    val mascot = onboardingMascotState(
        screen = screen,
        loading = loading,
        selected = selected != null,
        message = assistantMessage,
        assistantMood = assistantMood,
        assistantAnimation = assistantAnimation,
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFFF1F6FF), Color.White, Color(0xFFF7F3FF)),
                ),
            )
            .systemBarsPadding()
            .padding(horizontal = DS.ScreenPadding, vertical = 36.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 440.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            MascotCompanion(
                state = mascot,
                mascotSize = if (screen == SCREEN_WELCOME || screen == SCREEN_COMPLETE) 112.dp else 88.dp,
                reduceMotion = reduceMotion,
                showMessage = false,
            )
            TypewriterMessage(
                text = assistantMessage,
                reduceMotion = reduceMotion,
            )
            AnimatedContent(
                targetState = screen to if (screen == SCREEN_CORE) coreIndex else followupTopic,
                transitionSpec = {
                    if (reduceMotion) {
                        fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                    } else {
                        (
                            slideIntoContainer(
                                AnimatedContentTransitionScope.SlideDirection.Left,
                                tween(280),
                            ) + fadeIn(tween(220))
                        ) togetherWith (
                            slideOutOfContainer(
                                AnimatedContentTransitionScope.SlideDirection.Left,
                                tween(220),
                            ) + fadeOut(tween(160))
                        )
                    }
                },
                label = "onboarding-question",
                modifier = Modifier.fillMaxWidth(),
            ) { target ->
                when (target.first) {
                    SCREEN_WELCOME -> WelcomeContent(
                        reduceMotion = reduceMotion,
                        onStart = {
                            assistantMessage = "先从你的日常开始。"
                            assistantMood = "focus"
                            assistantAnimation = "scan"
                            screen = SCREEN_CORE
                        },
                        onSkip = onSkip,
                    )
                    SCREEN_CORE -> QuestionContent(
                        progress = "${coreIndex + 1} / ${coreQuestions.size}",
                        progressFraction = (coreIndex + 1f) / coreQuestions.size,
                        prompt = coreQuestions[coreIndex].prompt,
                        choices = coreQuestions[coreIndex].choices,
                        selected = selected,
                        loading = loading,
                        onSelect = { saveAnswer(coreQuestions[coreIndex].id, it) },
                        onContinue = { selected?.let(::continueCore) },
                        onSkip = { continueCore(null) },
                        onBack = {
                            if (coreIndex == 0) screen = SCREEN_WELCOME else coreIndex -= 1
                        },
                    )
                    SCREEN_FOLLOWUP -> followupQuestion?.let { question ->
                        QuestionContent(
                            progress = "再了解一点",
                            progressFraction = ((answeredFollowups.size + 1f) / 3f).coerceAtMost(1f),
                            prompt = question.prompt,
                            choices = question.choices,
                            selected = selected,
                            loading = loading,
                            onSelect = { saveAnswer(question.topic, it) },
                            onContinue = { selected?.let(::continueFollowup) },
                            onSkip = { continueFollowup(null) },
                            onBack = {
                                coreIndex = coreQuestions.lastIndex
                                screen = SCREEN_CORE
                            },
                        )
                    }
                    SCREEN_CONSENT -> ConsentContent(
                        checked = learningConsent,
                        onChecked = { learningConsent = it },
                        onBack = {
                            coreIndex = coreQuestions.lastIndex
                            screen = SCREEN_CORE
                        },
                        onFinish = { screen = SCREEN_COMPLETE },
                    )
                    else -> CompletionContent()
                }
            }
        }
    }
}

@Composable
private fun WelcomeContent(
    reduceMotion: Boolean,
    onStart: () -> Unit,
    onSkip: () -> Unit,
) {
    var visible by remember { mutableStateOf(reduceMotion) }
    LaunchedEffect(reduceMotion) {
        if (!reduceMotion) {
            delay(140)
            visible = true
        }
    }
    AnimatedVisibility(
        visible = visible,
        enter = if (reduceMotion) {
            fadeIn(tween(0))
        } else {
            fadeIn(tween(320)) + slideInVertically(tween(360)) { it / 5 }
        },
        exit = fadeOut(tween(if (reduceMotion) 0 else 120)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "你平时怎么安排事情？",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = Ink,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
            Text(
                "选几项，之后随时能改。",
                style = MaterialTheme.typography.bodyLarge,
                color = Muted,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
            Spacer(Modifier.height(8.dp))
            PrimaryFullButton("开始", onStart)
            TextButton(onClick = onSkip) {
                Text("以后再设置")
            }
        }
    }
}

@Composable
private fun QuestionContent(
    progress: String,
    progressFraction: Float,
    prompt: String,
    choices: List<OnboardingChoice>,
    selected: String?,
    loading: Boolean,
    onSelect: (String) -> Unit,
    onContinue: () -> Unit,
    onSkip: () -> Unit,
    onBack: () -> Unit,
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progressFraction.coerceIn(0f, 1f),
        animationSpec = tween(360),
        label = "onboarding-progress",
    )
    Column(
        modifier = Modifier.fillMaxWidth().testTag("onboarding-question"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(progress, style = MaterialTheme.typography.labelLarge, color = BrandBlue)
        LinearProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier.fillMaxWidth(),
            color = BrandBlue,
            trackColor = BrandBlue.copy(alpha = 0.1f),
        )
        Text(
            prompt,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Ink,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            choices.forEach { choice ->
                QuestionOptionRow(
                    text = choice.label,
                    selected = selected == choice.id,
                    enabled = !loading,
                    onClick = { onSelect(choice.id) },
                )
            }
        }
        Button(
            onClick = onContinue,
            enabled = selected != null && !loading,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(DS.RadiusButton),
            colors = ButtonDefaults.buttonColors(containerColor = BrandBlue),
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(end = 8.dp).height(18.dp),
                    strokeWidth = 2.dp,
                    color = Color.White,
                )
                Text("墨斐正在想…")
            } else {
                Text("继续", fontWeight = FontWeight.SemiBold)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack, enabled = !loading) { Text("返回") }
            TextButton(onClick = onSkip, enabled = !loading) { Text("跳过本题") }
        }
    }
}

@Composable
private fun ConsentContent(
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    onBack: () -> Unit,
    onFinish: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(
            "要根据使用继续调整吗？",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Ink,
        )
        Text(
            "开启后，墨斐会根据你确认过的计划调整建议。可随时关闭。",
            color = Muted,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("继续调整建议", color = Ink, fontWeight = FontWeight.SemiBold)
                Text("在设置中可以随时关闭", style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            Switch(checked = checked, onCheckedChange = onChecked)
        }
        PrimaryFullButton("完成设置", onFinish)
        TextButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("返回修改")
        }
    }
}

@Composable
private fun QuestionOptionRow(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (selected) BrandBlue.copy(alpha = 0.08f) else Color.Transparent,
        animationSpec = tween(180),
        label = "onboarding-option-background",
    )
    val textColor by animateColorAsState(
        targetValue = if (selected) BrandBlue else Ink,
        animationSpec = tween(180),
        label = "onboarding-option-text",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = backgroundColor,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled,
        )
        Text(
            text,
            modifier = Modifier.padding(start = 8.dp),
            style = MaterialTheme.typography.titleMedium,
            color = textColor,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun CompletionContent() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("记下了", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("之后也能在设置里修改。", color = Muted, textAlign = TextAlign.Center)
    }
}

@Composable
private fun PrimaryFullButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(DS.RadiusButton),
        colors = ButtonDefaults.buttonColors(containerColor = BrandBlue),
    ) {
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun TypewriterMessage(text: String, reduceMotion: Boolean) {
    AnimatedContent(
        targetState = text,
        modifier = Modifier.heightIn(min = 36.dp),
        transitionSpec = {
            if (reduceMotion) {
                fadeIn(tween(0)) togetherWith fadeOut(tween(0))
            } else {
                (
                    fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 4 }
                ) togetherWith (
                    fadeOut(tween(120)) + slideOutVertically(tween(120)) { -it / 5 }
                )
            }
        },
        label = "onboarding-assistant-message",
    ) { message ->
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            color = Ink,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

private fun onboardingMascotState(
    screen: String,
    loading: Boolean,
    selected: Boolean,
    message: String,
    assistantMood: String,
    assistantAnimation: String,
): MascotState {
    val mood = when {
        screen == SCREEN_COMPLETE -> MascotMood.COMPLETE
        loading -> MascotMood.FOCUS
        selected || screen == SCREEN_CONSENT -> MascotMood.CONFIRM
        screen == SCREEN_WELCOME -> MascotMood.IDLE
        else -> when (assistantMood.lowercase()) {
            "confirm" -> MascotMood.CONFIRM
            "complete" -> MascotMood.COMPLETE
            "rest" -> MascotMood.REST
            else -> MascotMood.FOCUS
        }
    }
    val animationHint = when {
        loading -> MascotAnimationHint.SCAN
        selected -> MascotAnimationHint.PEEK
        else -> when (assistantAnimation.lowercase()) {
            "peek" -> MascotAnimationHint.PEEK
            "celebrate" -> MascotAnimationHint.CELEBRATE
            "settle" -> MascotAnimationHint.SETTLE
            "scan" -> MascotAnimationHint.SCAN
            else -> MascotAnimationHint.BREATHE
        }
    }
    return MascotState(
        mood = mood,
        userMessage = message,
        colorRole = when (mood) {
            MascotMood.FOCUS -> MascotColorRole.FOCUS
            MascotMood.CONFIRM, MascotMood.COMPLETE -> MascotColorRole.CONFIRM
            else -> MascotColorRole.DEFAULT
        },
        animationHint = animationHint,
    )
}

private fun encodeAnswers(answers: Map<String, String>): String =
    answers.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value}" }

private fun decodeAnswers(blob: String): Map<String, String> =
    blob.split(";").mapNotNull { entry ->
        val split = entry.split("=", limit = 2)
        if (split.size == 2 && split.all(String::isNotBlank)) split[0] to split[1] else null
    }.toMap()

private fun encodeChoices(choices: List<OnboardingChoice>): String =
    choices.joinToString(";;") { "${it.id}|${it.label}" }

private fun decodeChoices(blob: String): List<OnboardingChoice> =
    blob.split(";;").mapNotNull { entry ->
        val split = entry.split("|", limit = 2)
        if (split.size == 2 && split.all(String::isNotBlank)) {
            OnboardingChoice(split[0], split[1])
        } else {
            null
        }
    }
