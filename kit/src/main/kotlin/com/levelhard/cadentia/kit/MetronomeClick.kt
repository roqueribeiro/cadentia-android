package com.levelhard.cadentia.kit

import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

/**
 * Síntese do clique do metrônomo — port 1:1 do `MetronomeClick.swift` (que
 * porta o `utils/music/metronomeSynth.js` do web). O web agenda
 * OscillatorNodes curtos; aqui cada som renderiza a mesma forma de onda +
 * envelope num buffer PCM que o motor agenda com precisão de amostra.
 * 4 sons, cada um com variante de acento mais brilhante/alta.
 */
object MetronomeClick {
    enum class Sound(val id: String) {
        Click("click"),
        Woodblock("woodblock"),
        Cowbell("cowbell"),
        Beep("beep");

        /** Chave de i18n, a mesma do web (`music.metronome.sounds.*`). */
        val nameKey: String get() = "music.metronome.sounds.$id"

        companion object {
            fun from(id: String?): Sound? = entries.firstOrNull { it.id == id }
        }
    }

    private enum class Waveform {
        Sine, Triangle, Square;

        fun sample(phase: Double): Double {
            val t = phase - floor(phase) // 0..<1
            return when (this) {
                Sine -> sin(2 * Math.PI * t)
                Square -> if (t < 0.5) 1.0 else -1.0
                Triangle -> if (t < 0.5) 4 * t - 1 else 3 - 4 * t
            }
        }
    }

    private data class Tone(
        val frequency: Double,
        val duration: Double,
        val gain: Double,
        val waveform: Waveform,
    )

    /**
     * Renderiza um clique em PCM mono. `volume` 0…1 escala como o ganho por
     * batida do web (micro-batidas de subdivisão passam volume * 0,4).
     */
    fun render(
        sound: Sound,
        accent: Boolean,
        volume: Double,
        sampleRate: Double,
    ): FloatArray {
        val tones: List<Tone> = when (sound) {
            Sound.Click -> listOf(
                Tone(
                    frequency = if (accent) 1500.0 else 1000.0, duration = 0.04,
                    gain = volume * (if (accent) 0.6 else 0.4), waveform = Waveform.Sine,
                ),
            )
            Sound.Woodblock -> listOf(
                Tone(
                    frequency = if (accent) 1200.0 else 900.0, duration = 0.06,
                    gain = volume * (if (accent) 0.7 else 0.5), waveform = Waveform.Triangle,
                ),
            )
            Sound.Cowbell -> {
                // Dois osciladores empilhados para a textura de sino.
                val freqs = if (accent) listOf(800.0, 540.0) else listOf(600.0, 400.0)
                freqs.map {
                    Tone(frequency = it, duration = 0.18, gain = volume * 0.3, waveform = Waveform.Square)
                }
            }
            Sound.Beep -> listOf(
                Tone(
                    frequency = if (accent) 880.0 else 440.0, duration = 0.1,
                    gain = volume * (if (accent) 0.5 else 0.35), waveform = Waveform.Square,
                ),
            )
        }

        val tailSeconds = 0.02
        val totalDuration = (tones.maxOfOrNull { it.duration } ?: 0.0) + tailSeconds
        val frameCount = (totalDuration * sampleRate).toInt()
        val samples = FloatArray(maxOf(frameCount, 1))

        val attackSeconds = 0.001
        for (tone in tones) {
            if (tone.gain <= 0) continue
            val toneFrames = ((tone.duration + tailSeconds) * sampleRate).toInt()
            for (i in 0 until minOf(toneFrames, samples.size)) {
                val t = i / sampleRate
                // Envelope do web: rampa linear 0→gain em 1 ms, depois
                // decaimento exponencial até 0,001 em `duration`.
                val envelope: Double = if (t < attackSeconds) {
                    tone.gain * (t / attackSeconds)
                } else {
                    val decay = (t - attackSeconds) / maxOf(tone.duration - attackSeconds, 0.001)
                    tone.gain * (0.001 / tone.gain).pow(minOf(decay, 1.0))
                }
                samples[i] += (envelope * tone.waveform.sample(tone.frequency * t)).toFloat()
            }
        }
        return samples
    }
}
