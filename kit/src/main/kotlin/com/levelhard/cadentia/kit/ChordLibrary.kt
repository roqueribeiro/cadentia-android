package com.levelhard.cadentia.kit

// GERADO por scripts/gen-chords.py a partir do ChordLibrary.swift do
// cadentia-ios (que nasce do chords.js do roqueos-front). Não edite.

/**
 * Biblioteca curada de acordes (77): classes de altura, forma de violão e
 * voicing de piano. `guitarFrets` = [E A D G B e]; -1 abafada, 0 solta.
 */
data class Chord(
    val id: String,
    val displayName: String,
    val root: String,
    val quality: String,
    val notes: List<String>,
    val guitarFrets: List<Int>,
    /** Nomes com oitava, ex.: ["C4", "E4", "G4"]. */
    val pianoNotes: List<String>,
)

object ChordLibrary {
    data class Quality(val id: String, val label: String)

    val qualities: List<Quality> = listOf(
        Quality("maj", "maj"),
        Quality("m", "m"),
        Quality("7", "7"),
        Quality("maj7", "maj7"),
        Quality("m7", "m7"),
        Quality("sus2", "sus2"),
        Quality("sus4", "sus4"),
        Quality("dim", "dim"),
        Quality("aug", "aug"),
        Quality("m7b5", "m7b5"),
        Quality("dim7", "dim7"),
    )

    /** Ids das qualidades, na ordem — o que o AppSettings.sanitize valida. */
    val qualityIds: List<String> = qualities.map { it.id }

    val roots = listOf("C", "D", "E", "F", "G", "A", "B")

    val all: List<Chord> = listOf(
        Chord("C", "C", "C", "maj", listOf("C", "E", "G"), listOf(-1, 3, 2, 0, 1, 0), listOf("C4", "E4", "G4")),
        Chord("D", "D", "D", "maj", listOf("D", "F#", "A"), listOf(-1, -1, 0, 2, 3, 2), listOf("D4", "F#4", "A4")),
        Chord("E", "E", "E", "maj", listOf("E", "G#", "B"), listOf(0, 2, 2, 1, 0, 0), listOf("E4", "G#4", "B4")),
        Chord("F", "F", "F", "maj", listOf("F", "A", "C"), listOf(1, 3, 3, 2, 1, 1), listOf("F4", "A4", "C5")),
        Chord("G", "G", "G", "maj", listOf("G", "B", "D"), listOf(3, 2, 0, 0, 0, 3), listOf("G3", "B3", "D4")),
        Chord("A", "A", "A", "maj", listOf("A", "C#", "E"), listOf(-1, 0, 2, 2, 2, 0), listOf("A3", "C#4", "E4")),
        Chord("B", "B", "B", "maj", listOf("B", "D#", "F#"), listOf(-1, 2, 4, 4, 4, 2), listOf("B3", "D#4", "F#4")),
        Chord("Cm", "Cm", "C", "m", listOf("C", "Eb", "G"), listOf(-1, 3, 5, 5, 4, 3), listOf("C4", "Eb4", "G4")),
        Chord("Dm", "Dm", "D", "m", listOf("D", "F", "A"), listOf(-1, -1, 0, 2, 3, 1), listOf("D4", "F4", "A4")),
        Chord("Em", "Em", "E", "m", listOf("E", "G", "B"), listOf(0, 2, 2, 0, 0, 0), listOf("E4", "G4", "B4")),
        Chord("Fm", "Fm", "F", "m", listOf("F", "Ab", "C"), listOf(1, 3, 3, 1, 1, 1), listOf("F4", "Ab4", "C5")),
        Chord("Gm", "Gm", "G", "m", listOf("G", "Bb", "D"), listOf(3, 5, 5, 3, 3, 3), listOf("G3", "Bb3", "D4")),
        Chord("Am", "Am", "A", "m", listOf("A", "C", "E"), listOf(-1, 0, 2, 2, 1, 0), listOf("A3", "C4", "E4")),
        Chord("Bm", "Bm", "B", "m", listOf("B", "D", "F#"), listOf(-1, 2, 4, 4, 3, 2), listOf("B3", "D4", "F#4")),
        Chord("Cdim", "C°", "C", "dim", listOf("C", "Eb", "Gb"), listOf(-1, 3, 4, 5, 4, -1), listOf("C4", "Eb4", "Gb4")),
        Chord("Ddim", "D°", "D", "dim", listOf("D", "F", "Ab"), listOf(-1, -1, 0, 1, 3, 1), listOf("D4", "F4", "Ab4")),
        Chord("Edim", "E°", "E", "dim", listOf("E", "G", "Bb"), listOf(0, 1, 2, 0, 2, -1), listOf("E4", "G4", "Bb4")),
        Chord("Fdim", "F°", "F", "dim", listOf("F", "Ab", "B"), listOf(1, 2, 3, 1, -1, -1), listOf("F4", "Ab4", "B4")),
        Chord("Gdim", "G°", "G", "dim", listOf("G", "Bb", "Db"), listOf(3, 4, 5, 3, -1, -1), listOf("G3", "Bb3", "Db4")),
        Chord("Adim", "A°", "A", "dim", listOf("A", "C", "Eb"), listOf(-1, 0, 1, 2, 1, -1), listOf("A3", "C4", "Eb4")),
        Chord("Bdim", "B°", "B", "dim", listOf("B", "D", "F"), listOf(-1, 2, 3, 4, 3, -1), listOf("B3", "D4", "F4")),
        Chord("Caug", "C+", "C", "aug", listOf("C", "E", "G#"), listOf(-1, 3, 2, 1, 1, 0), listOf("C4", "E4", "G#4")),
        Chord("Daug", "D+", "D", "aug", listOf("D", "F#", "A#"), listOf(-1, -1, 0, 3, 3, 2), listOf("D4", "F#4", "A#4")),
        Chord("Eaug", "E+", "E", "aug", listOf("E", "G#", "C"), listOf(0, 3, 2, 1, 1, 0), listOf("E4", "G#4", "C5")),
        Chord("Faug", "F+", "F", "aug", listOf("F", "A", "C#"), listOf(-1, -1, 3, 2, 2, 1), listOf("F4", "A4", "C#5")),
        Chord("Gaug", "G+", "G", "aug", listOf("G", "B", "D#"), listOf(3, 2, 1, 0, 0, 3), listOf("G3", "B3", "D#4")),
        Chord("Aaug", "A+", "A", "aug", listOf("A", "C#", "F"), listOf(-1, 0, 3, 2, 2, 1), listOf("A3", "C#4", "F4")),
        Chord("Baug", "B+", "B", "aug", listOf("B", "D#", "G"), listOf(-1, 2, 1, 0, 0, 3), listOf("B3", "D#4", "G4")),
        Chord("C7", "C7", "C", "7", listOf("C", "E", "G", "Bb"), listOf(-1, 3, 2, 3, 1, 0), listOf("C4", "E4", "G4", "Bb4")),
        Chord("D7", "D7", "D", "7", listOf("D", "F#", "A", "C"), listOf(-1, -1, 0, 2, 1, 2), listOf("D4", "F#4", "A4", "C5")),
        Chord("E7", "E7", "E", "7", listOf("E", "G#", "B", "D"), listOf(0, 2, 0, 1, 0, 0), listOf("E4", "G#4", "B4", "D5")),
        Chord("F7", "F7", "F", "7", listOf("F", "A", "C", "Eb"), listOf(1, 3, 1, 2, 1, 1), listOf("F4", "A4", "C5", "Eb5")),
        Chord("G7", "G7", "G", "7", listOf("G", "B", "D", "F"), listOf(3, 2, 0, 0, 0, 1), listOf("G3", "B3", "D4", "F4")),
        Chord("A7", "A7", "A", "7", listOf("A", "C#", "E", "G"), listOf(-1, 0, 2, 0, 2, 0), listOf("A3", "C#4", "E4", "G4")),
        Chord("B7", "B7", "B", "7", listOf("B", "D#", "F#", "A"), listOf(-1, 2, 1, 2, 0, 2), listOf("B3", "D#4", "F#4", "A4")),
        Chord("Cmaj7", "Cmaj7", "C", "maj7", listOf("C", "E", "G", "B"), listOf(-1, 3, 2, 0, 0, 0), listOf("C4", "E4", "G4", "B4")),
        Chord("Dmaj7", "Dmaj7", "D", "maj7", listOf("D", "F#", "A", "C#"), listOf(-1, -1, 0, 2, 2, 2), listOf("D4", "F#4", "A4", "C#5")),
        Chord("Emaj7", "Emaj7", "E", "maj7", listOf("E", "G#", "B", "D#"), listOf(0, 2, 1, 1, 0, 0), listOf("E4", "G#4", "B4", "D#5")),
        Chord("Fmaj7", "Fmaj7", "F", "maj7", listOf("F", "A", "C", "E"), listOf(1, 3, 3, 2, 1, 0), listOf("F4", "A4", "C5", "E5")),
        Chord("Gmaj7", "Gmaj7", "G", "maj7", listOf("G", "B", "D", "F#"), listOf(3, 2, 0, 0, 0, 2), listOf("G3", "B3", "D4", "F#4")),
        Chord("Amaj7", "Amaj7", "A", "maj7", listOf("A", "C#", "E", "G#"), listOf(-1, 0, 2, 1, 2, 0), listOf("A3", "C#4", "E4", "G#4")),
        Chord("Bmaj7", "Bmaj7", "B", "maj7", listOf("B", "D#", "F#", "A#"), listOf(-1, 2, 4, 3, 4, -1), listOf("B3", "D#4", "F#4", "A#4")),
        Chord("Cm7", "Cm7", "C", "m7", listOf("C", "Eb", "G", "Bb"), listOf(-1, 3, 5, 3, 4, 3), listOf("C4", "Eb4", "G4", "Bb4")),
        Chord("Dm7", "Dm7", "D", "m7", listOf("D", "F", "A", "C"), listOf(-1, -1, 0, 2, 1, 1), listOf("D4", "F4", "A4", "C5")),
        Chord("Em7", "Em7", "E", "m7", listOf("E", "G", "B", "D"), listOf(0, 2, 0, 0, 0, 0), listOf("E4", "G4", "B4", "D5")),
        Chord("Fm7", "Fm7", "F", "m7", listOf("F", "Ab", "C", "Eb"), listOf(1, 3, 1, 1, 1, 1), listOf("F4", "Ab4", "C5", "Eb5")),
        Chord("Gm7", "Gm7", "G", "m7", listOf("G", "Bb", "D", "F"), listOf(3, 5, 3, 3, 3, 3), listOf("G3", "Bb3", "D4", "F4")),
        Chord("Am7", "Am7", "A", "m7", listOf("A", "C", "E", "G"), listOf(-1, 0, 2, 0, 1, 0), listOf("A3", "C4", "E4", "G4")),
        Chord("Bm7", "Bm7", "B", "m7", listOf("B", "D", "F#", "A"), listOf(-1, 2, 0, 2, 0, 2), listOf("B3", "D4", "F#4", "A4")),
        Chord("Cm7b5", "Cm7♭5", "C", "m7b5", listOf("C", "Eb", "Gb", "Bb"), listOf(-1, 3, 4, 3, 4, -1), listOf("C4", "Eb4", "Gb4", "Bb4")),
        Chord("Dm7b5", "Dm7♭5", "D", "m7b5", listOf("D", "F", "Ab", "C"), listOf(-1, -1, 0, 1, 1, 1), listOf("D4", "F4", "Ab4", "C5")),
        Chord("Em7b5", "Em7♭5", "E", "m7b5", listOf("E", "G", "Bb", "D"), listOf(0, 1, 0, 0, 0, -1), listOf("E4", "G4", "Bb4", "D5")),
        Chord("Fm7b5", "Fm7♭5", "F", "m7b5", listOf("F", "Ab", "B", "Eb"), listOf(1, 2, 3, 1, -1, -1), listOf("F4", "Ab4", "B4", "Eb5")),
        Chord("Gm7b5", "Gm7♭5", "G", "m7b5", listOf("G", "Bb", "Db", "F"), listOf(3, 4, 3, 3, -1, -1), listOf("G3", "Bb3", "Db4", "F4")),
        Chord("Am7b5", "Am7♭5", "A", "m7b5", listOf("A", "C", "Eb", "G"), listOf(-1, 0, 1, 0, 1, -1), listOf("A3", "C4", "Eb4", "G4")),
        Chord("Bm7b5", "Bm7♭5", "B", "m7b5", listOf("B", "D", "F", "A"), listOf(-1, 2, 0, 2, 0, -1), listOf("B3", "D4", "F4", "A4")),
        Chord("Cdim7", "C°7", "C", "dim7", listOf("C", "Eb", "Gb", "A"), listOf(-1, 3, 4, 2, 4, 2), listOf("C4", "Eb4", "Gb4", "A4")),
        Chord("Ddim7", "D°7", "D", "dim7", listOf("D", "F", "Ab", "B"), listOf(-1, -1, 0, 1, 0, 1), listOf("D4", "F4", "Ab4", "B4")),
        Chord("Edim7", "E°7", "E", "dim7", listOf("E", "G", "Bb", "Db"), listOf(-1, -1, 2, 3, 2, 3), listOf("E4", "G4", "Bb4", "Db5")),
        Chord("Fdim7", "F°7", "F", "dim7", listOf("F", "Ab", "B", "D"), listOf(-1, -1, 3, 4, 3, 4), listOf("F4", "Ab4", "B4", "D5")),
        Chord("Gdim7", "G°7", "G", "dim7", listOf("G", "Bb", "Db", "E"), listOf(-1, -1, 5, 6, 5, 6), listOf("G3", "Bb3", "Db4", "E4")),
        Chord("Adim7", "A°7", "A", "dim7", listOf("A", "C", "Eb", "Gb"), listOf(-1, -1, 1, 2, 1, 2), listOf("A3", "C4", "Eb4", "Gb4")),
        Chord("Bdim7", "B°7", "B", "dim7", listOf("B", "D", "F", "Ab"), listOf(-1, -1, 3, 4, 3, 4), listOf("B3", "D4", "F4", "Ab4")),
        Chord("Csus2", "Csus2", "C", "sus2", listOf("C", "D", "G"), listOf(-1, 3, 0, 0, 1, 3), listOf("C4", "D4", "G4")),
        Chord("Dsus2", "Dsus2", "D", "sus2", listOf("D", "E", "A"), listOf(-1, -1, 0, 2, 3, 0), listOf("D4", "E4", "A4")),
        Chord("Esus2", "Esus2", "E", "sus2", listOf("E", "F#", "B"), listOf(0, 2, 4, 4, 0, 0), listOf("E4", "F#4", "B4")),
        Chord("Fsus2", "Fsus2", "F", "sus2", listOf("F", "G", "C"), listOf(-1, 3, 3, 0, 1, 1), listOf("F4", "G4", "C5")),
        Chord("Gsus2", "Gsus2", "G", "sus2", listOf("G", "A", "D"), listOf(3, 0, 0, 2, 3, 3), listOf("G3", "A3", "D4")),
        Chord("Asus2", "Asus2", "A", "sus2", listOf("A", "B", "E"), listOf(-1, 0, 2, 2, 0, 0), listOf("A3", "B3", "E4")),
        Chord("Bsus2", "Bsus2", "B", "sus2", listOf("B", "C#", "F#"), listOf(-1, 2, 4, 4, 2, 2), listOf("B3", "C#4", "F#4")),
        Chord("Csus4", "Csus4", "C", "sus4", listOf("C", "F", "G"), listOf(-1, 3, 3, 0, 1, 1), listOf("C4", "F4", "G4")),
        Chord("Dsus4", "Dsus4", "D", "sus4", listOf("D", "G", "A"), listOf(-1, -1, 0, 2, 3, 3), listOf("D4", "G4", "A4")),
        Chord("Esus4", "Esus4", "E", "sus4", listOf("E", "A", "B"), listOf(0, 2, 2, 2, 0, 0), listOf("E4", "A4", "B4")),
        Chord("Fsus4", "Fsus4", "F", "sus4", listOf("F", "Bb", "C"), listOf(1, 3, 3, 3, 1, 1), listOf("F4", "Bb4", "C5")),
        Chord("Gsus4", "Gsus4", "G", "sus4", listOf("G", "C", "D"), listOf(3, 3, 0, 0, 1, 3), listOf("G3", "C4", "D4")),
        Chord("Asus4", "Asus4", "A", "sus4", listOf("A", "D", "E"), listOf(-1, 0, 2, 2, 3, 0), listOf("A3", "D4", "E4")),
        Chord("Bsus4", "Bsus4", "B", "sus4", listOf("B", "E", "F#"), listOf(-1, 2, 4, 4, 5, 2), listOf("B3", "E4", "F#4")),
    )

    fun find(id: String?): Chord? = all.firstOrNull { it.id == id }

    fun find(root: String, quality: String): Chord? =
        all.firstOrNull { it.root == root && it.quality == quality }
}
