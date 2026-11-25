package dev.kento.data.settings

import dev.kento.core.text.tr

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

private val Context.store by preferencesDataStore("connection")
private val bookJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }

private const val REFUSELESS_HOST = "api.refuseless.com"
private const val OPENUNCENSORED_HOST = "api.openuncensored.com"

/** Reads stored settings of any version; books older than [BOOK_VERSION] are migrated by [migrateBook]. */
internal fun decodeBook(clear: String): ProfileBook {
    val obj = bookJson.parseToJsonElement(clear).jsonObject
    val version = (obj["version"] as? JsonPrimitive)?.intOrNull ?: 0
    return if (version >= BOOK_VERSION) bookJson.decodeFromJsonElement<ProfileBook>(obj).let { book ->
        // One connection only; a hand-edited or merged book with more keeps the active one.
        if (book.profiles.size == 1) book else book.replace(book.active)
    } else migrateBook(obj)
}

/** Whether a stored book predates the single Refuseless connection. */
internal fun legacyBook(clear: String): Boolean = runCatching {
    ((bookJson.parseToJsonElement(clear).jsonObject["version"] as? JsonPrimitive)?.intOrNull ?: 0) < BOOK_VERSION
}.getOrDefault(false)

private fun host(url: String?) = runCatching { java.net.URI(url.orEmpty().trim()).host?.lowercase() }.getOrNull()
private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

/**
 * Books before 0.6 held any number of OpenAI-compatible connections, each with its own address and key, and a
 * separate image API of any protocol. Now text goes to Refuseless and pictures to OpenUncensored, so the book becomes
 * one connection: the prompts and sampling of the active profile (they are the user's own words and
 * habits), the key, models and reasoning level of the profile that pointed at Refuseless, and for pictures the
 * OpenUncensored key from the old image API or from an old OpenUncensored chat profile. Version 0.1.1 stored a single
 * profile at the top level, always for Refuseless. The old message count `historyLimit` is not carried over: the chat
 * memory is a token budget now and starts at [HISTORY_TOKENS].
 */
internal fun migrateBook(obj: JsonObject): ProfileBook {
    val legacy = "profiles" !in obj
    val raw = if (legacy) listOf(obj) else (obj["profiles"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    fun JsonObject.base() = text("baseUrl").ifBlank { if (legacy) REFUSELESS_URL else "" }
    val active = raw.firstOrNull { it.text("id") == obj.text("activeId") } ?: raw.firstOrNull() ?: JsonObject(emptyMap())
    val refuseless = (listOf(active) + raw).firstOrNull { host(it.base()) == REFUSELESS_HOST }
    val own = bookJson.decodeFromJsonElement<Settings>(active)
    val source = refuseless?.let { bookJson.decodeFromJsonElement<Settings>(it) }
    val effort = refuseless?.text("effort")?.let { if (it == "none") "off" else it }?.takeIf { it in EFFORT_ORDER }
    val model = source?.model.orEmpty().ifBlank { if (legacy) "deepseek-v4.1-flash-abliterated" else "" }
    val chat = Settings(
        apiKey = source?.apiKey.orEmpty(),
        model = model,
        memoryModel = source?.memoryModel.orEmpty(),
        systemPrompt = own.systemPrompt, promptFormat = own.promptFormat, promptValues = own.promptValues, promptClock = own.promptClock,
        efforts = if (effort != null && model.isNotBlank()) mapOf(model to effort) else emptyMap(),
        id = "refuseless", name = "Refuseless",
        visionOverrides = (source?.visionOverrides.orEmpty() + own.visionOverrides),
        sampling = source?.sampling.orEmpty() + own.sampling)
    val oldImages = obj["images"] as? JsonObject
    val imagesOnOu = host(oldImages?.text("baseUrl")) == OPENUNCENSORED_HOST && oldImages?.text("apiKey")?.isNotBlank() == true
    val ouKey = if (imagesOnOu) oldImages!!.text("apiKey")
        else raw.firstOrNull { host(it.base()) == OPENUNCENSORED_HOST && it.text("apiKey").isNotBlank() }?.text("apiKey").orEmpty()
    val images = ImageEndpoint(apiKey = ouKey.trim(),
        catalog = if (imagesOnOu) runCatching {
            bookJson.decodeFromJsonElement<List<dev.kento.data.net.ImageModel>>(oldImages!!["catalog"] ?: JsonArray(emptyList()))
        }.getOrDefault(emptyList()) else emptyList(),
        generationModel = active.text("generationModel"), editModel = active.text("editModel"))
    fun <T> part(key: String, fallback: T, read: (JsonElement) -> T): T = obj[key]?.let { runCatching { read(it) }.getOrNull() } ?: fallback
    val memory = part("memory", MemorySettings()) { bookJson.decodeFromJsonElement<MemorySettings>(it) }
    return ProfileBook(listOf(chat), chat.id,
        web = part("web", WebSettings()) { bookJson.decodeFromJsonElement<WebSettings>(it) },
        // The chat model no longer saves facts itself (memory_write is gone): the background reader takes over.
        memory = memory.copy(background = true),
        bubble = obj.text("bubble").ifBlank { "blue" },
        images = images,
        speech = part("speech", SpeechEndpoint()) { bookJson.decodeFromJsonElement<SpeechEndpoint>(it) })
}

class SettingsStore(context: Context) {
    private val store = context.applicationContext.store
    private val field = stringPreferencesKey("encrypted_profile")
    /** The encrypted book as it was before the 0.6 migration, kept once so nothing the user typed is lost. */
    private val previous = stringPreferencesKey("encrypted_profile_v0")
    private val box = SecretBox()
    private val json = Json { encodeDefaults = true }
    /**
     * True when the saved settings exist but cannot be decrypted: the Keystore key was lost (new screen lock,
     * restored phone, firmware fault). The app then runs on defaults and offers [reset]; chats and dossier are
     * in Room and stay untouched.
     */
    val unreadable = kotlinx.coroutines.flow.MutableStateFlow(false)
    private fun read(blob: String?): ProfileBook {
        if (blob == null) return ProfileBook()
        return try {
            decodeBook(box.open(blob)).also { unreadable.value = false }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            unreadable.value = true
            dev.kento.data.log.Journal.note("settings", tr("Настройки не расшифровываются: ") + e.javaClass.simpleName)
            ProfileBook()
        }
    }
    val profiles = store.data.map { read(it[field]) }.flowOn(Dispatchers.IO)

    /** Forgets the broken key and the unreadable settings; the next save starts from clean defaults. */
    suspend fun reset() = withContext(Dispatchers.IO) {
        box.forget()
        store.edit { it.remove(field) }
        unreadable.value = false
    }
    /** The Refuseless connection with the user's prompts and models. */
    val settings = profiles.map { it.active }
    suspend fun updateBook(change: (ProfileBook) -> ProfileBook) = withContext(Dispatchers.IO) {
        // Saving over unreadable settings is a reset: the broken key cannot seal anything either.
        val resetting = unreadable.value
        if (resetting) { box.forget(); unreadable.value = false }
        store.edit { prefs ->
            val blob = prefs[field]
            // A read that fails without the user's reset must not quietly replace their settings with defaults.
            val clear = if (blob == null) null else try { box.open(blob) } catch (e: Exception) {
                if (resetting) null else throw e
            }
            if (blob != null && clear != null && prefs[previous] == null && legacyBook(clear)) prefs[previous] = blob
            val current = clear?.let(::decodeBook) ?: ProfileBook()
            prefs[field] = box.seal(json.encodeToString(change(current)))
        }
        Unit
    }
    suspend fun update(change: (Settings) -> Settings) = updateBook { it.replace(change(it.active)) }
}
