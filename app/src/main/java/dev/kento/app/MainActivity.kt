package dev.kento.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.kento.core.ui.KentoTheme
import dev.kento.feature.chat.*
import dev.kento.feature.settings.*
import dev.kento.data.web.Research

private enum class Screen { Chat, Settings, Dossier, Recents, Search }

class MainActivity : ComponentActivity() {
    private val services get() = application as KentoApp
    private val chat: ChatViewModel by viewModels { viewModelFactory {
        initializer { ChatViewModel(services.db, services.settings, services.api, createSavedStateHandle(), services.dictation, services.media, services.recovery,
            dev.kento.data.memory.ChatContext(services.db, services.database.memory(), services.api),
            Research(services.web),
            services::scheduleMemory, services.nameChat, services.chatEngine.takeIf { services.keepRepliesAfterScreen }) }
    } }
    private val editor: SettingsEditor by viewModels { viewModelFactory {
        initializer { SettingsEditor(services.settings, services.api, services.database.memory(), services.web,
            services::scheduleMemory,
            dev.kento.data.backup.Backup(services.database, services.media, services.settings)) }
    } }

    /** A chat a "ready" notification asked to open; the chat screen picks it up. */
    private val opening = mutableStateOf<String?>(null)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(ChatEngine.OPEN_CHAT)?.let { opening.value = it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) intent?.getStringExtra(ChatEngine.OPEN_CHAT)?.let { opening.value = it }
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent {
            var screen by rememberSaveable { mutableStateOf(Screen.Chat) }
            val states = rememberSaveableStateHolder()
            val home = { screen = Screen.Chat }
            val target by opening
            val ready by chat.ready.collectAsState()
            LaunchedEffect(target, ready) {
                val id = target ?: return@LaunchedEffect
                if (!ready) return@LaunchedEffect
                screen = Screen.Chat
                chat.select(id)
                opening.value = null
            }
            val routes = remember { ChatRoutes({ screen = Screen.Settings }, { screen = Screen.Dossier },
                { search -> screen = if (search) Screen.Search else Screen.Recents }) }
            var welcome by rememberSaveable { mutableStateOf(services.showWelcome && !welcomed(this@MainActivity)) }
            KentoTheme { Surface(color = Color.Black) {
                if (welcome) { Welcome { welcome = false }; return@Surface }
                // After the welcome the chat rises out of the black instead of cutting in.
                val entry = remember { Animatable(0f) }
                LaunchedEffect(Unit) { entry.animateTo(1f, tween(520, easing = EaseOutCubic)) }
                AnimatedContent(screen, androidx.compose.ui.Modifier.graphicsLayer { alpha = entry.value }, label = "screen", transitionSpec = {
                    // Deeper screens rise in from a little to the right; going home the chat settles back underneath.
                    val forward = targetState != Screen.Chat
                    (if (forward) {
                        (fadeIn(tween(230, 40)) + slideInHorizontally(tween(340, easing = EaseOutCubic)) { it / 9 }) togetherWith
                            fadeOut(tween(180))
                    } else {
                        (fadeIn(tween(240)) + scaleIn(tween(320, easing = EaseOutCubic), initialScale = .985f)) togetherWith
                            (fadeOut(tween(170)) + slideOutHorizontally(tween(280, easing = EaseInCubic)) { it / 9 })
                    }).apply { targetContentZIndex = if (forward) 1f else -1f }
                }) { target ->
                    states.SaveableStateProvider(target.name) {
                        when (target) {
                            Screen.Chat -> ChatScreen(chat, routes)
                            Screen.Settings -> SettingsScreen(editor, home)
                            Screen.Dossier -> DossierScreen(editor.memory, home)
                            Screen.Recents, Screen.Search -> RecentsScreen(chat, target == Screen.Search, home)
                        }
                    }
                }
                SearchVerificationDialog(services.searchBrowser, chat::stop)
            } }
        }
    }
}
