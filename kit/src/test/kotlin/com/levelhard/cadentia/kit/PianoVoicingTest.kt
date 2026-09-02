package com.levelhard.cadentia.kit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O teclado de estudo desenha duas oitavas a partir de C4; a biblioteca escreve
 * os acordes na altura em que soam. Port do `PianoVoicingTests`: percorre a
 * biblioteca inteira e falha com o comportamento antigo (sem deslocamento).
 */
class PianoVoicingTest {
    @Test
    fun everyChordFitsWithoutLosingANote() {
        val offenders = ArrayList<String>()
        for (chord in ChordLibrary.all) {
            val fitted = PianoVoicing.fitted(chord.pianoNotes)
            assertEquals("${chord.id} perdeu nota", chord.pianoNotes.size, fitted.size)
            if (!PianoVoicing.fits(fitted)) offenders += "${chord.id} $fitted"
        }
        assertTrue("não coube em C4–B5: ${offenders.joinToString()}", offenders.isEmpty())
    }

    /** O que o usuário via: G maior acendendo uma tecla só. */
    @Test
    fun theRootIsAlwaysDrawn() {
        for (chord in ChordLibrary.all) {
            val fitted = PianoVoicing.fitted(chord.pianoNotes)
            val rootClass = MusicNotes.pitchClass(chord.root)
            val classes = fitted.mapNotNull { MusicNotes.pitchClass(it.dropLast(1)) }
            assertTrue("${chord.id}: a tônica ${chord.root} não está em $fitted", rootClass != null && rootClass in classes)
        }
    }

    @Test
    fun shapeIsPreserved() {
        val original = listOf("G3", "B3", "D4")
        val fitted = PianoVoicing.fitted(original)
        assertEquals(listOf("G4", "B4", "D5"), fitted)
        fun semitones(notes: List<String>): List<Int> {
            val midi = notes.mapNotNull { note ->
                val octave = note.last().digitToIntOrNull() ?: return@mapNotNull null
                MusicNotes.noteToMidi(note.dropLast(1), octave)
            }
            return midi.zipWithNext { a, b -> b - a }
        }
        assertEquals(semitones(original), semitones(fitted))
    }

    @Test
    fun leavesWhatAlreadyFitsAlone() {
        assertEquals(listOf("C4", "E4", "G4"), PianoVoicing.fitted(listOf("C4", "E4", "G4")))
        val scale = listOf("C", "D", "E", "F", "G", "A", "B")
        assertEquals(scale, PianoVoicing.fitted(scale))
    }
}
