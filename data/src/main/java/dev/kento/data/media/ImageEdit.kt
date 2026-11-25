package dev.kento.data.media

import dev.kento.core.text.tr

import android.graphics.Bitmap
import android.graphics.Matrix
import dev.kento.data.net.ProviderIssue
import java.io.File
import kotlin.math.roundToInt

/** Coordinates are fractions of the upright image, so EXIF rotation doesn't change the selection. */
data class ImageEdit(val operation: String, val x: Double = 0.0, val y: Double = 0.0,
    val width: Double = 1.0, val height: Double = 1.0, val degrees: Int = 90, val edge: Int = 1280, val quality: Int = 75) {
    fun write(source: File, destination: File) {
        if (edge !in 64..4096 || quality !in 10..95 || degrees !in setOf(90, 180, 270))
            throw ProviderIssue(tr("Недопустимые параметры обработки изображения"))
        val image = Images.decode(source, if (operation == "resize") edge else 3200)
        try {
            val result = when (operation) {
                "resize", "compress" -> image
                "rotate" -> Bitmap.createBitmap(image, 0, 0, image.width, image.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
                "crop" -> {
                    if (!(x >= 0 && y >= 0 && width > 0 && height > 0 && x + width <= 1.000001 && y + height <= 1.000001))
                        throw ProviderIssue(tr("Область обрезки выходит за края изображения"))
                    val left = (image.width * x).toInt().coerceAtMost(image.width - 1)
                    val top = (image.height * y).toInt().coerceAtMost(image.height - 1)
                    Bitmap.createBitmap(image, left, top, (image.width * width).roundToInt().coerceIn(1, image.width - left),
                        (image.height * height).roundToInt().coerceIn(1, image.height - top))
                }
                else -> throw ProviderIssue(tr("Неизвестная операция обработки изображения"))
            }
            try {
                destination.outputStream().use {
                    if (!result.compress(Bitmap.CompressFormat.JPEG, if (operation == "compress") quality else 92, it))
                        throw ProviderIssue(tr("Не удалось сохранить обработанную копию"))
                }
            } finally { if (result !== image) result.recycle() }
        } finally { image.recycle() }
    }
}
