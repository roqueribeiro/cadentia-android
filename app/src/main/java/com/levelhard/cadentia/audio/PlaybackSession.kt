package com.levelhard.cadentia.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * O que o `AVAudioSession` de categoria `.playback` + `UIBackgroundModes:
 * audio` dão ao iOS de graça, feito à mão: enquanto alguma coisa toca de
 * forma contínua (metrônomo, tablatura, bateria, Frequência, gravador, stems),
 * o app segura o **foco de áudio** e um **serviço de primeiro plano** de
 * reprodução ([PlaybackService]).
 *
 * Sem o serviço, o Android congela o processo ~10 s depois de o app ir para
 * trás (visto no emulador: `ActivityManager: freezing <pid>`) e o metrônomo
 * para com a tela bloqueada. Sem o foco, uma ligação ou outro app de música
 * toca por cima, e o Cadentia não sabe que deveria calar.
 *
 * Cada motor pede uma [Lease] ao começar e devolve ao parar. A primeira
 * abre foco + serviço; a última fecha. Perda de foco chama `onInterrupt` de
 * cada arrendatário (o motor para do jeito dele); perda TRANSITÓRIA
 * (ligação) chama `onResume` quando o foco volta, para quem o declarou —
 * o metrônomo volta a bater, o player de stems volta a tocar. Perda
 * definitiva não volta: outro app de música assumiu.
 *
 * Toques avulsos (uma nota do piano, um pad) NÃO passam por aqui: uma
 * notificação de "tocando" aparecendo a cada tecla seria absurdo, e o iOS
 * também mistura o Cordas com o que estiver tocando (`mixWithOthers`).
 */
object PlaybackSession {
    private const val TAG = "CadentiaAudio"

    class Lease internal constructor(
        internal var label: String,
        internal val onInterrupt: () -> Unit,
        internal val onResume: (() -> Unit)?,
    )

    private var appContext: Context? = null
    private val leases = LinkedHashSet<Lease>()
    private val interrupted = LinkedHashSet<Lease>()
    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus = false
    /** Enquanto os `onInterrupt` rodam, o `end` que eles provocam NÃO apaga quem deve voltar. */
    private var interrupting = false
    private val main = Handler(Looper.getMainLooper())

    /** Chamado uma vez no `Application.onCreate`. */
    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    /** Uma reprodução contínua começou. `label` é o que a notificação mostra. */
    fun begin(label: String, onInterrupt: () -> Unit, onResume: (() -> Unit)? = null): Lease {
        val lease = Lease(label, onInterrupt, onResume)
        synchronized(this) {
            leases += lease
            interrupted -= lease
        }
        main.post { sync() }
        return lease
    }

    /** O motor parou (por vontade própria ou por [Lease.onInterrupt]). */
    fun end(lease: Lease) {
        synchronized(this) {
            leases -= lease
            // Parou sozinho (a pessoa, não a interrupção): não volta quando o foco voltar.
            if (!interrupting) interrupted -= lease
        }
        main.post { sync() }
    }

    fun update(lease: Lease, label: String) {
        synchronized(this) { lease.label = label }
        main.post { sync() }
    }

    /** O botão "Parar" da notificação: tudo para. */
    fun stopAll() {
        val current = synchronized(this) { leases.toList() }
        current.forEach { runCatching { it.onInterrupt() } }
    }

    val isActive: Boolean get() = synchronized(this) { leases.isNotEmpty() }

    private fun sync() {
        val context = appContext ?: return
        // Durante uma interrupção transitória ninguém está tocando, mas o foco
        // e o serviço continuam de pé: soltar o foco é nunca receber o GAIN de
        // volta, e um serviço de primeiro plano não nasce com o app atrás.
        val (active, label) = synchronized(this) {
            (leases.isNotEmpty() || interrupted.isNotEmpty()) to (leases.lastOrNull() ?: interrupted.lastOrNull())?.label
        }
        if (active) {
            if (!hasFocus) requestFocus(context)
            PlaybackService.show(context, label ?: "")
        } else {
            if (hasFocus) abandonFocus(context)
            PlaybackService.hide(context)
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Outro app de música assumiu: para e não volta (o iOS faz o mesmo
                // com a interrupção "ended" sem `shouldResume`).
                hasFocus = false
                val current = synchronized(this) { leases.toList().also { interrupted.clear() } }
                Log.i(TAG, "foco de áudio perdido: ${current.size} motores param")
                current.forEach { runCatching { it.onInterrupt() } }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Ligação, alarme: para agora e volta quando o foco voltar.
                val current = synchronized(this) {
                    leases.toList().also { list ->
                        interrupted.clear()
                        interrupted += list.filter { it.onResume != null }
                    }
                }
                Log.i(TAG, "foco de áudio interrompido: ${current.size} motores param, ${interrupted.size} voltam depois")
                interrupting = true
                try {
                    current.forEach { runCatching { it.onInterrupt() } }
                } finally {
                    interrupting = false
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Uma notificação de outro app: música de estudo não abaixa (o
                // metrônomo abaixado é um metrônomo que a pessoa perde).
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasFocus = true
                val back = synchronized(this) { interrupted.toList().also { interrupted.clear() } }
                if (back.isNotEmpty()) Log.i(TAG, "foco de áudio de volta: ${back.size} motores retomam")
                back.forEach { runCatching { it.onResume?.invoke() } }
            }
        }
    }

    private fun requestFocus(context: Context) {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener(focusListener, main)
            .build()
        val result = manager.requestAudioFocus(request)
        focusRequest = request
        hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!hasFocus) Log.w(TAG, "foco de áudio recusado ($result); tocando mesmo assim")
    }

    private fun abandonFocus(context: Context) {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        focusRequest?.let { manager.abandonAudioFocusRequest(it) }
        focusRequest = null
        hasFocus = false
        synchronized(this) { interrupted.clear() }
    }
}
