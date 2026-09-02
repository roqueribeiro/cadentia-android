package com.levelhard.cadentia.kit

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Tudo que o app lembra entre aberturas — port do `AppSettings.swift`. App
 * standalone: JSON puro na persistência local, sem backend. Nomes e faixas
 * herdados do schema de música do RoqueOS para uma futura sincronização ser
 * trivial.
 *
 * A decodificação é tolerante por construção (o mesmo contrato que o iOS
 * implementa à mão): chave ausente cai no default da propriedade e chave
 * desconhecida é ignorada — adicionar um campo nunca apaga as escolhas de
 * quem já usa o app, e um build antigo lendo arquivo novo ignora o que não
 * conhece. O `SettingsCodec` no fim prende esse contrato.
 */
@Serializable
data class AppSettings(
    var tuner: Tuner = Tuner(),
    var metronome: Metronome = Metronome(),
    var drums: Drums = Drums(),
    var studio: Studio = Studio(),
    var piano: Piano = Piano(),
) {
    @Serializable
    data class Tuner(
        /** A4 de referência em Hz, 415–466 (afinações de orquestra), padrão 440. */
        var referenceA: Double = 440.0,
        var lastInstrument: String = "chromatic",
        /** Afinações usadas por último, mais recente primeiro — fixadas no topo da folha (1.16). */
        var recentInstruments: List<String> = emptyList(),
    )

    @Serializable
    data class Metronome(
        var bpm: Int = 120,
        var timeSignature: String = "4/4",
        var subdivision: Int = 1,
        var sound: String = "click",
        var volume: Double = 0.7,
        /** "off" | "3:2" | "4:3" | "5:4" | "7:4". */
        var polyrhythm: String = "off",
        var practiceTimerMinutes: Int = 15,
    )

    @Serializable
    data class Drums(
        var kit: String = "acoustic",
        var bpm: Int = 100,
        var volume: Double = 0.8,
        /** padId → 16 passos. Vazio = sequencer em branco. */
        var pattern: Map<String, List<Boolean>> = emptyMap(),
        /** Os 9 pads de performance (3×3 como multipad de hardware). */
        var padLayout: List<String> = defaultPadLayout,
        var reverbEnabled: Boolean = false,
        var reverbMix: Double = 0.35,
    ) {
        companion object {
            val defaultPadLayout = listOf(
                "crash", "hihat-c", "hihat-o",
                "tom-low", "tom-mid", "tom-high",
                "kick", "snare", "clap",
            )
        }
    }

    @Serializable
    data class Studio(
        var hz: Double = 440.0,
        var wave: String = "sine",
        var volume: Double = 0.3,
        var binauralEnabled: Boolean = false,
        var binauralOffset: Double = 10.0,
        var reverbEnabled: Boolean = false,
        var reverbMix: Double = 0.3,
        var delayEnabled: Boolean = false,
        var delayTimeMs: Double = 350.0,
        var delayFeedback: Double = 0.4,
        var delayMix: Double = 0.3,
    )

    @Serializable
    data class Piano(
        /** Um id de `InstrumentVoice`. */
        var voice: String = "acoustic-piano",
        /** Pedal de sustain. Desligado, o abafador cai quando a tecla sobe. */
        var sustain: Boolean = false,
        var octave: Int = 4,
        var chordRoot: String = "C",
        var chordQuality: String = "maj",
        var scaleRoot: String = "C",
        var scaleType: String = "major",
    )

    /** Aperta qualquer coisa que um arquivo editado à mão (ou versão velha) quebraria. */
    fun sanitize() {
        if (tuner.referenceA !in 415.0..466.0) tuner.referenceA = 440.0
        metronome.bpm = metronome.bpm.coerceIn(40, 240)
        metronome.subdivision = metronome.subdivision.coerceIn(1, 8)
        metronome.volume = metronome.volume.coerceIn(0.0, 1.0)
        metronome.practiceTimerMinutes = metronome.practiceTimerMinutes.coerceIn(1, 120)
        if (metronome.polyrhythm !in listOf("off", "3:2", "4:3", "5:4", "7:4")) {
            metronome.polyrhythm = "off"
        }
        if (drums.kit !in DrumSynth.kitIDs) drums.kit = "acoustic"
        drums.bpm = drums.bpm.coerceIn(40, 240)
        drums.volume = drums.volume.coerceIn(0.0, 1.0)
        drums.reverbMix = drums.reverbMix.coerceIn(0.0, 1.0)
        if (drums.padLayout.size != 9 || drums.padLayout.any { it !in DrumSynth.padIDs }) {
            drums.padLayout = Drums.defaultPadLayout
        }
        drums.pattern = drums.pattern
            .filterKeys { it in DrumSynth.padIDs }
            .mapValues { (_, steps) ->
                if (steps.size == 16) steps else (steps + List(16) { false }).take(16)
            }
        studio.hz = studio.hz.coerceIn(20.0, 20000.0)
        if (ToneSynth.Waveform.from(studio.wave) == null) studio.wave = "sine"
        studio.volume = studio.volume.coerceIn(0.0, 1.0)
        studio.binauralOffset = studio.binauralOffset.coerceIn(1.0, 40.0)
        studio.reverbMix = studio.reverbMix.coerceIn(0.0, 1.0)
        studio.delayTimeMs = studio.delayTimeMs.coerceIn(50.0, 1000.0)
        studio.delayFeedback = studio.delayFeedback.coerceIn(0.0, 0.9)
        studio.delayMix = studio.delayMix.coerceIn(0.0, 1.0)
        // "epiano" era o id antigo antes do elenco alinhar com o web; migra em
        // vez de resetar a escolha de alguém em silêncio.
        if (piano.voice == "epiano") piano.voice = "electric-piano"
        if (InstrumentVoice.from(piano.voice) == null) piano.voice = "acoustic-piano"
        piano.octave = piano.octave.coerceIn(2, 6)
        if (piano.chordRoot !in ChordLibrary.roots) piano.chordRoot = "C"
        if (piano.chordQuality !in ChordLibrary.qualityIds) piano.chordQuality = "maj"
        if (piano.scaleRoot !in MusicNotes.noteNames) piano.scaleRoot = "C"
        if (ScaleType.all.none { it.id == piano.scaleType }) piano.scaleType = "major"
    }
}

/**
 * Codifica/decodifica o blob de settings — o papel do JSONEncoder/Decoder no
 * `SettingsStore` do iOS. O store de verdade (SharedPreferences + StateFlow)
 * vive no app; aqui fica o contrato testável: round-trip, tolerância a chave
 * ausente/desconhecida, e lixo vira defaults em vez de perder tudo.
 */
object SettingsCodec {
    const val PREFS_KEY = "cadentia.settings.v1"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(settings: AppSettings): String = json.encodeToString(settings)

    /** Lixo ou null → defaults, nunca crash: o app abre de qualquer jeito. */
    fun decode(raw: String?): AppSettings {
        if (raw.isNullOrBlank()) return AppSettings()
        return try {
            json.decodeFromString<AppSettings>(raw).also { it.sanitize() }
        } catch (_: Exception) {
            AppSettings()
        }
    }
}
