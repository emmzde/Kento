package dev.kento.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import dev.kento.data.settings.Settings
import dev.kento.data.settings.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.security.KeyStore
import java.util.concurrent.TimeUnit

class KeySecurityJourneyTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun keyStaysEncryptedAndUnauthorizedResponseOmitsIt() {
        val app = ApplicationProvider.getApplicationContext<TestApp>()
        val key = "KENTO_SECURITY_FIXTURE_b7189efaf3"
        val model = "security-fixture"
        val prompt = "Key security check"
        MockWebServer().use { server ->
            server.start()
            app.apiBase = server.url("/v1").toString()
            runBlocking {
                app.settings.update { Settings(id = it.id, apiKey = key).select(model) }
            }
            val persisted = runBlocking { SettingsStore(app).settings.first() }
            assertTrue("The saved key could not be read back", persisted.apiKey == key)
            val preferences = app.filesDir.resolve("datastore/connection.preferences_pb").readBytes().toString(Charsets.UTF_8)
            assertTrue(preferences.contains("encrypted_profile"))
            assertFalse("Plaintext API key in DataStore", preferences.contains(key))
            val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val encryptionKey = keystore.getKey("kento.settings.v1", null)
            assertNotNull(encryptionKey)
            assertNull("Android Keystore key must be non-exportable", encryptionKey.encoded)

            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"Invalid credential $key"}"""))
            ui.onNodeWithContentDescription("Сообщение").performClick()
            ui.waitUntil(10_000) { ui.onAllNodesWithText(model).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithContentDescription("Сообщение").performTextInput(prompt)
            ui.onNodeWithContentDescription("Отправить").performClick()
            ui.waitUntil(10_000) {
                ui.onAllNodesWithText("Проверь API-ключ (HTTP 401)", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            ui.onNodeWithText(key, substring = true).assertDoesNotExist()
            val request = checkNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertTrue("Authorization header was missing or changed", request.getHeader("Authorization") == "Bearer $key")
            assertFalse("API key in request body", request.body.readUtf8().contains(key))
            runBlocking {
                val chat = checkNotNull(app.db.latest())
                val reply = app.db.recent(chat, 1).first().single()
                assertEquals("failed", reply.state)
                // The provider's explanation is shown, with the echoed key cut out.
                assertTrue(reply.issue, reply.issue.startsWith("Проверь API-ключ (HTTP 401)"))
                assertFalse("API key in the error text", reply.issue.contains(key))
                val export = "key-security-export-fixture"
                val message = app.db.start(export, "Export security check", model)
                app.db.checkpoint(message, "Example with credential $key", "", "done")
            }
        }
    }
}
