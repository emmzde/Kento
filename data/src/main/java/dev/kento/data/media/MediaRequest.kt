package dev.kento.data.media

import dev.kento.core.text.tr

import dev.kento.data.db.Attachment
import dev.kento.data.net.*
import dev.kento.data.tools.*
import dev.kento.data.settings.Settings
import kotlinx.serialization.json.*

/**
 * Whether the chat model takes images: the user's choice on the connection page ("Vision" / "Local OCR"), otherwise
 * the `vision` field of the Refuseless catalogue; null while the catalogue is not loaded. Kento does not probe models.
 */
fun Settings.seesImages(): Boolean? = visionOverrides[model] ?: option()?.vision

/**
 * Most images one request carries. The catalogue has no field for it: kimi-k3.1-pro-abliterated and
 * gemma-4-26b-storyteller answer `400 too_many_images` above 10, the other vision models took 24 (checked live,
 * 2026-11-24). Photos past it travel as recognized text, and image_edit stops adding pictures there.
 */
const val IMAGE_LIMIT = 10

/** How one photo of a message reaches the chat model. */
internal enum class PhotoUse {
    /** The picture alone; the model reads text in it itself, or calls the ocr tool. */
    PIXELS,
    /** The picture and its recognized text: the user asked for the full OCR text, or the catalogue is not loaded. */
    PIXELS_AND_TEXT,
    /** Recognized text only: a text-only model. */
    TEXT,
    /** Recognized text only: the photo is past [IMAGE_LIMIT]. */
    TEXT_OVER_LIMIT,
}

/**
 * The use of each photo of a message, in order. [vision] as [Settings.seesImages]; [fullText] is the "attach the full
 * OCR text" switch of each photo. A model of unknown vision gets the text too: Refuseless replaces images for a
 * text-only model with a note.
 */
internal fun photoUses(vision: Boolean?, fullText: List<Boolean>): List<PhotoUse> = fullText.mapIndexed { i, full ->
    when {
        vision == false -> PhotoUse.TEXT
        i >= IMAGE_LIMIT -> PhotoUse.TEXT_OVER_LIMIT
        full || vision == null -> PhotoUse.PIXELS_AND_TEXT
        else -> PhotoUse.PIXELS
    }
}

data class MediaInput(val text: String, val images: List<String>) {
    // Reserve text before selecting optional chat history. Never shorten an attachment.
    val budget: Int get() = dev.kento.data.memory.textTokens(text) + images.size * 512
}

class MediaRequest(private val local: LocalMedia) {
    suspend fun prepare(s: Settings, rows: List<Attachment>, status: (String) -> Unit): MediaInput {
        val photos = rows.filter { it.mime.startsWith("image/") }
        val uses = photos.zip(photoUses(s.seesImages(), photos.map { it.includeOcr })).toMap()
        val pixels = uses.values.count { it == PhotoUse.PIXELS || it == PhotoUse.PIXELS_AND_TEXT }
        val images = mutableListOf<String>()
        val labels = mutableListOf<String>()
        for ((i, row) in rows.withIndex()) {
            status(tr("Готовлю вложение {0} из {1}", i + 1, rows.size))
            // The JPEG sent to the model has no EXIF, and many models cannot call the exif tool.
            val meta = if (!row.mime.startsWith("image/")) "" else Images.metadata(local.file(row), forModel = true).lines().drop(1)
                .joinToString("; ")
            labels += when (uses[row]) {
                PhotoUse.PIXELS, PhotoUse.PIXELS_AND_TEXT -> {
                    images += Images.jpeg(local.file(row), minOf(768 * 1024, 4 * 1024 * 1024 / pixels))
                    attachmentLabel(i, row, meta, if (uses[row] == PhotoUse.PIXELS_AND_TEXT) local.recognize(row) else null)
                }
                // A text-only model still gets the message: it can tell the user what it cannot see.
                PhotoUse.TEXT -> attachmentLabel(i, row, meta, local.recognize(row), seen = false)
                PhotoUse.TEXT_OVER_LIMIT -> attachmentLabel(i, row, meta, local.recognize(row), overLimit = true)
                null -> if (row.mime == "application/pdf") attachmentLabel(i, row, content = "PDF text:\n" + local.pdfText(row))
                    else attachmentLabel(i, row, content = "Audio transcript:\n" + local.transcript(row))
            }
        }
        return MediaInput(labels.joinToString("\n\n"), images)
    }

    /**
     * Tools over the attachments already sent in this chat. The set and its schemas never change: calls name an
     * attachment by its id (listed in the runtime context and by read_media_queue), and the tool checks it here.
     * [seesImages] is whether the chat model accepts images; image_edit refuses otherwise. [room] is how many more
     * pictures the request may carry (see [IMAGE_LIMIT]): the images of the message are already in it.
     */
    fun tools(rows: List<Attachment>, seesImages: Boolean, room: () -> Int = { IMAGE_LIMIT }): List<ChatTool> {
        fun find(args: JsonObject, kind: (Attachment) -> Boolean) = rows.find { it.id == args.text("uri") && kind(it) }
            ?: throw ToolRefusal("No attachment of this type with this uri in this chat; read_media_queue lists them")
        val image: (Attachment) -> Boolean = { it.mime.startsWith("image/") }
        var edited = 0
        val actions = mapOf<String, suspend (JsonObject) -> ToolOutput>(
            "read_media_queue" to {
                ToolOutput(JsonArray(rows.map { row -> buildJsonObject {
                    put("uri", row.id); put("name", row.name); put("type", row.mime); put("bytes", row.bytes)
                } }).toString())
            },
            "image_edit" to { args ->
                if (!seesImages) throw ToolRefusal("this model does not accept images")
                val source = find(args, image)
                if (edited >= 4) throw ToolRefusal("At most four edited images per reply")
                if (edited >= room()) throw ToolRefusal("This request already carries $IMAGE_LIMIT images, the most it can")
                val edit = ImageEdit(args.str("operation").orEmpty(),
                    args.str("x")?.toDoubleOrNull() ?: 0.0, args.str("y")?.toDoubleOrNull() ?: 0.0,
                    args.str("width")?.toDoubleOrNull() ?: 1.0, args.str("height")?.toDoubleOrNull() ?: 1.0,
                    args.str("degrees")?.toIntOrNull() ?: 90, args.str("edge")?.toIntOrNull() ?: 1280,
                    args.str("quality")?.toIntOrNull() ?: 75)
                val temporary = local.cameraTarget()
                val picture = try {
                    edit.write(local.file(source), temporary)
                    Images.jpeg(temporary, 192 * 1024)
                } catch (e: ProviderIssue) { throw ToolRefusal("could not edit the image with these parameters") }
                finally { temporary.delete() }
                edited++
                ToolOutput("Edited image $edited is attached to this result.", listOf(picture))
            },
            "ocr" to { args -> ToolOutput(local.recognize(find(args, image)).ifEmpty { "No text found in the image." }) },
            "exif" to { args -> ToolOutput(Images.metadata(local.file(find(args, image)), forModel = true)) },
            "pdf_extract" to { args ->
                val row = find(args) { it.mime == "application/pdf" }
                val first = args.str("first_page")?.toIntOrNull()
                val last = args.str("last_page")?.toIntOrNull()
                try { ToolOutput(local.pdfText(row, if (first == null && last == null) null else (first ?: 1)..(last ?: first ?: 1))) }
                catch (e: ProviderIssue) {
                    throw ToolRefusal("no text extracted: the PDF may be encrypted or scanned without a text layer, or the pages " +
                        "are outside the document (at most 20 pages per call)")
                }
            },
            "transcribe_audio" to { args -> ToolOutput(local.transcript(find(args) { it.mime.startsWith("audio/") })) },
        )
        return MEDIA_TOOLS.map { spec ->
            object : ChatTool {
                override val name = spec.name
                override val timeoutMs = spec.timeoutMs
                override val schema = spec.schema
                override fun status(args: JsonObject) = tr(spec.status)
                override suspend fun run(args: JsonObject) = actions.getValue(spec.name)(args)
            }
        }
    }
}

/** One attachment tool as declared: the same in every chat, so the request prefix stays cached. */
internal class MediaToolSpec(val name: String, val schema: JsonObject, val status: String, val timeoutMs: Long = 120_000)

private fun JsonObjectBuilder.uri() = putJsonObject("uri") {
    put("type", "string"); put("description", "Attachment id from the runtime context or read_media_queue")
}

/** The attachment tools in their fixed order; [MediaRequest.tools] runs them. Status lines are UI keys for `tr`. */
internal val MEDIA_TOOLS = listOf(
    MediaToolSpec("read_media_queue", functionSchema("read_media_queue", "List the attachments of this conversation with their ids."),
        "Смотрю вложения"),
    MediaToolSpec("image_edit", functionSchema("image_edit",
        "Create a temporary edited copy of an image attachment and look at it. Crop coordinates are fractions from the top left.",
        listOf("uri", "operation")) {
        uri()
        putJsonObject("operation") { put("type", "string"); putJsonArray("enum") { listOf("crop", "resize", "rotate", "compress").forEach { add(it) } } }
        for (key in listOf("x", "y", "width", "height")) putJsonObject(key) { put("type", "number"); put("minimum", 0); put("maximum", 1) }
        putJsonObject("degrees") { put("type", "integer"); putJsonArray("enum") { add(90); add(180); add(270) } }
        putJsonObject("edge") { put("type", "integer"); put("minimum", 64); put("maximum", 4096) }
        putJsonObject("quality") { put("type", "integer"); put("minimum", 10); put("maximum", 95) }
    }, "Обрабатываю изображение"),
    MediaToolSpec("ocr", functionSchema("ocr", "Read all text in an image attachment, locally.", listOf("uri")) { uri() }, "Распознаю текст"),
    MediaToolSpec("exif", functionSchema("exif", "Read the metadata of an image attachment: size, capture time, GPS and camera if present.",
        listOf("uri")) { uri() }, "Читаю EXIF"),
    MediaToolSpec("pdf_extract", functionSchema("pdf_extract", "Extract the text of a PDF attachment, up to 20 consecutive pages per call.",
        listOf("uri")) {
        uri()
        for (key in listOf("first_page", "last_page")) putJsonObject(key) { put("type", "integer"); put("minimum", 1) }
    }, "Читаю PDF"),
    MediaToolSpec("transcribe_audio", functionSchema("transcribe_audio", "Transcribe the speech of an audio attachment, locally.",
        listOf("uri")) { uri() }, "Расшифровываю аудио", timeoutMs = 600_000),
)

/**
 * How one attachment of the newest message reads to the model: number, name, type, size and id, then the EXIF line,
 * then the recognized [text] or other [content] (PDF text, transcript). Data only, in English.
 */
internal fun attachmentLabel(index: Int, row: Attachment, meta: String = "", text: String? = null, seen: Boolean = true,
    content: String? = null, overLimit: Boolean = false): String = buildString {
    append("Attachment ${index + 1}: ${row.name} (${row.mime}, ${row.bytes} bytes, id ${row.id})")
    if (meta.isNotEmpty()) append("\nEXIF: $meta")
    if (!seen) append("\nThis model does not accept images; only the text recognized in the image is available.")
    if (overLimit) append("\nSent as recognized text only: one request carries at most $IMAGE_LIMIT images.")
    when {
        content != null -> append("\n" + content)
        text == null -> {}
        text.isEmpty() -> append("\nNo text found in the image.")
        else -> append("\nText recognized in the image:\n$text")
    }
}
