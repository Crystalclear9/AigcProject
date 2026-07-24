package com.suishouban.app.notification

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

data class InstalledAppInfo(
    val packageName: String,
    val label: String,
)

/** Pure normalization keeps the platform query small and the displayed allowlist deterministic. */
object InstalledAppPolicy {
    fun selectableApps(
        apps: List<InstalledAppInfo>,
        ownPackageName: String,
    ): List<InstalledAppInfo> = apps
        .asSequence()
        .filter { it.packageName.isNotBlank() && it.packageName != ownPackageName }
        .distinctBy { it.packageName }
        .sortedWith(compareBy<InstalledAppInfo>({ it.label.lowercase() }, { it.packageName }))
        .toList()
}

/** Lists launcher-visible Apps without requesting broad QUERY_ALL_PACKAGES visibility. */
class InstalledAppRepository(private val context: Context) {
    fun listSelectableApps(): List<InstalledAppInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(0L),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentActivities(intent, 0)
        }
        val apps = resolved.map { info ->
            InstalledAppInfo(
                packageName = info.activityInfo.packageName,
                label = info.loadLabel(context.packageManager).toString().ifBlank {
                    info.activityInfo.packageName
                },
            )
        }
        return InstalledAppPolicy.selectableApps(apps, context.packageName)
    }
}
