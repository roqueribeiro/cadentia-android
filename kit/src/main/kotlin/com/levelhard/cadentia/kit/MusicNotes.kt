package com.levelhard.cadentia.kit

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.round

/**
 * Conversões nota ↔ frequência — port 1:1 do `MusicNotes.swift` do
 * CadentiaKit (que por sua vez porta o `utils/music/notes.js` do web).
 * 12-TET, A4 configurável (415–466, padrão 440), cobre C0–B9.
 *
 * Paridade: `round()` de Kotlin arredonda empate para longe do zero, o mesmo
 * contrato do `rounded()` de Swift — os três engines concordam nos cents.
 */
object MusicNotes {
    val noteNames = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    val noteNamesFlat = listOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")

    private const val A4_MIDI = 69

    data class Note(
        val name: String,
        val octave: Int,
        val midi: Int,
        val targetFrequency: Double,
        /** Desvio da nota exata, -50…+50. */
        val cents: Int,
    )

    /**
     * Classe de altura (0 = dó) de um nome com ou sem oitava ("C#4", "Bb", "E-1").
     * `null` quando não é nota.
     */
    fun pitchClass(note: String): Int? {
        var name = note
        while (name.isNotEmpty() && (name.last().isDigit() || name.last() == '-')) name = name.dropLast(1)
        if (name.isEmpty()) return null
        noteNames.indexOf(name).takeIf { it >= 0 }?.let { return it }
        noteNamesFlat.indexOf(name).takeIf { it >= 0 }?.let { return it }
        return null
    }

    fun midiToFrequency(midiNote: Int, referenceA: Double = 440.0): Double =
        referenceA * 2.0.pow((midiNote - A4_MIDI) / 12.0)

    /** "A",4 → 69; aceita sustenidos ('C#', 'F♯') e bemóis ('Bb', 'e♭'). */
    fun noteToMidi(name: String, octave: Int): Int? {
        val first = name.firstOrNull() ?: return null
        val normalized = first.uppercase() + name.drop(1).lowercase()
            .replace("♯", "#")
            .replace("♭", "b")
        var idx = noteNames.indexOf(normalized).takeIf { it >= 0 }
        if (idx == null) idx = noteNamesFlat.indexOf(normalized).takeIf { it >= 0 }
        if (idx == null) return null
        return (octave + 1) * 12 + idx
    }

    fun frequency(name: String, octave: Int, referenceA: Double = 440.0): Double? {
        val midi = noteToMidi(name, octave) ?: return null
        return midiToFrequency(midi, referenceA)
    }

    /** Nota mais próxima de uma frequência detectada (null para hz <= 0). */
    fun noteFromFrequency(hz: Double, referenceA: Double = 440.0): Note? {
        if (hz <= 0) return null
        val midiFloat = 12 * log2(hz / referenceA) + A4_MIDI
        val midi = round(midiFloat).toInt()
        val cents = round((midiFloat - midi) * 100).toInt()
        val octave = floor(midi / 12.0).toInt() - 1
        val name = noteNames[((midi % 12) + 12) % 12]
        return Note(
            name = name,
            octave = octave,
            midi = midi,
            targetFrequency = midiToFrequency(midi, referenceA),
            cents = cents,
        )
    }

    /** Cents com sinal entre duas frequências; positivo = detectada está aguda. */
    fun centsOff(detected: Double, target: Double): Int {
        if (detected <= 0 || target <= 0) return 0
        return round(1200 * log2(detected / target)).toInt()
    }
}
