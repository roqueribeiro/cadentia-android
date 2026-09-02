package com.levelhard.cadentia.kit

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Casos portados 1:1 do `MusicNotesTests.swift` do CadentiaKit (que segue o
 * `notes.spec.js` do roqueos-front) — os três engines concordam nas conversões
 * e nos cents.
 */
class MusicNotesTest {
    // midi ↔ frequency

    @Test fun a4Is440() {
        assertEquals(440.0, MusicNotes.midiToFrequency(69), 0.0)
    }

    @Test fun octaveDoublesFrequency() {
        assertTrue(abs(MusicNotes.midiToFrequency(81) - 880) < 0.0001)
        assertTrue(abs(MusicNotes.midiToFrequency(57) - 220) < 0.0001)
    }

    @Test fun referenceAScalesEverything() {
        assertEquals(442.0, MusicNotes.midiToFrequency(69, referenceA = 442.0), 0.0)
    }

    // noteToMidi

    @Test fun namedNotes() {
        assertEquals(69, MusicNotes.noteToMidi("A", 4))
        assertEquals(60, MusicNotes.noteToMidi("C", 4))
        assertEquals(58, MusicNotes.noteToMidi("Bb", 3))
    }

    @Test fun normalizesCaseAndUnicodeAccidentals() {
        assertEquals(58, MusicNotes.noteToMidi("bb", 3))
        assertEquals(61, MusicNotes.noteToMidi("c♯", 4))
        assertEquals(63, MusicNotes.noteToMidi("E♭", 4))
    }

    @Test fun rejectsGarbage() {
        assertNull(MusicNotes.noteToMidi("H", 4))
        assertNull(MusicNotes.noteToMidi("", 4))
    }

    // noteFromFrequency

    @Test fun exactA4() {
        val note = MusicNotes.noteFromFrequency(440.0)!!
        assertEquals("A", note.name)
        assertEquals(4, note.octave)
        assertEquals(69, note.midi)
        assertEquals(0, note.cents)
    }

    @Test fun sharpDetunedNoteReportsCents() {
        // 445 Hz ≈ A4 +19,56 cents
        val note = MusicNotes.noteFromFrequency(445.0)!!
        assertEquals("A", note.name)
        assertEquals(20, note.cents)
    }

    @Test fun lowE2GuitarString() {
        val hz = MusicNotes.frequency("E", 2)!!
        val note = MusicNotes.noteFromFrequency(hz)!!
        assertEquals("E", note.name)
        assertEquals(2, note.octave)
        assertEquals(0, note.cents)
    }

    @Test fun rejectsNonPositive() {
        assertNull(MusicNotes.noteFromFrequency(0.0))
        assertNull(MusicNotes.noteFromFrequency(-10.0))
    }

    // centsOff

    @Test fun centsOffSignConvention() {
        assertEquals(0, MusicNotes.centsOff(detected = 440.0, target = 440.0))
        // Um semitom agudo = +100 cents.
        assertEquals(100, MusicNotes.centsOff(detected = 466.16, target = 440.0))
        assertEquals(-100, MusicNotes.centsOff(detected = 415.30, target = 440.0))
        assertEquals(0, MusicNotes.centsOff(detected = 0.0, target = 440.0))
    }

    // instrument presets

    @Test fun presetIdsMatchWebRegistry() {
        assertEquals(
            listOf(
                "chromatic", "guitar-standard", "guitar-drop-d", "bass-4",
                "bass-5", "ukulele", "cavaquinho", "violin",
            ),
            InstrumentPreset.all.map { it.id },
        )
    }

    @Test fun findFallsBackToChromatic() {
        assertEquals("chromatic", InstrumentPreset.find("nope").id)
        assertEquals("chromatic", InstrumentPreset.find(null).id)
        assertEquals("violin", InstrumentPreset.find("violin").id)
    }

    @Test fun nearestStringPicksLogClosest() {
        val guitar = InstrumentPreset.find("guitar-standard")
        // 84 Hz fica mais perto de E2 (82,41).
        val low = guitar.nearestString(84.0)!!
        assertEquals("E", low.note.name)
        assertEquals(2, low.note.octave)
        // 328 Hz fica mais perto de E4 (329,63), não de B3 (246,94).
        val high = guitar.nearestString(328.0)!!
        assertEquals("E", high.note.name)
        assertEquals(4, high.note.octave)
    }

    @Test fun chromaticHasNoTargetString() {
        assertNull(InstrumentPreset.find("chromatic").nearestString(440.0))
    }
}
