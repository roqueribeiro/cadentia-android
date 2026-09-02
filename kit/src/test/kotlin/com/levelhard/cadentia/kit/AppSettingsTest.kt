package com.levelhard.cadentia.kit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port 1:1 do `AppSettingsTests.swift` (o store de UserDefaults vira o codec). */
class AppSettingsTest {
    @Test fun defaults() {
        val settings = AppSettings()
        assertEquals(440.0, settings.tuner.referenceA, 0.0)
        assertEquals("chromatic", settings.tuner.lastInstrument)
        assertEquals(120, settings.metronome.bpm)
        assertEquals("4/4", settings.metronome.timeSignature)
        assertEquals("click", settings.metronome.sound)
        assertEquals("off", settings.metronome.polyrhythm)
        assertEquals(15, settings.metronome.practiceTimerMinutes)
    }

    @Test fun sanitizeClampsBrokenValues() {
        val settings = AppSettings()
        settings.tuner.referenceA = 9000.0
        settings.metronome.bpm = 999
        settings.metronome.subdivision = 0
        settings.metronome.volume = 3.0
        settings.metronome.polyrhythm = "9:1"
        settings.sanitize()
        assertEquals(440.0, settings.tuner.referenceA, 0.0)
        assertEquals(240, settings.metronome.bpm)
        assertEquals(1, settings.metronome.subdivision)
        assertEquals(1.0, settings.metronome.volume, 0.0)
        assertEquals("off", settings.metronome.polyrhythm)
    }

    @Test fun codecRoundTrips() {
        val settings = AppSettings()
        settings.metronome.bpm = 96
        settings.tuner.lastInstrument = "violin"
        val reloaded = SettingsCodec.decode(SettingsCodec.encode(settings))
        assertEquals(96, reloaded.metronome.bpm)
        assertEquals("violin", reloaded.tuner.lastInstrument)
    }

    @Test fun corruptDataFallsBackToDefaults() {
        assertEquals(AppSettings(), SettingsCodec.decode("not json"))
        assertEquals(AppSettings(), SettingsCodec.decode(null))
        assertEquals(AppSettings(), SettingsCodec.decode(""))
    }

    /** O pedal de sustain é preferência, então sobrevive a reabertura. */
    @Test fun sustainDefaultsOffAndRoundTrips() {
        val settings = AppSettings()
        assertFalse(settings.piano.sustain)

        settings.piano.sustain = true
        settings.sanitize()
        val restored = SettingsCodec.decode(SettingsCodec.encode(settings))
        assertTrue(restored.piano.sustain)
    }

    /**
     * Settings gravados antes de o pedal existir têm que decodificar, mantendo
     * tudo que a pessoa escolheu — o contrato de tolerância que no iOS exigiu
     * decoder manual e aqui é `ignoreUnknownKeys` + defaults.
     */
    @Test fun settingsWrittenBeforeSustainKeepEverything() {
        val legacy = """
        {"tuner":{"referenceA":442,"lastInstrument":"guitarStandard"},
         "metronome":{"bpm":132,"timeSignature":"4/4","subdivision":2,"sound":"woodblock","volume":0.5,"polyrhythm":"off","practiceTimerMinutes":20},
         "drums":{"kit":"latin","bpm":90,"volume":0.6,"pattern":{},"padLayout":["crash","hihat-c","hihat-o","tom-low","tom-mid","tom-high","kick","snare","clap"],"reverbEnabled":true,"reverbMix":0.4},
         "studio":{"hz":528,"wave":"triangle","volume":0.25,"binauralEnabled":true,"binauralOffset":8,"reverbEnabled":false,"reverbMix":0.3,"delayEnabled":false,"delayTimeMs":350,"delayFeedback":0.4,"delayMix":0.3},
         "piano":{"voice":"electric-piano","octave":5,"chordRoot":"G","chordQuality":"m7","scaleRoot":"A","scaleType":"minor"}}
        """.trimIndent()
        val decoded = SettingsCodec.decode(legacy)
        assertFalse(decoded.piano.sustain)
        // Nada mais pode se perder no caminho.
        assertEquals("electric-piano", decoded.piano.voice)
        assertEquals(5, decoded.piano.octave)
        assertEquals(442.0, decoded.tuner.referenceA, 0.0)
        assertEquals(132, decoded.metronome.bpm)
        assertEquals("latin", decoded.drums.kit)
        assertEquals(528.0, decoded.studio.hz, 0.0)
    }

    /** Uma seção inteira ausente (build muito mais velho) também sobrevive. */
    @Test fun settingsMissingSectionsFallBackPerSection() {
        val decoded = SettingsCodec.decode("""{"metronome":{"bpm":150}}""")
        assertEquals(150, decoded.metronome.bpm)
        assertEquals(AppSettings.Metronome().volume, decoded.metronome.volume, 0.0)
        assertEquals(AppSettings.Piano().voice, decoded.piano.voice)
        assertEquals(440.0, decoded.tuner.referenceA, 0.0)
    }
}
