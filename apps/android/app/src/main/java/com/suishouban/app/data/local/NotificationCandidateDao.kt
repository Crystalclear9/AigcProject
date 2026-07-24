package com.suishouban.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NotificationCandidateDao {
    @Query("SELECT * FROM notification_candidates ORDER BY posted_at_millis DESC")
    fun observeAll(): Flow<List<NotificationCandidateEntity>>

    @Query("SELECT * FROM notification_candidates WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): NotificationCandidateEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(candidate: NotificationCandidateEntity): Long

    @Query("DELETE FROM notification_candidates WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM notification_candidates WHERE expires_at_millis <= :nowMillis")
    suspend fun deleteExpired(nowMillis: Long): Int
}
