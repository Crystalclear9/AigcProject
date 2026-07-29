package com.suishouban.app.data.repository

import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Context
import android.provider.OpenableColumns
import com.suishouban.app.data.local.AppDatabase
import com.suishouban.app.data.local.toDomain
import com.suishouban.app.data.local.toEntity
import com.suishouban.app.data.model.ActionCard
import com.suishouban.app.data.model.AnalyzeResult
import com.suishouban.app.data.model.CardStatus
import com.suishouban.app.data.remote.AnalyzeScreenshotTextRequest
import com.suishouban.app.data.remote.ApiFactory
import com.suishouban.app.data.remote.toDomain
import com.suishouban.app.data.remote.toDto
import com.suishouban.app.data.remote.WorkflowResumeRequest
import com.suishouban.app.data.remote.WorkflowReactRequest
import com.suishouban.app.data.remote.OcrCandidateRequest
import com.suishouban.app.data.remote.OcrBlockDto
import com.suishouban.app.ocr.StructuredOcrResult
import com.suishouban.app.data.remote.DraftFieldOperation
import com.suishouban.app.data.remote.DraftPatchRequest
import com.suishouban.app.data.remote.WorkflowEventEnvelope
import com.suishouban.app.data.model.NodeTrace
import com.suishouban.app.data.model.UserProfileContext
import com.suishouban.app.data.remote.IntakeSessionResponseDto
import com.suishouban.app.domain.ActionEnhancementInput
import com.suishouban.app.domain.ActionEnhancer
import com.suishouban.app.domain.LocalRuleActionEnhancer
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import retrofit2.HttpException

data class SaveConfirmedResult(
    val card: ActionCard,
    val syncError: String? = null,
)

class ActionCardRepository(
    context: Context,
    private val settingsRepository: AppSettingsRepository,
) {
    private val appContext = context.applicationContext
    private val dao = AppDatabase.get(context).cardDao()
    private val localEnhancer: ActionEnhancer = LocalRuleActionEnhancer()
    private val workflowPrefs = appContext.getSharedPreferences("workflow_runtime", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun observeCards(
        type: String? = null,
        status: String? = null,
        keyword: String? = null,
    ): Flow<List<ActionCard>> {
        val normalizedType = type?.takeIf { it.isNotBlank() && it != "all" }
        val normalizedStatus = status?.takeIf { it.isNotBlank() && it != "all" }
        val normalizedKeyword = keyword?.takeIf { it.isNotBlank() }
        return dao.observeFiltered(normalizedType, normalizedStatus, normalizedKeyword)
            .map { list -> list.map { it.toDomain() } }
    }

    fun observeAll(): Flow<List<ActionCard>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    suspend fun analyzeImage(
        uri: Uri,
        screenshotTime: String? = null,
        profileContext: UserProfileContext? = null,
    ): AnalyzeResult? {
        if (!settingsRepository.settings.value.keepOriginalScreenshot) return null
        val api = workflowApiOrNull() ?: return null

        return runCatching {
            val imagePart = buildImagePart(uri)
            intakeToResult(
                api.startIntake(
                    text = "".textPart(),
                    sourceKind = "screenshot".textPart(),
                    workspaceType = "personal".textPart(),
                    roleTemplate = "action_analyst".textPart(),
                    profileContext = gson.toJson(profileContext ?: emptyMap<String, String>())
                        .toRequestBody(JSON_MEDIA_TYPE),
                    files = listOf(imagePart),
                )
            )
        }.recoverCatching {
            val imagePart = buildImagePart(uri)
            val timePart = screenshotTime?.toRequestBody("text/plain".toMediaType())
            responseToResult(api.startImageWorkflow(imagePart, timePart))
        }.getOrNull()
    }

    suspend fun analyzeFiles(
        uris: List<Uri>,
        sourceKind: String,
        profileContext: UserProfileContext? = null,
        workspaceType: String = "personal",
    ): AnalyzeResult? {
        if (uris.isEmpty()) return null
        val api = workflowApiOrNull() ?: return null
        val parts = uris.take(8).mapIndexed { index, uri -> buildIntakePart(uri, index) }
        return runCatching {
            intakeToResult(
                api.startIntake(
                    text = "".textPart(),
                    sourceKind = sourceKind.textPart(),
                    workspaceType = workspaceType.textPart(),
                    roleTemplate = (
                        if (workspaceType == "team") "team_coordinator" else "action_analyst"
                        ).textPart(),
                    profileContext = gson.toJson(profileContext ?: emptyMap<String, String>())
                        .toRequestBody(JSON_MEDIA_TYPE),
                    files = parts,
                )
            )
        }.getOrNull()
    }

    suspend fun analyzeText(
        text: String,
        screenshotTime: String? = null,
        enginePrefix: String? = null,
        profileContext: UserProfileContext? = null,
        workspaceType: String = "personal",
    ): AnalyzeResult {
        val api = workflowApiOrNull()
        if (api != null) {
            val remoteResult = runCatching {
                intakeToResult(
                    api.startIntake(
                        text = text.cloudSafe().textPart(),
                        sourceKind = "text".textPart(),
                        workspaceType = workspaceType.textPart(),
                        roleTemplate = (
                            if (workspaceType == "team") "team_coordinator" else "action_analyst"
                            ).textPart(),
                        profileContext = gson.toJson(profileContext ?: emptyMap<String, String>())
                            .toRequestBody(JSON_MEDIA_TYPE),
                    )
                ).let { it.copy(engine = prefixEngine(it.engine, enginePrefix)) }
            }.recoverCatching {
                val response = api.startTextWorkflow(
                    AnalyzeScreenshotTextRequest(text.cloudSafe(), screenshotTime)
                )
                responseToResult(response).copy(engine = prefixEngine(response.engine, enginePrefix))
            }.getOrNull()
            if (remoteResult != null) return remoteResult
        }
        val localResult = localEnhancer.enhance(
            ActionEnhancementInput(
                ocrText = text,
                screenshotTime = screenshotTime,
                source = enginePrefix ?: "local",
            )
        )
        return localResult.copy(engine = prefixEngine(localResult.engine, enginePrefix))
    }

    suspend fun analyzeTextLocal(text: String, screenshotTime: String? = null, enginePrefix: String? = null): AnalyzeResult {
        val localResult = localEnhancer.enhance(
            ActionEnhancementInput(
                ocrText = text,
                screenshotTime = screenshotTime,
                source = enginePrefix ?: "local",
            )
        )
        return localResult.copy(engine = prefixEngine(localResult.engine, enginePrefix))
    }

    suspend fun resumeWithOcr(runId: String, text: String): AnalyzeResult {
        val api = requireRemoteApi()
        return responseToResult(
            api.resumeWorkflow(
                runId,
                WorkflowResumeRequest(command = "provide_ocr_text", ocrText = text.cloudSafe()),
            )
        )
    }

    suspend fun submitOcrCandidate(
        runId: String,
        text: String,
        structured: StructuredOcrResult? = null,
    ): AnalyzeResult {
        val api = requireRemoteApi()
        return responseToResult(
            api.submitOcrCandidate(
                runId,
                OcrCandidateRequest(
                    text = text.cloudSafe(),
                    engine = structured?.let { "mlkit:${it.variant}" } ?: "mlkit",
                    blocks = structured?.blocks?.map { block ->
                        OcrBlockDto(
                            id = block.id,
                            text = block.text.cloudSafe(),
                            left = block.left,
                            top = block.top,
                            right = block.right,
                            bottom = block.bottom,
                            readingOrder = block.readingOrder,
                            pageIndex = block.pageIndex,
                        )
                    }.orEmpty(),
                    arrivedAtMs = System.currentTimeMillis(),
                    imageWidth = structured?.imageWidth,
                    imageHeight = structured?.imageHeight,
                    rotationDegrees = structured?.rotationDegrees ?: 0,
                    variant = structured?.variant ?: "original",
                ),
            )
        )
    }

    suspend fun resolveOcr(runId: String, correctedText: String): AnalyzeResult {
        val api = requireRemoteApi()
        return responseToResult(
            api.resolveOcr(
                runId,
                OcrCandidateRequest(
                    text = correctedText.cloudSafe(),
                    engine = "user-corrected",
                    confidence = 1.0,
                ),
            ),
        )
    }

    suspend fun followWorkflow(runId: String, onUpdate: (AnalyzeResult) -> Unit): AnalyzeResult {
        val api = requireRemoteApi()
        var latest = responseToResult(api.getWorkflow(runId))
        onUpdate(latest)
        if (workflowPrefs.getString("active_run_id", null) != runId) {
            workflowPrefs.edit()
                .putString("active_run_id", runId)
                .remove("last_event_id")
                .apply()
        }
        return withContext(Dispatchers.IO) {
            var attempt = 0
            while (attempt < 4 && latest.workflowStatus !in streamTerminalWorkflowStatuses) {
                try {
                    val lastEventId = workflowPrefs.getLong("last_event_id", 0)
                        .takeIf { it > 0 }
                        ?.toString()
                    api.workflowEvents(runId, lastEventId).use { body ->
                        body.charStream().buffered().useLines { lines ->
                            var eventName = ""
                            var eventId: Long? = null
                            var data = ""
                            for (line in lines) {
                                when {
                                    line.startsWith("id:") -> eventId = line.substringAfter(":").trim().toLongOrNull()
                                    line.startsWith("event:") -> eventName = line.substringAfter(":").trim()
                                    line.startsWith("data:") -> data += line.substringAfter(":").trim()
                                    line.isBlank() && eventName.isNotBlank() -> {
                                        val snapshot = runCatching {
                                            gson.fromJson(data, WorkflowEventEnvelope::class.java).snapshot
                                        }.getOrElse {
                                            throw IllegalStateException("工作流事件解析失败，请重试或查看诊断", it)
                                        }
                                        if (snapshot != null) {
                                            latest = responseToResult(snapshot)
                                            withContext(Dispatchers.Main) { onUpdate(latest) }
                                        } else if (eventName in snapshotRequiredEvents) {
                                            latest = responseToResult(api.getWorkflow(runId))
                                            withContext(Dispatchers.Main) { onUpdate(latest) }
                                        }
                                        eventId?.let {
                                            workflowPrefs.edit().putLong("last_event_id", it).apply()
                                        }
                                        if (eventName == "completed" || eventName == "failed") break
                                        eventName = ""
                                        eventId = null
                                        data = ""
                                    }
                                }
                            }
                        }
                    }
                    attempt = 0
                } catch (error: Exception) {
                    attempt += 1
                    if (attempt >= 4) {
                        if (error is IllegalStateException && error.message?.startsWith("工作流事件解析失败") == true) {
                            throw error
                        }
                        throw IllegalStateException("工作流事件流中断，请重试或查看诊断", error)
                    }
                    kotlinx.coroutines.delay(500L shl (attempt - 1))
                }
            }
            if (latest.workflowStatus in terminalWorkflowStatuses) clearActiveWorkflow()
            latest
        }
    }

    suspend fun reviewAndConfirm(
        runId: String,
        baseRevision: Int,
        cards: List<ActionCard>,
        fieldVersions: Map<String, Map<String, Int>>,
    ): AnalyzeResult {
        val api = requireRemoteApi()
        val operations = cards.map { it.safeForCloud() }.flatMap { card ->
            editableFields(card).flatMap { (field, value) ->
                listOf(
                    DraftFieldOperation(
                        operation = "set",
                        cardId = card.id,
                        field = field,
                        value = value,
                        baseFieldVersion = fieldVersions[card.id]?.get(field),
                    ),
                    DraftFieldOperation(operation = "lock", cardId = card.id, field = field),
                )
            }
        }
        val patched = api.patchDraft(
            runId,
            DraftPatchRequest(baseRevision = baseRevision, operations = operations),
        )
        val confirmed = api.confirmWorkflow(runId, com.suishouban.app.data.remote.ConfirmWorkflowRequest(patched.revision))
        clearActiveWorkflow()
        return responseToResult(confirmed)
    }

    suspend fun refineWithReact(
        runId: String,
        baseRevision: Int,
        instruction: String,
        selectedCardIds: List<String>,
    ): AnalyzeResult {
        val api = requireRemoteApi()
        return responseToResult(
            api.reactWorkflow(
                runId,
                WorkflowReactRequest(
                    baseRevision = baseRevision,
                    instruction = instruction,
                    selectedCardIds = selectedCardIds,
                ),
            )
        )
    }

    fun activeRunId(): String? {
        val settings = settingsRepository.settings.value
        if (!settings.preferCloudModel || settings.apiBaseUrl.trim().isBlank()) return null
        return workflowPrefs.getString("active_run_id", null)
    }

    fun clearActiveWorkflow() {
        workflowPrefs.edit().remove("active_run_id").remove("last_event_id").apply()
    }

    suspend fun testConnection(): String {
        val api = remoteApiOrNull() ?: return "当前未配置 AI 增强服务，手机端 OCR、行动判定、卡片和提醒可用"
        val health = api.health()
        if (!health.ready) {
            return "\u4e91\u7aef\u7aef\u70b9\u53ef\u8bbf\u95ee\uff0c\u4f46\u5de5\u4f5c\u6d41\u8fd0\u884c\u65f6\u5904\u4e8e ${health.status}"
        }
        val probe = runCatching { api.providerProbe() }
        return if (probe.isSuccess && probe.getOrThrow().allSucceeded) {
            listOf(
                "\u4e91\u7aef\u914d\u7f6e\u53ef\u7528",
                "vivo \u6a21\u578b\u5df2\u5b9e\u9645\u8c03\u7528",
                "vivo OCR \u5df2\u5b9e\u9645\u8c03\u7528",
                "\u56fe\u7247\u751f\u6210\u63a2\u9488\u5df2\u901a\u8fc7",
                "\u5de5\u4f5c\u6d41\u8fd0\u884c\u65f6\u6b63\u5e38\uff08LangGraph ${health.langGraphVersion}\uff09",
            ).joinToString("\n")
        } else if (probe.exceptionOrNull() is HttpException && (probe.exceptionOrNull() as HttpException).code() == 403) {
            listOf(
                "\u4e91\u7aef\u914d\u7f6e\u53ef\u7528",
                "\u5de5\u4f5c\u6d41\u8fd0\u884c\u65f6\u6b63\u5e38\uff08LangGraph ${health.langGraphVersion}\uff09",
                "provider \u63a2\u9488\u672a\u542f\u7528\uff0c\u65e0\u6cd5\u8bc1\u660e vivo API \u5df2\u5b9e\u9645\u8c03\u7528",
            ).joinToString("\n")
        } else {
            val error = probe.exceptionOrNull()?.javaClass?.simpleName ?: "provider_probe_failed"
            "\u4e91\u7aef\u914d\u7f6e\u53ef\u7528\uff0c\u4f46 vivo API \u5b9e\u9645\u8c03\u7528\u63a2\u9488\u5931\u8d25\uff1a$error"
        }
    }

    private fun responseToResult(response: com.suishouban.app.data.remote.AnalyzeScreenshotTextResponse): AnalyzeResult {
        return AnalyzeResult(
            ocrText = response.ocrText,
            cards = response.cards.map { it.toDomain() },
            previewActions = response.previewActions,
            engine = response.engine,
            traceId = response.runId.ifBlank { response.traceId },
            fallbackReason = response.fallbackReason,
            warnings = response.warnings,
            workflowStatus = response.workflowStatus,
            pendingAction = response.pendingAction,
            nodeTrace = response.nodeTrace.map {
                NodeTrace(it.node, it.status, it.durationMs, it.engine, it.detail)
            },
            revision = response.revision,
            resultStage = response.resultStage,
            overallConfidence = response.overallConfidence,
            route = response.route,
            cacheStatus = response.cacheStatus ?: "bypass",
            timeToFirstDraftMs = response.timeToFirstDraftMs,
            timeToFinalMs = response.timeToFinalMs,
            activeAgents = response.activeAgents,
            decisionReasons = response.decisionReasons,
            riskLevel = response.riskLevel,
            validationErrors = response.validationErrors,
            fieldConflicts = response.fieldConflicts,
            fieldVersions = response.fieldVersions,
            providerUsage = response.providerUsage.mapValues { it.value.toDomain() },
            modelEnhancementStatus = response.modelEnhancementStatus,
            ocrEnhancementStatus = response.ocrEnhancementStatus,
            ocrQualityReport = response.ocrQualityReport?.toDomain(),
            ocrReviewReasons = response.ocrReviewReasons,
            imageGenerationStatus = response.imageGenerationStatus,
            reactSuggestions = response.reactSuggestions,
        )
    }

    private fun intakeToResult(response: IntakeSessionResponseDto): AnalyzeResult {
        response.workflow?.let { workflow ->
            return responseToResult(workflow).copy(
                ocrText = response.canonicalText.ifBlank { workflow.ocrText },
                warnings = response.warnings + workflow.warnings,
            )
        }
        return AnalyzeResult(
            ocrText = response.canonicalText,
            cards = response.cards.map { it.toDomain() },
            previewActions = emptyList(),
            engine = "intake:${response.classification}",
            traceId = response.workflowRunId ?: response.sessionId,
            warnings = response.warnings,
            workflowStatus = if (response.shouldCreateCards) "running" else "completed",
            resultStage = if (response.shouldCreateCards) "provisional" else "final",
            overallConfidence = response.classificationConfidence,
            route = if (response.shouldCreateCards) "intake_graph" else "classification_only",
        )
    }

    private fun String.textPart(): RequestBody =
        toRequestBody("text/plain; charset=utf-8".toMediaType())

    private fun editableFields(card: ActionCard): List<Pair<String, Any?>> = listOf(
        "card_type" to card.cardType,
        "title" to card.title,
        "summary" to card.summary,
        "deadline" to card.deadline,
        "start_time" to card.startTime,
        "end_time" to card.endTime,
        "location" to card.location,
        "materials" to card.materials,
        "submit_method" to card.submitMethod,
        "priority" to card.priority,
        "tags" to card.tags,
        "reminders" to card.reminders,
        "need_confirm" to card.needConfirm,
    )

    private fun prefixEngine(engine: String, prefix: String?): String {
        return EngineLabels.withPrefix(engine, prefix)
    }

    private fun buildImagePart(uri: Uri): MultipartBody.Part {
        val bytes = readCompressedJpeg(uri)
        val body = bytes.toRequestBody("image/jpeg".toMediaType())
        return MultipartBody.Part.createFormData("image", "screenshot.jpg", body)
    }

    private fun buildIntakePart(uri: Uri, index: Int): MultipartBody.Part {
        val resolver = appContext.contentResolver
        val declaredMime = resolver.getType(uri).orEmpty()
        val (bytes, mime, fallbackName) = if (declaredMime.startsWith("image/")) {
            Triple(readCompressedJpeg(uri), "image/jpeg", "image-$index.jpg")
        } else {
            val data = resolver.openInputStream(uri)?.use { input ->
                input.readNBytes(MAX_INTAKE_FILE_BYTES + 1)
            } ?: error("Unable to read attachment")
            require(data.size <= MAX_INTAKE_FILE_BYTES) { "Attachment exceeds 15 MB" }
            Triple(
                data,
                declaredMime.ifBlank { "application/octet-stream" },
                "attachment-$index",
            )
        }
        val displayName = resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }.orEmpty().ifBlank { fallbackName }
        return MultipartBody.Part.createFormData(
            "files",
            displayName,
            bytes.toRequestBody(mime.toMediaType()),
        )
    }

    private fun readCompressedJpeg(uri: Uri): ByteArray {
        val resolver = appContext.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }

        val sampleSize = ImageUploadPolicy.calculateSampleSize(bounds.outWidth, bounds.outHeight)
        val bitmapOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bitmapOptions) }
            ?: error("无法读取图片")

        return bitmap.useAndCompress()
    }

    private fun Bitmap.useAndCompress(): ByteArray {
        try {
            var quality = 88
            var bytes: ByteArray
            do {
                val output = ByteArrayOutputStream()
                // 云端 OCR 对超大图的上传耗时敏感；JPEG 在保留文字可读性的同时压低请求体。
                compress(Bitmap.CompressFormat.JPEG, quality, output)
                bytes = output.toByteArray()
                quality -= 10
            } while (bytes.size > ImageUploadPolicy.MAX_UPLOAD_BYTES && quality >= 48)
            return bytes
        } finally {
            recycle()
        }
    }

    suspend fun saveConfirmed(card: ActionCard): SaveConfirmedResult {
        val confirmed = card.safeForLocalStorage().copy(status = CardStatus.CONFIRMED)
        dao.upsert(confirmed.toEntity())
        val api = remoteApiOrNull()
        if (api == null) return SaveConfirmedResult(confirmed)
        val remoteAttempt = withTimeoutOrNull(1_500) {
            runCatching { api.createCard(confirmed.toDto()) }
        }
        val syncError = when {
            remoteAttempt == null -> "Remote sync timed out; the card is saved locally."
            remoteAttempt.isFailure -> "Remote sync failed; the card is saved locally."
            else -> null
        }
        return SaveConfirmedResult(confirmed, syncError)
    }

    suspend fun saveDraft(card: ActionCard) {
        dao.upsert(card.safeForLocalStorage().toEntity())
    }

    suspend fun update(card: ActionCard) {
        val safeCard = card.safeForLocalStorage()
        dao.upsert(safeCard.toEntity())
        remoteApiOrNull()?.let { api -> runCatching { api.updateCard(safeCard.id, safeCard.safeForCloud().toDto()) } }
    }

    suspend fun replan(card: ActionCard, changedFields: List<String>): ActionCard? {
        val api = remoteApiOrNull() ?: return null
        val request = com.suishouban.app.data.remote.CardReplanRequestDto(
            changedFields = changedFields.distinct(),
            priorityMode = card.priorityMode,
            manualPriority = card.priority.takeIf { card.priorityLocked },
            importance = when (card.priority) {
                "high" -> 0.85
                "low" -> 0.25
                else -> 0.5
            },
            blockedDependents = card.dependencies.size,
            teamImpact = if (card.workspaceType == "team") 0.7 else 0.0,
        )
        return runCatching { api.replanCard(card.id, request).card.toDomain() }.getOrNull()
    }

    suspend fun complete(id: String) {
        dao.updateStatus(id, CardStatus.DONE)
        remoteApiOrNull()?.let { api -> runCatching { api.completeCard(id) } }
    }

    suspend fun archive(id: String) {
        dao.updateStatus(id, CardStatus.ARCHIVED)
    }

    suspend fun syncFromServer() {
        val cards = requireRemoteApi()
            .listCards()
            .map { it.toDomain().toEntity() }
        dao.upsertAll(cards)
    }

    private fun workflowApiOrNull(): com.suishouban.app.data.remote.SuiShouBanApi? {
        val settings = settingsRepository.settings.value
        if (!settings.preferCloudModel) return null
        return remoteApiOrNull(settings)
    }

    private fun remoteApiOrNull(settings: AppSettings = settingsRepository.settings.value): com.suishouban.app.data.remote.SuiShouBanApi? {
        val baseUrl = WorkflowUrlPolicy.normalize(settings.apiBaseUrl) ?: return null
        return ApiFactory.create(baseUrl)
    }

    private fun requireRemoteApi(): com.suishouban.app.data.remote.SuiShouBanApi {
        return remoteApiOrNull() ?: error("未配置云端增强端点")
    }

    private fun String.cloudSafe(): String {
        return if (settingsRepository.settings.value.privacyMask) maskSensitiveText() else this
    }

    private fun ActionCard.safeForCloud(): ActionCard {
        return if (settingsRepository.settings.value.privacyMask) {
            copy(
                sourceText = sourceText.maskSensitiveText(),
                summary = summary.maskSensitiveText(),
                evidenceSummary = evidenceSummary.map { it.maskSensitiveText() },
            )
        } else {
            this
        }
    }

    private fun ActionCard.safeForLocalStorage(): ActionCard {
        return if (settingsRepository.settings.value.privacyMask) {
            copy(sourceText = sourceText.maskSensitiveText())
        } else {
            this
        }
    }

}

private fun String.maskSensitiveText(): String {
    if (isBlank()) return this
    return this
        .replace(Regex("""(?<!\d)1[3-9]\d{9}(?!\d)"""), "手机号[已脱敏]")
        .replace(Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"""), "邮箱[已脱敏]")
        .replace(Regex("""\b(?:\d[ -]?){15,19}\b"""), "账号[已脱敏]")
        .replace(Regex("""((?:微信|QQ|账号|学号|工号)[:：]?\s*)[A-Za-z0-9_\-]{5,}"""), "$1[已脱敏]")
}

private val terminalWorkflowStatuses = setOf("completed", "failed", "cancelled")
private val streamTerminalWorkflowStatuses = terminalWorkflowStatuses + "awaiting_review"
private val snapshotRequiredEvents = setOf(
    "draft_created",
    "draft_updated",
    "review_required",
    "completed",
    "failed",
)
private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
private const val MAX_INTAKE_FILE_BYTES = 15 * 1024 * 1024
