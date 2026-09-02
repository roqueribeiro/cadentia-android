package com.levelhard.cadentia.kit

/** Tipos de escala — port do `Scales.swift`; mesmos ids e intervalos do web. */
data class ScaleType(
    val id: String,
    /** Semitons a partir da tônica. */
    val intervals: List<Int>,
) {
    /** Chave de i18n (web `music.scales.types.*`). */
    val nameKey: String get() = "music.scales.types.$id"

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
