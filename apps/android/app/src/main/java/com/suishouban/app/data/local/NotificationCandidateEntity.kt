package com.suishouban.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Local-only notification text awaiting explicit user review; it is never an action card. */
@Entity(
    tableName = "notification_candidates",
    indices = [Index(value = ["content_hash"], unique = true)],
)
data class NotificationCandidateEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "notification_key") val notificationKey: String,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "app_label") val appLabel: String,
    val title: String,
    val body: String,
    @ColumnInfo(name = "posted_at_millis") val postedAtMillis: Long,
    @ColumnInfo(name = "content_hash") val contentHash: String,
    @ColumnInfo(name = "expires_at_millis") val expiresAtMillis: Long,
)
