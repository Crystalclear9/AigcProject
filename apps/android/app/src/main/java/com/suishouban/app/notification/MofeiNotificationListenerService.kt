package com.suishouban.app.notification

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.suishouban.app.SuiShouBanApp
import com.suishouban.app.data.repository.NotificationCandidateInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Copies allowlisted notification text into the local candidate queue.
 *
 * The listener never dismisses, snoozes, marks as read, or otherwise mutates the source
 * notification. Policy filtering happens before any candidate is persisted or analyzed.
 */
class MofeiNotificationListenerService : NotificationListenerService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val source = sbn ?: return
        val app = application as? SuiShouBanApp ?: return
        val settings = app.settingsRepository.settings.value
        if (!settings.mofeiNotificationDraftsEnabled) return

        val notification = source.notification ?: return
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = sequenceOf(
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
            extras.getCharSequence(Notification.EXTRA_TEXT),
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT),
        ).mapNotNull { it?.toString()?.takeIf(String::isNotBlank) }
            .firstOrNull()
            .orEmpty()
        val appLabel = runCatching {
            val applicationInfo = packageManager.getApplicationInfo(source.packageName, 0)
            packageManager.getApplicationLabel(applicationInfo).toString()
        }.getOrDefault(source.packageName)

        // Copy primitive values while inside the callback; repository work then leaves the main
        // listener thread and cannot retain system-owned StatusBarNotification objects.
        val input = NotificationCandidateInput(
            notificationKey = source.key,
            packageName = source.packageName,
            appLabel = appLabel,
            title = title,
            body = body,
            postedAtMillis = source.postTime,
            isOngoing = notification.flags and Notification.FLAG_ONGOING_EVENT != 0,
            isGroupSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
        )
        val allowlist = settings.mofeiNotificationPackageAllowlist.toSet()
        serviceScope.launch {
            app.notificationCandidateRepository.ingest(input, allowlist)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        val app = application as? SuiShouBanApp ?: return
        serviceScope.launch { app.notificationCandidateRepository.deleteExpired() }
    }

    override fun onListenerDisconnected() {
        // Android may revoke special access while the process is alive. Do not reconnect or open
        // settings automatically; the capability ring will seal this action from live state.
        val app = application as? SuiShouBanApp
        if (app != null) serviceScope.launch { app.notificationCandidateRepository.deleteExpired() }
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
