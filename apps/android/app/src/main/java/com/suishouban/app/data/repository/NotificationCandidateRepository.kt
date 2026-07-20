package com.suishouban.app.data.repository

import com.suishouban.app.data.local.NotificationCandidateDao
import com.suishouban.app.data.local.NotificationCandidateEntity
import kotlinx.coroutines.flow.Flow

/** Stores only candidates accepted by the local privacy policy. */
class NotificationCandidateRepository(
    private val dao: NotificationCandidateDao,
    private val policy: NotificationCandidatePolicy,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    fun observeActive(): Flow<List<NotificationCandidateEntity>> = dao.observeActive(nowMillis())

    fun observeActiveCount(): Flow<Int> = dao.observeActiveCount(nowMillis())

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

    suspend fun findById(id: String): NotificationCandidateEntity? = dao.findById(id)

    suspend fun delete(id: String) = dao.delete(id)

    suspend fun deleteExpired(): Int = dao.deleteExpired(nowMillis())
}
