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

/**
 * Afinações de instrumento — port do `InstrumentPreset.swift` (ids e cordas
 * do `utils/music/instruments.js` do web, para `tuner.lastInstrument`
 * fazer round-trip com a PWA).
 */
data class InstrumentPreset(
    val id: String,
    /** Chave de i18n, a mesma do web (`music.tuner.instruments.*`). */
    val nameKey: String,
    /** Grave → agudo; vazio = cromático (qualquer nota). */
    val strings: List<StringNote>,
) {
    data class StringNote(val name: String, val octave: Int) {
        fun frequency(referenceA: Double = 440.0): Double =
            MusicNotes.frequency(name, octave, referenceA) ?: 0.0
    }

    data class NearestString(val note: StringNote, val frequency: Double)

    /** Corda mais próxima da frequência detectada (distância log), null no cromático. */
    fun nearestString(hz: Double, referenceA: Double = 440.0): NearestString? {
        if (hz <= 0 || strings.isEmpty()) return null
        var closest: NearestString? = null
        var minDist = Double.POSITIVE_INFINITY
        for (s in strings) {
            val f = s.frequency(referenceA)
            if (f <= 0) continue
            val dist = abs(log2(hz / f))
            if (dist < minDist) {
                minDist = dist
                closest = NearestString(s, f)
            }
        }
        return closest
    }

    companion object {
        val all: List<InstrumentPreset> = listOf(
            InstrumentPreset("chromatic", "music.tuner.instruments.chromatic", emptyList()),
            InstrumentPreset(
                "guitar-standard", "music.tuner.instruments.guitarStandard",
                listOf(
                    StringNote("E", 2), StringNote("A", 2), StringNote("D", 3),
                    StringNote("G", 3), StringNote("B", 3), StringNote("E", 4),
                ),
            ),
            InstrumentPreset(
                "guitar-drop-d", "music.tuner.instruments.guitarDropD",
                listOf(
                    StringNote("D", 2), StringNote("A", 2), StringNote("D", 3),
                    StringNote("G", 3), StringNote("B", 3), StringNote("E", 4),
                ),
            ),
            InstrumentPreset(
                "bass-4", "music.tuner.instruments.bass4",
                listOf(
                    StringNote("E", 1), StringNote("A", 1),
                    StringNote("D", 2), StringNote("G", 2),
                ),
            ),
            InstrumentPreset(
                "bass-5", "music.tuner.instruments.bass5",
                listOf(
                    StringNote("B", 0), StringNote("E", 1), StringNote("A", 1),
                    StringNote("D", 2), StringNote("G", 2),
                ),
            ),
            InstrumentPreset(
                "ukulele", "music.tuner.instruments.ukulele",
                listOf(
                    StringNote("G", 4), StringNote("C", 4),
                    StringNote("E", 4), StringNote("A", 4),
                ),
            ),
            InstrumentPreset(
                "cavaquinho", "music.tuner.instruments.cavaquinho",
                listOf(
                    StringNote("D", 4), StringNote("G", 4),
                    StringNote("B", 4), StringNote("D", 5),
                ),
            ),
            InstrumentPreset(
                "violin", "music.tuner.instruments.violin",
                listOf(
                    StringNote("G", 3), StringNote("D", 4),
                    StringNote("A", 4), StringNote("E", 5),
                ),
            ),
        )

        fun find(id: String?): InstrumentPreset = all.firstOrNull { it.id == id } ?: all[0]
    }
}
