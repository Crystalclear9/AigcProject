package com.suishouban.app.mascot.action

import android.content.Context
import android.provider.Settings

/** Reads Android special-access state without persisting a second, potentially stale copy. */
object MofeiPermissionState {
    fun notificationAccessGranted(context: Context): Boolean {
        val raw = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        )
        return isNotificationAccessGranted(enabledListenerPackages(raw), context.packageName)
    }

    /** Parses the colon-separated flattened component list stored by Android settings. */
    fun enabledListenerPackages(flattenedComponents: String?): Set<String> =
        flattenedComponents
            .orEmpty()
            .split(':')
            .mapNotNull { flattened ->
                flattened.substringBefore('/').trim().takeIf { packageName ->
                    '/' in flattened && packageName.isNotBlank()
                }
            }
            .toSet()

    fun isNotificationAccessGranted(
        enabledPackages: Set<String>,
        packageName: String,
    ): Boolean = packageName in enabledPackages
}
