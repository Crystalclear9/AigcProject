package com.suishouban.app.data.repository

import com.suishouban.app.data.local.NotificationCandidateDao
import com.suishouban.app.data.local.NotificationCandidateEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest

/** Stores only candidates accepted by the local privacy policy. */
class NotificationCandidateRepository(
    private val dao: NotificationCandidateDao,
    private val policy: NotificationCandidatePolicy,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    /** Re-evaluates expiry at the exact next boundary, even when Room emits no database change. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeActive(): Flow<List<NotificationCandidateEntity>> = dao.observeAll().transformLatest { candidates ->
        while (true) {
            val now = nowMillis()
            val active = activeAt(candidates, now)
            emit(active)
            val nextExpiry = active.minOfOrNull(NotificationCandidateEntity::expiresAtMillis)
                ?: awaitCancellation()
            delay((nextExpiry - now).coerceAtLeast(1L))
        }
    }

    fun observeActiveCount(): Flow<Int> = observeActive().map { it.size }.distinctUntilChanged()

    suspend fun ingest(
        input: NotificationCandidateInput,
        allowlist: Set<String>,
    ): NotificationCandidateDecision {
        val decision = policy.evaluate(input, allowlist)
        if (decision != NotificationCandidateDecision.ACCEPT) return decision

        val contentHash = policy.contentHash(input)
        dao.insertIgnore(
            NotificationCandidateEntity(
                // Hash identity makes repository insertion idempotent across reposted system keys.
                id = contentHash,
                notificationKey = input.notificationKey,
                packageName = input.packageName,
                appLabel = input.appLabel,
                title = input.title.trim(),
                body = input.body.trim(),
                postedAtMillis = input.postedAtMillis,
                contentHash = contentHash,
                expiresAtMillis = policy.expiresAt(input.postedAtMillis),
            ),
        )
        return decision
    }

    suspend fun findById(id: String): NotificationCandidateEntity? =
        dao.findById(id)?.takeIf { it.expiresAtMillis > nowMillis() }

    suspend fun delete(id: String) = dao.delete(id)

    suspend fun deleteExpired(): Int = dao.deleteExpired(nowMillis())

    companion object {
        internal fun activeAt(
            candidates: List<NotificationCandidateEntity>,
            nowMillis: Long,
        ): List<NotificationCandidateEntity> = candidates.filter { it.expiresAtMillis > nowMillis }
    }
}
