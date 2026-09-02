package com.levelhard.cadentia.kit

/**
 * Identidade do kit de bateria — port da superfície do `DrumSynth.swift` que
 * o resto do app referencia (ids de kit e de pad). A síntese dos sons entra
 * com a aba Bateria (fase 2), neste mesmo arquivo.
 */
object DrumSynth {
    val kitIDs = listOf("acoustic", "electronic", "latin")

    fun kitNameKey(id: String): String = "music.drums.kits.$id"

    val padIDs = listOf(
        "kick", "snare", "hihat-c", "hihat-o",
        "crash", "ride", "clap", "rim",
        "tom-low", "tom-mid", "tom-high", "cowbell",
        "shaker", "conga-low", "conga-mid", "conga-high",
    )
}
