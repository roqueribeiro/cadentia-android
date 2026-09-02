package com.levelhard.cadentia.kit

import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port 1:1 do `BackingTrackTests` do iOS. */
class BackingTrackTest {
    /** O catálogo é fonte da verdade DESTE app: inventário + toda referência resolve. */
    @Test fun catalogIsCompleteAndEveryReferenceResolves() {
        assertEquals(48, BackingTrack.all.size)
        assertEquals(
            listOf("rock", "blues", "jazz", "funk", "bossa", "pop", "latin", "electronic"),
            BackingTrack.genres,
        )
        for (template in BackingTrack.all) {
            assertNotNull("${template.id} pattern", DrumPattern.find(template.drumPatternId))
            for (chord in template.chordProgression) {
                assertTrue("${template.id}/$chord", ChordLibrary.all.any { it.id == chord })
            }
        }
    }

    @Test fun buildsPlayableTab() {
        val template = BackingTrack.all.first { it.id == "rock-am-4bar" }
        val tab = template.build(title = "Rock em Am")
        assertEquals(100, tab.transport.bpm)
        assertEquals(listOf("guitar", "bass", "drums"), tab.tracks.map { it.type })
        assertEquals(16, tab.tracks[0].measures.size)
        assertEquals(16, tab.chordMarks.size)
        assertEquals(listOf("Am", "F", "C", "G"), tab.chordMarks.take(4).map { it.displayName })
        assertNotNull(tab.tracks[0].measures[0].strings[1].steps[0]?.v)
        // A bateria carrega o groove rock-basic (bumbo no 0).
        val drums = tab.tracks.first { it.type == "drums" }
        val kickRow = drums.rowsMeta.indexOfFirst { it.padId == "kick" }
        assertEquals(1, drums.measures[0].strings[kickRow].steps[0]?.v)
        // E o conjunto inteiro faz round-trip.
        assertEquals(tab, RostabParser.parse(tab.serialize()))
    }
}

/** Port 1:1 do `BackingTrackGrooveTests.swift`: base é tocável, não cifra. */
class BackingTrackGrooveTest {
    private fun attacks(track: Tablature.Track, measures: Int): Int {
        var total = 0
        for (measure in track.measures.take(measures)) {
            for (line in measure.strings) {
                total += line.steps.count { it != null }
            }
        }
        return total
    }

    @Test fun everyTrackBuildsHarmonyBassAndDrums() {
        for (template in BackingTrack.all) {
            val tab = template.build(template.id)
            assertEquals("${template.id} precisa de harmonia, baixo e bateria", 3, tab.tracks.size)
            assertTrue("${template.id} ficou sem baixo", tab.tracks.any { it.type == "bass" })
            assertTrue("${template.id} ficou sem bateria", tab.tracks.any { it.type == "drums" })
        }
    }

    /** O teste que pega a regressão de verdade: mais de uma batida por compasso. */
    @Test fun harmonyHasAGrooveAndNotOneChordPerMeasure() {
        for (template in BackingTrack.all) {
            val tab = template.build(template.id)
            val count = attacks(tab.tracks[0], 1)
            assertTrue("${template.id}: só $count ataques no primeiro compasso, isso é cifra", count > 6)
        }
    }

    @Test fun bassPlaysTheRootOfEachChord() {
        for (template in BackingTrack.all) {
            val tab = template.build(template.id)
            val bass = tab.tracks.first { it.type == "bass" }
            val notes = attacks(bass, bass.measures.size)
            assertTrue("${template.id}: baixo com $notes notas", notes >= template.measureCount)
        }
    }

    /** Cada gênero soa diferente porque a levada é diferente. */
    @Test fun genresDoNotShareTheSameGroove() {
        val genres = listOf("rock", "blues", "jazz", "funk", "bossa", "latin", "electronic", "pop")
        val shapes = genres.map { genre ->
            BackingGroove.comp(genre).joinToString(",") { "${it.step}:${it.dur}" }
        }
        assertEquals("há gêneros com a mesma levada", genres.size, shapes.toSet().size)
    }

    @Test fun bassFretsAreReachableOnTheNeck() {
        for (root in listOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")) {
            for (degree in listOf(0, 7, 9, 12)) {
                val fret = BackingGroove.bassFret(root, degree)
                assertNotNull("$root sem casa no braço", fret)
                assertTrue(fret!! in 0..23)
            }
        }
    }

    /**
     * O teste que teria pegado o baixo dissonante ANTES do founder ouvir:
     * nota por nota, a ALTURA REAL (corda + casa) contra o acorde do
     * compasso — só fundamental, quinta e sexta, sem terça "por via das
     * dúvidas" (com terça, o bug original teria passado).
     */
    @Test fun bassNotesBelongToTheChordOfTheirMeasure() {
        val semitone = mapOf(
            "C" to 0, "C#" to 1, "D" to 2, "D#" to 3, "E" to 4, "F" to 5, "F#" to 6,
            "G" to 7, "G#" to 8, "A" to 9, "A#" to 10, "B" to 11,
        )
        for (template in BackingTrack.all) {
            val tab = template.build(template.id)
            val bass = tab.tracks.first { it.type == "bass" }
            for ((measureIdx, measure) in bass.measures.withIndex()) {
                if (measureIdx >= template.chordProgression.size) continue
                val chord = ChordLibrary.all.firstOrNull { it.id == template.chordProgression[measureIdx] }
                    ?: continue
                val rootPc = semitone[chord.root] ?: continue
                val allowed = setOf(0, 7, 9).map { (rootPc + it) % 12 }.toSet()
                for (line in measure.strings) {
                    for (cell in line.steps.filterNotNull()) {
                        val string = bass.tuning[line.stringIndex]
                        val pitch = BackingGroove.midiOf(string) + cell.v
                        assertTrue(
                            "${template.id} compasso $measureIdx: nota ${pitch % 12} fora de ${chord.id}",
                            (pitch % 12) in allowed,
                        )
                    }
                }
            }
        }
    }

    /** Toda nota do baixo mora na corda mais GRAVE. */
    @Test fun bassSitsOnTheLowestPitchedString() {
        for (template in BackingTrack.all) {
            val tab = template.build(template.id)
            val bass = tab.tracks.first { it.type == "bass" }
            val lowest = bass.tuning.indices.minByOrNull { BackingGroove.midiOf(bass.tuning[it]) }
            for (measure in bass.measures) {
                for (line in measure.strings) {
                    if (line.steps.any { it != null }) {
                        assertEquals("${template.id}: baixo na corda errada", lowest, line.stringIndex)
                    }
                }
            }
        }
    }
}

/** Port do `StringVoicesTests.swift` (morava no arquivo TablatureTests do iOS). */
class StringVoicesTest {
    private val sampleRate = 44100.0

    @Test fun everyModelRendersWithNaturalDecay() {
        for (model in StringVoices.Model.entries) {
            val buffer = StringVoices.render(
                model, frequency = 110.0, duration = 0.6, velocity = 0.85f,
                gain = 0.7f, sampleRate = sampleRate,
            )
            assertTrue(model.id, !buffer.isEmpty)
            assertTrue("${model.id} silent", buffer.peak > 0.02f)
            assertTrue("${model.id} clips", buffer.peak <= 1f)

            val samples = buffer.summedToMono()
            fun rms(from: Int, to: Int): Float {
                val lo = from.coerceIn(0, samples.size)
                val hi = to.coerceIn(0, samples.size)
                if (hi <= lo) return 0f
                var sum = 0f
                for (i in lo until hi) sum += samples[i] * samples[i]
                return sqrt(sum / (hi - lo))
            }
            val early = rms((0.05 * sampleRate).toInt(), (0.15 * sampleRate).toInt())
            val late = rms((0.35 * sampleRate).toInt(), (0.45 * sampleRate).toInt())
            assertTrue("${model.id} does not decay", late < early)
            val tail = samples.takeLast((0.01 * sampleRate).toInt())
            assertTrue("${model.id} tail rings", (tail.maxOfOrNull { abs(it) } ?: 1f) < 0.05f)
        }
    }

    /**
     * Regressão: linha de atraso dois slots comprida e peso de interpolação
     * invertido tocavam ~14 cents abaixo. Corda tem que CAIR na nota.
     */
    @Test fun everyModelPlaysInTune() {
        for (model in StringVoices.Model.entries) {
            val target = if (model.id.startsWith("bass")) 82.41 else 220.0
            val buffer = StringVoices.render(
                model, frequency = target, duration = 1.0, velocity = 0.85f,
                gain = 0.8f, sampleRate = sampleRate,
            )
            val samples = buffer.summedToMono()
            val start = (0.12 * sampleRate).toInt()
            val end = minOf(start + 16384, samples.size)
            assertTrue("${model.id}: buffer curto demais", end > start + 4096)
            val pitch = YINPitchDetector.detect(samples.copyOfRange(start, end), sampleRate)
            assertNotNull("${model.id}: YIN não rastreou a corda", pitch)
            val cents = MusicNotes.centsOff(detected = pitch!!.frequency, target = target)
            assertTrue("${model.id} is $cents cents off", abs(cents) <= 6)
        }
    }

    @Test fun modelIdsMatchTheWebRegistry() {
        assertEquals(
            listOf(
                "guitar-clean", "guitar-acoustic", "guitar-nylon", "guitar-jazz",
                "guitar-distorted", "bass-fingered", "bass-picked", "bass-slap",
            ),
            StringVoices.Model.entries.map { it.id },
        )
    }
}
