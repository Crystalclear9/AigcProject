package com.suishouban.app.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min

data class OcrTextBlock(
    val id: String,
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val readingOrder: Int,
    val pageIndex: Int = 0,
)

data class StructuredOcrResult(
    val text: String,
    val blocks: List<OcrTextBlock>,
    val imageWidth: Int,
    val imageHeight: Int,
    val rotationDegrees: Int,
    val variant: String,
)

class TextRecognitionService {
    suspend fun recognize(context: Context, uri: Uri): String =
        recognizeDetailed(context, uri).text

    suspend fun recognizeDetailed(
        context: Context,
        uri: Uri,
        variant: String = "original",
    ): StructuredOcrResult = suspendCancellableCoroutine { continuation ->
        val image = runCatching { InputImage.fromFilePath(context, uri) }
            .getOrElse {
                continuation.resumeWithException(it)
                return@suspendCancellableCoroutine
            }
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        recognizer.process(image)
            .addOnSuccessListener { result ->
                recognizer.close()
                continuation.resume(result.toStructured(image, variant))
            }
            .addOnFailureListener { error ->
                recognizer.close()
                continuation.resumeWithException(error)
            }
        continuation.invokeOnCancellation {
            recognizer.close()
        }
    }

    suspend fun recognize(bitmap: Bitmap): String =
        recognizeDetailed(bitmap).text

    suspend fun recognizeDetailed(
        bitmap: Bitmap,
        variant: String = "original",
        pageIndex: Int = 0,
        offsetY: Int = 0,
    ): StructuredOcrResult = suspendCancellableCoroutine { continuation ->
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { result ->
                recognizer.close()
                val structured = result.toStructured(image, variant, pageIndex, offsetY)
                continuation.resume(structured)
            }
            .addOnFailureListener { error ->
                recognizer.close()
                continuation.resumeWithException(error)
            }
        continuation.invokeOnCancellation { recognizer.close() }
    }

    suspend fun recognizeCandidates(context: Context, uri: Uri): List<StructuredOcrResult> {
        val bitmap = context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
            ?: return listOf(recognizeDetailed(context, uri))
        return try {
            val original = recognizeBitmapWithTiling(bitmap, "original")
            val maxDimension = maxOf(bitmap.width, bitmap.height)
            val scale = min(2f, 2800f / maxDimension.coerceAtLeast(1))
            val scaled = if (scale > 1.12f) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt(),
                    (bitmap.height * scale).toInt(),
                    true,
                )
            } else {
                null
            }
            val enhancedSource = scaled ?: bitmap
            val enhanced = contrastBitmap(enhancedSource)
            val results = buildList {
                add(original)
                if (scaled != null) add(recognizeBitmapWithTiling(scaled, "upscaled"))
                add(recognizeBitmapWithTiling(enhanced, "contrast"))
            }
            enhanced.recycle()
            scaled?.recycle()
            results
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun recognizeBitmapWithTiling(
        bitmap: Bitmap,
        variant: String,
    ): StructuredOcrResult {
        val tileHeight = (bitmap.width * 1.8f).toInt().coerceAtLeast(1200)
        if (bitmap.height <= tileHeight * 2) {
            return recognizeDetailed(bitmap, variant)
        }
        val overlap = (tileHeight * 0.15f).toInt()
        val blocks = mutableListOf<OcrTextBlock>()
        var top = 0
        var page = 0
        while (top < bitmap.height) {
            val height = min(tileHeight, bitmap.height - top)
            val crop = Bitmap.createBitmap(bitmap, 0, top, bitmap.width, height)
            val result = recognizeDetailed(crop, "$variant-tile", page, top)
            blocks += result.blocks
            crop.recycle()
            if (top + height >= bitmap.height) break
            top += height - overlap
            page += 1
        }
        val merged = mergeOverlappingBlocks(blocks)
        return StructuredOcrResult(
            text = merged.joinToString("\n") { it.text },
            blocks = merged.mapIndexed { index, block -> block.copy(readingOrder = index) },
            imageWidth = bitmap.width,
            imageHeight = bitmap.height,
            rotationDegrees = 0,
            variant = "$variant-tiled",
        )
    }

    private fun contrastBitmap(source: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val matrix = ColorMatrix().apply {
            setSaturation(0f)
            val contrast = 1.22f
            val translate = (-0.5f * contrast + 0.5f) * 255f
            postConcat(
                ColorMatrix(
                    floatArrayOf(
                        contrast, 0f, 0f, 0f, translate,
                        0f, contrast, 0f, 0f, translate,
                        0f, 0f, contrast, 0f, translate,
                        0f, 0f, 0f, 1f, 0f,
                    )
                )
            )
        }
        Canvas(output).drawBitmap(
            source,
            0f,
            0f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(matrix)
            },
        )
        return output
    }
}

private fun com.google.mlkit.vision.text.Text.toStructured(
    image: InputImage,
    variant: String,
    pageIndex: Int = 0,
    offsetY: Int = 0,
): StructuredOcrResult {
    val blocks = textBlocks
        .flatMap { block -> block.lines }
        .mapIndexedNotNull { index, line ->
            val bounds = line.boundingBox ?: return@mapIndexedNotNull null
            OcrTextBlock(
                id = "$variant:$pageIndex:$index",
                text = line.text,
                left = bounds.left,
                top = bounds.top + offsetY,
                right = bounds.right,
                bottom = bounds.bottom + offsetY,
                readingOrder = index,
                pageIndex = pageIndex,
            )
        }
        .sortedWith(compareBy<OcrTextBlock> { it.top }.thenBy { it.left })
    return StructuredOcrResult(
        text = blocks.joinToString("\n") { it.text }.ifBlank { text },
        blocks = blocks,
        imageWidth = image.width,
        imageHeight = image.height,
        rotationDegrees = image.rotationDegrees,
        variant = variant,
    )
}

private fun mergeOverlappingBlocks(blocks: List<OcrTextBlock>): List<OcrTextBlock> {
    val accepted = mutableListOf<OcrTextBlock>()
    blocks.sortedWith(compareBy<OcrTextBlock> { it.top }.thenBy { it.left }).forEach { block ->
        val compact = block.text.replace(Regex("\\s+"), "")
        val duplicate = accepted.any { existing ->
            val other = existing.text.replace(Regex("\\s+"), "")
            compact.isNotBlank() &&
                (compact == other || compact.contains(other) || other.contains(compact)) &&
                kotlin.math.abs(existing.top - block.top) < 180
        }
        if (!duplicate) accepted += block
    }
    return accepted
}
