package com.suishouban.app.data.repository

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import com.google.gson.Gson
import com.suishouban.app.data.local.CardRefinementDao
import com.suishouban.app.data.local.toDomain
import com.suishouban.app.data.local.toEntity
import com.suishouban.app.data.model.ActionCard
import com.suishouban.app.data.model.ActionPlan
import com.suishouban.app.data.model.CardAttachment
import com.suishouban.app.data.model.CardRefinementPreference
import com.suishouban.app.data.model.PlanItem
import com.suishouban.app.data.model.PlanItemKinds
import com.suishouban.app.data.model.PlanItemStatuses
import com.suishouban.app.data.model.PlanStatuses
import com.suishouban.app.data.model.UserProfile
import com.suishouban.app.data.model.toContext
import com.suishouban.app.data.remote.ApiFactory
import com.suishouban.app.data.remote.CardRefinementConfirmRequestDto
import com.suishouban.app.data.remote.CardRefinementOptionsDto
import com.suishouban.app.data.remote.CardRefinementReactRequestDto
import com.suishouban.app.data.remote.CardRefinementRunResponseDto
import com.suishouban.app.data.remote.SuiShouBanApi
import com.suishouban.app.data.remote.toDomain
import com.suishouban.app.data.remote.toDto
import com.suishouban.app.domain.LocalCardRefiner
import com.suishouban.app.domain.LocalRefinementOptions
import com.suishouban.app.ocr.TextRecognitionService
import com.suishouban.app.reminder.ReminderScheduler
import java.io.IOException
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink

data class PendingAttachment(
    val id: String = UUID.randomUUID().toString(),
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
)

data class RefinementDraft(
    val plan: ActionPlan,
    val attachments: List<CardAttachment>,
    val runId: String? = null,
    val revision: Int = plan.revision,
    val warnings: List<String> = emptyList(),
    val modelEnhancementStatus: String = "not_configured",
    val usedCloud: Boolean = false,
)

data class ApplyRefinementResult(
    val savedPlan: ActionPlan,
    val scheduledMilestones: Int,
    val warnings: List<String>,
)

class CardRefinementRepository(
    private val context: Context,
    private val dao: CardRefinementDao,
    private val settingsRepository: AppSettingsRepository,
    private val profileRepository: UserProfileRepository,
    private val reminderScheduler: ReminderScheduler,
    private val textRecognitionService: TextRecognitionService,
) {
    private val resolver: ContentResolver = context.contentResolver
    private val gson = Gson()

    fun observePlan(cardId: String): Flow<ActionPlan?> =
        dao.observePlan(cardId).map { it?.toDomain() }

    fun observeAcceptedPlans(): Flow<List<ActionPlan>> =
        dao.observeAcceptedPlans().map { rows -> rows.map { it.toDomain() } }

    fun observeAttachments(cardId: String): Flow<List<CardAttachment>> =
        dao.observeAttachments(cardId).map { rows -> rows.map { it.toDomain() } }

    fun observePreference(cardId: String): Flow<CardRefinementPreference?> =
        dao.observePreference(cardId).map { it?.toDomain() }

    suspend fun describeAttachments(uris: List<Uri>): List<PendingAttachment> {
        require(uris.size <= MAX_FILES) { "最多选择 $MAX_FILES 个文件" }
        val described = uris.map(::describeAttachment)
        require(described.all { it.sizeBytes in 1..MAX_FILE_BYTES }) {
            "单个文件不能超过 15MB，且不能为空"
        }
        require(described.sumOf(PendingAttachment::sizeBytes) <= MAX_TOTAL_BYTES) {
            "附件总大小不能超过 40MB"
        }
        described.forEach { require(isSupported(it)) { "不支持的文件：${it.displayName}" } }
        return described
    }

    suspend fun startRefinement(
        card: ActionCard,
        attachments: List<PendingAttachment>,
        options: LocalRefinementOptions,
        instruction: String = "",
    ): RefinementDraft {
        val profile = profileRepository.current()
        val api = refinementApiOrNull()
        if (api != null) {
            runCatching {
                val response = api.startCardRefinement(
                    card = gson.toJson(card.toDto()).toRequestBody(JSON),
                    options = gson.toJson(
                        CardRefinementOptionsDto(
                            granularity = options.granularity,
                            includeMilestones = options.includeMilestones,
                            includeWorkBlocks = options.includeWorkBlocks,
                            milestoneReminders = options.milestoneReminders,
                            useProfile = options.useProfile,
                        )
                    ).toRequestBody(JSON),
                    profileContext = if (
                        options.useProfile &&
                        settingsRepository.settings.value.personalizedPlanningEnabled
                    ) {
                        gson.toJson(profile.toContext().toDto()).toRequestBody(JSON)
                    } else {
                        null
                    },
                    instruction = instruction.toRequestBody(TEXT),
                    files = attachments.map(::toMultipart),
                )
                awaitReview(api = api, initial = response)
            }.onSuccess { response ->
                response.plan?.let { planDto ->
                    val plan = planDto.toDomain()
                    return RefinementDraft(
                        plan = plan,
                        attachments = mergeAttachmentDescriptors(card.id, attachments, response),
                        runId = response.runId,
                        revision = response.revision,
                        warnings = response.warnings +
                            response.validationErrors +
                            plan.constraintErrors,
                        modelEnhancementStatus = response.modelEnhancementStatus,
                        usedCloud = true,
                    )
                }
            }.onFailure { error ->
                val fallback = localDraft(card, attachments, options, profile, instruction)
                return fallback.copy(
                    warnings = fallback.warnings +
                        "云端细化不可用，已使用本地规则：${error.message ?: error.javaClass.simpleName}",
                )
            }
        }
        return localDraft(card, attachments, options, profile, instruction)
    }

    suspend fun refineDraft(
        draft: RefinementDraft,
        selectedItemIds: Set<String>,
        instruction: String,
    ): RefinementDraft {
        require(selectedItemIds.isNotEmpty()) { "请至少选择一个计划项" }
        val runId = draft.runId
        val api = refinementApiOrNull()
        if (runId != null && api != null) {
            return runCatching {
                api.reactCardRefinement(
                    runId,
                    CardRefinementReactRequestDto(
                        baseRevision = draft.revision,
                        instruction = instruction,
                        selectedItemIds = selectedItemIds.toList(),
                    ),
                )
            }.map { response ->
                draft.copy(
                    plan = response.plan?.toDomain() ?: draft.plan,
                    revision = response.revision,
                    warnings = response.warnings + response.validationErrors,
                    modelEnhancementStatus = response.modelEnhancementStatus,
                )
            }.getOrElse {
                localReact(draft, selectedItemIds, instruction).copy(
                    warnings = draft.warnings + "AI 调整失败，已保留本地修改建议",
                )
            }
        }
        return localReact(draft, selectedItemIds, instruction)
    }

    suspend fun applyDraft(
        card: ActionCard,
        draft: RefinementDraft,
        selectedItems: List<PlanItem>,
    ): ApplyRefinementResult {
        require(selectedItems.isNotEmpty()) { "请至少保留一个计划项" }
        require(draft.plan.constraintErrors.isEmpty()) {
            "计划仍有约束冲突，请修改或重新生成后再应用"
        }
        val acceptedItems = selectedItems.map {
            it.copy(status = PlanItemStatuses.ACCEPTED)
        }
        var acceptedPlan = draft.plan.copy(
            items = acceptedItems,
            status = PlanStatuses.ACCEPTED,
            updatedAt = OffsetDateTime.now().toString(),
        )
        val api = refinementApiOrNull()
        if (draft.runId != null && api != null) {
            acceptedPlan = runCatching {
                api.confirmCardRefinement(
                    draft.runId,
                    CardRefinementConfirmRequestDto(
                        revision = draft.revision,
                        selectedItemIds = acceptedItems.map(PlanItem::id),
                        items = acceptedItems.map(PlanItem::toDto),
                    ),
                ).plan?.toDomain()
            }.getOrNull() ?: acceptedPlan
        }
        dao.acceptPlan(
            acceptedPlan.toEntity(),
            acceptedPlan.items.map { it.toEntity(acceptedPlan.id) },
            draft.attachments.map { it.toEntity() },
        )
        var scheduled = 0
        val warnings = mutableListOf<String>()
        acceptedPlan.items
            .filter { it.kind == PlanItemKinds.MILESTONE && it.reminderEnabled }
            .forEach { item ->
                val result = reminderScheduler.scheduleMilestone(card, acceptedPlan.id, item)
                if (result.scheduled) scheduled += result.scheduledCount else warnings += result.message
            }
        profileRepository.recordSignal(
            "planning_granularity",
            settingsRepository.settings.value.defaultRefinementGranularity,
        )
        return ApplyRefinementResult(acceptedPlan, scheduled, warnings)
    }

    suspend fun savePreference(preference: CardRefinementPreference) {
        dao.upsertPreference(preference.toEntity())
    }

    private suspend fun localDraft(
        card: ActionCard,
        attachments: List<PendingAttachment>,
        options: LocalRefinementOptions,
        profile: UserProfile,
        instruction: String,
    ): RefinementDraft {
        val extracted = extractLocalEvidence(attachments)
        val plan = LocalCardRefiner.refine(
            card,
            options,
            profile.takeIf { options.useProfile },
            instruction,
            extracted.text,
        )
        val localAttachments = attachments.map {
            val outcome = extracted.outcomes[it.id]
            CardAttachment(
                id = it.id,
                cardId = card.id,
                displayName = it.displayName,
                mimeType = it.mimeType,
                uri = it.uri.toString(),
                sizeBytes = it.sizeBytes,
                sha256 = sha256(it.uri),
                extractionStatus = outcome?.status ?: "pending",
                warning = outcome?.warning ?: "Office 文档需要 HTTPS 增强服务解析",
            )
        }
        return RefinementDraft(
            plan = plan,
            attachments = localAttachments,
            warnings = plan.warnings + localAttachments.mapNotNull(CardAttachment::warning),
        )
    }

    private fun localReact(
        draft: RefinementDraft,
        selectedItemIds: Set<String>,
        instruction: String,
    ): RefinementDraft = draft.copy(
        plan = draft.plan.copy(
            revision = draft.plan.revision + 1,
            generatedBy = "${draft.plan.generatedBy}+local_react",
            items = draft.plan.items.map { item ->
                if (item.id !in selectedItemIds) {
                    item
                } else {
                    item.copy(
                        description = listOf(item.description, "调整要求：$instruction")
                            .filter(String::isNotBlank)
                            .joinToString("\n"),
                        needConfirm = (item.needConfirm + "已按本地规则调整，请复核").distinct(),
                    )
                }
            },
        ),
        revision = draft.revision + 1,
    )

    private suspend fun awaitReview(
        api: SuiShouBanApi,
        initial: CardRefinementRunResponseDto,
    ): CardRefinementRunResponseDto {
        var current = initial
        repeat(POLL_ATTEMPTS) {
            if (current.status !in setOf("queued", "running")) return current
            delay(POLL_DELAY_MILLIS)
            current = api.getCardRefinement(initial.runId)
        }
        throw IOException("细化工作流等待超时")
    }

    private fun refinementApiOrNull() = settingsRepository.settings.value.let { settings ->
        if (!settings.preferCloudModel) return@let null
        WorkflowUrlPolicy.normalize(settings.apiBaseUrl)?.let(ApiFactory::create)
    }

    private fun describeAttachment(uri: Uri): PendingAttachment {
        var name = uri.lastPathSegment ?: "附件"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        if (size < 0) {
            size = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        }
        val mime = resolver.getType(uri) ?: mimeFromName(name)
        return PendingAttachment(
            uri = uri,
            displayName = name,
            mimeType = mime,
            sizeBytes = size,
        )
    }

    private fun toMultipart(input: PendingAttachment): MultipartBody.Part {
        val body = ContentUriRequestBody(resolver, input.uri, input.mimeType, input.sizeBytes)
        return MultipartBody.Part.createFormData("files", input.displayName, body)
    }

    private fun mergeAttachmentDescriptors(
        cardId: String,
        pending: List<PendingAttachment>,
        response: CardRefinementRunResponseDto,
    ): List<CardAttachment> {
        val remaining = pending.toMutableList()
        return response.attachments.map { descriptor ->
            val match = remaining.firstOrNull { it.displayName == descriptor.name }
                ?: remaining.firstOrNull()
            if (match != null) remaining.remove(match)
            descriptor.toDomain(cardId, match?.uri?.toString().orEmpty())
        }
    }

    private fun isSupported(input: PendingAttachment): Boolean {
        if (input.mimeType in SUPPORTED_MIME_TYPES) return true
        return extension(input.displayName) in SUPPORTED_EXTENSIONS
    }

    private fun isLocallyReadable(input: PendingAttachment): Boolean =
        input.mimeType.startsWith("text/") ||
            input.mimeType.startsWith("image/") ||
            extension(input.displayName) == "pdf"

    private suspend fun extractLocalEvidence(
        attachments: List<PendingAttachment>,
    ): LocalEvidence {
        val snippets = mutableListOf<String>()
        val outcomes = mutableMapOf<String, LocalExtractionOutcome>()
        attachments.forEach { attachment ->
            val fileExtension = extension(attachment.displayName)
            val result = runCatching {
                when {
                    attachment.mimeType.startsWith("text/") ||
                        fileExtension in setOf("txt", "md", "markdown") ->
                        resolver.openInputStream(attachment.uri)?.bufferedReader()?.use {
                            it.readText().take(MAX_LOCAL_EVIDENCE_CHARS)
                        }.orEmpty()
                    attachment.mimeType.startsWith("image/") ||
                        fileExtension in setOf("jpg", "jpeg", "png") ->
                        textRecognitionService.recognize(context, attachment.uri)
                    fileExtension == "pdf" || attachment.mimeType == "application/pdf" ->
                        extractPdfOcr(attachment.uri)
                    else -> ""
                }
            }
            result.onSuccess { text ->
                if (text.isBlank()) {
                    outcomes[attachment.id] = LocalExtractionOutcome(
                        status = if (isLocallyReadable(attachment)) "degraded" else "pending",
                        warning = if (isLocallyReadable(attachment)) {
                            "本机未从此文件读取到文字，可配置 HTTPS 增强服务后重试"
                        } else {
                            "Office 文档需要 HTTPS 增强服务解析"
                        },
                    )
                } else {
                    val compact = summarizeLocalEvidence(text)
                    snippets += "${attachment.displayName}：$compact"
                    outcomes[attachment.id] = LocalExtractionOutcome("succeeded", null)
                }
            }.onFailure { error ->
                outcomes[attachment.id] = LocalExtractionOutcome(
                    "failed",
                    "本机解析失败：${error.message ?: error.javaClass.simpleName}",
                )
            }
        }
        return LocalEvidence(
            text = snippets.joinToString("\n").take(MAX_LOCAL_EVIDENCE_CHARS),
            outcomes = outcomes,
        )
    }

    private suspend fun extractPdfOcr(uri: Uri): String {
        val descriptor = resolver.openFileDescriptor(uri, "r")
            ?: throw IOException("无法打开 PDF")
        descriptor.use {
            PdfRenderer(it).use { renderer ->
                val pageCount = minOf(renderer.pageCount, MAX_LOCAL_PDF_PAGES)
                return buildList {
                    repeat(pageCount) { index ->
                        renderer.openPage(index).use { page ->
                            val width = minOf(page.width, PDF_RENDER_MAX_WIDTH).coerceAtLeast(1)
                            val height = (page.height.toDouble() / page.width * width)
                                .toInt()
                                .coerceAtLeast(1)
                            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            try {
                                page.render(
                                    bitmap,
                                    null,
                                    null,
                                    PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                                )
                                textRecognitionService.recognize(bitmap)
                                    .takeIf(String::isNotBlank)
                                    ?.let(::add)
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    }
                }.joinToString("\n").take(MAX_LOCAL_EVIDENCE_CHARS)
            }
        }
    }

    private fun sha256(uri: Uri): String = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        } ?: return@runCatching ""
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    private fun mimeFromName(name: String): String = when (extension(name)) {
        "pdf" -> "application/pdf"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "md", "markdown" -> "text/markdown"
        "txt" -> "text/plain"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        else -> "application/octet-stream"
    }

    private fun extension(name: String): String =
        name.substringAfterLast('.', "").lowercase()

    companion object {
        const val MAX_FILES = 8
        const val MAX_FILE_BYTES = 15L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 40L * 1024 * 1024
        private const val POLL_ATTEMPTS = 80
        private const val POLL_DELAY_MILLIS = 250L
        private const val MAX_LOCAL_EVIDENCE_CHARS = 12_000
        private const val MAX_LOCAL_PDF_PAGES = 5
        private const val PDF_RENDER_MAX_WIDTH = 1_400
        private val JSON = "application/json; charset=utf-8".toMediaTypeOrNull()
        private val TEXT = "text/plain; charset=utf-8".toMediaTypeOrNull()
        private val SUPPORTED_EXTENSIONS = setOf(
            "pdf", "docx", "pptx", "xlsx", "txt", "md", "markdown", "jpg", "jpeg", "png",
        )
        private val SUPPORTED_MIME_TYPES = setOf(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "text/plain",
            "text/markdown",
            "image/jpeg",
            "image/png",
        )
    }
}

private data class LocalExtractionOutcome(
    val status: String,
    val warning: String?,
)

private data class LocalEvidence(
    val text: String,
    val outcomes: Map<String, LocalExtractionOutcome>,
)

internal fun summarizeLocalEvidence(text: String, maxChars: Int = 360): String {
    val actionSignals = Regex(
        "(截止|提交|参加|会议|考试|报名|评审|材料|平台|地点|教室|会议室|邮箱|学习通|" +
            "\\bdeadline\\b|\\bsubmit\\b|\\bmeeting\\b|\\brequired?\\b|\\bdue\\b)",
        RegexOption.IGNORE_CASE,
    )
    val candidates = text.lineSequence()
        .map { line ->
            line.replace(Regex("[#>*`|]+"), " ")
                .replace(Regex("\\[[^]]+]\\([^)]*\\)"), " ")
                .replace(Regex("\\s+"), " ")
                .trim(' ', '-', '·')
        }
        .filter { it.length >= 4 }
        .distinct()
        .take(80)
        .mapIndexed { index, line ->
            val score = actionSignals.findAll(line).count() * 3 +
                Regex("\\d{1,4}[年./-]\\d{1,2}|\\d{1,2}:\\d{2}").findAll(line).count() * 2
            Triple(index, line, score)
        }
        .toList()
    if (candidates.isEmpty()) return ""
    val selected = candidates
        .sortedWith(compareByDescending<Triple<Int, String, Int>> { it.third }.thenBy { it.first })
        .take(4)
        .sortedBy { it.first }
        .map { it.second }
    return selected.joinToString("；").take(maxChars).trim()
}

private class ContentUriRequestBody(
    private val resolver: ContentResolver,
    private val uri: Uri,
    private val mimeType: String,
    private val size: Long,
) : RequestBody() {
    override fun contentType() = mimeType.toMediaTypeOrNull()
    override fun contentLength(): Long = size

    override fun writeTo(sink: BufferedSink) {
        resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                sink.write(buffer, 0, read)
            }
        } ?: throw IOException("无法读取附件")
    }
}
