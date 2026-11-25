package dev.kento.data.media

import dev.kento.core.text.tr

import android.graphics.*
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import dev.kento.data.net.ProviderIssue
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt

object Images {
    fun bounds(file: File) = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
        BitmapFactory.decodeFile(file.path, this)
    }

    fun decode(file: File, edge: Int): Bitmap {
        val size = bounds(file)
        if (size.outWidth < 1 || size.outHeight < 1) throw ProviderIssue(tr("Повреждённое изображение"))
        val longest = maxOf(size.outWidth, size.outHeight)
        var sample = 1
        while (longest.toLong() / sample > edge * 1.5) sample *= 2
        val raw = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw ProviderIssue(tr("Не удалось прочитать изображение"))
        val exif = runCatching { ExifInterface(file) }.getOrNull()
        val matrix = Matrix().apply {
            if (exif?.isFlipped == true) postScale(-1f, 1f)
            postRotate(exif?.rotationDegrees?.toFloat() ?: 0f)
            val scale = minOf(1f, edge.toFloat() / maxOf(raw.width, raw.height))
            postScale(scale, scale)
        }
        val result = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        if (raw !== result) raw.recycle()
        return result
    }

    fun jpeg(file: File, byteLimit: Int): String {
        var bitmap = decode(file, 2048)
        try {
            // Composite alpha on white before JPEG, including screenshots with transparent margins.
            if (bitmap.hasAlpha()) {
                val opaque = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                Canvas(opaque).apply { drawColor(Color.WHITE); drawBitmap(bitmap, 0f, 0f, null) }
                bitmap.recycle()
                bitmap = opaque
            }
            while (true) {
                val bytes = ByteArrayOutputStream()
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 86, bytes)) throw ProviderIssue(tr("Не удалось сжать изображение"))
                if (bytes.size() <= byteLimit) return "data:image/jpeg;base64," + Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
                val next = Bitmap.createScaledBitmap(bitmap, maxOf(1, (bitmap.width * .75).roundToInt()),
                    maxOf(1, (bitmap.height * .75).roundToInt()), true)
                if (next === bitmap) throw ProviderIssue(tr("Не удалось уменьшить изображение до лимита"))
                bitmap.recycle()
                bitmap = next
            }
        } finally { bitmap.recycle() }
    }

    /** [forModel]: English labels for the model; otherwise the interface language of the info sheet. */
    fun metadata(file: File, forModel: Boolean = false): String {
        val exif = runCatching { ExifInterface(file) }.getOrNull()
        val size = bounds(file)
        return buildString {
            append("${size.outWidth} × ${size.outHeight}")
            exif?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.let { append(if (forModel) "\nCaptured: $it" else tr("\nВремя съёмки: {0}", it)) }
            exif?.latLong?.let { append("\nGPS: ${it[0]}, ${it[1]}") }
            exif?.getAttribute(ExifInterface.TAG_MODEL)?.let { append(if (forModel) "\nCamera: $it" else tr("\nКамера: {0}", it)) }
        }
    }
}
