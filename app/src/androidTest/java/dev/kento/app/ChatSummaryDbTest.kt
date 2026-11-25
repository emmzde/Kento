package dev.kento.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.kento.data.db.ChatSummary
import dev.kento.data.db.KentoDb
import dev.kento.data.db.PromptSize
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** On the real SQLite: the v9 → v10 upgrade and the rules that keep a summary true. */
class ChatSummaryDbTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()

    @Test fun version9UpgradesToSummariesAndMeasuredPrompts() = runBlocking {
        val name = "summary-migration-" + UUID.randomUUID()
        val schema = InstrumentationRegistry.getInstrumentation().context.assets
            .open("dev.kento.data.db.KentoDb/9.json").bufferedReader().use { Json.parseToJsonElement(it.readText()) }
            .jsonObject.getValue("database").jsonObject
        try {
            app.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
                for (entity in schema.getValue("entities").jsonArray.map { it.jsonObject }) {
                    val table = entity.getValue("tableName").jsonPrimitive.content
                    fun sql(o: JsonObject) = o.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table)
                    db.execSQL(sql(entity))
                    (entity["indices"] as? JsonArray)?.forEach { db.execSQL(sql(it.jsonObject)) }
                    (entity["contentSyncTriggers"] as? JsonArray)?.forEach { db.execSQL(it.jsonPrimitive.content) }
                }
                schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
                db.execSQL("INSERT INTO chats (id, title, updatedAt) VALUES ('old', 'Старый чат', 1)")
                db.execSQL("INSERT INTO messages (id, chatId, role, text, thoughts, state, model, issue) VALUES (1, 'old', 'user', 'Вопрос', '', 'done', '', '')")
                db.execSQL("INSERT INTO messages (id, chatId, role, text, thoughts, state, model, issue) VALUES (2, 'old', 'assistant', 'Ответ', '', 'done', 'm', '')")
                db.version = 9
            }
            val upgraded = Room.databaseBuilder(app, KentoDb::class.java, name).build()
            try {
                val chats = upgraded.chats()
                val rows = chats.contextSince("old", 0)
                assertEquals(listOf("Ответ", "Вопрос"), rows.map { it.text })
                // Old replies were never measured; the chat counts by the estimate until its next reply.
                assertTrue(rows.all { it.promptTokens == 0 && it.promptEstimate == 0 })
                assertNull(chats.lastPrompt("old"))
                chats.setPrompt(2, 1_200, 2_400)
                assertEquals(PromptSize(1_200, 2_400), chats.lastPrompt("old"))
                assertEquals(listOf("Ответ"), chats.contextSince("old", 1).map { it.text })
                assertNull(chats.summary("old"))
                chats.saveSummary(ChatSummary("old", 1, "Сводка", 5))
                assertEquals(ChatSummary("old", 1, "Сводка", 5), chats.summary("old"))
                // One per chat: a newer fold replaces it.
                chats.saveSummary(ChatSummary("old", 2, "Новая сводка", 6))
                assertEquals("Новая сводка", chats.summary("old")?.text)
                // The summary goes with its chat.
                chats.delete("old")
                assertNull(chats.summary("old"))
            } finally { upgraded.close() }
        } finally { app.deleteDatabase(name) }
    }

    @Test fun anEditInsideTheSummaryDropsItAndOneAfterItKeepsIt() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(app, KentoDb::class.java).build()
        try {
            val chats = db.chats()
            val chat = "edits"
            val replies = listOf("q1", "q2", "q3").map { q -> chats.start(chat, q, "m").also { chats.checkpoint(it, "a-$q", "", "done") } }
            val questions = chats.contextSince(chat, 0).filter { it.role == "user" }.map { it.id }.sorted()
            chats.saveSummary(ChatSummary(chat, replies[0], "Сводка о q1"))
            // Retrying the newest reply leaves the summary of older messages as it is.
            assertTrue(chats.retry(chat, replies[2], "m"))
            chats.checkpoint(replies[2], "a-q3 again", "", "done")
            assertNotNull(chats.summary(chat))
            // Edits after the summarized part keep it.
            assertNotNull(chats.edit(chat, questions[2], "q3 edited", "m"))
            assertNotNull(chats.summary(chat))
            assertNotNull(chats.edit(chat, questions[1], "q2 edited", "m"))
            assertNotNull(chats.summary(chat))
            // An edit of a summarized message drops it; the next request builds it again.
            assertNotNull(chats.edit(chat, questions[0], "q1 edited", "m"))
            assertNull(chats.summary(chat))
            assertEquals(listOf("q1 edited"), chats.contextSince(chat, 0).map { it.text })
        } finally { db.close() }
    }
}
