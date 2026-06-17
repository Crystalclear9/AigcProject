package com.suishouban.app.data.repository

import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Context
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
import com.suishouban.app.data.remote.OcrCandidateRequest
import com.suishouban.app.data.remote.DraftFieldOperation
import com.suishouban.app.data.remote.DraftPatchRequest
import com.suishouban.app.data.remote.WorkflowEventEnvelope
import com.suishouban.app.data.model.NodeTrace
import com.suishouban.app.domain.ActionEnhancementInput
import com.suishouban.app.domain.ActionEnhancer
import com.suishouban.app.domain.LocalRuleActionEnhancer
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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

    suspend fun analyzeImage(uri: Uri, screenshotTime: String? = null): AnalyzeResult? {
        val api = workflowApiOrNull() ?: return null

        return runCatching {
            val imagePart = buildImagePart(uri)
            val timePart = screenshotTime?.toRequestBody("text/plain".toMediaType())
            responseToResult(api.startImageWorkflow(imagePart, timePart))
        }.getOrNull()
    }

    suspend fun analyzeText(text: String, screenshotTime: String? = null, enginePrefix: String? = null): AnalyzeResult {
        val api = workflowApiOrNull()
        if (api != null) {
            val remoteResult = runCatching {
                val response = api.startTextWorkflow(AnalyzeScreenshotTextRequest(text, screenshotTime))
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

    suspend fun resumeWithOcr(runId: String, text: String): AnalyzeResult {
        val api = requireRemoteApi()
        return responseToResult(
            api.resumeWorkflow(
                runId,
                WorkflowResumeRequest(command = "provide_ocr_text", ocrText = text),
            )
        )
    }

    suspend fun submitOcrCandidate(runId: String, text: String): AnalyzeResult {
        val api = requireRemoteApi()
        return responseToResult(api.submitOcrCandidate(runId, OcrCandidateRequest(text)))
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
        val operations = cards.flatMap { card ->
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

    fun activeRunId(): String? {
        val settings = settingsRepository.settings.value
        if (!settings.preferCloudModel || settings.apiBaseUrl.trim().isBlank()) return null
        return workflowPrefs.getString("active_run_id", null)
    }

    fun clearActiveWorkflow() {
        workflowPrefs.edit().remove("active_run_id").remove("last_event_id").apply()
    }

    suspend fun testConnection(): String {
        val api = remoteApiOrNull() ?: return "本机模式：未配置云端增强端点，端侧 OCR、行动判定、卡片和提醒可用"
        val health = api.health()
        return if (health.ready) {
            "云端增强在线，工作流运行时正常（LangGraph ${health.langGraphVersion}）"
        } else {
            "云端端点可访问，但工作流运行时处于 ${health.status}"
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
        )
    }

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
        val confirmed = card.copy(status = CardStatus.CONFIRMED)
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
        dao.upsert(card.toEntity())
    }

    suspend fun update(card: ActionCard) {
        dao.upsert(card.toEntity())
        remoteApiOrNull()?.let { api -> runCatching { api.updateCard(card.id, card.toDto()) } }
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
        val baseUrl = settings.apiBaseUrl.trim().takeIf { it.isNotBlank() } ?: return null
        return ApiFactory.create(baseUrl)
    }

    private fun requireRemoteApi(): com.suishouban.app.data.remote.SuiShouBanApi {
        return remoteApiOrNull() ?: error("未配置云端增强端点")
    }

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
