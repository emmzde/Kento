package dev.kento.app

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import dev.kento.data.media.saveToGallery
import dev.kento.data.net.*
import dev.kento.data.settings.ImageEndpoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/** OpenUncensored images through Kento's client, against a local server that answers in its documented shapes. */
class ImageGenerationTest {
    private val app get() = ApplicationProvider.getApplicationContext<TestApp>()
    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(24, 14, Bitmap.Config.ARGB_8888)
        return try { ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() } }
        finally { bitmap.recycle() }
    }

    @Test fun catalogGenerationRoomAndGalleryKeepOriginal() = runBlocking {
        val bytes = png()
        val encoded = Base64.getEncoder().encodeToString(bytes)
        val chat = UUID.randomUUID().toString()
        MockWebServer().use { server ->
            server.start()
            app.imageBase = server.url("/v1").toString()
            val endpoint = ImageEndpoint(apiKey = "ou-only-for-api")
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"draw","operations":["generate","edit"],"edit_mode":"variant",
                "max_input_images":1,"price_per_image_usd":0.01},{"id":"edit","operations":["edit"],"edit_mode":"reference"}]}"""))
            val models = app.api.images.models(endpoint)
            assertEquals("/v1/images/models", server.takeRequest().path)
            assertEquals(listOf("draw"), models.filter { it.accepts(0) }.map { it.id })
            assertFalse(models.first().accepts(2))
            server.enqueue(MockResponse().setBody("""{"created":1,"data":[{"b64_json":"$encoded"}],
                "ou":{"model":"draw","providers":["venice"],"cost_usd":0.0102,"images_failed":0}}"""))
            val reply = app.db.start(chat, "Нарисуй маяк", models.first().id, true)
            try {
                var billed: Double? = null
                app.media.storeGenerated(chat, reply) { billed = app.api.images.create(endpoint, models.first(), "Нарисуй маяк", emptyList(), it) }
                assertEquals(0.0102, billed!!, 1e-12)
                val post = server.takeRequest()
                assertEquals("/v1/images/generations", post.path)
                assertEquals("Bearer ou-only-for-api", post.getHeader("Authorization"))
                val body = Json.parseToJsonElement(post.body.readUtf8()).jsonObject
                assertEquals(1, body.getValue("n").jsonPrimitive.int)
                assertFalse("images" in body)
                app.media.recover()
                val message = app.db.recent(chat, 2).first().first()
                assertTrue(message.imageGeneration)
                assertEquals("done", message.state)
                val row = app.media.dao.sent(reply).single()
                assertEquals("image/png", row.mime)
                assertArrayEquals(bytes, app.media.file(row).readBytes())
                val uri = app.media.saveToGallery(app, row)
                try { assertArrayEquals(bytes, app.contentResolver.openInputStream(uri)!!.use { it.readBytes() }) }
                finally { app.contentResolver.delete(uri, null, null) }
            } finally { app.db.delete(chat); app.media.recover(); app.imageBase = null }
        }
        Unit
    }

    @Test fun editsSendTheOriginalAsDataUrlsAndReadDataUrlReplies() = runBlocking {
        val bytes = png()
        val encoded = Base64.getEncoder().encodeToString(bytes)
        val ref = File.createTempFile("image-ref", ".png", app.cacheDir).apply { writeBytes(bytes) }
        val result = File.createTempFile("image-result", ".png", app.cacheDir)
        try {
            MockWebServer().use { server ->
                server.start()
                app.imageBase = server.url("/v1").toString()
                val endpoint = ImageEndpoint(apiKey = "ou-key")
                val m = ImageModel("qwen-image-3-edit", generate = false, edit = true, maxInputs = 3)
                val refs = listOf(ImageReference(ref, "image/png"), ImageReference(ref, "image/png"))
                server.enqueue(MockResponse().setBody("""{"data":[{"url":"data:image/png;base64,$encoded"}]}"""))
                val billed = app.api.images.create(endpoint, m, "зелёный фон", refs, result)
                assertNull(billed)
                val edited = server.takeRequest()
                assertEquals("/v1/images/edits", edited.path)
                val images = Json.parseToJsonElement(edited.body.readUtf8()).jsonObject.getValue("images").jsonArray
                assertEquals(listOf("data:image/png;base64,$encoded", "data:image/png;base64,$encoded"), images.map { it.jsonPrimitive.content })
                assertArrayEquals(bytes, result.readBytes())
                // The provider's code says what was wrong with the photos.
                server.enqueue(MockResponse().setResponseCode(400)
                    .setBody("""{"error":{"code":"too_many_images","message":"qwen-image-3-edit takes up to 3 images"}}"""))
                val refused = runCatching { app.api.images.create(endpoint, m, "ещё", refs, result) }.exceptionOrNull() as ProviderIssue
                assertTrue(refused.message!!.startsWith("Слишком много фото для этой модели"))
            }
        } finally { ref.delete(); result.delete(); app.imageBase = null }
    }

    @Test fun failedOrCancelledGenerationDoesNotReplayPost() = runBlocking {
        val target = File.createTempFile("image-output", ".tmp", app.cacheDir)
        try {
            MockWebServer().use { server ->
                server.start()
                app.imageBase = server.url("/v1").toString()
                val endpoint = ImageEndpoint(apiKey = "ou-key")
                val m = ImageModel("one")
                server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After","0"))
                val failed = runCatching { app.api.images.create(endpoint, m, "test", emptyList(), target) }.exceptionOrNull()
                assertEquals(503, (failed as ProviderIssue).status)
                assertEquals(1, server.requestCount)
                server.takeRequest()
                server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
                val job = launch(Dispatchers.IO) { app.api.images.create(endpoint, m, "cancel", emptyList(), target) }
                assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                withTimeout(3000) { job.cancelAndJoin() }
                assertEquals(2, server.requestCount)
            }
        } finally { target.delete(); app.imageBase = null }
    }
}
