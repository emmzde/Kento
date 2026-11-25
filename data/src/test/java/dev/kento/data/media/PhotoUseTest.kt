package dev.kento.data.media

import dev.kento.data.media.PhotoUse.*
import dev.kento.data.settings.ModelOption
import dev.kento.data.settings.Settings
import org.junit.Assert.*
import org.junit.Test

/** Vision from the catalogue only, and OCR text only where it is needed. */
class PhotoUseTest {
    @Test fun visionComesFromTheCatalogueOrTheUsersChoice() {
        val catalog = listOf(ModelOption("sees", vision = true), ModelOption("reads", vision = false), ModelOption("unknown"))
        assertEquals(true, Settings(model = "sees", catalog = catalog).seesImages())
        assertEquals(false, Settings(model = "reads", catalog = catalog).seesImages())
        assertNull(Settings(model = "unknown", catalog = catalog).seesImages())
        assertNull(Settings(model = "not-loaded").seesImages())
        // "Vision" / "Local OCR" on the connection page wins over the catalogue.
        assertEquals(false, Settings(model = "sees", catalog = catalog, visionOverrides = mapOf("sees" to false)).seesImages())
        assertEquals(true, Settings(model = "reads", catalog = catalog, visionOverrides = mapOf("reads" to true)).seesImages())
    }

    /** No probe request: preparing attachments has no provider to call at all. */
    @Test fun preparingAttachmentsCannotReachTheProvider() {
        val parameters = MediaRequest::class.java.constructors.single().parameterTypes.toList()
        assertEquals(listOf(LocalMedia::class.java), parameters)
    }

    @Test fun aBatchForAVisionModelGoesWithoutOcrText() {
        assertEquals(List(5) { PIXELS }, photoUses(true, List(5) { false }))
        assertEquals(listOf(PIXELS), photoUses(true, listOf(false)))
    }

    @Test fun aTextModelGetsTheOcrTextOfEveryPhoto() {
        assertEquals(List(20) { TEXT }, photoUses(false, List(20) { false }))
    }

    @Test fun theOcrSwitchAddsTheTextToThePictures() {
        assertEquals(listOf(PIXELS_AND_TEXT, PIXELS_AND_TEXT), photoUses(true, listOf(true, true)))
        assertEquals(listOf(PIXELS_AND_TEXT, PIXELS), photoUses(true, listOf(true, false)))
    }

    /** Refuseless replaces images for a text-only model with a note, so a model of unknown vision gets both. */
    @Test fun unknownVisionSendsPicturesWithTheirText() {
        assertEquals(List(3) { PIXELS_AND_TEXT }, photoUses(null, List(3) { false }))
    }

    /** kimi-k3.1-pro and gemma-4-26b refuse more than 10 images per request (400 too_many_images, checked live). */
    @Test fun photosPastTheLimitTravelAsText() {
        val uses = photoUses(true, List(20) { false })
        assertEquals(List(IMAGE_LIMIT) { PIXELS } + List(20 - IMAGE_LIMIT) { TEXT_OVER_LIMIT }, uses)
        assertEquals(10, IMAGE_LIMIT)
        assertEquals(IMAGE_LIMIT, photoUses(null, List(12) { true }).count { it == PIXELS_AND_TEXT })
    }

    @Test fun labelOfAPhotoPastTheLimitSaysWhy() {
        val row = dev.kento.data.db.Attachment("id-11", "c", 1, "page.jpg", "image/jpeg", 10, "h")
        val label = attachmentLabel(10, row, text = "Page eleven", overLimit = true)
        assertTrue(label, label.contains("at most 10 images") && label.endsWith("Text recognized in the image:\nPage eleven"))
        assertFalse(attachmentLabel(0, row).contains("Text recognized"))
    }
}
