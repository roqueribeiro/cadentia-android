package com.levelhard.cadentia.kit

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port 1:1 do `InstrumentVoicesTests.swift`. */
class InstrumentVoicesTest {
    private val sampleRate = 44100.0

    /** Registro natural por voz: julgar um baixo em 220 Hz diz mais do teste que do som. */
    private fun testFrequency(voice: InstrumentVoice): Double = when (voice) {
        InstrumentVoice.BassFingered, InstrumentVoice.BassPicked, InstrumentVoice.BassSlap -> 82.41
        InstrumentVoice.Cello -> 130.81
        InstrumentVoice.Flute, InstrumentVoice.Violin -> 587.33
        InstrumentVoice.Marimba, InstrumentVoice.Vibraphone -> 440.0
        else -> 220.0
    }

    @Test fun rosterMatchesTheWebRegistry() {
        // Estes ids viajam dentro de .rostab escritos pelo RoqueOS web.
        // Renomear um aqui quebra em silêncio a reprodução de arquivo antigo.
        assertEquals(
            setOf(
                "sine", "electric-piano", "acoustic-piano", "organ", "lead",
                "guitar-clean", "guitar-acoustic", "guitar-nylon", "guitar-jazz",
                "guitar-distorted", "bass-fingered", "bass-picked", "bass-slap",
                "vibraphone", "marimba", "cello", "violin", "flute", "saxophone",
                "strings", "brass",
            ),
            InstrumentVoice.entries.map { it.id }.toSet(),
        )
    }

    @Test fun everyVoiceRendersAudibleAndBounded() {
        for (voice in InstrumentVoice.entries) {
            val note = InstrumentSynth.render(
                voice, testFrequency(voice), duration = 1.0,
                velocity = 0.85f, gain = 1f, sampleRate = sampleRate,
            )
            assertTrue("${voice.id} empty", !note.isEmpty)
            assertTrue("${voice.id} too quiet at ${note.peak}", note.peak > 0.1f)
            assertTrue("${voice.id} clips at ${note.peak}", note.peak < 0.95f)
        }
    }

    /**
     * Toda voz tem que soar a nota escrita. Órgão, lead e baixo de palheta
     * já saíram uma oitava fora durante a reescrita no iOS: sub-oitava forte
     * (ou comb reforçando o segundo harmônico) faz o ouvido seguir o pitch
     * errado.
     */
    @Test fun everyVoicePlaysTheWrittenPitch() {
        for (voice in InstrumentVoice.entries) {
            val target = testFrequency(voice)
            val note = InstrumentSynth.render(
                voice, target, duration = 1.4,
                velocity = 0.85f, gain = 1f, sampleRate = sampleRate,
            )
            val samples = note.summedToMono()
            val start = minOf((0.28 * sampleRate).toInt(), maxOf(0, samples.size - 16384))
            val end = minOf(start + 16384, samples.size)
            assertTrue("${voice.id}: buffer curto demais para analisar", end > start + 4096)
            val pitch = YINPitchDetector.detect(samples.copyOfRange(start, end), sampleRate)
            assertNotNull("${voice.id}: sem pitch detectável", pitch)
            val cents = MusicNotes.centsOff(detected = pitch!!.frequency, target = target)
            assertTrue("${voice.id} is $cents cents off", abs(cents) <= 12)
        }
    }

    @Test fun everyVoiceReleasesToSilence() {
        for (voice in InstrumentVoice.entries) {
            val note = InstrumentSynth.render(
                voice, testFrequency(voice), duration = 0.6,
                velocity = 0.85f, gain = 1f, sampleRate = sampleRate,
            ).summedToMono()
            val tail = note.takeLast((0.01 * sampleRate).toInt())
            assertTrue("${voice.id} tail rings", (tail.maxOfOrNull { abs(it) } ?: 1f) < 0.08f)
        }
    }

    @Test fun louderNotesAreBrighter() {
        // O piano acústico inclina o espectro com a velocity: a diferença
        // entre um piano e um sampler preso numa camada.
        fun brightness(velocity: Float): Double {
            val note = InstrumentSynth.render(
                InstrumentVoice.AcousticPiano, 220.0, duration = 0.8,
                velocity = velocity, gain = 1f, sampleRate = sampleRate,
            ).summedToMono()
            var total = 0.0
            var high = 0.0
            var previous = 0f
            for (sample in note.take(16384)) {
                total += (sample * sample).toDouble()
                val difference = sample - previous
                high += (difference * difference).toDouble()
                previous = sample
            }
            return if (total > 0) high / total else 0.0
        }
        assertTrue(brightness(1.0f) > brightness(0.2f) * 1.1)
    }

    @Test fun pluckedVoicesRouteToTheStringModel() {
        assertEquals(StringVoices.Model.GuitarJazz, InstrumentVoice.GuitarJazz.stringModel)
        assertEquals(StringVoices.Model.BassSlap, InstrumentVoice.BassSlap.stringModel)
        assertNull(InstrumentVoice.AcousticPiano.stringModel)
        assertNull(InstrumentVoice.Organ.stringModel)
    }

    @Test fun trackTypeDefaultsMatchTheWeb() {
        assertEquals(InstrumentVoice.BassFingered, InstrumentVoice.forTrackType("bass").first())
        assertEquals(InstrumentVoice.GuitarClean, InstrumentVoice.forTrackType("guitar").first())
        assertEquals(InstrumentVoice.AcousticPiano, InstrumentVoice.forTrackType("keys").first())
    }

    @Test fun nameKeysPointAtRealTranslationDomains() {
        for (voice in InstrumentVoice.entries) {
            val key = voice.nameKey
            assertTrue(
                "${voice.id} has an unroutable i18n key: $key",
                key.startsWith("music.piano.voices.") || key.startsWith("tablature.voices."),
            )
        }
    }
}

/** Contrato dos 77 acordes gerados do iOS/web. */
class ChordLibraryTest {
    @Test fun seventySevenChordsWithUniqueIds() {
        assertEquals(77, ChordLibrary.all.size)
        assertEquals(77, ChordLibrary.all.map { it.id }.toSet().size)
    }

    @Test fun elevenQualitiesInWebOrder() {
        assertEquals(
            listOf("maj", "m", "7", "maj7", "m7", "sus2", "sus4", "dim", "aug", "m7b5", "dim7"),
            ChordLibrary.qualityIds,
        )
        assertEquals(listOf("C", "D", "E", "F", "G", "A", "B"), ChordLibrary.roots)
    }

    @Test fun everyChordIsWellFormed() {
        for (chord in ChordLibrary.all) {
            assertTrue("${chord.id}: root fora", chord.root in ChordLibrary.roots)
            assertTrue("${chord.id}: quality fora", chord.quality in ChordLibrary.qualityIds)
            assertEquals("${chord.id}: frets", 6, chord.guitarFrets.size)
            assertTrue("${chord.id}: sem notas", chord.notes.isNotEmpty())
            assertEquals("${chord.id}: piano != notas", chord.notes.size, chord.pianoNotes.size)
            assertTrue(
                "${chord.id}: casa fora do braço",
                chord.guitarFrets.all { it in -1..15 },
            )
            // Toda nota de piano resolve numa frequência de verdade.
            for (pianoNote in chord.pianoNotes) {
                val name = pianoNote.dropLast(1)
                val octave = pianoNote.last().digitToInt()
                assertNotNull("${chord.id}: $pianoNote inválida", MusicNotes.frequency(name, octave))
            }
        }
    }

    @Test fun findLooksUpById() {
        assertEquals("Am", ChordLibrary.find("Am")?.id)
        assertEquals(listOf("A", "C", "E"), ChordLibrary.find("Am")?.notes)
        assertNull(ChordLibrary.find("nope"))
        assertNull(ChordLibrary.find(null))
    }
}
