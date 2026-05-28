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
import com.suishouban.app.domain.LocalActionExtractor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream

class ActionCardRepository(
    context: Context,
    private val settingsRepository: AppSettingsRepository,
) {
    private val appContext = context.applicationContext
    private val dao = AppDatabase.get(context).cardDao()
    private val extractor = LocalActionExtractor()

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
        val settings = settingsRepository.settings.value
        if (!settings.preferCloudModel) return null

        return runCatching {
            val api = ApiFactory.create(settings.apiBaseUrl)
            val imagePart = buildImagePart(uri)
            val timePart = screenshotTime?.toRequestBody("text/plain".toMediaType())
            val response = api.analyzeScreenshotImage(imagePart, timePart)
            AnalyzeResult(
                ocrText = response.ocrText,
                cards = response.cards.map { it.toDomain() },
                previewActions = response.previewActions,
                engine = response.engine,
            )
        }.getOrNull()
    }

    suspend fun analyzeText(text: String, screenshotTime: String? = null, enginePrefix: String? = null): AnalyzeResult {
        val settings = settingsRepository.settings.value
        if (settings.preferCloudModel) {
            val remoteResult = runCatching {
                val api = ApiFactory.create(settings.apiBaseUrl)
                val response = api.analyzeScreenshotText(AnalyzeScreenshotTextRequest(text, screenshotTime))
                AnalyzeResult(
                    ocrText = response.ocrText,
                    cards = response.cards.map { it.toDomain() },
                    previewActions = response.previewActions,
                    engine = prefixEngine(response.engine, enginePrefix),
                )
            }.getOrNull()
            if (remoteResult != null) return remoteResult
        }
        val localResult = extractor.extract(text)
        return localResult.copy(engine = prefixEngine(localResult.engine, enginePrefix))
    }

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

    suspend fun saveConfirmed(card: ActionCard): ActionCard {
        val confirmed = card.copy(status = CardStatus.CONFIRMED)
        dao.upsert(confirmed.toEntity())
        runCatching {
            ApiFactory.create(settingsRepository.settings.value.apiBaseUrl).createCard(confirmed.toDto())
        }
        return confirmed
    }

    suspend fun saveDraft(card: ActionCard) {
        dao.upsert(card.toEntity())
    }

    suspend fun update(card: ActionCard) {
        dao.upsert(card.toEntity())
        runCatching {
            ApiFactory.create(settingsRepository.settings.value.apiBaseUrl).updateCard(card.id, card.toDto())
        }
    }

    suspend fun complete(id: String) {
        dao.updateStatus(id, CardStatus.DONE)
        runCatching {
            ApiFactory.create(settingsRepository.settings.value.apiBaseUrl).completeCard(id)
        }
    }

    suspend fun archive(id: String) {
        dao.updateStatus(id, CardStatus.ARCHIVED)
    }

    suspend fun syncFromServer() {
        runCatching {
            val cards = ApiFactory.create(settingsRepository.settings.value.apiBaseUrl)
                .listCards()
                .map { it.toDomain().toEntity() }
            dao.upsertAll(cards)
        }
    }

}
