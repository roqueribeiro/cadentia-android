package com.levelhard.cadentia.kit

/**
 * Presets de groove de 16 passos — port do `DrumPatterns.swift` (que porta o
 * `drumPatterns.js` do web: 25 clássicos curados). Mesma DSL de string:
 * 'x' = batida, '.' = pausa, 16 caracteres por pad.
 */
data class DrumPattern(
    val id: String,
    val category: String,
    val bpm: Int,
    /** padId → 16 booleanos. */
    val pads: Map<String, List<Boolean>>,
) {
    /** Chave de i18n (id kebab → camel, paridade web: afro-6-8 → afro68). */
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
            return "music.drums.patternName.$camel"
        }

    companion object {
        private fun p(id: String, category: String, bpm: Int, padDefs: Map<String, String>) =
            DrumPattern(id, category, bpm, padDefs.mapValues { (_, steps) -> steps.map { it == 'x' || it == 'X' } })

        val categories = listOf("rock", "funk", "jazz", "latin", "electronic", "folk", "world", "pop")

        fun find(id: String): DrumPattern? = all.firstOrNull { it.id == id }

        fun byCategory(category: String): List<DrumPattern> = all.filter { it.category == category }

        val all: List<DrumPattern> = listOf(
            // ROCK
            p("rock-basic", "rock", 100, mapOf(
                "kick" to "x...x...x...x...",
                "snare" to "....x.......x...",
                "hihat-c" to "x.x.x.x.x.x.x.x.",
            )),
            p("rock-half-time", "rock", 90, mapOf(
                "kick" to "x.......x.......",
                "snare" to "........x.......",
                "hihat-c" to "x.x.x.x.x.x.x.x.",
            )),
            p("rock-driving", "rock", 130, mapOf(
                "kick" to "x.x.x.x.x.x.x.x.",
                "snare" to "....x.......x...",
                "hihat-c" to "xxxxxxxxxxxxxxxx",
                "crash" to "x...............",
            )),
            p("metal-double", "rock", 160, mapOf(
                "kick" to "xxxxxxxxxxxxxxxx",
                "snare" to "....x.......x...",
                "hihat-c" to "x...x...x...x...",
                "crash" to "x.......x.......",
            )),
            p("blues-shuffle", "rock", 90, mapOf(
                "kick" to "x..x...x..x...x.",
                "snare" to "....x.......x...",
                "hihat-c" to "x..x..x..x..x..x",
            )),
            // FUNK
            p("funk-basic", "funk", 100, mapOf(
                "kick" to "x..x...x.x..x...",
                "snare" to "....x.......x...",
                "hihat-c" to "xx.xx.xxxx.xx.xx",
            )),
            p("funk-shuffle", "funk", 95, mapOf(
                "kick" to "x..x..x...x..x..",
                "snare" to "....x.......x...",
                "hihat-c" to "x.x.x.x.x.x.x.x.",
                "hihat-o" to "......x.......x.",
            )),
            p("disco", "funk", 120, mapOf(
                "kick" to "x...x...x...x...",
                "snare" to "....x.......x...",
                "hihat-c" to ".x.x.x.x.x.x.x.x",
                "hihat-o" to "..x...x...x...x.",
            )),
            // JAZZ
            p("jazz-swing", "jazz", 120, mapOf(
                "ride" to "x..x.xx..x.xx..x",
                "hihat-c" to "....x.......x...",
                "snare" to "..x.....x...x...",
                "kick" to "x.......x.......",
            )),
            p("jazz-bebop", "jazz", 180, mapOf(
                "ride" to "x..x.xx..x.xx..x",
                "hihat-c" to "....x.......x...",
                "snare" to "......x.....x.x.",
            )),
            // LATIN
            p("bossa-nova", "latin", 110, mapOf(
                "kick" to "x..x..x.x..x..x.",
                "rim" to "..x...x...x...x.",
                "hihat-c" to "x.x.x.x.x.x.x.x.",
                "conga-low" to "..x...x.....x..x",
            )),
            p("samba", "latin", 100, mapOf(
                "kick" to "x..xx..xx..xx..x",
                "snare" to "..x...x...x...x.",
                "hihat-c" to "xxxxxxxxxxxxxxxx",
                "conga-mid" to "..x...x...x...x.",
            )),
            p("latin-clave", "latin", 110, mapOf(
                "conga-low" to "x..x..x.....x.x.",
                "conga-mid" to "....x...x.......",
                "cowbell" to "x...x...x...x...",
                "kick" to "x.......x.......",
            )),
            p("mambo", "latin", 180, mapOf(
                "cowbell" to "x.x.x.x.x.x.x.x.",
                "conga-low" to "x..x..x.....x.x.",
                "conga-mid" to "..x...x...x...x.",
                "kick" to "x.......x.......",
            )),
            p("reggae-one-drop", "latin", 75, mapOf(
                "kick" to "........x.......",
                "snare" to "........x.......",
                "hihat-c" to ".x.x.x.x.x.x.x.x",
                "rim" to "..x...x...x...x.",
            )),
            // ELECTRONIC / HIP-HOP
            p("hip-hop-basic", "electronic", 90, mapOf(
                "kick" to "x.....x.x...x...",
                "snare" to "....x.......x...",
                "hihat-c" to "x.x.x.x.x.x.x.x.",
                "clap" to "....x.......x...",
            )),
            p("hip-hop-trap", "electronic", 70, mapOf(
                "kick" to "x...x.....x.x...",
                "snare" to "....x.......x...",
                "hihat-c" to "xxxxxxxxxxxxxxxx",
                "hihat-o" to ".........x......",
            )),
            p("house", "electronic", 124, mapOf(
                "kick" to "x...x...x...x...",
                "snare" to "....x.......x...",
                "hihat-c" to ".x.x.x.x.x.x.x.x",
                "hihat-o" to "..x...x...x...x.",
                "clap" to "....x.......x...",
            )),
            p("techno", "electronic", 130, mapOf(
                "kick" to "x...x...x...x...",
                "hihat-c" to ".x.x.x.x.x.x.x.x",
                "hihat-o" to "..x...x...x...x.",
                "rim" to "......x.......x.",
            )),
            p("drum-and-bass", "electronic", 170, mapOf(
                "kick" to "x.....x...x.....",
                "snare" to "....x.......x...",
                "hihat-c" to "xxxxxxxxxxxxxxxx",
                "ride" to "..x...x...x...x.",
            )),
            // FOLK / WORLD / POP
            p("country-train", "folk", 110, mapOf(
                "kick" to "x.x.x.x.x.x.x.x.",
                "snare" to "....x.......x...",
                "hihat-c" to "xx.xxx.xxx.xxx.x",
            )),
            p("afro-6-8", "world", 100, mapOf(
                "conga-low" to "x..x..x..x..x..x",
                "conga-mid" to "..x..x..x..x..x.",
                "cowbell" to "x.....x.....x...",
                "kick" to "x.....x.....x...",
            )),
            p("pop-basic", "pop", 100, mapOf(
                "kick" to "x.......x.......",
                "snare" to "....x.......x...",
                "hihat-c" to "x.x.x.x.x.x.x.x.",
            )),
            p("ballad-slow", "pop", 70, mapOf(
                "kick" to "x.......x.......",
                "snare" to "....x.......x...",
                "ride" to "x.x.x.x.x.x.x.x.",
            )),
            p("march", "world", 120, mapOf(
                "kick" to "x...x...x...x...",
                "snare" to ".x.x.x.x.x.x.x.x",
            )),
        )
    }
}
