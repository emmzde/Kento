package dev.kento.feature.chat

import dev.kento.core.text.tr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder

/** Holds Android's microphone foreground-service permission while the user dictates. */
class DictationService : Service() {
    private val voice get() = (application as DictationHost).dictation

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            if (voice.listening) voice.stop() else stopSelfResult(startId)
        } else {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, tr("Диктовка"), NotificationManager.IMPORTANCE_LOW))
            val pendingFlags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val stop = PendingIntent.getService(this, 0, Intent(this, DictationService::class.java).setAction(STOP), pendingFlags)
            val notification = Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle(tr("Микрофон Kento включён"))
                .setContentText(tr("Речь добавляется в черновик сообщения"))
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .addAction(Notification.Action.Builder(null, tr("Отключить микрофон"), stop).build())
            packageManager.getLaunchIntentForPackage(packageName)?.let {
                notification.setContentIntent(PendingIntent.getActivity(this, 0, it, pendingFlags))
            }
            try {
                startForeground(1, notification.build())
                voice.attach(this)
            } catch (_: SecurityException) {
                voice.close()
                voice.issue = tr("Android запретил доступ к микрофону. Проверь разрешения Kento.")
                stopSelfResult(startId)
            } catch (_: IllegalStateException) {
                voice.close()
                voice.issue = tr("Android приостановил запуск диктовки. Включи микрофон в открытом чате.")
                stopSelfResult(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        voice.detach(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val CHANNEL = "dictation"
        const val STOP = "dev.kento.feature.chat.STOP_DICTATION"
    }
}
