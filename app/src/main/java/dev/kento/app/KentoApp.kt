package dev.kento.app

import android.app.Application
import android.util.Log
import dev.kento.data.db.KentoDb
import dev.kento.data.net.Provider
import dev.kento.data.settings.SettingsStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.firstOrNull
import dev.kento.data.memory.MemoryWorker
import dev.kento.data.web.WebAccess
import dev.kento.data.web.SearchAccess
import dev.kento.data.web.DuckDuckGoSearch
import dev.kento.data.web.AndroidPageRenderer
import dev.kento.data.web.WebCache
import dev.kento.feature.chat.ChatDictation
import dev.kento.feature.chat.DictationHost

internal fun traceSearch(event: String) {
    if (Log.isLoggable("KentoSearch", Log.DEBUG)) Log.d("KentoSearch", event)
}

open class KentoApp : Application(), DictationHost, dev.kento.data.net.ProviderHost {
    override fun onCreate() {
        super.onCreate()
        dev.kento.core.text.I18n.init(this)
        dev.kento.core.ui.Appearance.load(this)
        dev.kento.data.log.Journal.init(this)
        // The engine posts "answer ready" only when no screen of the app is visible.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            var started = 0
            override fun onActivityStarted(activity: android.app.Activity) { started++; chatEngine.visible = true }
            override fun onActivityStopped(activity: android.app.Activity) { started--; chatEngine.visible = started > 0 }
            override fun onActivityCreated(activity: android.app.Activity, state: android.os.Bundle?) = Unit
            override fun onActivityResumed(activity: android.app.Activity) = Unit
            override fun onActivityPaused(activity: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(activity: android.app.Activity, state: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: android.app.Activity) = Unit
        })
    }
    /** Reads the facts of finished replies in the background; instrumented journeys run it themselves when they test it. */
    open fun scheduleMemory() = MemoryWorker.schedule(this)
    /** Whether replies outlive the chat screen; instrumented journeys turn it off so tests stay independent. */
    open val keepRepliesAfterScreen = true
    /** The first launch opens with the welcome screen; instrumented journeys skip it. */
    open val showWelcome = true
    /** Replies run here, not in a screen, so leaving the chat does not cut them off. */
    val chatEngine by lazy {
        dev.kento.feature.chat.ChatEngine(this, db, api, dev.kento.data.memory.ChatContext(db, database.memory(), api),
            dev.kento.data.web.Research(web), media, ::scheduleMemory, nameChat)
    }

    override val dictation by lazy {
        ChatDictation(this).also { voice ->
            // Cloud transcription follows the speech settings as soon as they are saved.
            scope.launch { settings.profiles.catch { }.collect { voice.cloud = it.speech.takeIf { s -> s.configured } } }
        }
    }
    val database by lazy { KentoDb.open(this) }
    val db by lazy { database.chats() }
    val media by lazy { dev.kento.data.media.LocalMedia(this, database) }
    val settings by lazy { SettingsStore(this) }
    override val api by lazy { Provider() }
    open val nameChat: suspend (dev.kento.data.settings.Settings, String) -> String? by lazy { dev.kento.data.memory.ChatTitles(api)::suggest }
    open val searchBrowser by lazy { DuckDuckGoSearch(this) }
    val webCache by lazy { WebCache(database.web(), ::traceSearch) }
    open val web: SearchAccess by lazy { WebAccess(searchBrowser, webCache, AndroidPageRenderer(this)) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val recovery by lazy { scope.async {
        db.recoverInterrupted()
        media.recover()
        val book = settings.profiles.catch { /* SettingsScreen reports unreadable profiles. */ }.firstOrNull()
        // Facts the user shared in a reply that finished while the app was gone are read now.
        if (book != null && book.memory.enabled && book.memory.background)
            scheduleMemory()
    } }
}
