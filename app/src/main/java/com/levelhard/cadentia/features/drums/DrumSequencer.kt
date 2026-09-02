package com.levelhard.cadentia.features.drums

import com.levelhard.cadentia.audio.PolyphonicSampler
import com.levelhard.cadentia.kit.DrumSynth
import kotlinx.coroutines.CoroutineScope
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
        val volume = volume
        val rate = sampler.sampleRate
        val variation = roundRobin
        roundRobin = (roundRobin + 1) % DrumSynth.roundRobinCount
        sampler.play(cacheKey(kit, pad, 1f, variation, volume)) {
            DrumSynth.renderStereo(kit, pad, 1f, variation, rate, volume).interleaved()
        }
    }

    /** Renderiza todos os pads do kit atual no cache: primeiro hit sem soluço. */
    fun prewarm() {
        if (!sampler.startIfNeeded()) return
        val kit = kit
        val volume = volume
        val rate = sampler.sampleRate
        for (pad in DrumSynth.padIDs) {
            for (variation in 0 until DrumSynth.roundRobinCount) {
                sampler.prewarm(cacheKey(kit, pad, 1f, variation, volume)) {
                    DrumSynth.renderStereo(kit, pad, 1f, variation, rate, volume).interleaved()
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

    private fun cacheKey(kit: String, pad: String, velocity: Float, variation: Int, volume: Float): String =
        "$kit/$pad/%.2f/%d/%.2f".format(velocity, variation, volume)

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
                val volume = volume
                // Caminha o round robin por compasso: linha de semicolcheias
                // repetida circula os quatro renders em vez de repetir um.
                val variation = (stepIndex / 16 + step) % DrumSynth.roundRobinCount
                sampler.schedule(
                    key = cacheKey(kit, pad, velocity, variation, volume),
                    atSeconds = at,
                ) {
                    DrumSynth.renderStereo(kit, pad, velocity, variation, rate, volume).interleaved()
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
