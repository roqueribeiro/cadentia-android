package com.levelhard.cadentia.features.studio

import com.levelhard.cadentia.audio.PolyphonicSampler
import com.levelhard.cadentia.kit.AppSettings
import com.levelhard.cadentia.kit.ToneSynth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Gerador de tom contínuo com binaural e reverb/delay de bus — port do
 * `ToneGeneratorEngine.swift` sobre o nosso stream Oboe.
 *
 * O iOS renderiza num AVAudioSourceNode; aqui o tom contínuo nasce de
 * CHUNKS curtos emendados no relógio de frames do sampler, sem tocar no
 * C++. A emenda não estala porque a FASE atravessa os chunks (cada chunk
 * continua exatamente de onde o anterior parou, nos dois canais) e o
 * agendamento acumula em FRAMES inteiros (nada de truncamento criando gap
 * de um frame). Knob mudou: os chunks futuros são calados com um fade
 * curto e o gerador reagenda do agora, com a fase preservada — resposta em
 * ~80 ms, o glide por amostra do iOS trocado por um corte suave.
 */
class ToneGeneratorEngine {
    private companion object {
        const val CHUNK_SECONDS = 0.15
        const val TICK_MS = 50L
        const val HORIZON_SECONDS = 0.45
        /** O mesmo alisamento de 5 ms por amostra do iOS, dentro do chunk. */
        const val VOLUME_SMOOTHING = 0.005f
    }

    private val sampler = PolyphonicSampler()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()

    // Snapshot dos knobs (a thread do agendador lê sob lock).
    private var frequency = 440.0
    private var wave = ToneSynth.Waveform.Sine
    private var volume = 0.3f
    private var binauralOffset = 0.0

    // Estado do oscilador: fase por canal e volume alisado atravessam chunks.
    private var phaseL = 0.0
    private var phaseR = 0.0
    private var smoothedVolume = 0f

    private var job: Job? = null
    private var chunkSerial = 0L
    @Volatile private var rescheduleWanted = false

    var isPlaying = false
        private set

    fun start(settings: AppSettings.Studio): Boolean {
        apply(settings)
        if (isPlaying) return true
        if (!sampler.startIfNeeded()) return false

        smoothedVolume = 0f
        isPlaying = true
        job = scope.launch {
            var nextFrame = ((sampler.nowSeconds() + 0.06) * sampler.sampleRate).toLong()
            while (isPlaying) {
                if (rescheduleWanted) {
                    rescheduleWanted = false
                    // Cala os chunks já agendados e recomeça do agora; a fase
                    // continua, então não há salto de forma de onda.
                    sampler.dampAll(0.03f)
                    nextFrame = ((sampler.nowSeconds() + 0.08) * sampler.sampleRate).toLong()
                }
                val rate = sampler.sampleRate
                val horizonFrame = ((sampler.nowSeconds() + HORIZON_SECONDS) * rate).toLong()
                while (nextFrame < horizonFrame) {
                    val frames = (CHUNK_SECONDS * rate).toInt()
                    val pcm = renderChunk(frames, rate)
                    val serial = chunkSerial++
                    sampler.schedule(key = "tone/$serial", atSeconds = nextFrame / rate) { pcm }
                    nextFrame += frames
                }
                delay(TICK_MS)
            }
        }
        return true
    }

    fun stop() {
        if (!isPlaying) return
        isPlaying = false
        job?.cancel()
        job = null
        sampler.dampAll(0.05f)
    }

    fun shutdown() {
        stop()
        scope.cancel()
        sampler.stop()
    }

    /** Aplica todos os knobs ao vivo (seguro chamar tocando). */
    fun apply(settings: AppSettings.Studio) {
        synchronized(lock) {
            frequency = settings.hz
            wave = ToneSynth.Waveform.from(settings.wave) ?: ToneSynth.Waveform.Sine
            volume = settings.volume.toFloat()
            binauralOffset = if (settings.binauralEnabled) settings.binauralOffset else 0.0
        }
        sampler.setReverb(enabled = settings.reverbEnabled, mix = settings.reverbMix.toFloat())
        sampler.setDelay(
            enabled = settings.delayEnabled,
            timeMs = settings.delayTimeMs.toFloat(),
            feedback = settings.delayFeedback.toFloat(),
            mix = settings.delayMix.toFloat(),
        )
        if (isPlaying) rescheduleWanted = true
    }

    private fun renderChunk(frames: Int, rate: Double): FloatArray {
        val freq: Double
        val waveform: ToneSynth.Waveform
        val target: Float
        val offset: Double
        synchronized(lock) {
            freq = frequency
            waveform = wave
            target = volume
            offset = binauralOffset
        }
        val freqR = if (offset > 0) freq + offset else freq

        val out = FloatArray(frames * 2)
        var pl = phaseL
        var pr = phaseR
        var sv = smoothedVolume
        for (i in 0 until frames) {
            // O alisamento de 5 ms mata os cliques de partida/parada/slider.
            sv += (target - sv) * VOLUME_SMOOTHING
            pl += freq / rate
            pr += freqR / rate
            out[2 * i] = (waveform.sample(pl)).toFloat() * sv
            out[2 * i + 1] = (waveform.sample(pr)).toFloat() * sv
        }
        if (pl > 1_000_000) pl -= 1_000_000
        if (pr > 1_000_000) pr -= 1_000_000
        phaseL = pl
        phaseR = pr
        smoothedVolume = sv
        return out
    }
}
