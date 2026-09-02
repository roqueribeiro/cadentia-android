package com.levelhard.cadentia.features.tab

import com.levelhard.cadentia.audio.PolyphonicSampler
import com.levelhard.cadentia.features.drums.DrumVoicing
import com.levelhard.cadentia.kit.DrumSynth
import com.levelhard.cadentia.kit.SampleBank
import com.levelhard.cadentia.kit.InstrumentSynth
import com.levelhard.cadentia.kit.InstrumentVoice
import com.levelhard.cadentia.kit.MetronomeClick
import com.levelhard.cadentia.kit.MusicNotes
import com.levelhard.cadentia.kit.RostabParser
import com.levelhard.cadentia.kit.Tablature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Player multitrack de tablatura sobre o sampler polifônico — port do
 * `TabPlayerEngine.swift`. Cada trilha anda o próprio plano de reprodução
 * (repetições e blocos expandidos, semântica do web) num relógio comum de
 * semicolcheias; a nota renderiza pela voz que a trilha declara: cordas
 * Karplus-Strong, teclas do ToneSynth, kits do DrumSynth.
 *
 * O engine guarda a PRÓPRIA cópia da tablatura (`load` faz round-trip pela
 * serialização): a tela edita o objeto dela à vontade e o som só muda
 * quando ela recarrega — a semântica de struct que o Swift dava de graça.
 */
class TabPlayerEngine {
    private companion object {
        const val LOOKAHEAD_MS = 25L
        const val SCHEDULE_AHEAD_SECONDS = 0.12
    }

    private val sampler = PolyphonicSampler()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()

    private var tab: Tablature? = null
    private var plans: List<Tablature.PlaybackPlan> = emptyList()
    private var task: Job? = null
    private var nextStepSeconds = 0.0
    private var beatIndex = 0

    @Volatile var isPlaying = false
        private set
    @Volatile var loopEnabled = true
    /** Quatro cliques de semínima antes da música começar (apoio de estudo). */
    @Volatile var countInEnabled = false
    /** null = seguir o transport.bpm do arquivo. */
    @Volatile var bpmOverride: Int? = null

    /** Dispara no instante audível de cada batida global (para o cursor). */
    @Volatile var onBeat: ((Int) -> Unit)? = null
    @Volatile var onFinished: (() -> Unit)? = null

    val bpm: Int get() = bpmOverride ?: (synchronized(lock) { tab?.transport?.bpm } ?: Tablature.DEFAULT_BPM)

    fun load(newTab: Tablature) {
        stop()
        // Cópia própria: round-trip já testado como estável no :kit.
        val own = RostabParser.parse(newTab.serialize())
        synchronized(lock) {
            tab = own
            plans = own.tracks.map { own.playbackPlan(it) }
        }
        sampler.setReverb(enabled = own.masterFx.reverbMix > 0, mix = own.masterFx.reverbMix.toFloat())
        sampler.setDelay(
            enabled = own.masterFx.delayMix > 0,
            timeMs = own.masterFx.delayTime.toFloat(),
            feedback = own.masterFx.delayFeedback.toFloat(),
            mix = own.masterFx.delayMix.toFloat(),
        )
    }

    /** Maior plano (a reprodução acaba quando TODA trilha acabou). */
    val totalBeats: Int get() = synchronized(lock) { plans.maxOfOrNull { it.entries.size } ?: 0 }

    fun planForTrack(index: Int): Tablature.PlaybackPlan? =
        synchronized(lock) { plans.getOrNull(index) }

    /**
     * Mixer ao vivo, sem recarregar (lido na hora de agendar). Voz e kit
     * também entram aqui: trocar o instrumento muda o som das notas, não as
     * notas — a música não tem por que parar enquanto você prova timbres.
     */
    fun updateTrack(
        index: Int,
        volume: Double? = null,
        muted: Boolean? = null,
        soloed: Boolean? = null,
        voiceId: String? = null,
        kitId: String? = null,
    ) {
        synchronized(lock) {
            val track = tab?.tracks?.getOrNull(index) ?: return
            volume?.let { track.volume = it }
            muted?.let { track.muted = it }
            soloed?.let { track.soloed = it }
            voiceId?.let { track.voiceId = it }
            kitId?.let { track.kitId = it }
        }
    }

    /** true = tocando; false = o stream de áudio não abriu. */
    fun play(fromBeat: Int = 0): Boolean {
        synchronized(lock) { if (tab == null) return false }
        stop()
        if (!sampler.startIfNeeded()) return false
        beatIndex = fromBeat
        var startAt = sampler.nowSeconds() + 0.08
        if (countInEnabled) {
            // Contagem: 4 cliques de semínima (acento no 1) antes da batida 0.
            val beatSeconds = 60.0 / max(bpm, 1)
            val rate = sampler.sampleRate
            for (i in 0 until 4) {
                val accent = i == 0
                sampler.schedule(key = "countin/$accent", atSeconds = startAt + i * beatSeconds) {
                    PolyphonicSampler.interleave(
                        MetronomeClick.render(
                            sound = MetronomeClick.Sound.Click, accent = accent,
                            volume = 0.6, sampleRate = rate,
                        ),
                    )
                }
            }
            startAt += 4 * beatSeconds
        }
        nextStepSeconds = startAt
        isPlaying = true
        task = scope.launch {
            while (isPlaying) {
                scheduleAhead()
                delay(LOOKAHEAD_MS)
            }
        }
        return true
    }

    fun stop() {
        isPlaying = false
        task?.cancel()
        task = null
    }

    fun shutdown() {
        stop()
        scope.cancel()
        sampler.stop()
    }

    // ---- agendamento ----

    private fun scheduleAhead() {
        val now = sampler.nowSeconds()
        if (nextStepSeconds < now) nextStepSeconds = now + 0.06
        val stepSeconds = 60.0 / max(bpm, 1) / 4
        val horizon = now + SCHEDULE_AHEAD_SECONDS

        synchronized(lock) {
            val tab = tab ?: return
            val anySolo = tab.tracks.any { it.soloed }
            val hasInfinity = plans.any { it.infiniteFrom != null }
            val total = plans.maxOfOrNull { it.entries.size } ?: 0

            while (nextStepSeconds < horizon) {
                // Fim da música: todo plano finito esgotado (infinito nunca acaba).
                if (!hasInfinity && beatIndex >= total) {
                    if (loopEnabled && total > 0) {
                        beatIndex = 0
                    } else {
                        isPlaying = false
                        scope.launch(Dispatchers.Main) { onFinished?.invoke() }
                        return
                    }
                }

                val whenSeconds = nextStepSeconds
                for ((trackIdx, track) in tab.tracks.withIndex()) {
                    if (track.muted || (anySolo && !track.soloed)) continue
                    val entry = plans[trackIdx].entryAtBeat(beatIndex) ?: continue
                    scheduleCells(track, entry, whenSeconds, stepSeconds)
                }

                val beat = beatIndex
                scope.launch {
                    delay(((whenSeconds - sampler.nowSeconds()) * 1000).toLong().coerceAtLeast(0))
                    if (!isPlaying) return@launch
                    launch(Dispatchers.Main) { onBeat?.invoke(beat) }
                }

                nextStepSeconds += stepSeconds
                beatIndex += 1
            }
        }
    }

    private fun scheduleCells(
        track: Tablature.Track,
        entry: Tablature.PlanEntry,
        whenSeconds: Double,
        stepSeconds: Double,
    ) {
        val measure = track.measures.getOrNull(entry.measureIdx) ?: return
        val gain = track.volume.toFloat()
        val rate = sampler.sampleRate

        for (line in measure.strings) {
            val cell = line.steps.getOrNull(entry.stepIdx) ?: continue
            var duration = cell.effectiveDuration * stepSeconds
            // Articulação é velocity, não só volume: ghost note em instrumento
            // de verdade sai mais opaca além de mais baixa, e as vozes HD
            // respondem a isso.
            var velocity = 0.85f
            if (cell.articulations["pm"] == true) {
                duration = minOf(duration, stepSeconds * 0.9)
                velocity *= 0.75f
            }
            if (cell.articulations["ghost"] == true) velocity *= 0.42f
            if (cell.articulations["accent"] == true) velocity = 1f

            if (track.type == "drums") {
                val pad = track.padId(line.stringIndex) ?: continue
                val kit = track.kitId ?: "acoustic"
                // Round robin pela batida, para hits repetidos variarem.
                val variation = beatIndex % DrumSynth.roundRobinCount
                // O ganho da trilha vai no schedule (a voz escala ao vivo),
                // então a chave do cache não precisa dele.
                // Chave por arquivo quando vem de sample (ver DrumVoicing); o
                // acento do sample vai no ganho da voz junto com o da trilha.
                val voicing = DrumVoicing.of(kit, pad, velocity, variation, gain)
                sampler.schedule(key = "tab/${voicing.key}", atSeconds = whenSeconds, gain = voicing.gain) {
                    DrumSynth.renderStereo(
                        kit = kit, pad = pad, velocity = velocity, variation = variation,
                        sampleRate = rate, gain = 1f, velocityGainApplied = voicing.sampled,
                    ).interleaved()
                }
                continue
            }

            val midi = track.midi(line.stringIndex, cell.v) ?: continue
            val frequency = MusicNotes.midiToFrequency(midi)
            val voiceId = track.voiceId ?: if (track.type == "bass") "bass-fingered" else "guitar-clean"
            val voice = InstrumentVoice.from(voiceId) ?: defaultVoice(track.type)
            // Quantiza a duração na chave do cache: uma tablatura produz
            // muitas durações quase iguais e cada chave distinta é um render
            // e um buffer separados.
            val quantised = (duration * 40).roundToInt() / 40.0
            val key = "tab/${SampleBank.shared.soundGeneration}/${voice.id}/$midi/" +
                String.format(Locale.ROOT, "%.3f/%.2f", quantised, velocity)
            sampler.schedule(key = key, atSeconds = whenSeconds, gain = gain) {
                InstrumentSynth.render(
                    voice = voice, frequency = frequency, duration = quantised,
                    velocity = velocity, sampleRate = rate,
                ).interleaved()
            }
        }
    }

    private fun defaultVoice(type: String): InstrumentVoice = when (type) {
        "bass" -> InstrumentVoice.BassFingered
        "keys" -> InstrumentVoice.AcousticPiano
        else -> InstrumentVoice.GuitarClean
    }
}
