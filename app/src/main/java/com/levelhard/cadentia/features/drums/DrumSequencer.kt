package com.levelhard.cadentia.features.drums

import com.levelhard.cadentia.audio.PlaybackSession
import com.levelhard.cadentia.audio.PolyphonicSampler
import com.levelhard.cadentia.kit.DrumSynth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Sequencer de bateria de 16 passos sobre o sampler — port do
 * `DrumSequencer` do iOS: relógio de lookahead (25 ms / 120 ms, rebase
 * pós-stall) no relógio de frames do stream.
 *
 * Os passos carregam velocity para o groove respirar (tempo forte bate mais
 * que as semicolcheias), e cada hit caminha pelo round robin do kit para
 * nota repetida não ser byte a byte idêntica.
 */
class DrumSequencer {
    private companion object {
        const val LOOKAHEAD_MS = 25L
        const val SCHEDULE_AHEAD_SECONDS = 0.12

        /** Pad ao vivo e os três acentos de `accent(step)`. */
        val ACCENTS = floatArrayOf(1.0f, 0.92f, 0.76f, 0.62f)

        /** O que toda levada usa, esteja ou não na grade no momento. */
        val CORE_PADS = setOf("kick", "snare", "hihat-c", "hihat-o")
    }

    val sampler = PolyphonicSampler()

    var kit = "acoustic"
    var bpm = 100
    var volume = 0.8f
    var pattern: Map<String, List<Boolean>> = emptyMap()
    var onStep: ((Int) -> Unit)? = null

    var isRunning = false
        private set
    private var job: Job? = null
    private var nextStepSeconds = 0.0
    private var stepIndex = 0
    private var roundRobin = 0

    /**
     * Mapa de acento de um compasso de 16 passos: o tempo, as colcheias, e
     * as semicolcheias no meio. É o que separa um groove de um metrônomo
     * com passos a mais.
     */
    private fun accent(step: Int): Float = when {
        step % 4 == 0 -> if (step == 0) 1.0f else 0.92f
        step % 2 == 0 -> 0.76f
        else -> 0.62f
    }

    /**
     * Quem cala quem — port 1:1 do `SamplerEngine.swift`.
     *
     * Um chimbal só existe num estado por vez: fechar o pedal abafa o que
     * estava aberto. Com síntese isso quase passava, porque o aberto morria
     * rápido; com sample ele soa por até 3,5 s e fica por cima da levada
     * inteira. O SFZ do Virtuosity declara isso com `group`/`off_by`, mas esse
     * par é o único entre os dezesseis pads: um grafo de choke inteiro para
     * uma aresta seria arquitetura por esporte.
     */
    private val chokes = mapOf("hihat-c" to "hihat-o")
    private val sounding = HashMap<String, Long>()

    private fun remember(pad: String, voiceTag: Long) {
        if (voiceTag != 0L) synchronized(sounding) { sounding[pad] = voiceTag }
    }

    /** Abafa quem este pad cala. `damp` é um fade, não um corte seco: cortar um prato no meio do ciclo é um clique. */
    private fun choke(pad: String) {
        val victim = chokes[pad] ?: return
        val tag = synchronized(sounding) { sounding.remove(victim) } ?: return
        sampler.damp(tag, 0.06f)
    }

    /** Pad ao vivo (também com o sequencer rodando): velocity cheia, round robin anda. */
    fun hitPad(pad: String) {
        if (!sampler.startIfNeeded()) return
        val kit = kit
        val rate = sampler.sampleRate
        val variation = roundRobin
        roundRobin = (roundRobin + 1) % DrumSynth.roundRobinCount
        val voicing = DrumVoicing.of(kit, pad, 1f, variation, volume)
        choke(pad)
        val tag = sampler.play(voicing.key, gain = voicing.gain) {
            DrumSynth.renderStereo(
                kit, pad, 1f, variation, rate, gain = 1f, velocityGainApplied = voicing.sampled,
            ).interleaved()
        }
        remember(pad, tag)
    }

    private var prewarmJob: Job? = null

    /**
     * Aquece o cache fora da thread principal — port do `prewarm()` da 1.16.
     *
     * São 16 pads × 4 variações × 4 dinâmicas. Com síntese eram milissegundos;
     * com sample cada um abre e decodifica um FLAC, e fazer isso na entrada da
     * tela (ou no Start, como esta versão fazia) travava a tela: medido no
     * emulador, 84 frames pulados ao apertar Start com o cache pela metade.
     *
     * Só o que a levada usa, e o núcleo do kit por cima: aquecer os 16 pads
     * despejava 179 MB num cache de 44 MB no iOS, expulsando bumbo, caixa e
     * chimbal para dar lugar a cowbell e conga. As dinâmicas aquecidas são as
     * mesmas que `accent()` produz: um cache que só conhece a pancada cheia
     * erra em toda semicolcheia fraca. Com a chave por arquivo, pedidos
     * diferentes caem na mesma entrada, e a checagem de cache vem ANTES do
     * render — renderizar 256 buffers para descobrir que já estavam lá é o
     * custo inteiro pago de novo a cada Play.
     */
    fun prewarm(scope: CoroutineScope) {
        if (!sampler.startIfNeeded()) return
        prewarmJob?.cancel()
        val kit = kit
        val rate = sampler.sampleRate
        val used = pattern.filter { it.value.contains(true) }.keys
        val pads = DrumSynth.padIDs.filter { it in used || it in CORE_PADS }
        data class Warm(val key: String, val pad: String, val velocity: Float, val variation: Int, val sampled: Boolean)
        val planned = HashSet<String>()
        val jobs = ArrayList<Warm>()
        for (pad in pads) {
            for (velocity in ACCENTS) {
                for (variation in 0 until DrumSynth.roundRobinCount) {
                    val voicing = DrumVoicing.of(kit, pad, velocity, variation, 1f)
                    if (!planned.add(voicing.key) || sampler.hasCached(voicing.key)) continue
                    jobs.add(Warm(voicing.key, pad, velocity, variation, voicing.sampled))
                }
            }
        }
        if (jobs.isEmpty()) return
        prewarmJob = scope.launch(Dispatchers.Default) {
            for (job in jobs) {
                ensureActive()
                sampler.prewarm(job.key) {
                    DrumSynth.renderStereo(
                        kit, job.pad, job.velocity, job.variation, rate, gain = 1f,
                        velocityGainApplied = job.sampled,
                    ).interleaved()
                }
            }
        }
    }

    /** O que a notificação de reprodução mostra; a tela define com a string traduzida. */
    var sessionLabel: String = "Drums"

    /** A sessão de áudio parou isto por fora (ligação, outro app, "Parar" na notificação). */
    var onSessionStopped: (() -> Unit)? = null
    private var lease: PlaybackSession.Lease? = null

    fun start(scope: CoroutineScope): Boolean {
        if (isRunning) return true
        if (!sampler.startIfNeeded()) return false
        prewarm(scope)
        stepIndex = 0
        nextStepSeconds = sampler.nowSeconds() + 0.06
        isRunning = true
        lease = PlaybackSession.begin(sessionLabel, onInterrupt = {
            stop()
            onSessionStopped?.invoke()
        })
        job = scope.launch {
            while (isActive && isRunning) {
                scheduleAhead(this)
                delay(LOOKAHEAD_MS)
            }
        }
        return true
    }

    fun stop() {
        isRunning = false
        job?.cancel()
        job = null
        synchronized(sounding) { sounding.clear() }
        lease?.let { PlaybackSession.end(it) }
        lease = null
    }

    fun shutdown() {
        stop()
        prewarmJob?.cancel()
        prewarmJob = null
        sampler.stop()
    }

    private fun scheduleAhead(scope: CoroutineScope) {
        val now = sampler.nowSeconds()
        if (nextStepSeconds < now) {
            nextStepSeconds = now + 0.06
        }
        val rate = sampler.sampleRate
        // 16 passos = semicolcheias: passo = tempo / 4.
        val stepSeconds = 60.0 / maxOf(bpm, 1) / 4
        val horizon = now + SCHEDULE_AHEAD_SECONDS
        while (nextStepSeconds < horizon) {
            val step = stepIndex % 16
            val at = nextStepSeconds
            val velocity = accent(step)
            for ((pad, steps) in pattern) {
                if (step >= steps.size || !steps[step]) continue
                val kit = kit
                // Caminha o round robin por compasso: linha de semicolcheias
                // repetida circula os quatro renders em vez de repetir um.
                val variation = (stepIndex / 16 + step) % DrumSynth.roundRobinCount
                val voicing = DrumVoicing.of(kit, pad, velocity, variation, volume)
                val tag = sampler.schedule(key = voicing.key, atSeconds = at, gain = voicing.gain) {
                    DrumSynth.renderStereo(
                        kit, pad, velocity, variation, rate, gain = 1f,
                        velocityGainApplied = voicing.sampled,
                    ).interleaved()
                }
                // O registro é IMEDIATO e o abafamento é que espera (lição do
                // iOS: com aberto e fechado no mesmo passo, registrar depois
                // da espera fazia o choke às vezes rodar antes de o aberto
                // existir, e ele tocava os 3,5 s inteiros por cima da levada).
                remember(pad, tag)
                if (chokes[pad] != null) {
                    scope.launch {
                        delay((maxOf(0.0, at - sampler.nowSeconds()) * 1000).toLong())
                        if (isRunning) choke(pad)
                    }
                }
            }
            scope.launch {
                delay((maxOf(0.0, at - sampler.nowSeconds()) * 1000).toLong())
                if (isRunning) onStep?.invoke(step)
            }
            nextStepSeconds += stepSeconds
            stepIndex += 1
        }
    }
}
