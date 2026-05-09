package com.suishouban.app.data.repository

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

class ActionCardRepository(
    context: Context,
    private val settingsRepository: AppSettingsRepository,
) {
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

    suspend fun analyzeText(text: String): AnalyzeResult {
        val settings = settingsRepository.settings.value
        if (settings.preferCloudModel) {
            val remoteResult = runCatching {
                val api = ApiFactory.create(settings.apiBaseUrl)
                val response = api.analyzeScreenshotText(AnalyzeScreenshotTextRequest(text))
                AnalyzeResult(
                    ocrText = response.ocrText,
                    cards = response.cards.map { it.toDomain() },
                    previewActions = response.previewActions,
                    engine = response.engine,
                )
            }.getOrNull()
            if (remoteResult != null) return remoteResult
        }
        return extractor.extract(text)
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
