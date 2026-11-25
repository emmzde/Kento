package dev.kento.data.media

import dev.kento.core.text.tr

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import dev.kento.data.db.Attachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

suspend fun LocalMedia.saveToGallery(context: Context, row: Attachment): Uri = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, row.name)
        put(MediaStore.MediaColumns.MIME_TYPE, row.mime)
        if (Build.VERSION.SDK_INT >= 29) {
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Kento/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        } else {
            @Suppress("DEPRECATION")
            val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Kento")
            if (!folder.isDirectory && !folder.mkdirs()) throw IOException(tr("Не удалось открыть папку Pictures/Kento"))
            val copy = File(folder, "Kento-${java.util.UUID.randomUUID()}." + row.name.substringAfterLast('.'))
            @Suppress("DEPRECATION")
            put(MediaStore.MediaColumns.DATA, copy.path)
        }
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: throw IOException(tr("Галерея недоступна"))
    try {
        val out = resolver.openOutputStream(uri, "w") ?: throw IOException(tr("Не удалось открыть изображение"))
        out.use { file(row).inputStream().use { input -> input.copyTo(it) } }
        if (Build.VERSION.SDK_INT >= 29) {
            val publish = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            if (resolver.update(uri, publish, null, null) < 1) throw IOException(tr("Не удалось опубликовать изображение"))
        }
        uri
    } catch (e: Exception) {
        resolver.delete(uri, null, null)
        throw e
    }
}
