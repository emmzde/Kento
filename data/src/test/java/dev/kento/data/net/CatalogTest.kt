package dev.kento.data.net

import dev.kento.data.settings.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Shapes copied from the live catalogues of Refuseless and OpenUncensored (2026-11-23). */
class CatalogTest {
    private val catalog = modelCatalog("""{"object":"list","data":[
        {"id":"glm-5.3-abliterated","object":"model","created":1795457793,"owned_by":"refuseless","abliterated":true,"vision":false,
         "context_length":1048576,"reasoning_efforts":["low","high","max"],"reasoning_default":"low",
         "pricing":{"currency":"USD","input_per_m":1,"cached_input_per_m":0.15,"output_per_m":2}},
        {"id":"deepseek-v4.1-flash-abliterated","abliterated":true,"vision":true,"context_length":1048576,
         "reasoning_efforts":["off","low","high","max"],"reasoning_default":"low",
         "pricing":{"currency":"USD","input_per_m":0.12,"cached_input_per_m":0.019,"output_per_m":0.45}},
        {"id":"deepseek-v4.1-flash-fast","abliterated":false,"vision":true,"reasoning_efforts":["off","low","high","max"],
         "reasoning_default":"low","pricing":{"currency":"USD","input_per_m":0.01,"cached_input_per_m":0.002,"output_per_m":0.08}},
        {"id":"deepseek-v4.1-flash-abliterated-fast","abliterated":true,"vision":true,"reasoning_efforts":["off","low","high","max"],
         "reasoning_default":"low","pricing":{"currency":"USD","input_per_m":0.12,"cached_input_per_m":0.025,"output_per_m":0.45}},
        {"id":"kimi-k3.1-pro-abliterated","abliterated":true,"vision":true,"reasoning_efforts":["low","high","max"],"reasoning_default":"low",
         "pricing":{"currency":"USD","input_per_m":1.5,"cached_input_per_m":0.15,"output_per_m":3,"discounted":true,
         "regular":{"input_per_m":2.5,"cached_input_per_m":0.25,"output_per_m":5},
         "long_context":{"threshold":200000,"input_per_m":3,"cached_input_per_m":0.3,"output_per_m":6}}},
        {"id":"gemma-4-26b-storyteller","abliterated":false,"vision":true,"context_length":262144,
         "reasoning_efforts":["off","low","high","max"],"reasoning_default":"off",
         "pricing":{"currency":"USD","input_per_m":0.08,"cached_input_per_m":0.002,"output_per_m":0.2}},
        {"id":"embed-preview","abliterated":false}]}""")
    private fun chat(model: String) = Settings(model = model, catalog = catalog)

    @Test fun catalogueFieldsArePublishedOnesOnly() {
        val glm = chat("glm-5.3-abliterated")
        assertEquals(listOf("low", "high", "max"), glm.levels())
        assertEquals("low", glm.option()!!.defaultEffort)
        assertEquals(false, glm.option()!!.vision)
        assertEquals(1.0, glm.option()!!.priceIn!!, 1e-9)
        assertEquals(0.15, glm.option()!!.priceCached!!, 1e-9)
        assertEquals(listOf("off", "low", "high", "max"), chat("deepseek-v4.1-flash-abliterated").levels())
        assertFalse(chat("deepseek-v4.1-flash-fast").option()!!.abliterated)
        val kimi = chat("kimi-k3.1-pro-abliterated").option()!!
        assertTrue(kimi.discounted)
        assertEquals(LongContext(200_000, 3.0, 0.3, 6.0), kimi.longContext)
        // A model with no levels gets none invented.
        assertTrue(chat("embed-preview").levels().isEmpty())
        assertNull(chat("embed-preview").effort())
    }

    @Test fun chatThinksHighByDefaultAndKeepsTheChoicePerModel() {
        val ds = chat("deepseek-v4.1-flash-abliterated")
        assertEquals("high", ds.effort())
        assertEquals("high", payload(ds, emptyList(), chat = true).str("reasoning_effort"))
        val chosen = ds.withEffort("max")
        assertEquals("max", payload(chosen, emptyList(), chat = true).str("reasoning_effort"))
        // Another model keeps its own level, and a level the model does not list is never sent (Refuseless: 400).
        assertEquals("high", chosen.copy(model = "glm-5.3-abliterated").effort())
        val glmOff = chat("glm-5.3-abliterated").withEffort("off")
        assertEquals("high", glmOff.effort())
        assertEquals("low", payload(chat("glm-5.3-abliterated"), emptyList(), effort = "low").str("reasoning_effort"))
        assertNull(payload(chat("glm-5.3-abliterated"), emptyList(), effort = "off")["reasoning_effort"])
    }

    @Test fun backgroundRequestsThinkAsLittleAsTheModelAllows() {
        assertEquals("off", payload(chat("deepseek-v4.1-flash-abliterated"), emptyList()).str("reasoning_effort"))
        // GLM cannot switch reasoning off: its lightest level is the background one.
        assertEquals("low", payload(chat("glm-5.3-abliterated"), emptyList()).str("reasoning_effort"))
        assertEquals("deepseek-v4.1-flash-abliterated-fast", chat("glm-5.3-abliterated").background())
        assertEquals("glm-5.3-abliterated", chat("glm-5.3-abliterated").copy(memoryModel = "glm-5.3-abliterated").background())
        assertEquals("m", Settings(model = "m").background())
    }

    @Test fun samplingAndOutputCapTravelOnlyWithChatRequests() {
        val values = Sampling(temperature = 0.3, topP = 0.9, topK = 40, minP = 0.05, maxTokens = 800, seed = 7, repetitionPenalty = 1.1)
        val s = chat("deepseek-v4.1-flash-abliterated").withSampling(values)
        val request = payload(s, emptyList(), chat = true)
        assertEquals(0.3, request.getValue("temperature").jsonPrimitive.double, 1e-9)
        assertEquals(0.9, request.getValue("top_p").jsonPrimitive.double, 1e-9)
        assertEquals(40, request.getValue("top_k").jsonPrimitive.int)
        assertEquals(0.05, request.getValue("min_p").jsonPrimitive.double, 1e-9)
        assertEquals(1.1, request.getValue("repetition_penalty").jsonPrimitive.double, 1e-9)
        assertEquals(800, request.getValue("max_tokens").jsonPrimitive.int)
        assertEquals(7, request.getValue("seed").jsonPrimitive.int)
        assertEquals(true, request.getValue("stream_options").jsonObject.getValue("include_usage").jsonPrimitive.boolean)
        // Without a value the chat still caps its output, so the credit hold stays small; background requests do not.
        assertEquals(32_768, payload(chat("deepseek-v4.1-flash-abliterated"), emptyList(), chat = true).getValue("max_tokens").jsonPrimitive.int)
        val background = payload(s, emptyList())
        assertFalse("top_p" in background || "temperature" in background || "seed" in background || "max_tokens" in background)
        assertEquals(0.2, payload(s, emptyList(), temperature = 0.2, chat = true).getValue("temperature").jsonPrimitive.double, 1e-9)
        // Sampling belongs to the model it was set for.
        assertFalse("temperature" in payload(s.copy(model = "glm-5.3-abliterated"), emptyList(), chat = true))
        assertTrue(s.withSampling(Sampling()).sampling.isEmpty())
    }

    @Test fun outputCapFollowsTheReasoningLevelUnlessTheUserSetsOne() {
        fun cap(s: Settings, effort: String? = null) = payload(s, emptyList(), chat = true, effort = effort).getValue("max_tokens").jsonPrimitive.int
        val deepseek = chat("deepseek-v4.1-flash-abliterated")
        assertEquals(8_192, cap(deepseek.withEffort("off")))
        assertEquals(16_384, cap(deepseek.withEffort("low")))
        assertEquals(32_768, cap(deepseek.withEffort("high")))
        assertEquals(65_536, cap(deepseek.withEffort("max")))
        // The level actually sent decides, also when a caller overrides it.
        assertEquals(65_536, cap(deepseek.withEffort("low"), effort = "max"))
        // GLM has no "off": the chat goes out at "high" and gets its cap.
        assertEquals(32_768, cap(chat("glm-5.3-abliterated").withEffort("off")))
        // A model without levels thinks at the provider default "low".
        assertEquals(16_384, cap(Settings(model = "private-model")))
        // A value from "Advanced" wins on every level.
        assertEquals(3_000, cap(deepseek.withEffort("max").withSampling(Sampling(maxTokens = 3_000))))
        assertEquals(3_000, deepseek.withSampling(Sampling(maxTokens = 3_000)).maxTokens("off"))
    }

    @Test fun pickerDetailsNameRefusalsAndDiscounts() {
        val details = chat("deepseek-v4.1-flash-fast").modelDetails()
        assertEquals("фото · рассуждения · с отказами · $0.01 / $0.08 за 1M", details.getValue("deepseek-v4.1-flash-fast"))
        assertTrue(details.getValue("kimi-k3.1-pro-abliterated").endsWith("скидка"))
        assertEquals("рассуждения · $1 / $2 за 1M", details.getValue("glm-5.3-abliterated"))
    }

    @Test fun catalogueIsReadAgainWhenOldOrFromAnOlderParser() {
        val now = 1_000_000_000_000L
        val fresh = Settings(catalogLoaded = true, catalogVersion = CATALOG_VERSION, catalogAt = now - 60_000)
        assertFalse(fresh.catalogStale(now))
        assertTrue(fresh.copy(catalogAt = now - CATALOG_MAX_AGE_MS - 1).catalogStale(now))
        assertTrue(fresh.copy(catalogVersion = CATALOG_VERSION - 1).catalogStale(now))
        assertTrue(Settings().catalogStale(now))
    }

    @Test fun imageCatalogueOfOpenUncensored() {
        val models = imageCatalog("""{"object":"list","data":[
            {"id":"seedream-v5-5-edit","type":"image","name":"Seedream 5.5 Edit","price_per_image_usd":0.0255,"price_per_input_image_usd":0.0102,
             "operations":["edit"],"edit_mode":"reference","max_input_images":10,"description":"Reference editor"},
            {"id":"lustify-v8","type":"image","price_per_image_usd":0.01,"operations":["generate","edit"],"edit_mode":"variant","max_input_images":1},
            {"id":"z-image-turbo","type":"image","price_per_image_usd":0.01,"operations":["generate"]}]}""")
        val seedream = models.first()
        assertEquals("$0.0255 + $0.0102 за фото · всего $0.0561", seedream.priceText(3))
        assertEquals("Reference editor", seedream.description)
        assertFalse(seedream.accepts(0))
        assertTrue(seedream.accepts(10))
        assertFalse(seedream.accepts(11))
        assertTrue(models[1].accepts(1) && !models[1].accepts(2))
        assertEquals("$0.01 за изображение", models[2].priceText(0))
        val endpoint = ImageEndpoint("ou-key", models, generationModel = "z-image-turbo")
        assertEquals("z-image-turbo", endpoint.option(0)!!.id)
        assertEquals("seedream-v5-5-edit", endpoint.option(2)!!.id)
        assertEquals("lustify-v8", endpoint.copy(editModel = "lustify-v8").option(1)!!.id)
        assertEquals(OPENUNCENSORED_URL, endpoint.baseUrl)
        assertFalse(ImageEndpoint().configured)
    }
}
