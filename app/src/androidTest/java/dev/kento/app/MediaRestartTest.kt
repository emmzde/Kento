package dev.kento.app

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.kento.data.media.Images
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Run seed and verify in separate instrumentation processes. Only the QA package is used. */
class MediaRestartTest {
    @Test fun draftSurvivesProcessRestart() = runBlocking {
        val stage = InstrumentationRegistry.getArguments().getString("restartStage")
        assumeTrue(stage == "seed" || stage == "verify")
        val app = ApplicationProvider.getApplicationContext<TestApp>()
        app.recovery.await()
        val chat = "media-restart-fixture"
        val prefs = app.getSharedPreferences("restart-test", 0)
        if (stage == "seed") {
            app.db.delete(chat)
            val target = app.media.cameraTarget()
            val bmp = Bitmap.createBitmap(75, 96, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
            target.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 96, it) }
            bmp.recycle()
            app.media.acceptCapture(chat, target.name)
            val row = app.media.dao.observe(chat).first().single()
            assertTrue(prefs.edit().putInt("pid", android.os.Process.myPid()).putString("id", row.id).commit())
        } else {
            assertNotEquals(prefs.getInt("pid", 0), android.os.Process.myPid())
            assertEquals(chat, app.media.dao.latestDraft())
            val row = app.media.dao.observe(chat).first().single()
            assertEquals(prefs.getString("id", ""), row.id)
            assertEquals(96, Images.bounds(app.media.file(row)).outHeight)
            androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java).use {
                val device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                // Photo tiles show the picture without a caption (design 0.4.1); the file name is their description.
                assertTrue("Restored draft preview", device.wait(androidx.test.uiautomator.Until.hasObject(
                    androidx.test.uiautomator.By.desc("Камера.jpg")), 10000))
            }
            app.db.delete(chat)
            app.media.recover()
            assertFalse(app.media.file(row).exists())
        }
    }
}
