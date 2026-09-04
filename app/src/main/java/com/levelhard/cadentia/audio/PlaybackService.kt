package com.levelhard.cadentia.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.levelhard.cadentia.MainActivity
import com.levelhard.cadentia.R

/**
 * O serviço de primeiro plano de **reprodução** (`mediaPlayback`): existe
 * enquanto [PlaybackSession] tem alguém tocando, e é o que impede o Android
 * de congelar o processo com o app atrás — o `UIBackgroundModes: audio` do
 * iOS. A notificação é obrigatória e diz o que está tocando, com um "Parar".
 *
 * Mesmas lições do `SeparationService`: `startForeground` da plataforma com
 * o tipo explícito; `startForeground` recusado → `stopSelf` na hora (um
 * `startForegroundService` sem `startForeground` em 5 s derruba o app);
 * parar fala com a instância viva, porque `startService` do fundo é recusado.
 */
class PlaybackService : Service() {
    private var inForeground = false
    private var stopped = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (stopRequested) {
            stopRequested = false
            stopForegroundAndSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP_ALL -> {
                Log.i(TAG, "reprodução parada pela pessoa na notificação")
                PlaybackSession.stopAll()
                stopForegroundAndSelf()
                return START_NOT_STICKY
            }
            ACTION_HIDE -> {
                stopForegroundAndSelf()
                return START_NOT_STICKY
            }
        }
        show(intent?.getStringExtra(EXTRA_LABEL) ?: "")
        return START_NOT_STICKY
    }

    private fun show(label: String) {
        if (stopped) return
        ensureChannel()
        val notification = build(label)
        if (inForeground) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                @Suppress("DEPRECATION")
                startForeground(NOTIFICATION_ID, notification)
            }
            inForeground = true
            Log.i(TAG, "serviço de reprodução em primeiro plano: $label")
        } catch (error: Exception) {
            Log.w(TAG, "startForeground recusado: ${error.message}")
            stopSelf()
        }
    }

    private fun stopForegroundAndSelf() {
        stopped = true
        if (inForeground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            inForeground = false
            Log.i(TAG, "serviço de reprodução parado")
        }
        stopSelf()
    }

    override fun onDestroy() {
        if (running === this) running = null
        super.onDestroy()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.cadentia_playback_channel), NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        manager.createNotificationChannel(channel)
    }

    private fun build(label: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, PlaybackService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_playing)
            .setContentTitle(label.ifBlank { getString(R.string.cadentia_playback_channel) })
            .setContentText(getString(R.string.cadentia_playback_background))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setContentIntent(open)
            .addAction(0, getString(R.string.cadentia_playback_stop), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val TAG = "CadentiaAudio"
        const val CHANNEL_ID = "cadentia.reproducao"
        const val NOTIFICATION_ID = 4102
        const val ACTION_STOP_ALL = "com.levelhard.cadentia.playback.STOP_ALL"
        const val ACTION_HIDE = "com.levelhard.cadentia.playback.HIDE"
        private const val EXTRA_LABEL = "label"

        @Volatile
        private var running: PlaybackService? = null

        @Volatile
        private var stopRequested = false

        fun show(context: Context, label: String) {
            stopRequested = false
            running?.takeIf { !it.stopped }?.let {
                it.show(label)
                return
            }
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, PlaybackService::class.java).putExtra(EXTRA_LABEL, label),
                )
            } catch (error: Exception) {
                Log.w(TAG, "serviço de reprodução indisponível: ${error.message}")
            }
        }

        fun hide(context: Context) {
            val live = running
            if (live != null) {
                if (!live.stopped) live.stopForegroundAndSelf()
                return
            }
            stopRequested = true
            try {
                context.startService(Intent(context, PlaybackService::class.java).setAction(ACTION_HIDE))
            } catch (error: Exception) {
                Log.w(TAG, "parar o serviço de reprodução: ${error.message}")
            }
        }
    }
}
