package com.levelhard.cadentia.kit

/**
 * Superfície pública da bateria — port do `DrumSynth.swift`: ids de pad e de
 * kit, chaves de i18n e renderização. A síntese vive em [DrumKitHD]; este
 * tipo é o contrato estável contra o qual o app, o validador de settings e
 * os padrões do sequencer são escritos (os mesmos 16 ids remapeados nos três
 * kits, para um groove sobreviver à troca de kit como no web).
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

    /**
     * Chave de i18n por pad (web `music.drums.pads.*`; os chimbais escrevem
     * Closed/Open por extenso no schema do web, não o sufixo -c/-o).
     */
    fun labelKey(pad: String): String = when (pad) {
        "hihat-c" -> "music.drums.pads.hihatClosed"
        "hihat-o" -> "music.drums.pads.hihatOpen"
        else -> {
            val camel = StringBuilder()
            var upperNext = false
            for (ch in pad) {
                if (ch == '-') {
                    upperNext = true
                    continue
                }
                camel.append(if (upperNext) ch.uppercaseChar() else ch)
                upperNext = false
            }
            "music.drums.pads.$camel"
        }
    }

    /** Quantas variações de round robin existem por pad. */
    val roundRobinCount: Int get() = DrumKitHD.roundRobinCount

    /** Batida estéreo — o que o sampler agenda. */
    fun renderStereo(
        kit: String,
        pad: String,
        velocity: Float = 0.85f,
        variation: Int = 0,
        sampleRate: Double,
        gain: Float = 1f,
    ): StereoBuffer = DrumKitHD.render(kit, pad, velocity, variation, sampleRate, gain)

    /** Batida mono, para análise offline, testes e prévias de forma de onda. */
    fun render(kit: String, pad: String, sampleRate: Double, gain: Float = 1f): FloatArray =
        renderStereo(kit, pad, sampleRate = sampleRate, gain = gain).summedToMono()
}
