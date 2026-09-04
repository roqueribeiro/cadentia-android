package com.levelhard.cadentia.features.metronome

import com.levelhard.cadentia.audio.PlaybackSession
import com.levelhard.cadentia.audio.PolyphonicSampler
import com.levelhard.cadentia.kit.MetronomeClick
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Metrônomo sample-accurate sobre o sampler — port do
 * `MetronomeAudioEngine.swift`: o laço de lookahead (tick de 25 ms /
 * horizonte de 120 ms) coloca cada clique no relógio de frames do stream.
 * Polirritmia e trilha principal se sobrepõem livremente — num player único
 * elas ENFILEIRARIAM e derivariam (a lição da v0.2 do iOS).
 */
class MetronomeEngine(private val sampler: PolyphonicSampler) {
    private companion object {
        const val LOOKAHEAD_MS = 25L
        const val SCHEDULE_AHEAD_SECONDS = 0.12
    }

    private var schedulerJob: Job? = null
    private var nextBeatSeconds = 0.0
    private var beatIndex = 0
    private var polyNextBeatSeconds = 0.0

    // Ajustáveis ao vivo — lidos pelo laço a cada tick.
    var bpm = 120
    var subdivision = 1
    var beatsPerBar = 4
    var sound: MetronomeClick.Sound = MetronomeClick.Sound.Click
    var volume = 0.7

    /**
     * Razão "N:D" → segunda trilha de cliques a bpm × N/D com o som "oposto"
     * (paridade com o web: click ↔ woodblock).
     */
    var polyrhythm: Pair<Int, Int>? = null
        set(value) {
            field = value
            if (isRunning) polyNextBeatSeconds = sampler.nowSeconds() + 0.06
        }

    /** Callback do tempo forte visual, disparado no instante audível. */
    var onBeat: ((Int) -> Unit)? = null

    var isRunning = false
        private set

    /** O que a notificação de reprodução mostra; a tela define com a string traduzida. */
    var sessionLabel: String = "Metronome"

    /**
     * A sessão de áudio parou ou retomou o metrônomo por fora (ligação, outro
     * app de música, "Parar" na notificação): a tela acompanha por aqui.
     */
    var onSessionChange: ((running: Boolean) -> Unit)? = null
    private var lease: PlaybackSession.Lease? = null
    private var lastScope: CoroutineScope? = null

    fun start(scope: CoroutineScope): Boolean {
        if (isRunning) return true
        if (!sampler.startIfNeeded()) return false
        beatIndex = 0
        nextBeatSeconds = sampler.nowSeconds() + 0.06
        polyNextBeatSeconds = nextBeatSeconds
        isRunning = true
        lastScope = scope
        lease = PlaybackSession.begin(
            sessionLabel,
            onInterrupt = {
                stop()
                onSessionChange?.invoke(false)
            },
            onResume = {
                // Ligação acabou: volta a bater (o metrônomo é o caso em que
                // parar e não voltar mais atrapalha).
                if (lastScope?.let { start(it) } == true) onSessionChange?.invoke(true)
            },
        )
        schedulerJob = scope.launch {
            while (isActive && isRunning) {
                scheduleAhead(this)
                delay(LOOKAHEAD_MS)
            }
        }
        return true
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        schedulerJob?.cancel()
        schedulerJob = null
        sampler.stop()
        lease?.let { PlaybackSession.end(it) }
        lease = null
    }

    /** Três bipes acentuados a cada 0,25 s (alarme do practice timer). */
    fun playAlert() {
        if (!sampler.startIfNeeded()) return
        val rate = sampler.sampleRate
        val now = sampler.nowSeconds() + 0.05
        repeat(3) { i ->
            sampler.schedule(key = "alert/beep", atSeconds = now + i * 0.25) {
                PolyphonicSampler.interleave(
                    MetronomeClick.render(
                        MetronomeClick.Sound.Beep, accent = true, volume = 0.7, sampleRate = rate,
                    ),
                )
            }
        }
    }

    // MARK: agendamento

    private fun scheduleAhead(scope: CoroutineScope) {
        val now = sampler.nowSeconds()
        // Rebase depois de um stall (interrupção/segundo plano): sem isto,
        // cada batida perdida explode de uma vez.
        if (nextBeatSeconds < now) nextBeatSeconds = now + 0.06
        val horizon = now + SCHEDULE_AHEAD_SECONDS

        while (nextBeatSeconds < horizon) {
            scheduleBeat(scope, at = nextBeatSeconds, index = beatIndex)
            val secondsPerBeat = 60.0 / maxOf(bpm, 1) / maxOf(subdivision, 1)
            nextBeatSeconds += secondsPerBeat
            beatIndex += 1
        }

        polyrhythm?.let { (num, den) ->
            if (polyNextBeatSeconds < now) polyNextBeatSeconds = now + 0.06
            val polyBpm = bpm.toDouble() * num / maxOf(den, 1)
            val polySound = if (sound == MetronomeClick.Sound.Click) {
                MetronomeClick.Sound.Woodblock
            } else {
                MetronomeClick.Sound.Click
            }
            val rate = sampler.sampleRate
            val gain = volume * 0.55
            while (polyNextBeatSeconds < horizon) {
                sampler.schedule(
                    key = "poly/${polySound.id}/%.2f".format(gain),
                    atSeconds = polyNextBeatSeconds,
                ) {
                    PolyphonicSampler.interleave(
                        MetronomeClick.render(polySound, accent = false, volume = gain, sampleRate = rate),
                    )
                }
                polyNextBeatSeconds += 60.0 / maxOf(polyBpm, 1.0)
            }
        }
    }

    private fun scheduleBeat(scope: CoroutineScope, at: Double, index: Int) {
        // A mesma aritmética de batida do onTick do web: acento na batida 1
        // do compasso, micro-batidas de subdivisão a 40% do ganho.
        val sub = maxOf(subdivision, 1)
        val beatInBar = index % (maxOf(beatsPerBar, 1) * sub)
        val macroBeat = beatInBar / sub
        val isMicro = beatInBar % sub != 0
        val isAccent = macroBeat == 0 && !isMicro

        val sound = sound
        val gain = if (isMicro) volume * 0.4 else volume
        val rate = sampler.sampleRate
        sampler.schedule(
            key = "click/${sound.id}/$isAccent/%.2f".format(gain),
            atSeconds = at,
        ) {
            PolyphonicSampler.interleave(
                MetronomeClick.render(sound, accent = isAccent, volume = gain, sampleRate = rate),
            )
        }

        if (!isMicro) {
            // O contador visual dispara quando o clique fica audível.
            scope.launch {
                delay((maxOf(0.0, at - sampler.nowSeconds()) * 1000).toLong())
                if (isRunning) onBeat?.invoke(macroBeat)
            }
        }
    }
}
