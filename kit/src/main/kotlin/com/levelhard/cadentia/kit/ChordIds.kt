package com.levelhard.cadentia.kit

/**
 * A identidade da biblioteca de acordes — o que o `AppSettings.sanitize`
 * valida. Os 77 acordes completos (notas, casas de violão, teclas) entram
 * com o Piano (fase 2), gerados do `ChordLibrary.swift`, neste objeto.
 */
object ChordLibrary {
    val roots = listOf("C", "D", "E", "F", "G", "A", "B")

    /** Ids das 11 qualidades, na ordem do iOS/web. */
    val qualityIds = listOf(
        "maj", "m", "7", "maj7", "m7", "sus2", "sus4", "dim", "aug", "m7b5", "dim7",
    )
}
