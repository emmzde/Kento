package dev.kento.data.media

import dev.kento.core.text.tr

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import com.googlecode.tesseract.android.TessBaseAPI
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import dev.kento.data.db.*
import dev.kento.data.net.ProviderIssue
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.*
import java.security.MessageDigest
import java.util.UUID

const val MAX_ATTACHMENTS = 20
const val MAX_FILE = 20L * 1024 * 1024
const val MAX_PAGES = 20
private const val WEEK = 7L * 86400 * 1000

class LocalMedia(private val app: Context, private val database: KentoDb) {
    val dao = database.media()
    val directory = File(app.filesDir, "media").apply { mkdirs() }
    private val captures = File(directory, "capture").apply { mkdirs() }
    private val ocrLock = Mutex()
    private val writes = Mutex()
    fun file(row: Attachment) = File(directory, UUID.fromString(row.id).toString())
    fun cameraTarget() = File(captures, UUID.randomUUID().toString() + ".jpg")
    fun captureFile(name: String): File {
        require(name.matches(Regex("[a-f0-9-]{36}\\.(jpg|png)")))
        return File(captures, name)
    }

    suspend fun recover() = withContext(Dispatchers.IO) {
        writes.withLock {
            val ids = dao.files().toSet()
            directory.listFiles()?.filter { it.isFile && it.name !in ids }?.forEach { it.delete() }
            captures.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 86400000 }?.forEach { it.delete() }
            dao.expire(System.currentTimeMillis())
        }
    }

    suspend fun import(chat: String, uris: List<Uri>) = storeBatch(chat, uris.size) { index ->
        val uri = uris[index]
        if (uri.scheme != "content") throw ProviderIssue(tr("Выберите файл через системный диалог"))
        val resolver = app.contentResolver
        val info = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (!it.moveToFirst()) null else it.getString(0) to it.getLong(1)
        }
        if ((info?.second ?: 0) > MAX_FILE) throw ProviderIssue(tr("Файл больше 20 МБ"))
        copy(chat, info?.first ?: tr("Вложение"), resolver.getType(uri).orEmpty()) {
            resolver.openInputStream(uri) ?: throw ProviderIssue(tr("Нет доступа к файлу"))
        }
    }

    suspend fun acceptCapture(chat: String, name: String) {
        val source = captureFile(name)
        try { storeBatch(chat, 1) {
            val png = name.endsWith(".png")
            copy(chat, if (png) tr("Скриншот.png") else tr("Камера.jpg"), if (png) "image/png" else "image/jpeg") { source.inputStream() }
        } } finally { source.delete() }
    }

    private suspend fun storeBatch(chat: String, count: Int, read: (Int) -> Attachment) = withContext(Dispatchers.IO) {
        writes.withLock {
            if (dao.countDraft(chat) + count > MAX_ATTACHMENTS) throw ProviderIssue(tr("Можно прикрепить до 20 файлов к сообщению"))
            val rows = mutableListOf<Attachment>()
            try {
                repeat(count) { ensureActive(); rows += read(it) }
                if (rows.isNotEmpty()) database.withTransaction {
                    dao.ensureChat(Conversation(chat, rows.first().name, System.currentTimeMillis()))
                    dao.add(rows)
                }
            } catch (e: Exception) {
                rows.forEach { file(it).delete() }
                throw e
            }
        }
    }

    suspend fun storeGenerated(chat: String, reply: Long, generate: suspend (File) -> Unit) = withContext(Dispatchers.IO) {
        val scratch = File.createTempFile("generated-", ".tmp", captures)
        try {
            generate(scratch)
            ensureActive()
            writes.withLock {
                val row = copy(chat, "Kento", "image/*", limit = 32L * 1024 * 1024) { scratch.inputStream() }
                val ext = if (row.mime == "image/jpeg") "jpg" else row.mime.substringAfter('/')
                val result = row.copy(messageId = reply, name = "Kento-${row.id}.$ext")
                try {
                    withContext(NonCancellable) {
                        database.withTransaction {
                            dao.add(listOf(result))
                            database.chats().checkpoint(reply, "", "", "done")
                        }
                    }
                } catch (e: Exception) { file(row).delete(); throw e }
            }
        } finally { scratch.delete() }
    }

    private fun copy(chat: String, display: String, hint: String, limit: Long = MAX_FILE, open: () -> InputStream): Attachment {
        val name = display.substringAfterLast('/').filterNot(Char::isISOControl).take(180).ifBlank { tr("Вложение") }
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isNotEmpty() && ext !in setOf("jpg","jpeg","png","webp","heic","heif","pdf","wav","mp3","m4a","aac","ogg","flac","opus","3gp"))
            throw ProviderIssue(tr("Неподдерживаемое расширение файла: .{0}", ext))
        val id = UUID.randomUUID().toString()
        val dest = File(directory, id)
        try {
            val sha = MessageDigest.getInstance("SHA-256")
            var total = 0L
            open().use { src -> dest.outputStream().buffered().use { out ->
                val buf = ByteArray(32768)
                while (true) {
                    val n = src.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > limit) throw ProviderIssue(tr("Файл больше {0} МБ: {1}", limit / 1048576, name))
                    out.write(buf, 0, n)
                    sha.update(buf, 0, n)
                }
            } }
            if (total == 0L) throw ProviderIssue(tr("Пустой файл: {0}", name))
            val mime = when {
                hint.startsWith("image/") || ext in setOf("jpg","jpeg","png","webp","heic","heif") -> {
                    val bounds = Images.bounds(dest)
                    if (bounds.outMimeType !in setOf("image/jpeg","image/png","image/webp","image/heic","image/heif"))
                        throw ProviderIssue(tr("Повреждённое или неподдерживаемое изображение: {0}", name))
                    Images.decode(dest, 160).recycle()
                    bounds.outMimeType
                }
                ext == "pdf" || hint == "application/pdf" -> {
                    pdf(dest) { if (it.numberOfPages < 1) throw ProviderIssue(tr("В PDF нет страниц")) }
                    "application/pdf"
                }
                else -> OfflineSpeech.audioType(dest)
            }
            return Attachment(id, chat, name = name, mime = mime, bytes = total, hash = sha.digest().joinToString("") { "%02x".format(it) })
        } catch (e: Exception) { dest.delete(); throw e }
    }

    suspend fun editDraft(row: Attachment, edit: ImageEdit) = withContext(Dispatchers.IO) {
        writes.withLock {
            val scratch = cameraTarget()
            try {
                edit.write(file(row), scratch)
                val replacement = copy(row.chatId, row.name.substringBeforeLast('.') + ".jpg", "image/jpeg") { scratch.inputStream() }

                try {
                    if (dao.replaceDraft(row.id, replacement.id, replacement.name, replacement.mime, replacement.bytes, replacement.hash) != 1)
                        throw ProviderIssue(tr("Вложение уже отправлено"))
                } catch (e: Exception) { file(replacement).delete(); throw e }
                file(row).delete()
            } finally { scratch.delete() }
        }
    }

    suspend fun remove(row: Attachment) = withContext(Dispatchers.IO) {
        writes.withLock { if (dao.remove(row.id) > 0) file(row).delete() }
    }

    suspend fun ocr(row: Attachment): String = recognize(row).ifEmpty {
        throw ProviderIssue(tr("На изображении «{0}» не удалось распознать текст", row.name))
    }

    /** Empty when the image holds no readable text. */
    suspend fun recognize(row: Attachment): String = withContext(Dispatchers.IO) {
        ocrLock.withLock {
            val now = System.currentTimeMillis()
            dao.cached(row.hash, now)?.let { return@withLock it }
            val base = File(app.filesDir, "ocr")
            for (lang in listOf("rus", "eng")) installAsset(app, "$lang.traineddata", File(base, "tessdata/$lang.traineddata"))
            val engine = TessBaseAPI()
            try {
                if (!engine.init(base.path, "rus+eng")) throw ProviderIssue(tr("Не удалось открыть локальные языки OCR"))
                val bitmap = Images.decode(file(row), 3200)
                val text = try {
                    engine.setImage(bitmap)
                    engine.utF8Text.orEmpty().trim()
                } finally { bitmap.recycle() }
                if (text.isNotEmpty() && text.toByteArray().size < 512000) dao.cache(OcrEntry(row.hash, text, now + WEEK))
                text
            } finally { engine.recycle() }
        }
    }

    private fun <T> pdf(file: File, action: (PDDocument) -> T): T {
        PDFBoxResourceLoader.init(app)
        val scratch = com.tom_roush.pdfbox.io.MemoryUsageSetting.setupMixed(4L * 1024 * 1024, 128L * 1024 * 1024).setTempDir(app.cacheDir)
        try { return PDDocument.load(file, scratch).use(action) }
        catch (e: ProviderIssue) { throw e }
        catch (e: IOException) { throw ProviderIssue(tr("Не удалось открыть PDF. Проверьте файл и защиту паролем")) }
    }

    fun pdfText(row: Attachment, pages: IntRange? = null) = pdf(file(row)) { doc ->
        val range = pages ?: (1..doc.numberOfPages)
        if (range.isEmpty() || range.first < 1 || range.last > doc.numberOfPages || range.last - range.first >= MAX_PAGES)
            throw ProviderIssue(tr("Выберите до {0} страниц PDF в пределах документа ({1})", MAX_PAGES, doc.numberOfPages))
        val text = StringWriter()
        val bounded = object : Writer() {
            override fun write(chars: CharArray, offset: Int, length: Int) {
                if (text.buffer.length + length > 512000) throw ProviderIssue(tr("Слишком много текста в PDF. Разделите документ."))
                text.write(chars, offset, length)
            }
            override fun flush() = Unit
            override fun close() = Unit
        }
        PDFTextStripper().apply { startPage = range.first; endPage = range.last }.writeText(doc, bounded)
        val result = text.toString().trim()
        if (result.isEmpty()) throw ProviderIssue(tr("В PDF нет текстового слоя"))
        result
    }

    suspend fun transcript(row: Attachment): String {
        val key = "whisper-small-q5_1-phrases:" + row.hash
        val now = System.currentTimeMillis()
        dao.cached(key, now)?.let { return it }
        return OfflineSpeech.transcribe(app, file(row)).also { dao.cache(OcrEntry(key, it, now + WEEK)) }
    }
}

internal fun installAsset(context: Context, name: String, dest: File): File {
    if (dest.isFile) return dest
    dest.parentFile!!.mkdirs()
    val temp = File.createTempFile("install", ".part", dest.parentFile)
    try {
        context.assets.open(name).use { src -> temp.outputStream().use { src.copyTo(it) } }
        if (!temp.renameTo(dest) && !dest.exists()) throw ProviderIssue(tr("Не удалось сохранить локальную модель"))
    } finally { temp.delete() }
    return dest
}
