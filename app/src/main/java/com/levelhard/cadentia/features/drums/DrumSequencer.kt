package com.levelhard.cadentia.features.drums

import com.levelhard.cadentia.audio.PolyphonicSampler
import com.levelhard.cadentia.kit.DrumSynth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    /** Pad ao vivo (também com o sequencer rodando): velocity cheia, round robin anda. */
    fun hitPad(pad: String) {
        if (!sampler.startIfNeeded()) return
        val kit = kit
        val rate = sampler.sampleRate
        val variation = roundRobin
        roundRobin = (roundRobin + 1) % DrumSynth.roundRobinCount
        val voicing = DrumVoicing.of(kit, pad, 1f, variation, volume)
        sampler.play(voicing.key, gain = voicing.gain) {
            DrumSynth.renderStereo(
                kit, pad, 1f, variation, rate, gain = 1f, velocityGainApplied = voicing.sampled,
            ).interleaved()
        }
    }

    /**
     * Renderiza todos os pads do kit atual no cache: primeiro hit sem soluço.
     * Síncrono — quem chama da tela usa `prewarmInBackground`, que faz o
     * mesmo trabalho fora da thread principal; `start()` chama direto porque
     * o primeiro passo precisa dos pads prontos antes de agendar.
     */
    fun prewarm() {
        if (!sampler.startIfNeeded()) return
        renderAllPads()
    }

    /** O aquecimento da entrada da tela e da troca de kit, sem congelar a UI. */
    fun prewarmInBackground(scope: CoroutineScope): Job? {
        if (!sampler.startIfNeeded()) return null
        return scope.launch(Dispatchers.Default) { renderAllPads() }
    }

    /**
     * Todas as dinâmicas que o sequenciador e o pad produzem, em toda variação.
     * Com sample ligado a chave é por arquivo, então as 16 combinações de um
     * pad convergem para os poucos arquivos que o pack tem de verdade.
     */
    private fun renderAllPads() {
        val kit = kit
        val rate = sampler.sampleRate
        for (pad in DrumSynth.padIDs) {
            for (velocity in ACCENTS) {
                for (variation in 0 until DrumSynth.roundRobinCount) {
                    val voicing = DrumVoicing.of(kit, pad, velocity, variation, volume)
                    sampler.prewarm(voicing.key) {
                        DrumSynth.renderStereo(
                            kit, pad, velocity, variation, rate, gain = 1f,
                            velocityGainApplied = voicing.sampled,
                        ).interleaved()
                    }
                }
            }
        }
    }

    fun start(scope: CoroutineScope): Boolean {
        if (isRunning) return true
        if (!sampler.startIfNeeded()) return false
        prewarm()
        stepIndex = 0
        nextStepSeconds = sampler.nowSeconds() + 0.06
        isRunning = true
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
    }

    fun shutdown() {
        stop()
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
                sampler.schedule(key = voicing.key, atSeconds = at, gain = voicing.gain) {
                    DrumSynth.renderStereo(
                        kit, pad, velocity, variation, rate, gain = 1f,
                        velocityGainApplied = voicing.sampled,
                    ).interleaved()
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
