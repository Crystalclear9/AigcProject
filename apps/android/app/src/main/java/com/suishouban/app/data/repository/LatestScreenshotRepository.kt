package com.suishouban.app.data.repository

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Finds only screenshots, never an arbitrary recent gallery image. */
class LatestScreenshotRepository(private val context: Context) {
    suspend fun findLatest(): Uri? = withContext(Dispatchers.IO) {
        if (!hasImageReadPermission()) return@withContext null
        val projection = buildList {
            add(MediaStore.Images.Media._ID)
            add(MediaStore.Images.Media.DISPLAY_NAME)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Images.Media.RELATIVE_PATH)
                add(MediaStore.Images.Media.IS_PENDING)
            }
        }.toTypedArray()
        val cursor = runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC",
            )
        }.getOrNull() ?: return@withContext null

        cursor.use {
            var inspected = 0
            while (it.moveToNext() && inspected++ < RECENT_MEDIA_SCAN_LIMIT) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    it.getInt(it.getColumnIndexOrThrow(MediaStore.Images.Media.IS_PENDING)) == 1
                ) continue
                val name = it.getString(it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)).orEmpty()
                val path = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    it.getString(it.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)).orEmpty()
                } else {
                    ""
                }
                if (!isScreenshotCandidate(name, path)) continue
                val id = it.getLong(it.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                return@withContext ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            }
        }
        null
    }

    private fun hasImageReadPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val RECENT_MEDIA_SCAN_LIMIT = 20
        private val SCREENSHOT_KEYWORDS = listOf("Screenshots", "ScreenRecord", "截图", "截屏", "screenshot")

        fun isScreenshotCandidate(name: String, path: String): Boolean = SCREENSHOT_KEYWORDS.any { keyword ->
            name.contains(keyword, ignoreCase = true) || path.contains(keyword, ignoreCase = true)
        }
    }
}
