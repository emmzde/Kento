package dev.kento.feature.chat

import dev.kento.core.text.tr

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.kento.data.db.ChatDao
import dev.kento.data.net.Provider
import dev.kento.data.settings.Settings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * Replies outlive the chat screen. The engine lives as long as the app process: a reply keeps streaming when the
 * user leaves the chat, presses back or switches apps, and [GenerationService] keeps the process in the foreground
 * meanwhile. When the app is not on screen at the end, a notification says the answer is ready and opens that chat.
 */
class ChatEngine(private val app: Context, private val db: ChatDao, api: Provider, context: dev.kento.data.memory.ChatContext,
    research: dev.kento.data.web.Research, media: dev.kento.data.media.LocalMedia, scheduleMemory: () -> Unit,
    nameChat: suspend (Settings, String) -> String? = dev.kento.data.memory.ChatTitles(api)::suggest) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal val generation = Generation(db, api, context, research, media, scheduleMemory, scope, nameChat)
    val sending = MutableStateFlow(false)
    internal var task: Job? = null
    /** Whether an activity of the app is started; set by the application's lifecycle callbacks. */
    @Volatile var visible = true

    internal fun begin() {
        runCatching { app.startForegroundService(Intent(app, GenerationService::class.java)) }
    }

    internal fun end(chat: String) {
        runCatching { app.stopService(Intent(app, GenerationService::class.java)) }
        if (visible) return
        scope.launch(Dispatchers.IO) {
            val reply = runCatching { db.recent(chat, 1).first().firstOrNull() }.getOrNull() ?: return@launch
            if (reply.role != "assistant" || reply.state == "stopped") return@launch
            ready(chat, reply.state == "done", reply.text)
        }
    }

    private fun ready(chat: String, ok: Boolean, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(READY, tr("Готовые ответы"), NotificationManager.IMPORTANCE_DEFAULT))
        val open = app.packageManager.getLaunchIntentForPackage(app.packageName)?.apply {
            putExtra(OPEN_CHAT, chat)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val preview = text.lineSequence().map(String::trim).firstOrNull { it.isNotEmpty() }?.trimStart('#', '*', ' ').orEmpty().take(140)
        val notification = Notification.Builder(app, READY)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(if (ok) tr("Ответ готов") else tr("Ответ не получен"))
            .setContentText(if (ok) preview else tr("Откройте чат, чтобы повторить"))
            .setAutoCancel(true)
            .apply { open?.let { setContentIntent(PendingIntent.getActivity(app, chat.hashCode(), it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)) } }
            .build()
        runCatching { manager.notify(chat.hashCode(), notification) }
    }

    companion object {
        /** Intent extra with the chat a "ready" notification opens. */
        const val OPEN_CHAT = "dev.kento.OPEN_CHAT"
        private const val READY = "replies"
    }
}

/** Keeps the process in the foreground while a reply is being written, so Android does not cut it off. */
class GenerationService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, tr("Ответ в работе"), NotificationManager.IMPORTANCE_MIN))
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(tr("Kento отвечает…"))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .apply {
                packageManager.getLaunchIntentForPackage(packageName)?.let {
                    setContentIntent(PendingIntent.getActivity(this@GenerationService, 0, it, PendingIntent.FLAG_IMMUTABLE))
                }
            }.build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(2, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(2, notification)
        } catch (_: RuntimeException) {
            // Android refused a foreground start (background limits): the reply still runs while the process lives.
            stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object { const val CHANNEL = "generation" }
}
