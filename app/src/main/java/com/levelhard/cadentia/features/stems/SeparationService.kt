package com.levelhard.cadentia.features.stems

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
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.levelhard.cadentia.MainActivity
import com.levelhard.cadentia.R

/**
 * A separação que continua com o app em segundo plano, e que o sistema mostra
 * como notificação com progresso e um botão de cancelar — o papel do
 * `SeparationJob` (BGContinuedProcessingTask + Ilha Dinâmica) do iOS 1.16.
 *
 * O trabalho **não mora aqui**: ele roda na corrotina do [StemsModel], no
 * processo do app. O serviço existe por dois motivos, e os dois são do
 * Android: um serviço de primeiro plano é o que impede o sistema de matar o
 * processo no meio de uma leva de doze músicas quando a pessoa troca de app,
 * e a notificação dele é o único lugar onde a leva aparece com o app fechado.
 *
 * Tipo `mediaProcessing` (Android 15+; é literalmente "processar mídia", com
 * teto de seis horas por dia) e `dataSync` antes disso. A permissão de
 * notificação (Android 13+) é pedida INLINE pela tela ao começar a leva; sem
 * ela o serviço sobe igual — só não aparece — e a tela continua dizendo tudo.
 *
 * Cancelar na notificação chega em [onStartCommand] como [ACTION_CANCEL] e vai
 * para quem se registrou em [cancelListener]: o modelo, que mata a leva.
 */
class SeparationService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var inForeground = false
    private var stopped = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A leva acabou ANTES de o serviço existir (uma música que já estava
        // separada, ou o modelo ausente): o pedido de parar chegou com o
        // serviço ainda por criar. Nasce e morre, sem notificação.
        if (stopRequested) {
            stopRequested = false
            stopForegroundAndSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_CANCEL -> {
                Log.i(TAG, "leva cancelada pela pessoa na notificação")
                cancelListener?.invoke()
                stopForegroundAndSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                stopForegroundAndSelf()
                return START_NOT_STICKY
            }
        }
        show(
            title = intent?.getStringExtra(EXTRA_TITLE) ?: getString(R.string.cadentia_stems_job_title),
            subtitle = intent?.getStringExtra(EXTRA_SUBTITLE) ?: "",
            done = intent?.getIntExtra(EXTRA_DONE, 0) ?: 0,
            total = intent?.getIntExtra(EXTRA_TOTAL, 0) ?: 0,
        )
        return START_NOT_STICKY
    }

    /** Sobe para o primeiro plano na primeira vez; depois só atualiza a notificação. */
    private fun show(title: String, subtitle: String, done: Int, total: Int) {
        if (stopped) return
        ensureChannel()
        val notification = build(title, subtitle, done, total)
        if (inForeground) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
            return
        }
        // A chamada da PLATAFORMA, e não `ServiceCompat.startForeground`: o
        // compat (core 1.16) mascara o tipo com os tipos que conhecia até o
        // Android 14 — `mediaProcessing` (Android 15) vira "type none", e um
        // serviço sem tipo é proibido. Medido no emulador API 37: "Starting
        // FGS with type none ... has been prohibited".
        val type = when {
            Build.VERSION.SDK_INT >= 35 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, type)
            } else {
                @Suppress("DEPRECATION")
                startForeground(NOTIFICATION_ID, notification)
            }
            inForeground = true
            Log.i(TAG, "serviço de separação em primeiro plano (tipo $type)")
        } catch (error: Exception) {
            // Sem permissão de primeiro plano neste instante (o app está atrás
            // e o Android 12+ recusa): a leva segue no processo, só sem a
            // notificação. Falha alta no log — e o serviço PARA já: um
            // `startForegroundService` sem `startForeground` em cinco
            // segundos derruba o app inteiro.
            Log.w(TAG, "startForeground recusado: ${error.message}")
            stopSelf()
            return
        }
        // A CPU acordada enquanto separa. O serviço segura o processo; o
        // wake lock segura o processador — um telefone com a tela apagada
        // entra em doze e uma rede neural a meia velocidade é uma playlist
        // que nunca termina. Com teto, para um bug nunca virar bateria zerada.
        if (wakeLock == null) {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cadentia:separacao").also {
                it.acquire(WAKE_LOCK_LIMIT_MS)
            }
        }
    }

    private fun stopForegroundAndSelf() {
        stopped = true
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        if (inForeground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            inForeground = false
            Log.i(TAG, "serviço de separação parado")
        }
        stopSelf()
    }

    override fun onDestroy() {
        if (running === this) running = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.cadentia_stems_job_title), NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
            description = getString(R.string.cadentia_stems_leave_free)
        }
        manager.createNotificationChannel(channel)
    }

    private fun build(title: String, subtitle: String, done: Int, total: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                putExtra("qa-tab", "stems")
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancel = PendingIntent.getService(
            this, 1,
            Intent(this, SeparationService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_separating)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(maxOf(1, total), done.coerceIn(0, maxOf(1, total)), total <= 0)
            .setContentIntent(open)
            .addAction(0, getString(R.string.cadentia_stems_batch_cancel), cancel)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val TAG = "CadentiaStems"
        const val CHANNEL_ID = "cadentia.separacao"
        const val NOTIFICATION_ID = 4101
        const val ACTION_CANCEL = "com.levelhard.cadentia.stems.CANCEL"
        const val ACTION_STOP = "com.levelhard.cadentia.stems.STOP"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_SUBTITLE = "subtitle"
        private const val EXTRA_DONE = "done"
        private const val EXTRA_TOTAL = "total"

        /** Seis horas: o mesmo teto que o Android 15 dá ao `mediaProcessing`. */
        private const val WAKE_LOCK_LIMIT_MS = 6L * 60 * 60 * 1000

        /** Quem mata a leva quando a pessoa toca em cancelar na notificação. */
        @Volatile
        var cancelListener: (() -> Unit)? = null

        /**
         * A instância viva, quando há uma. Atualizar e parar falam com ela
         * direto: `startService` do fundo é recusado pelo Android 8+, e uma
         * leva que termina com o app atrás deixaria a notificação presa.
         */
        @Volatile
        private var running: SeparationService? = null

        /** `stop` chegou antes de o serviço nascer: ele para assim que nascer. */
        @Volatile
        private var stopRequested = false

        /** A notificação aparece? (Android 13+ pede permissão; antes é automático.) */
        fun canNotify(context: Context): Boolean =
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        /**
         * Sobe (ou atualiza) o serviço com o que a notificação mostra. Mil
         * passos por música no `total`, como no iOS, para a barra andar dentro
         * de cada uma em vez de pular de música em música.
         */
        fun update(context: Context, title: String, subtitle: String, done: Int, total: Int) {
            stopRequested = false
            running?.takeIf { !it.stopped }?.let {
                it.show(title, subtitle, done, total)
                return
            }
            val intent = Intent(context, SeparationService::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_SUBTITLE, subtitle)
                .putExtra(EXTRA_DONE, done)
                .putExtra(EXTRA_TOTAL, total)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (error: Exception) {
                Log.w(TAG, "serviço de separação indisponível: ${error.message}")
            }
        }

        fun stop(context: Context) {
            val live = running
            if (live != null) {
                if (!live.stopped) live.stopForegroundAndSelf()
                return
            }
            // O serviço ainda não nasceu (o `startForegroundService` está na
            // fila): ele morre ao nascer. O `startService` a mais é só para o
            // caso de a fila já ter sido consumida.
            stopRequested = true
            try {
                context.startService(Intent(context, SeparationService::class.java).setAction(ACTION_STOP))
            } catch (error: Exception) {
                Log.w(TAG, "parar o serviço: ${error.message}")
            }
        }
    }
}
