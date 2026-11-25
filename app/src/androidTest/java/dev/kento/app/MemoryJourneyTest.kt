package dev.kento.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import dev.kento.data.db.KentoDb
import dev.kento.data.db.ChatSummary
import dev.kento.data.db.FactChange
import dev.kento.data.db.Remembered
import dev.kento.data.memory.ChatContext
import dev.kento.data.memory.MemoryArchive
import dev.kento.data.memory.MemoryWorker
import dev.kento.data.settings.ProfileBook
import dev.kento.data.settings.Settings
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

class MemoryJourneyTest {
    private val app get() = ApplicationProvider.getApplicationContext<TestApp>()

    @Test fun commonReplyWordsDoNotRecallUnrelatedRequests(): Unit = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(app, KentoDb::class.java).build()
        try {
            val chats = database.chats()
            val memory = database.memory()
            memory.addFact("Любимый инструмент — стеклянная гармоника")
            val past = chats.start("past", "Reply SilverCedar and only that word.", "fixture")
            chats.checkpoint(past, "SilverCedar", "", "done")
            val current = "The timing beacon is MicaGlider. Reply seeded."
            chats.start("current", current, "fixture")
            val turns = ChatContext(chats, memory).prepare("current", Settings(systemPrompt = "Prefix each answer with CTXOK", promptClock = false)).turns
            val system = turns.first().content
            assertTrue(system.startsWith("# Persona\nPrefix each answer with CTXOK"))
            // Saved facts travel in the runtime context of the newest message with date, topic and content;
            // the system prompt stays the same whatever the dossier holds.
            assertFalse(system.contains("Любимый инструмент"))
            val newest = turns.last().content
            assertTrue(newest.startsWith("<runtime_context>\nmemory:\n"))
            assertTrue(Regex("""- \[Date: \d{4}-\d{2}-\d{2}] Topic: \S+ Content: Любимый инструмент""").containsMatchIn(newest))
            // Other chats are no longer pasted into the request; the model looks them up with memory_search.
            assertTrue(newest.endsWith("</runtime_context>\n\n" + current))
            assertFalse(turns.any { it.content.contains("SilverCedar") })
        } finally { database.close() }
    }

    /** The core of the dossier goes with every message, topical notes only with theirs. */
    @Test fun coreGoesWithEveryMessageAndTopicalFactsFollowTheirTopic(): Unit = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(app, KentoDb::class.java).build()
        try {
            val chats = database.chats()
            val memory = database.memory()
            memory.addFact("Готовлюсь к марафону, бегаю 4 раза в неделю")
            memory.remember("У пользователя питон по кличке Квиллон.", "питомцы", 2, null)
            memory.remember("Пользователь собирает механические клавиатуры.", "хобби", 2, null)
            chats.start("net", "Какой блок IPv4 у TEST-NET-1?", "fixture")
            chats.start("pet", "Чем кормить питона?", "fixture")
            chats.start("keys", "Какие переключатели взять для клавиатуры?", "fixture")
            val context = ChatContext(chats, memory)
            suspend fun runtime(chat: String) = context.prepare(chat, Settings(promptClock = false)).turns.last().content
                .substringBefore("</runtime_context>")
            for (chat in listOf("net", "pet", "keys")) assertTrue(chat, runtime(chat).contains("Готовлюсь к марафону"))
            assertFalse(runtime("net").contains("Квиллон") || runtime("net").contains("клавиатуры"))
            assertTrue(runtime("pet").contains("Квиллон"))
            assertFalse(runtime("pet").contains("клавиатуры"))
            assertTrue(runtime("keys").contains("клавиатуры"))
            assertFalse(runtime("keys").contains("Квиллон"))
            // Memory off: no notes at all.
            assertFalse(context.prepare("pet", Settings(promptClock = false), memorySettings = dev.kento.data.settings.MemorySettings(enabled = false))
                .turns.any { it.content.contains("марафон") })
        } finally { database.close() }
    }

    @Test fun archiveRestoresFactsSummariesAndSourcesAfterClear(): Unit = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(app, KentoDb::class.java).build()
        try {
            val chatId = "archive-source"
            val chats = database.chats()
            val memory = database.memory()
            val archive = MemoryArchive(memory)
            val answer = chats.start(chatId, "Архивное слово кварц", "fixture")
            chats.checkpoint(answer, "Сохраню.", "", "done")
            val rows = chats.context(chatId).asReversed().map { it.id }
            assertTrue(memory.commit(chatId, 0, rows, listOf(FactChange(FactChange.Op.ADD, listOf(rows.first()), "Любимый минерал — кварц"))))
            // A retelling an older version wrote: kept in the dossier and the archive, no longer created.
            database.openHelper.writableDatabase.execSQL("""INSERT INTO memory_entries (kind, text, identity, edited, deleted, topic,
                importance, createdAt, fingerprint) VALUES ('summary', 'Разговор о минерале кварц', 'summary:old', 0, 0, '', 2, 1, '')""")
            memory.addFact("Ручной факт")
            memory.remember("Пользователь коллекционирует марки с парусниками.", "хобби", 2, null)
            memory.forget(memory.dossier().first { it.text.contains("марки") }.id)

            val before = memory.snapshot()
            val output = ByteArrayOutputStream()
            archive.save { output }
            memory.clear()
            assertTrue(memory.dossier().isEmpty())
            assertTrue(memory.entries(memory.entryMatches("кварц*").map { it.id }).isEmpty())

            repeat(2) { archive.restore { output.toByteArray().inputStream() } }
            assertEquals(before, memory.snapshot())
            assertEquals(answer, memory.cursor(chatId))
            assertEquals(setOf("fact", "summary"), memory.entries(memory.entryMatches("кварц*").map { it.id }).map { it.kind }.toSet())
            val fact = memory.dossier().first { it.text == "Любимый минерал — кварц" }
            assertEquals(listOf(rows.first()), memory.sources(fact.id).map { it.id })

            // The fingerprint of the deleted fact came back with the archive: it still is not saved again.
            assertEquals(Remembered.Declined, memory.remember("Пользователь коллекционирует марки с парусниками и кораблями.", "хобби", 2, null))

            val invalid = "{".byteInputStream()
            assertTrue(runCatching { archive.restore { invalid } }.isFailure)
            assertEquals(before, memory.snapshot())
        } finally { database.close() }
    }

    @Test fun migratedHistoryIsSearchableAndNewRowsStayIndexed() = runBlocking {
        val name = "migration-" + UUID.randomUUID()
        val fixture = InstrumentationRegistry.getInstrumentation().context.assets
            .open("dev.kento.data.db.KentoDb/1.json").bufferedReader().use { Json.parseToJsonElement(it.readText()) }
            .jsonObject.getValue("database").jsonObject
        try {
            app.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
                for (entity in fixture.getValue("entities").jsonArray.map { it.jsonObject }) {
                    val table = entity.getValue("tableName").jsonPrimitive.content
                    fun sql(o: JsonObject) = o.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table)
                    db.execSQL(sql(entity))
                    (entity["indices"] as? JsonArray)?.forEach { db.execSQL(sql(it.jsonObject)) }
                }
                fixture.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
                db.execSQL("INSERT INTO chats VALUES ('old','Старая история',1)")
                db.execSQL("INSERT INTO messages VALUES (1,'old','user','Архивная цитата','','done','','')")
                db.version = 1
            }
            val upgraded = Room.databaseBuilder(app, KentoDb::class.java, name).build()
            try {
                val memory = upgraded.memory()
                assertEquals("Архивная цитата", memory.recalled(memory.messageMatches("архив*").map { it.id }).single().text)
                val chats = upgraded.chats()
                val answer = chats.start("old", "После обновления", "qa")
                chats.checkpoint(answer, "Новая запись", "", "done")
                assertEquals(answer, memory.recalled(memory.messageMatches("запись").map { it.id }).single().id)
                // v11: the summaries of compressed chats are indexed for memory_search, and an upsert keeps the index right.
                chats.saveSummary(ChatSummary("old", answer, "Сводка про архивариуса"))
                assertEquals(1, memory.summaryMatches("архивариус*").size)
                chats.saveSummary(ChatSummary("old", answer, "Новая сводка про библиотекаря"))
                assertTrue(memory.summaryMatches("архивариус*").isEmpty())
                assertEquals("Новая сводка про библиотекаря", memory.summaries(memory.summaryMatches("библиотекар*").map { it.id }).single().text)
                chats.delete("old")
                assertTrue(memory.recalled(memory.messageMatches("архив* OR запись").map { it.id }).isEmpty())
                assertTrue(memory.summaryMatches("библиотекар*").isEmpty())
            } finally { upgraded.close() }
        } finally { app.deleteDatabase(name) }
    }

    /**
     * With the model lock gone, two analyses of one chat may overlap (the one-off and the periodic
     * work). Each words the fact its own way; the cursor, checked and moved in the commit transaction, keeps one.
     */
    @Test fun twoAnalysesOfOneChatWriteItsFactsOnce(): Unit = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(app, KentoDb::class.java).build()
        try {
            val chats = database.chats()
            val memory = database.memory()
            val reply = chats.start("twice", "I play the harpsichord.", "m")
            chats.checkpoint(reply, "Noted.", "", "done")
            val rows = chats.context("twice").map { it.id }.sorted()
            val user = rows.first()
            val wordings = listOf("The user plays the harpsichord.", "The user's instrument is the harpsichord.")
            val results = kotlinx.coroutines.coroutineScope {
                wordings.map { text -> async(kotlinx.coroutines.Dispatchers.IO) {
                    memory.commit("twice", 0, rows, listOf(FactChange(FactChange.Op.ADD, listOf(user), text, "music", 2)))
                } }.map { it.await() }
            }
            assertEquals(listOf(false, true), results.sorted())
            assertEquals(1, memory.dossier().count { it.text in wordings })
            assertEquals(rows.last(), memory.cursor("twice"))
        } finally { database.close() }
    }

    /**
     * After a reply the background model gets only the user's new messages and the saved
     * notes they may change, answers with a forced save_facts call, and the fact lands in the dossier, linked to its
     * message. The next run resumes after the cursor; a restatement merges instead of making a second note, and no
     * chat retelling is written any more.
     */
    @Test fun workerExtractsFactsAndResumesFromItsSavedCursor() = runBlocking {
        val oldSettings = app.settings.profiles.first()
        val source = UUID.randomUUID().toString()
        val other = UUID.randomUUID().toString()
        val memory = app.database.memory()
        val work = WorkManager.getInstance(app)
        // Stop a worker left by an earlier suite before it can read this chat with a half-written reply.
        work.cancelUniqueWork("kento-memory").result.get()
        // Chats left by earlier suites raced this one: a run over them could move our cursor before our reply
        // was registered. The QA database is the test's own, so it starts from these two chats only.
        app.db.history().first().forEach { app.db.delete(it.id) }
        MockWebServer().use { server ->
            // The worker talks to Refuseless only; the address is the app's, not a setting, so it is pointed here.
            app.apiBase = server.url("/v1").toString()
            val profile = Settings(model = "chat", memoryModel = "archivist")
            val question = "Я играю на клавесине."
            val answer = app.db.start(source, question, profile.model)
            app.db.checkpoint(answer, "Запомню.", "", "done")
            val userId = app.db.context(source).last().id
            val factText = "Пользователь играет на клавесине."
            fun call(vararg ops: JsonObject) = RefuselessSse.callsBody(Triple("call_1", "save_facts",
                buildJsonObject { putJsonArray("operations") { ops.forEach(::add) } }.toString()), text = "Done.")
            fun add(text: String, id: Long) = buildJsonObject {
                put("op", "add"); put("text", text); put("topic", "музыка"); put("importance", 3); putJsonArray("message_ids") { add(id) }
            }
            // Earlier suites may leave unprocessed chats; the worker visits them in chatId order, so answer by content.
            val replies = java.util.concurrent.ConcurrentHashMap<String, String>()
            val ours = java.util.concurrent.LinkedBlockingQueue<String>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val text = request.body.readUtf8()
                    val key = replies.keys.firstOrNull { it in text } ?: return MockResponse().setBody(call())
                    ours += text
                    return MockResponse().setBody(replies.getValue(key))
                }
            }
            suspend fun scheduleAndWait(id: Long) {
                // A run still visiting other chats would make KEEP drop this request.
                work.cancelUniqueWork("kento-memory").result.get()
                MemoryWorker.schedule(app, delaySeconds = 0)
                withTimeout(100_000) { while (memory.cursor(source) != id) delay(300) }
            }
            fun input(request: String): JsonObject = Json.parseToJsonElement(Json.parseToJsonElement(request).jsonObject
                .getValue("messages").jsonArray.last().jsonObject.getValue("content").jsonPrimitive.content).jsonObject
            try {
                replies[question] = call(add(factText, userId))
                app.settings.updateBook { ProfileBook(listOf(profile), profile.id, memory = dev.kento.data.settings.MemorySettings(background = true)) }
                scheduleAndWait(answer)
                val request = ours.poll(2, TimeUnit.SECONDS)!!
                val body = Json.parseToJsonElement(request).jsonObject
                assertEquals("archivist", body.getValue("model").jsonPrimitive.content)
                assertEquals("save_facts", body.getValue("tool_choice").jsonObject.getValue("function").jsonObject.getValue("name").jsonPrimitive.content)
                // Only the user's words go: the reply "Запомню." is not sent.
                assertEquals(listOf(question), input(request).getValue("messages").jsonArray.map { it.jsonObject.getValue("text").jsonPrimitive.content })
                val fact = memory.dossier().single { it.text == factText }
                assertEquals(listOf(userId), memory.sources(fact.id).map { it.id })
                assertTrue(memory.entries(memory.entryMatches("клавесин*").map { it.id }).none { it.kind == "summary" })

                val second = app.db.start(source, "Кроме того, люблю джаз. И да, я играю на клавесине.", profile.model)
                app.db.checkpoint(second, "Хорошо.", "", "done")
                val secondUser = app.db.context(source).first { it.role == "user" }.id
                replies.remove(question)
                replies["люблю джаз"] = call(add("Пользователь играет на клавесине уже давно.", secondUser),
                    add("Пользователь любит джаз.", secondUser))
                scheduleAndWait(second)
                val resumed = ours.poll(2, TimeUnit.SECONDS)!!
                assertTrue(resumed.contains("джаз"))
                assertEquals(1, input(resumed).getValue("messages").jsonArray.size)
                // The saved fact went along for comparison, with its id.
                assertTrue(input(resumed).getValue("saved_notes").jsonArray.any { it.jsonObject.getValue("id").jsonPrimitive.long == fact.id })
                assertEquals(1, memory.dossier().count { "клавесин" in it.text })
                assertEquals(listOf(userId, secondUser), memory.sources(fact.id).map { it.id })
                assertTrue(memory.dossier().any { it.text == "Пользователь любит джаз." })

                app.db.start(other, "Расскажи про клавесин", profile.model)
                suspend fun context() = ChatContext(app.db, memory).prepare(other, profile).turns.joinToString("\n") { it.content }
                val recalled = context()
                assertTrue(recalled.contains("клавесине уже давно"))
                assertFalse(recalled.contains(question))
                memory.amend(fact.id, "Теперь предпочитает орган")
                assertTrue(context().contains("предпочитает орган"))
                memory.forget(fact.id)
                assertFalse(context().contains("предпочитает орган"))
                assertFalse(memory.dossier().any { it.id == fact.id })
            } finally {
                app.settings.updateBook { oldSettings }
                app.db.delete(source)
                app.db.delete(other)
            }
        }
    }

    /** "Moved to Y" updates the old fact instead of adding a second one; outdated facts go. */
    @Test fun anUpdateReplacesTheOldFactAndOutdatedFactsLeaveEveryRequest(): Unit = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(app, KentoDb::class.java).build()
        try {
            val chats = database.chats()
            val memory = database.memory()
            val first = chats.start("job", "Я работаю в Яндексе.", "m")
            chats.checkpoint(first, "Ок.", "", "done")
            val said = chats.context("job").last().id
            assertTrue(memory.commit("job", 0, listOf(said, first), listOf(FactChange(FactChange.Op.ADD, listOf(said),
                "Пользователь работает в Яндексе.", "работа", 3))))
            val job = memory.dossier().single()
            // The background reader is offered that note when the user mentions the move, here as part of the core.
            assertTrue(memory.related("С прошлой недели я перешёл в Сбер, пишу бэкенд на Go.").any { it.id == job.id })
            val second = chats.start("job", "С прошлой недели я перешёл в Сбер, пишу бэкенд на Go.", "m")
            chats.checkpoint(second, "Поздравляю.", "", "done")
            val moved = chats.context("job").first { it.role == "user" }.id
            assertTrue(memory.commit("job", first, listOf(moved, second), listOf(FactChange(FactChange.Op.UPDATE, listOf(moved),
                "Пользователь работает в Сбере, пишет бэкенд на Go.", "работа", 3, job.id))))
            val now = memory.dossier().single()
            assertEquals(job.id, now.id)
            assertEquals("Пользователь работает в Сбере, пишет бэкенд на Go.", now.text)
            assertEquals(listOf(said, moved), memory.sources(job.id).map { it.id })

            val pet = memory.remember("У пользователя есть хомяк Пушок.", "питомцы", 2, null) as Remembered.Saved
            chats.start("pet", "Пушок умер, хомяков больше не будет.", "m")
            val gone = chats.context("pet").single().id
            assertTrue(memory.commit("pet", 0, listOf(gone), listOf(FactChange(FactChange.Op.OUTDATE, listOf(gone), target = pet.id))))
            assertEquals("outdated", memory.entry(pet.id)!!.kind)
            assertFalse(memory.dossier().any { it.id == pet.id })
            chats.start("ask", "Как ухаживать за хомяком Пушком?", "m")
            assertFalse(ChatContext(chats, memory).prepare("ask", Settings()).turns.any { it.content.contains("хомяк Пушок") })
            val search = dev.kento.data.memory.MemoryTools(memory).all().single()
            assertFalse(search.run(buildJsonObject { put("query", "хомяк Пушок") }).text.contains("У пользователя есть хомяк"))
            // Told again in the same words, it is true again.
            memory.remember("У пользователя есть хомяк Пушок.", "питомцы", 2, null)
            assertEquals("fact", memory.entry(pet.id)!!.kind)
        } finally { database.close() }
    }

    /**
     * The bug of the old commit, which compared exact text only. A fact the user deleted comes back neither
     * in the same nor in other words, from the background reader or any other write, and not after its chat is gone.
     */
    @Test fun aDeletedFactIsNotSavedAgainInOtherWords(): Unit = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(app, KentoDb::class.java).build()
        try {
            val chats = database.chats()
            val memory = database.memory()
            val reply = chats.start("city", "Я живу в Москве.", "m")
            chats.checkpoint(reply, "Ок.", "", "done")
            val said = chats.context("city").last().id
            memory.commit("city", 0, listOf(said, reply), listOf(FactChange(FactChange.Op.ADD, listOf(said), "Пользователь живёт в Москве.", "город", 3)))
            val fact = memory.dossier().single()
            memory.forget(fact.id)
            assertEquals("", memory.entry(fact.id)!!.text)
            assertEquals(Remembered.Declined, memory.remember("Пользователь живёт в Москве.", "город", 3, null))
            assertEquals(Remembered.Declined, memory.remember("Пользователь давно живёт в Москве.", "место", 2, null))
            chats.delete("city")
            val again = chats.start("again", "Я живу в Москве, если что.", "m")
            chats.checkpoint(again, "Понял.", "", "done")
            val repeated = chats.context("again").last().id
            assertTrue(memory.commit("again", 0, listOf(repeated, again), listOf(
                FactChange(FactChange.Op.ADD, listOf(repeated), "Пользователь живёт в городе Москве.", "город", 3))))
            assertTrue(memory.dossier().isEmpty())
            // Something else entirely is saved as usual.
            assertTrue(memory.remember("Пользователь пьёт кофе без сахара.", "еда", 2, null) is Remembered.Saved)
        } finally { database.close() }
    }

    /** What the user wrote or edited in the dossier is never rewritten or outdated by the background reader. */
    @Test fun theUsersOwnNotesAreNeverOverwritten(): Unit = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(app, KentoDb::class.java).build()
        try {
            val chats = database.chats()
            val memory = database.memory()
            memory.addFact("Пользователь бегает по утрам три раза в неделю.")
            val own = memory.dossier().single()
            memory.remember("Пользователь пишет на Kotlin.", "стек", 2, null)
            val edited = memory.dossier().first { it.text.contains("Kotlin") }
            memory.amend(edited.id, "Пользователь пишет на Kotlin и Rust.")
            chats.start("c", "Я бегаю по утрам три раза в неделю и пишу на Go.", "m")
            val said = chats.context("c").single().id
            assertTrue(memory.commit("c", 0, listOf(said), listOf(
                FactChange(FactChange.Op.UPDATE, listOf(said), "Пользователь пишет на Go.", "стек", 2, edited.id),
                FactChange(FactChange.Op.OUTDATE, listOf(said), target = own.id),
                FactChange(FactChange.Op.ADD, listOf(said), "Пользователь бегает по утрам три раза в неделю уже год.", "спорт", 2))))
            assertEquals("Пользователь бегает по утрам три раза в неделю.", memory.entry(own.id)!!.text)
            assertEquals("fact", memory.entry(own.id)!!.kind)
            assertEquals("Пользователь пишет на Kotlin и Rust.", memory.entry(edited.id)!!.text)
            // The restatement merged into the user's note; the update of an edited note became a fact of its own.
            assertEquals(1, memory.dossier().count { it.text.contains("бегает") })
            assertTrue(memory.dossier().any { it.text == "Пользователь пишет на Go." })
        } finally { database.close() }
    }

    /** 9.3: the summary of a compressed chat is the one retelling, and memory_search finds earlier chats by it. */
    @Test fun memorySearchFindsEarlierChatsByTheirSummary(): Unit = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(app, KentoDb::class.java).build()
        try {
            val chats = database.chats()
            val memory = database.memory()
            val old = chats.start("trip", "Планируем поход", "m")
            chats.checkpoint(old, "Хорошо.", "", "done")
            chats.saveSummary(ChatSummary("trip", old, "Решили идти маршрутом через перевал Каракая, выход 14 июля."))
            chats.start("now", "Напомни, через какой перевал мы решили идти?", "m")
            val search = dev.kento.data.memory.MemoryTools(memory, shown = { "now" to Long.MAX_VALUE }).all().single()
            val found = search.run(buildJsonObject { put("query", "перевал маршрут") }).text
            assertTrue(found, found.contains("(summary of that chat): Решили идти маршрутом через перевал Каракая"))
            // The summary of the chat in front of the model is already in its request.
            val own = dev.kento.data.memory.MemoryTools(memory, shown = { "trip" to Long.MAX_VALUE }).all().single()
            assertFalse(own.run(buildJsonObject { put("query", "перевал маршрут") }).text.contains("summary of that chat"))
        } finally { database.close() }
    }

    @Test fun restatedFactsMergeAndOldDetailsOutrankRecentNoise(): Unit = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(app, KentoDb::class.java).build()
        try {
            val chats = database.chats()
            val memory = database.memory()
            val first = memory.remember("Пользователь бегает по утрам три раза в неделю.", "спорт", 3, null)
            val again = memory.remember("Пользователь бегает по утрам три раза в неделю уже несколько лет.", "спорт", 2, null)
            assertTrue(again is Remembered.Saved && again.merged)
            assertEquals((first as Remembered.Saved).id, (again as Remembered.Saved).id)
            assertEquals(1, memory.activeFacts().size)
            assertTrue(memory.activeFacts().single().text.contains("несколько лет"))
            // An old, exact detail among many newer messages that share its common words.
            val old = chats.start("old", "Ветеринар выписал коту антибиотик Синулокс на неделю.", "fixture")
            chats.checkpoint(old, "Понял, давай коту лекарство с едой.", "", "done")
            repeat(60) { i ->
                val id = chats.start("noise-$i", "Кот опять не ест корм, лекарство и ветеринар $i", "fixture")
                chats.checkpoint(id, "Коту можно дать лекарство после еды, ветеринар подскажет.", "", "done")
            }
            val search = dev.kento.data.memory.MemoryTools(memory).all().first { it.name == "memory_search" }
            val found = search.run(buildJsonObject { put("query", "кот ветеринар лекарство антибиотик") }).text
            assertTrue(found, found.contains("Синулокс"))
        } finally { database.close() }
    }
}
