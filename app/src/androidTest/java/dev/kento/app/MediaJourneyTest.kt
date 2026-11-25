package dev.kento.app

import androidx.activity.compose.setContent
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.media.ExifInterface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dev.kento.data.media.*
import dev.kento.data.net.ProviderIssue
import dev.kento.data.settings.*
import dev.kento.feature.chat.ChatViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

class MediaJourneyTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<TestApp>()
    private val server = MockWebServer()
    private lateinit var before: ProfileBook
    private lateinit var vm: ChatViewModel
    private val chat get() = vm.activeId.value
    private val fixtureFiles = mutableListOf<File>()
    private val instrument get() = InstrumentationRegistry.getInstrumentation()

    @Before fun setup() {
        server.start()
        app.apiBase = server.url("/v1").toString()
        before = runBlocking { app.settings.profiles.first() }
        ui.waitForIdle()
        vm = ui.runOnIdle { ViewModelProvider(ui.activity)[ChatViewModel::class.java] }
        ui.waitUntil(15000) { vm.ready.value }
        ui.runOnIdle { vm.newChat() }
    }
    @After fun finish() {
        try {
            if (::vm.isInitialized) runBlocking { app.db.delete(chat); app.media.recover() }
            if (::before.isInitialized) runBlocking { app.settings.updateBook { before } }
            fixtureFiles.forEach { it.delete() }
        } finally { server.shutdown() }
    }

    private fun configure(vision: Boolean, tools: Boolean = false, window: Int = 65536, url: String = server.url("/v1").toString()) {
        // Every Refuseless model takes tools; a journey about the plain path switches the model's tools off as a
        // provider refusal would (ToolLoop remembers a 400 the same way).
        app.apiBase = url
        dev.kento.data.tools.ToolLoop.refuse("media-fixture", refused = !tools)
        runBlocking { app.settings.update { Settings(id = it.id, model = "media-fixture",
            catalog = listOf(ModelOption("media-fixture", vision = vision, contextLength = window)), catalogLoaded = true,
            catalogVersion = dev.kento.data.settings.CATALOG_VERSION, catalogAt = System.currentTimeMillis()) } }
        ui.waitUntil(10000) { vm.settings.value?.model == "media-fixture" }
    }

    private fun file(name: String) = File(app.media.cameraTarget().parentFile, name).also { fixtureFiles += it }
    private fun picture(index: Int): File {
        val f = file("scan-$index.jpg")
        val bmp = Bitmap.createBitmap(1100, 270, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 48f; typeface = Typeface.DEFAULT }
        Canvas(bmp).apply {
            drawColor(Color.WHITE)
            drawText("Начало страницы", 40f, 74f, paint)
            drawText("Archive number $index", 40f, 145f, paint)
            drawText("Конец документа", 40f, 221f, paint)
        }
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bmp.recycle()
        return f
    }
    private fun import(vararg files: File) = runBlocking {
        app.media.import(chat, files.map { FileProvider.getUriForFile(app, app.packageName + ".capture", it) })
    }
    private fun rows() = runBlocking { app.media.dao.observe(chat).first() }
    private fun submit(question: String) {
        val field = ui.onNodeWithContentDescription("Сообщение")
        field.performClick()
        field.performTextInput(question)
        ui.onNodeWithContentDescription("Отправить").performClick()
    }
    private fun answer(text: String) = RefuselessSse.answer(text)
    private fun waitForText(text: String) = ui.waitUntil(90000) { ui.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    /** Attachment tiles show only the picture or the bare name, so files are found by their accessible label. */
    private fun waitForFile(name: String) = ui.waitUntil(90000) { ui.onAllNodesWithContentDescription(name).fetchSemanticsNodes().isNotEmpty() }
    private fun request() = Json.parseToJsonElement(checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)).body.readUtf8()).jsonObject
    private fun last(json: JsonObject) = json.getValue("messages").jsonArray.last().jsonObject

    @Test fun tenScansSendEveryRecognizedLineWithQuestion() {
        configure(false)
        import(*(1..10).map(::picture).toTypedArray())
        val keys = rows().map { it.hash }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val text = last(Json.parseToJsonElement(request.body.clone().readUtf8()).jsonObject).getValue("content").jsonPrimitive.content
                return answer(if (keys.indices.all { text.contains("Archive number ${it + 1}") }) "Все десять страниц получены" else "Неполный пакет")
            }
        }
        submit("Прочти все страницы")
        // Ten Tesseract pages on an x86 emulator under a full suite can take longer than the usual 90 s.
        ui.waitUntil(180000) { ui.onAllNodesWithText("Все десять страниц получены", substring = true).fetchSemanticsNodes().isNotEmpty() }
        val text = last(request()).getValue("content").jsonPrimitive.content
        assertTrue(text.contains("Прочти все страницы"))
        keys.forEach { hash ->
            val saved = runBlocking { app.media.dao.cached(hash, System.currentTimeMillis()) }!!
            assertTrue(saved.contains("Начало страницы") && saved.contains("Конец документа"))
            assertTrue(text.contains(saved))
        }
        assertEquals(10, Regex("Text recognized in the image").findAll(text).count())
        assertEquals(1, server.requestCount)
    }

    /** Vision is the catalogue's, so sending photos makes no probe request before the answer. */
    @Test fun visionComesFromTheCatalogueWithoutAProbe() {
        configure(true)
        import(picture(4))
        server.enqueue(answer("Фото получено"))
        submit("Что на снимке?")
        waitForText("Фото получено")
        assertEquals(1, server.requestCount)
        val parts = last(request()).getValue("content").jsonArray
        assertEquals(1, parts.count { it.jsonObject.containsKey("image_url") })
        // The model reads the photo itself; its OCR text is not added.
        assertFalse(parts[0].jsonObject.getValue("text").jsonPrimitive.content.contains("Archive number 4"))
    }

    /** Twenty photos: the first ten go as pictures (kimi and gemma refuse more), the other ten as recognized text. */
    @Test fun draftLimitRejectsOverflowAndSendsTenImagesAndTenTexts() {
        configure(true)
        val pages = (1..20).map(::picture)
        import(*pages.toTypedArray())
        assertThrows(ProviderIssue::class.java) { import(picture(21)) }
        assertEquals(20, rows().size)
        server.enqueue(answer("Получены все фото"))
        submit("Сравни снимки")
        ui.waitUntil(240000) { ui.onAllNodesWithText("Получены все фото", substring = true).fetchSemanticsNodes().isNotEmpty() }
        val parts = last(request()).getValue("content").jsonArray
        assertEquals(IMAGE_LIMIT + 1, parts.size)
        val text = parts[0].jsonObject.getValue("text").jsonPrimitive.content
        assertTrue(text.contains("Сравни снимки"))
        assertEquals(IMAGE_LIMIT, parts.count { it.jsonObject.containsKey("image_url") })
        assertFalse(text, (1..10).any { Regex("Archive number $it\\b").containsMatchIn(text) })
        assertTrue(text, (11..20).all { text.contains("Archive number $it") })
        assertEquals(10, Regex("one request carries at most 10 images").findAll(text).count())
    }

    @Test fun visionCanIncludeFullOcrAlongsidePixels() {
        configure(true)
        import(picture(9))
        ui.onNodeWithText("Приложить полный текст OCR").performClick()
        ui.waitUntil(10000) { rows().single().includeOcr }
        server.enqueue(answer("Получены текст и фото"))
        submit("Сверь подпись")
        waitForText("Получены текст и фото")
        val parts = last(request()).getValue("content").jsonArray
        assertTrue(parts[0].jsonObject.getValue("text").jsonPrimitive.content.contains("Конец документа"))
        assertTrue(parts[1].jsonObject.containsKey("image_url"))
    }

    /** A batch for a vision model goes without OCR text unless the switch asks for it. */
    @Test fun visionBatchCarriesOcrTextOnlyWhenAskedAndToleratesPhotosWithoutText() {
        configure(true)
        fun sky(name: String, color: Int) = file(name).also { f ->
            val bmp = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            bmp.recycle()
        }
        import(picture(3), sky("sky.jpg", Color.rgb(90, 140, 200)))
        assertFalse(rows().any { it.includeOcr })
        server.enqueue(answer("Пакет получен"))
        submit("Что на фото?")
        waitForText("Пакет получен")
        val plain = last(request()).getValue("content").jsonArray
        val bare = plain[0].jsonObject.getValue("text").jsonPrimitive.content
        assertFalse(bare, bare.contains("Archive number 3") || bare.contains("Text recognized") || bare.contains("No text found"))
        assertEquals(2, plain.count { it.jsonObject.containsKey("image_url") })

        import(picture(5), sky("dusk.jpg", Color.rgb(200, 120, 60)))
        // The reply has just finished: the composer unlocks and shows the drafts with their OCR switch.
        ui.waitUntil(15000) { !vm.sending.value }
        waitForText("Приложить полный текст OCR")
        ui.onNodeWithText("Приложить полный текст OCR").performClick()
        ui.waitUntil(10000) { rows().filter { it.messageId == null }.all { it.includeOcr } }
        server.enqueue(answer("Второй пакет"))
        submit("А теперь?")
        waitForText("Второй пакет")
        val parts = last(request()).getValue("content").jsonArray
        val text = parts[0].jsonObject.getValue("text").jsonPrimitive.content
        assertTrue(text, text.contains("Archive number 5") && text.contains("Конец документа"))
        assertTrue(text, text.contains("dusk.jpg") && text.contains("No text found in the image"))
        assertEquals(2, parts.count { it.jsonObject.containsKey("image_url") })
    }

    @Test fun sendingClosesMicrophoneAndPendingRecognitionCallbacks() {
        configure(false)
        instrument.uiAutomation.grantRuntimePermission(app.packageName, android.Manifest.permission.RECORD_AUDIO)
        // Start and send in the same main-loop turn to exercise a late service attachment as well.
        server.enqueue(answer("Готово"))
        ui.runOnIdle {
            vm.voice.start()
            assertTrue(vm.voice.listening)
            vm.input.value = "Проверка остановки"
            vm.send()
            assertFalse(vm.voice.listening)
        }
        waitForText("Готово")
        assertFalse(vm.voice.listening)
        assertTrue(vm.input.value.isEmpty())
    }

    @Test fun streamingSpansRespectUnicodeCharacters() {
        val phrase = "текст 👩🏽‍💻 cafe\u0301 🐈"
        ui.runOnUiThread { ui.activity.setContent { dev.kento.feature.chat.StreamingMarkdown(phrase) } }
        waitForText(phrase)
        val annotated = ui.onNodeWithText(phrase).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].single()
        val boundaries = android.icu.text.BreakIterator.getCharacterInstance().apply { setText(phrase) }
        assertTrue("Style splits an extended grapheme", annotated.spanStyles.isNotEmpty() &&
            annotated.spanStyles.all { boundaries.isBoundary(it.start) && boundaries.isBoundary(it.end) })
    }

    @Test fun smallWindowRejectsEntireBatch() {
        configure(false, window = 128)
        import(picture(1), picture(2))
        submit("Полный текст")
        waitForText("не помещаются")
        assertEquals(0, server.requestCount)
        assertEquals(2, rows().size)
    }

    @Test fun screenCaptureSendsJpegWithPrompt() {
        configure(true)
        // PixelCopy needs the window drawn and focused; right after a cold start the first copy can come back empty.
        for (attempt in 1..3) {
            ui.onNodeWithContentDescription("Прикрепить").performClick()
            ui.onNodeWithText("Снимок экрана").performClick()
            val captured = runCatching {
                ui.waitUntil(20000) { ui.onAllNodesWithContentDescription("Скриншот.png").fetchSemanticsNodes().isNotEmpty() }
            }.isSuccess
            if (captured) break
            check(attempt < 3) { "Screen capture did not produce an attachment" }
            vm.problem.value = null
            ui.waitForIdle()
        }
        server.enqueue(answer("Снимок принят"))
        submit("Что на экране?")
        waitForText("Снимок принят")
        val parts = last(request()).getValue("content").jsonArray
        assertTrue(parts[0].jsonObject.getValue("text").jsonPrimitive.content.contains("Что на экране?"))
        val url = parts[1].jsonObject.getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content
        val jpeg = android.util.Base64.decode(url.substringAfter(','), android.util.Base64.DEFAULT)
        val image = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        assertTrue(image.width >= 300 && image.height >= 500)
        assertTrue(rows().single().messageId != null)
    }

    @Test fun exifDialogAndToolRoundTrip() {
        configure(true, tools = true)
        val f = picture(7)
        ExifInterface(f.path).apply {
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2024:08:19 09:15:00")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "40/1,24/1,0/1")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "49/1,51/1,0/1")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "E")
            saveAttributes()
        }
        import(f)
        waitForFile("scan-7.jpg")
        ui.onNodeWithContentDescription("scan-7.jpg").performClick()
        waitForText("40.4")
        waitForText("2024:08:19")
        ui.onNodeWithText("Закрыть").performClick()
        val id = rows().single().id
        server.enqueue(RefuselessSse.call("exif", """{"uri":"$id"}""", id = "c1"))
        server.enqueue(answer("Метаданные получены"))
        submit("Когда и где сделан снимок?")
        waitForText("Метаданные получены")
        assertTrue(request().getValue("tools").toString().contains("read_media_queue"))
        val tool = last(request())
        assertEquals("c1", tool.getValue("tool_call_id").jsonPrimitive.content)
        assertTrue(tool.getValue("content").jsonPrimitive.content.contains("49.85"))
    }

    @Test fun exifReachesModelsWithoutTools() {
        configure(true)
        val f = picture(8)
        ExifInterface(f.path).apply {
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2025:08:19 09:15:00")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "40/1,24/1,0/1")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "49/1,51/1,0/1")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "E")
            saveAttributes()
        }
        import(f)
        server.enqueue(answer("Метаданные в тексте"))
        submit("Где сделан снимок?")
        waitForText("Метаданные в тексте")
        val json = request()
        assertFalse(json.containsKey("tools"))
        val text = last(json).getValue("content").jsonArray[0].jsonObject.getValue("text").jsonPrimitive.content
        assertTrue(text, text.contains("EXIF: ") && text.contains("2025:08:19 09:15:00") && text.contains("40.4") && text.contains("49.85"))
    }

    @Test fun fivePagePdfHonorsRangeAndLimit() {
        fun document(pages: Int): File {
            val f = file("pages-$pages.pdf")
            val doc = PdfDocument()
            try {
                for (i in 1..pages) {
                    val page = doc.startPage(PdfDocument.PageInfo.Builder(500, 700, i).create())
                    page.canvas.drawText("Chapter $i", 50f, 100f, Paint().apply { textSize = 24f })
                    doc.finishPage(page)
                }
                f.outputStream().use(doc::writeTo)
            } finally { doc.close() }
            return f
        }
        import(document(5), document(21))
        val (row, long) = rows()
        val full = app.media.pdfText(row)
        assertTrue((1..5).all { "Chapter $it" in full })
        val part = app.media.pdfText(row, 2..4)
        assertTrue((2..4).all { "Chapter $it" in part })
        assertFalse("Chapter 5" in part || "Chapter 1" in part)
        assertThrows(ProviderIssue::class.java) { app.media.pdfText(row, 1..6) }
        assertThrows(ProviderIssue::class.java) { app.media.pdfText(long) }
        assertTrue("Chapter 21" in app.media.pdfText(long, 2..21))
    }

    /** The Whisper model is no longer in the APK; a fresh emulator downloads it once (190 MB) and keeps it. */
    private fun speechModel() = runBlocking {
        val model = dev.kento.data.media.SpeechModel
        if (model.ready(app)) return@runBlocking
        // tools/Test-Kento.ps1 leaves a copy in /data/local/tmp, which outlives reinstalls of the qa app.
        val cached = "/data/local/tmp/${model.NAME}"
        val target = model.file(app).apply { parentFile!!.mkdirs() }
        shell("run-as ${app.packageName} cp $cached ${target.path}")
        if (model.ready(app)) return@runBlocking
        model.download(app)
        kotlinx.coroutines.withTimeout(900_000) {
            while (dev.kento.data.media.SpeechModel.state.value !is dev.kento.data.media.SpeechModel.State.Ready) {
                (dev.kento.data.media.SpeechModel.state.value as? dev.kento.data.media.SpeechModel.State.Failed)?.let { error(it.message) }
                kotlinx.coroutines.delay(500)
            }
        }
        shell("sh -c 'run-as ${app.packageName} cat ${target.path} > $cached'")
    }

    private fun shell(command: String) = instrument.uiAutomation.executeShellCommand(command).let { fd ->
        android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
    }

    @Test fun offlineThirtySecondsOfSpeech() {
        speechModel()
        val source = instrument.context.assets.open("jfk.wav").use { it.readBytes() }
        val header = source.toString(Charsets.ISO_8859_1).indexOf("data")
        assertTrue(header > 0)
        val start = header + 8
        val sampleBytes = 16000 * 2 * 30
        val wav = source.copyOf(start + sampleBytes)
        ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(4, wav.size - 8); putInt(header + 4, sampleBytes)
        }
        for (i in 0 until sampleBytes) wav[start + i] = source[start + i % (source.size - start)]
        val f = file("recording.wav").apply { writeBytes(wav) }
        import(f)
        val text = runBlocking { app.media.transcript(rows().single()) }.lowercase()
        android.util.Log.i("KentoMediaQA", "Public speech fixture: $text")
        assertTrue(text, text.contains("country") && text.contains("ask") && text.contains("you"))
    }

    @Test fun russianSpeechTranscribedLocally() {
        speechModel()
        val f = file("russian.wav")
        instrument.context.assets.open("russian-speech.wav").use { src -> f.outputStream().use { src.copyTo(it) } }
        import(f)
        val text = runBlocking { app.media.transcript(rows().single()) }.lowercase()
        android.util.Log.i("KentoMediaQA", "Synthetic Russian fixture: $text")
        assertTrue(text, listOf("окно", "столе", "книга").all { it in text })
    }

    @Test fun mixedRussianAndEnglishRecordingKeepsBothLanguages() {
        speechModel()
        fun pcm(name: String): ByteArray {
            val raw = instrument.context.assets.open(name).use { it.readBytes() }
            val data = raw.toString(Charsets.ISO_8859_1).indexOf("data")
            return raw.copyOfRange(data + 8, raw.size)
        }
        val body = pcm("russian-speech.wav") + ByteArray(16000) + pcm("jfk.wav")
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + body.size); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(16000); putInt(32000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(body.size)
        }.array()
        val f = file("mixed.wav").apply { writeBytes(header + body) }
        import(f)
        val text = runBlocking { app.media.transcript(rows().single()) }.lowercase()
        android.util.Log.i("KentoMediaQA", "Mixed fixture: $text")
        assertTrue(text, listOf("окно", "книга", "country", "ask").all { it in text })
    }

    @Test fun editedDraftIsTheImageUsedForFollowup() {
        configure(true)
        import(picture(1), picture(2))
        val first = rows().first()
        runBlocking { app.media.editDraft(first, ImageEdit("rotate")) }
        val edited = rows().first()
        assertEquals(first.name, edited.name)
        assertFalse(app.media.file(first).exists())
        assertEquals(270, Images.bounds(app.media.file(edited)).outWidth)
        server.enqueue(answer("Вижу фото"))
        submit("Прочти фото")
        waitForText("Вижу фото")
        val initial = last(request()).getValue("content").jsonArray[1]
        server.enqueue(answer("Повторно вижу фото"))
        submit("Что на нём написано?")
        waitForText("Повторно вижу фото")
        val messages = request().getValue("messages").jsonArray
        assertTrue(messages.any { (it.jsonObject["content"] as? JsonArray)?.contains(initial) == true })
    }

    @Test fun badImportsAreAtomicAndDraftCanBeRemoved() {
        val valid = picture(4)
        for ((f, expected) in listOf(file("empty.png") to "Пустой", file("bad.jpg") to "изображение",
            file("large.jpg") to "20 МБ", file("program.exe") to "расширение")) {
            when (f.name) {
                "large.jpg" -> RandomAccessFile(f, "rw").use { it.setLength(25L * 1024 * 1024) }
                "empty.png" -> f.writeBytes(byteArrayOf())
                else -> f.writeText("broken")
            }
            val error = assertThrows(ProviderIssue::class.java) { import(valid, f) }
            assertTrue(error.message, error.message!!.contains(expected))
            assertTrue(rows().isEmpty())
        }
        import(valid)
        waitForFile(valid.name)
        ui.onNodeWithContentDescription("Убрать " + valid.name).performClick()
        ui.waitUntil(10000) { rows().isEmpty() }
    }

    @Test fun realCameraReturnsFullSizeFile() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("camera") == "true")
        configure(true)
        val device = UiDevice.getInstance(instrument)
        ui.onNodeWithContentDescription("Прикрепить").performClick()
        ui.onNodeWithText("Открыть камеру").performClick()
        // Complete the sheet's exit animation on the Compose test clock before waiting on the external activity.
        ui.waitForIdle()
        // The capture app may show its first-run permission or dismissible tutorial.
        fun tap(selector: BySelector, timeout: Long): Boolean {
            val node = device.wait(Until.findObject(selector), timeout) ?: return false
            node.click()
            return true
        }
        tap(By.res("com.android.permissioncontroller", "permission_allow_foreground_only_button"), 2000)
        tap(By.res("com.android.camera2", "confirm_button"), 1000)
        val shutter = By.res("com.android.camera2", "shutter_button")
        assertTrue("Camera shutter", tap(shutter, 10000))
        tap(By.res("com.android.camera2", "done_button"), 8000)
        waitForFile("Камера.jpg")
        val size = Images.bounds(app.media.file(rows().single()))
        assertTrue("Camera ${size.outWidth}×${size.outHeight}", maxOf(size.outWidth, size.outHeight) >= 640)
        android.util.Log.i("KentoMediaQA", "Camera original ${size.outWidth}x${size.outHeight}, ${rows().single().bytes} bytes")
    }

    @Test fun airplaneModeShowsFailureAndStopsSending() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("airplane") == "true")
        val device = UiDevice.getInstance(instrument)
        val before = device.executeShellCommand("settings get global airplane_mode_on").trim()
        val wifi = device.executeShellCommand("settings get global wifi_on").trim()
        try {
            device.executeShellCommand("cmd connectivity airplane-mode enable")
            device.executeShellCommand("svc wifi disable")
            configure(true, url = "https://kento-connectivity-check.invalid/v1")
            import(picture(1))
            submit("Проверка связи")
            waitForText("Проверь сеть")
            assertFalse(vm.sending.value)
        } finally {
            if (before == "0") device.executeShellCommand("cmd connectivity airplane-mode disable")
            if (wifi == "1") device.executeShellCommand("svc wifi enable")
        }
    }
}
