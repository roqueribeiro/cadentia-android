package com.levelhard.cadentia.kit.cordas

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Acorde por gesto, escolhido por quem toca — port do `HandChordAssignmentTests`. */
class HandChordAssignmentTest {
    private val fallback = CordaChords.sixStringSet

    @Test
    fun emptyFallsBackToTheDefault() {
        val resolved = HandChordAssignment().resolved(fallback)
        assertEquals(HandChordMapping.shapes.size, resolved.size)
        assertEquals(fallback.take(HandChordMapping.shapes.size), resolved)
    }

    @Test
    fun choicesWinAndTheRestStays() {
        val assignment = HandChordAssignment()
        assignment.set("Am", 0)
        assignment.set("F", 1)
        val resolved = assignment.resolved(fallback)
        assertEquals("Am", resolved[0])
        assertEquals("F", resolved[1])
        assertEquals(fallback[2], resolved[2])
    }

    @Test
    fun alwaysOneChordPerShape() {
        val resolved = HandChordAssignment().resolved(listOf("D", "G", "A"))
        assertEquals(HandChordMapping.shapes.size, resolved.size)
        assertTrue(resolved.all { it.isNotEmpty() })
    }

    @Test
    fun outOfRangeIsIgnored() {
        val assignment = HandChordAssignment()
        assignment.set("Bm", -1)
        assignment.set("Bm", 99)
        assertTrue(assignment.chords.all { it.isEmpty() })
    }

    @Test
    fun survivesEncoding() {
        val assignment = HandChordAssignment()
        assignment.set("Cmaj7", 4)
        val data = Json.encodeToString(mapOf("violao" to assignment))
        val back = Json.decodeFromString<Map<String, HandChordAssignment>>(data)
        assertEquals(assignment, back["violao"])
    }

    @Test
    fun violaDefaultsArePlayable() {
        for (name in CordaChords.violaSet) {
            assertNotNull("$name não tem forma na viola", CordaChords.frets(name, CordaInstrument.viola))
        }
    }
}

/** Acorde contando os dedos das duas mãos — port do `TwoHandChordsTests`. */
class TwoHandChordsTest {
    @Test
    fun everyCombinationIsItsOwnSlot() {
        val seen = HashSet<Int>()
        for (left in 0 until TwoHandChords.COUNTS) {
            for (right in 0 until TwoHandChords.COUNTS) {
                assertTrue(seen.add(TwoHandChords.slot(left, right)))
            }
        }
        assertEquals(TwoHandChords.SLOTS, seen.size)
    }

    @Test
    fun unsetCombinationsStaySilent() {
        assertNull(TwoHandChords().chord(3, 2))
        val some = TwoHandChords()
        some.set("Am", 1, 0)
        assertEquals("Am", some.chord(1, 0))
        assertNull(some.chord(2, 0))
    }

    @Test
    fun outOfRangeIsClamped() {
        val chords = TwoHandChords()
        chords.set("G", 99, -4)
        assertEquals("G", chords.chord(TwoHandChords.COUNTS - 1, 0))
        assertNull(chords.chord(0, 0))
    }

    @Test
    fun theDefaultStartsWithTheRightHandClosed() {
        val chords = TwoHandChords.standard(CordaChords.sixStringSet)
        for (left in 0 until TwoHandChords.COUNTS) {
            assertEquals(CordaChords.sixStringSet[left], chords.chord(left, 0))
        }
        assertEquals(CordaChords.sixStringSet[5], chords.chord(0, 1))
    }

    @Test
    fun aShortDefaultLeavesTheRestEmpty() {
        val chords = TwoHandChords.standard(listOf("D", "G", "A"))
        assertEquals("D", chords.chord(0, 0))
        assertEquals("A", chords.chord(2, 0))
        assertNull(chords.chord(3, 0))
        assertNull(chords.chord(4, 4))
    }

    @Test
    fun survivesEncoding() {
        val chords = TwoHandChords()
        chords.set("Bm", 2, 3)
        val data = Json.encodeToString(mapOf("violao" to chords))
        val back = Json.decodeFromString<Map<String, TwoHandChords>>(data)
        assertEquals("Bm", back["violao"]?.chord(2, 3))
    }

    @Test
    fun theGridMatchesWhatTheDetectorReads() {
        assertEquals(HandChordMapping.fingerCount(0b1111) + 1, TwoHandChords.COUNTS)
    }

    /** A contagem só troca quando fica parada 190 ms, e só na TROCA. */
    @Test
    fun theConfirmerWaitsAndReportsOnlyChanges() {
        val confirmer = HandCountConfirmer()
        assertNull(confirmer.update(3, 0, 0.0))
        assertNull("ainda não segurou", confirmer.update(3, 0, 0.1))
        assertEquals(3 to 0, confirmer.update(3, 0, 0.2))
        assertNull("repetir o mesmo par não reaplica", confirmer.update(3, 0, 0.3))
        assertNull("passou por dois no caminho", confirmer.update(2, 0, 0.35))
        assertNull(confirmer.update(1, 0, 0.4))
        assertEquals(1 to 0, confirmer.update(1, 0, 0.6))
        assertNull("mão sumiu: nada", confirmer.update(null, 0, 0.7))
    }
}

/** O braço sem a faixa da batida — port do `HandsFreeNeckTests`. */
class HandsFreeNeckTest {
    private val screen = Size(393.0, 700.0)

    private fun layout(free: Boolean, frets: Int = 5) =
        FretboardLayout(size = screen, instrument = CordaInstrument.violao, visibleFrets = frets, handsFree = free)

    @Test
    fun theNeckReachesTheBridge() {
        val free = layout(true)
        val strummed = layout(false)
        assertTrue(free.neckHeight > strummed.neckHeight)
        assertEquals(Math.round(700 - FretboardLayout.BRIDGE_HEIGHT).toDouble(), free.neckHeight, 0.0)
        assertTrue(free.neckHeight / strummed.neckHeight > 1.6)
    }

    @Test
    fun theBridgeStaysOnScreen() {
        val free = layout(true)
        assertEquals(free.neckHeight, free.strumBottom, 0.0)
        assertTrue(free.bridgeRect.maxY <= 700)
        assertTrue(free.bridgeRect.height > 0)
    }

    @Test
    fun theStrumBandIsGone() {
        assertEquals(0.0, layout(true).strumBottom - layout(true).neckHeight, 0.0)
        assertTrue(layout(false).strumBottom - layout(false).neckHeight > 0)
    }

    @Test
    fun moreFretsFit() {
        assertEquals(8, layout(false, frets = 12).visibleFrets)
        val budget = FretboardLayout.fretBudget(layout(true).fretSpanHeight)
        assertTrue(budget > 8)
        assertEquals(budget, layout(true, frets = 99).visibleFrets)
        assertEquals(3, layout(true, frets = 1).visibleFrets)
    }

    @Test
    fun theBudgetFollowsTheDevice() {
        val small = FretboardLayout.fretBudget(430.0)
        val large = FretboardLayout.fretBudget(760.0)
        assertTrue(small < large)
        assertTrue(small >= 3)
    }

    @Test
    fun theTightestFretIsStillTappable() {
        val free = layout(true, frets = 99)
        val tightest = free.fretY.zipWithNext { a, b -> b - a }.minOrNull() ?: 0.0
        assertTrue("a casa mais apertada tem $tightest pontos", tightest >= 44)
    }

    /** O defeito que o founder relatou: corda solta impossível de tocar sem mão direita. */
    @Test
    fun openStringsAreReachable() {
        val free = layout(true)
        assertTrue(free.openBandHeight > 0)
        assertEquals(0, free.fretAt(free.openBandHeight / 2))
        assertEquals(1, free.fretAt(free.openBandHeight + 2))
        assertEquals(0.0, layout(false).openBandHeight, 0.0)
    }

    @Test
    fun theOpenBandIgnoresTheShift() {
        val moved = FretboardLayout(size = screen, instrument = CordaInstrument.violao, visibleFrets = 5, shift = 5, handsFree = true)
        assertEquals(0, moved.fretAt(moved.openBandHeight / 2))
        assertEquals(6, moved.fretAt(moved.openBandHeight + 2))
    }

    @Test
    fun thePadsKeepTheirLayout() {
        val pads = FretboardLayout(size = screen, instrument = CordaInstrument.violao, hasRail = false, padCount = 15, handsFree = true)
        assertFalse(pads.handsFree)
        assertTrue(pads.strumBottom > pads.neckHeight)
    }
}
