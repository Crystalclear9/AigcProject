package com.suishouban.app.notification

import com.suishouban.app.mascot.action.MofeiPermissionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstalledAppPolicyTest {
    @Test
    fun notificationListenerPackagesAreParsedFromFlattenedComponents() {
        val enabled = MofeiPermissionState.enabledListenerPackages(
            "com.example.mail/.MailListener:" +
                "com.suishouban.app/com.suishouban.app.notification.MofeiNotificationListenerService:" +
                "malformed",
        )

        assertEquals(setOf("com.example.mail", "com.suishouban.app"), enabled)
        assertTrue(MofeiPermissionState.isNotificationAccessGranted(enabled, "com.suishouban.app"))
        assertFalse(MofeiPermissionState.isNotificationAccessGranted(enabled, "com.example.none"))
    }

    @Test
    fun selectableAppsExcludeSelfDeduplicateAndSortByLabel() {
        val apps = listOf(
            InstalledAppInfo("com.example.zeta", "邮箱"),
            InstalledAppInfo("com.suishouban.app", "随手办"),
            InstalledAppInfo("com.example.alpha", "聊天"),
            InstalledAppInfo("com.example.alpha", "聊天重复项"),
            InstalledAppInfo("", "无效"),
        )

        assertEquals(
            listOf(
                InstalledAppInfo("com.example.alpha", "聊天"),
                InstalledAppInfo("com.example.zeta", "邮箱"),
            ),
            InstalledAppPolicy.selectableApps(apps, ownPackageName = "com.suishouban.app"),
        )
    }
}
