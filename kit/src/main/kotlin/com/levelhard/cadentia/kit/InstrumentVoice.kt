package com.levelhard.cadentia.kit

/**
 * O elenco de instrumentos, casando 1:1 com os ids do `SYNTH_VOICES` do web —
 * um `.rostab` escrito no RoqueOS toca aqui com os mesmos instrumentos.
 * Port do `InstrumentVoices.swift` (a identidade; a síntese das vozes entra
 * com as Tablaturas).
 */
enum class InstrumentVoice(val id: String, val nameKey: String) {
    Sine("sine", "music.piano.voices.sine"),
    ElectricPiano("electric-piano", "music.piano.voices.electricPiano"),
    AcousticPiano("acoustic-piano", "tablature.voices.acousticPiano"),
    Organ("organ", "music.piano.voices.organ"),
    Lead("lead", "music.piano.voices.lead"),
    GuitarClean("guitar-clean", "tablature.voices.guitarClean"),
    GuitarAcoustic("guitar-acoustic", "tablature.voices.guitarAcoustic"),
    GuitarNylon("guitar-nylon", "tablature.voices.guitarNylon"),
    GuitarJazz("guitar-jazz", "tablature.voices.guitarJazz"),
    GuitarDistorted("guitar-distorted", "tablature.voices.guitarDistorted"),
    BassFingered("bass-fingered", "tablature.voices.bassFingered"),
    BassPicked("bass-picked", "tablature.voices.bassPicked"),
    BassSlap("bass-slap", "tablature.voices.bassSlap"),
    Vibraphone("vibraphone", "tablature.voices.vibraphone"),
    Marimba("marimba", "tablature.voices.marimba"),
    Cello("cello", "tablature.voices.cello"),
    Violin("violin", "tablature.voices.violin"),
    Flute("flute", "tablature.voices.flute"),
    Saxophone("saxophone", "tablature.voices.saxophone"),
    Strings("strings", "tablature.voices.strings"),
    Brass("brass", "tablature.voices.brass");

    companion object {
        fun from(id: String?): InstrumentVoice? = entries.firstOrNull { it.id == id }
    }
}
