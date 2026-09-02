package com.levelhard.cadentia.kit

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contrato de interop com o tablatureModel.js do web — port 1:1 do
 * `TablatureTests.swift`. A fixture é GERADA pelo próprio modelo do web
 * (scripts/gen-demo-tab.mjs), copiada verbatim: se o web escreve, nós lemos,
 * e a nossa serialização faz round-trip.
 */
class TablatureTest {
    private fun fixture(): String =
        checkNotNull(javaClass.getResourceAsStream("/demo.rostab")) { "demo.rostab não está no classpath" }
            .readBytes().decodeToString()

    @Test fun parsesWebGeneratedFile() {
        val tab = RostabParser.parse(fixture())
        assertEquals("Cadentia Demo", tab.meta.title)
        assertEquals(100, tab.transport.bpm)
        assertEquals(listOf("guitar", "bass", "drums"), tab.tracks.map { it.type })
        assertEquals(6, tab.tracks[0].tuning.size)
        assertEquals(4, tab.tracks[1].tuning.size)
        assertEquals(1, tab.repeatBlocks.size)
        assertEquals(2, tab.repeatBlocks[0].count)
        assertEquals(listOf("Am", "F"), tab.chordMarks.map { it.displayName })
        // O compasso 3 repete duas vezes (setMeasureRepeats do web).
        assertEquals(2, tab.tracks[0].measures[3].repeats)
    }

    @Test fun roundTripIsStable() {
        val first = RostabParser.parse(fixture())
        val second = RostabParser.parse(first.serialize())
        assertEquals(first, second)
    }

    @Test fun cellDurationsAndTriplets() {
        val tab = RostabParser.parse(fixture())
        val guitar = tab.tracks[0]
        // Semínima no compasso 0 passo 4 (dur 4).
        val quarter = guitar.measures[0].strings[2].steps[4]!!
        assertEquals(4, quarter.dur)
        assertEquals(4.0, quarter.effectiveDuration, 0.0)
        // Quiáltera de semínima no compasso 2 passo 8 → 4 × 2/3.
        val triplet = guitar.measures[2].strings[2].steps[8]!!
        assertEquals(3, triplet.tup)
        assertTrue(abs(triplet.effectiveDuration - 8.0 / 3.0) < 0.0001)
        // Palm-mute sobrevive.
        val muted = guitar.measures[1].strings[2].steps[8]!!
        assertEquals(true, muted.articulations["pm"])
    }

    @Test fun midiMapping() {
        val tab = RostabParser.parse(fixture())
        val guitar = tab.tracks[0]
        // Corda 0 = E2 grave (midi 40); casa 5 = A2 (midi 45).
        assertEquals(45, guitar.midi(rowIdx = 0, value = 5))
        val drums = tab.tracks[2]
        assertNull(drums.midi(rowIdx = 0, value = 1))
        assertEquals("kick", drums.padId(0))
        assertEquals("hihat-c", drums.padId(2))
    }

    @Test fun playbackPlanExpandsRepeats() {
        val tab = RostabParser.parse(fixture())
        val guitar = tab.tracks[0]
        val plan = tab.playbackPlan(guitar)
        // Bloco (compassos 0-1) × 2 = 4 compassos, compasso 2 uma vez,
        // compasso 3 × 2: (2×2 + 1 + 2) = 7 × 16 = 112 entradas, sem infinito.
        assertEquals(7 * 16, plan.entries.size)
        assertNull(plan.infiniteFrom)
        assertEquals(Tablature.PlanEntry(0, 0), plan.entries[0])
        assertEquals(Tablature.PlanEntry(0, 0), plan.entries[32]) // 2ª passada do bloco
        assertEquals(Tablature.PlanEntry(2, 0), plan.entries[64])
        assertEquals(Tablature.PlanEntry(3, 0), plan.entries[80])
        assertEquals(Tablature.PlanEntry(3, 0), plan.entries[96]) // repetição do compasso
        assertNull(plan.entryAtBeat(112)) // passou do fim
    }

    @Test fun infiniteMeasureLoopsModularly() {
        val tab = Tablature()
        val track = Tablature.Track()
        track.type = "guitar"
        track.measures = mutableListOf(
            Tablature.Measure(stepsPerMeasure = 4, repeats = 1),
            Tablature.Measure(stepsPerMeasure = 4, repeats = -1),
            Tablature.Measure(stepsPerMeasure = 4, repeats = 1),
        )
        tab.tracks = mutableListOf(track)
        val plan = tab.playbackPlan(track)
        // Compasso 0 (4) + uma passada do infinito (4); nada depois.
        assertEquals(8, plan.entries.size)
        assertEquals(4, plan.infiniteFrom)
        assertEquals(8, plan.infiniteTo)
        assertEquals(Tablature.PlanEntry(1, 1), plan.entryAtBeat(9))
        assertEquals(1, plan.entryAtBeat(1005)?.measureIdx)
    }

    @Test fun rejectsWrongFormatAndVersion() {
        assertThrows(RostabParseException::class.java) {
            RostabParser.parse("""{"format":"other","version":2,"tracks":[{}]}""")
        }
        assertThrows(RostabParseException::class.java) {
            RostabParser.parse("""{"format":"rostab","version":1,"tracks":[{}]}""")
        }
    }
}

/** Port 1:1 do `TablatureEditTests.swift`. */
class TablatureEditTest {
    @Test fun createEmptyMatchesWebDefaults() {
        val tab = Tablature.createEmpty()
        assertEquals(1, tab.tracks.size)
        val guitar = tab.tracks[0]
        assertEquals("guitar", guitar.type)
        assertEquals(listOf("E", "A", "D", "G", "B", "E"), guitar.tuning.map { it.name })
        assertEquals(4, guitar.measures.size)
        assertEquals(16, guitar.measures[0].stepsPerMeasure)
        assertEquals(6, guitar.rowCount)
    }

    @Test fun setAndClearFretPreservingRhythm() {
        val tab = Tablature.createEmpty()
        tab.setFret(trackIdx = 0, absoluteCol = 20, rowIdx = 2, value = 7, dur = 4)
        // Coluna 20 = compasso 1, passo 4.
        val cell = tab.tracks[0].measures[1].strings[2].steps[4]
        assertEquals(7, cell?.v)
        assertEquals(4, cell?.dur)
        // Mudar o valor mantém a figura.
        tab.setFret(trackIdx = 0, absoluteCol = 20, rowIdx = 2, value = 9)
        assertEquals(4, tab.tracks[0].measures[1].strings[2].steps[4]?.dur)
        tab.clearFret(trackIdx = 0, absoluteCol = 20, rowIdx = 2)
        assertNull(tab.tracks[0].measures[1].strings[2].steps[4])
    }

    @Test fun setDurationIsNoOpOnEmptyCells() {
        val tab = Tablature.createEmpty()
        tab.setDuration(trackIdx = 0, absoluteCol = 0, rowIdx = 0, dur = 8)
        assertNull(tab.tracks[0].measures[0].strings[0].steps[0])
    }

    @Test fun measuresStayInLockstepAcrossTracks() {
        val tab = Tablature.createEmpty()
        tab.addTrack("drums")
        tab.addMeasure()
        assertTrue(tab.tracks.all { it.measures.size == 5 })
        tab.removeMeasure(4)
        assertTrue(tab.tracks.all { it.measures.size == 4 })
        // Não remove o último compasso nem a última trilha.
        repeat(10) { tab.removeMeasure(0) }
        assertEquals(1, tab.tracks[0].measures.size)
        tab.removeTrack(1)
        tab.removeTrack(0)
        assertEquals(1, tab.tracks.size)
    }

    @Test fun removeMeasureShiftsBlocksAndChords() {
        val tab = Tablature.createEmpty()
        tab.repeatBlocks = mutableListOf(Tablature.RepeatBlock("b", 2, 3, 2))
        tab.addChordMark(measureIdx = 0, col = 0, chordId = "C", displayName = "C")
        tab.addChordMark(measureIdx = 3, col = 0, chordId = "G", displayName = "G")
        tab.removeMeasure(0)
        assertEquals(1, tab.repeatBlocks[0].startIdx)
        assertEquals(2, tab.repeatBlocks[0].endIdx)
        assertEquals(listOf("G"), tab.chordMarks.map { it.displayName })
        assertEquals(2, tab.chordMarks[0].measureIdx)
    }

    @Test fun repeatBlocksReplaceOverlapsAndFeedThePlan() {
        val tab = Tablature.createEmpty() // 4 compassos
        tab.addRepeatBlock(startIdx = 0, endIdx = 1, count = 2)
        tab.addRepeatBlock(startIdx = 1, endIdx = 2, count = 3) // sobrepõe → substitui
        assertEquals(1, tab.repeatBlocks.size)
        assertEquals(1, tab.repeatBlocks[0].startIdx)
        val plan = tab.playbackPlan(tab.tracks[0])
        // m0 + (m1+m2)×3 + m3 = 8 compassos × 16 passos.
        assertEquals(8 * 16, plan.entries.size)
        tab.removeRepeatBlock(tab.repeatBlocks[0].id)
        assertTrue(tab.repeatBlocks.isEmpty())
    }

    @Test fun drumTrackHasWebRows() {
        val track = Tablature.makeTrack("drums")
        assertEquals(
            listOf("kick", "snare", "hihat-c", "hihat-o", "clap", "crash", "ride", "rim"),
            track.rowsMeta.map { it.padId },
        )
        assertEquals("acoustic", track.kitId)
    }

    @Test fun insertChordAppliesShapeSkippingMuted() {
        val tab = Tablature.createEmpty()
        val chord = ChordLibrary.find("C", "maj")!! // [-1,3,2,0,1,0]
        tab.insertChord(chord, trackIdx = 0, measureIdx = 1, dur = 16)
        val measure = tab.tracks[0].measures[1]
        assertNull(measure.strings[0].steps[0]) // E grave abafada
        assertEquals(3, measure.strings[1].steps[0]?.v)
        assertEquals(0, measure.strings[3].steps[0]?.v) // corda solta é nota
        assertEquals(16, measure.strings[1].steps[0]?.dur)
    }
}

/** Port 1:1 do `TabRowDisplayTests.swift`. */
class TabRowDisplayTest {
    @Test fun guitarAndBassFlipDrumsDoNot() {
        assertEquals(5, TabRowDisplay.displayRow(0, rowCount = 6, trackType = "guitar"))
        assertEquals(0, TabRowDisplay.displayRow(5, rowCount = 6, trackType = "guitar"))
        assertEquals(3, TabRowDisplay.displayRow(0, rowCount = 4, trackType = "bass"))
        assertEquals(2, TabRowDisplay.displayRow(2, rowCount = 8, trackType = "drums"))
    }

    /** A função é o inverso dela mesma: desenhar e converter um toque usam a mesma conta. */
    @Test fun mappingIsItsOwnInverse() {
        for (type in listOf("guitar", "bass", "drums")) {
            for (count in listOf(4, 6, 8)) {
                for (row in 0 until count) {
                    val there = TabRowDisplay.displayRow(row, count, type)
                    assertEquals(row, TabRowDisplay.displayRow(there, count, type))
                }
            }
        }
    }
}
