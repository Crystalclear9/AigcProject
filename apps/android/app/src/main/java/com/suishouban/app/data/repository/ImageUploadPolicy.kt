package com.suishouban.app.data.repository

object ImageUploadPolicy {
    const val MAX_UPLOAD_EDGE = 1440
    const val MAX_UPLOAD_BYTES = 2 * 1024 * 1024

    fun calculateSampleSize(width: Int, height: Int, maxEdge: Int = MAX_UPLOAD_EDGE): Int {
        var sample = 1
        var currentWidth = width
        var currentHeight = height
        while (currentWidth > maxEdge || currentHeight > maxEdge) {
            sample *= 2
            currentWidth /= 2
            currentHeight /= 2
        }
        return sample.coerceAtLeast(1)
    }
}
