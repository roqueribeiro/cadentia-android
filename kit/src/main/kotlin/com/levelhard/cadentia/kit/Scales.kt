package com.levelhard.cadentia.kit

/** Tipos de escala — port do `Scales.swift`; mesmos ids e intervalos do web. */
data class ScaleType(
    val id: String,
    /** Semitons a partir da tônica. */
    val intervals: List<Int>,
) {
    /**
     * Chave de i18n (web `music.scales.types.*`): id kebab → camel
     * (minor-natural → minorNatural), como no iOS. Sem esta conversão o
     * Piano em modo Escalas caía com "chave i18n desconhecida" — achado do
     * QA no emulador.
     */
    val nameKey: String
        get() {
            val camel = StringBuilder()
            var upperNext = false
            for (ch in id) {
                if (ch == '-') {
                    upperNext = true
                    continue
                }
                camel.append(if (upperNext) ch.uppercaseChar() else ch)
                upperNext = false
            }
            return "music.scales.types.$camel"
        }

    /** Classes de altura para tônica + tipo: ("C", major) → C D E F G A B. */
    fun notes(root: String): List<String> {
        val rootIdx = MusicNotes.noteNames.indexOf(root)
        if (rootIdx < 0) return emptyList()
        return intervals.map { MusicNotes.noteNames[(rootIdx + it) % 12] }
    }

    data class ScaleNote(val name: String, val octave: Int, val midi: Int, val frequency: Double)

    /** A mesma escala com oitava + Hz ancorados em `octaveBase` (para tocar). */
    fun notesWithFrequency(root: String, octaveBase: Int = 4): List<ScaleNote> {
        val rootMidi = MusicNotes.noteToMidi(root, octaveBase) ?: return emptyList()
        return intervals.map { semi ->
            val midi = rootMidi + semi
            ScaleNote(
                name = MusicNotes.noteNames[midi % 12],
                octave = midi / 12 - 1,
                midi = midi,
                frequency = MusicNotes.midiToFrequency(midi),
            )
        }
    }

    companion object {
        val all: List<ScaleType> = listOf(
            ScaleType("major", listOf(0, 2, 4, 5, 7, 9, 11)),
            ScaleType("minor-natural", listOf(0, 2, 3, 5, 7, 8, 10)),
            ScaleType("minor-harmonic", listOf(0, 2, 3, 5, 7, 8, 11)),
            ScaleType("minor-melodic", listOf(0, 2, 3, 5, 7, 9, 11)),
            ScaleType("dorian", listOf(0, 2, 3, 5, 7, 9, 10)),
            ScaleType("phrygian", listOf(0, 1, 3, 5, 7, 8, 10)),
            ScaleType("lydian", listOf(0, 2, 4, 6, 7, 9, 11)),
            ScaleType("mixolydian", listOf(0, 2, 4, 5, 7, 9, 10)),
            ScaleType("locrian", listOf(0, 1, 3, 5, 6, 8, 10)),
            ScaleType("blues", listOf(0, 3, 5, 6, 7, 10)),
            ScaleType("pentatonic-major", listOf(0, 2, 4, 7, 9)),
            ScaleType("pentatonic-minor", listOf(0, 3, 5, 7, 10)),
        )

        fun find(id: String): ScaleType = all.firstOrNull { it.id == id } ?: all[0]
    }
}
