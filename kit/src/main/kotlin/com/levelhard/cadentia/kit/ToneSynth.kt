package com.levelhard.cadentia.kit

import kotlin.math.floor
import kotlin.math.sin

/**
 * Formas de onda cruas para o gerador de frequência e o osciloscópio —
 * port do `ToneSynth.swift`. (A síntese de nota musical vive nas vozes de
 * instrumento; aqui são só as formas puras que o gerador desenha e toca.)
 */
object ToneSynth {
    enum class Waveform(val id: String) {
        Sine("sine"),
        Square("square"),
        Triangle("triangle"),
        Sawtooth("sawtooth");

        /** Chave de i18n (web `music.frequency.waves.*`). */
        val nameKey: String get() = "music.frequency.waves.$id"

        /** Uma amostra em `phase` (ciclos, não radianos). */
        fun sample(phase: Double): Double {
            val t = phase - floor(phase)
            return when (this) {
                Sine -> sin(2 * Math.PI * t)
                Square -> if (t < 0.5) 1.0 else -1.0
                Triangle -> if (t < 0.5) 4 * t - 1 else 3 - 4 * t
                Sawtooth -> 2 * t - 1
            }
        }

        companion object {
            fun from(id: String?): Waveform? = entries.firstOrNull { it.id == id }
        }
    }
}
