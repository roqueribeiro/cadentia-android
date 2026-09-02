package com.levelhard.cadentia.kit.cordas

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A batida nas cordas paradas — port do `FixedStringsStrummerTests`. O defeito
 * que este desenho existe para matar: as cordas vinham de um violão desenhado e
 * escalado pelo rastreamento, então mudavam de lugar sozinhas.
 */
class FixedStringsStrummerTest {
    private fun run(strummer: FixedStringsStrummer, path: List<Pair<Double, Double>>, strings: Int = 6, height: Double = 852.0): List<FixedStringsStrummer.Pluck> {
        val out = ArrayList<FixedStringsStrummer.Pluck>()
        for ((y, t) in path) out += strummer.update(y * height, height, t, strings)
        return out
    }

    @Test
    fun stringsNeverMove() {
        val strummer = FixedStringsStrummer()
        fun near(a: Double, b: Double) = abs(a - b) < 1e-9
        assertTrue(near(strummer.stringY(0, 6), strummer.top))
        assertTrue(near(strummer.stringY(5, 6), strummer.bottom))
        assertTrue(near(strummer.stringY(0, 4), strummer.top))
        assertTrue(near(strummer.stringY(3, 4), strummer.bottom))
        val ys = (0 until 6).map { strummer.stringY(it, 6) }
        assertEquals(ys.sorted(), ys)
        assertEquals(6, ys.toSet().size)
    }

    @Test
    fun downStrumHitsEveryStringInOrder() {
        val strummer = FixedStringsStrummer()
        val plucks = run(strummer, listOf(0.45 to 0.0, 0.90 to 0.12))
        assertEquals(listOf(0, 1, 2, 3, 4, 5), plucks.map { it.string })
        assertEquals(0.0, plucks.first().delay, 0.0)
        assertTrue(plucks.last().delay > 0)
        assertTrue(plucks.zipWithNext().all { it.first.delay < it.second.delay })
    }

    @Test
    fun upStrumReversesTheOrder() {
        val strummer = FixedStringsStrummer()
        val plucks = run(strummer, listOf(0.90 to 0.0, 0.45 to 0.12))
        assertEquals(listOf(5, 4, 3, 2, 1, 0), plucks.map { it.string })
    }

    @Test
    fun aStillHandIsSilent() {
        val strummer = FixedStringsStrummer()
        val path = (0..40).map { step -> (0.60 + (if (step % 2 == 0) 0.0005 else -0.0005)) to step / 30.0 }
        assertTrue(run(strummer, path).isEmpty())
    }

    @Test
    fun losingTheHandDoesNotFakeAStrum() {
        val strummer = FixedStringsStrummer()
        strummer.update(0.45 * 852, 852.0, 0.0, 6)
        strummer.update(null, 852.0, 0.1, 6)
        val plucks = strummer.update(0.95 * 852, 852.0, 0.2, 6)
        assertTrue(plucks.isEmpty())
    }

    @Test
    fun harderIsLouderButBounded() {
        val slow = FixedStringsStrummer()
        val fast = FixedStringsStrummer()
        val calm = run(slow, listOf(0.45 to 0.0, 0.90 to 0.30))
        val hard = run(fast, listOf(0.45 to 0.0, 0.90 to 0.05))
        assertTrue(calm.first().velocity < hard.first().velocity)
        assertTrue(hard.all { it.velocity <= 1 })
        assertTrue(calm.all { it.velocity > 0 })
        val ratio = hard.first().velocity / maxOf(calm.first().velocity, 0.0001)
        assertTrue("só ${ratio}x entre a passada lenta e a forte", ratio > 2)
    }

    @Test
    fun aPartialSweepHitsOnlyWhatItCrossed() {
        val strummer = FixedStringsStrummer()
        val mid = strummer.stringY(2, 6)
        val plucks = run(strummer, listOf((strummer.top - 0.02) to 0.0, (mid + 0.001) to 0.08))
        assertEquals(listOf(0, 1, 2), plucks.map { it.string })
    }

    /** A regressão que este teste existe para impedir: a unidade é PONTO de tela. */
    @Test
    fun pointsFromTheTrackerActuallyStrum() {
        val strummer = FixedStringsStrummer()
        val plucks = run(strummer, listOf(0.45 to 0.0, 0.90 to 0.12), height = 852.0)
        assertEquals("a mão atravessou a faixa inteira e saiu ${plucks.size} corda(s)", listOf(0, 1, 2, 3, 4, 5), plucks.map { it.string })
    }

    @Test
    fun fractionsWhereItWantsPointsStaySilent() {
        val strummer = FixedStringsStrummer()
        strummer.update(0.45, 852.0, 0.0, 6)
        val plucks = strummer.update(0.90, 852.0, 0.12, 6)
        assertTrue(plucks.isEmpty())
    }

    @Test
    fun fourStringsFillTheSameBand() {
        val strummer = FixedStringsStrummer()
        val plucks = run(strummer, listOf(0.45 to 0.0, 0.90 to 0.12), strings = 4)
        assertEquals(listOf(0, 1, 2, 3), plucks.map { it.string })
    }
}

/** Chacoalhar a mão para fazer levada — port do `ShakeStrummerTests`. */
class ShakeStrummerTest {
    private fun run(strummer: FixedStringsStrummer, path: List<Pair<Double, Double>>, height: Double = 852.0): List<FixedStringsStrummer.Pluck> {
        val out = ArrayList<FixedStringsStrummer.Pluck>()
        for ((y, t) in path) out += strummer.update(y * height, height, t, 6)
        return out
    }

    /** Acima da faixa (0,50 a 0,84), onde nenhuma corda é cruzada. */
    private fun shake(times: Int, around: Double = 0.30, reach: Double = 0.08): List<Pair<Double, Double>> {
        val path = ArrayList<Pair<Double, Double>>()
        var time = 0.0
        for (step in 0 until times * 2 + 1) {
            path.add((around + (if (step % 2 == 0) -reach else reach)) to time)
            time += 0.09
        }
        return path
    }

    @Test
    fun shakingAwayFromTheStringsStillPlays() {
        val strummer = FixedStringsStrummer()
        val plucks = run(strummer, shake(3))
        assertTrue("chacoalhou três vezes e não saiu som", plucks.isNotEmpty())
        assertEquals(0, plucks.size % 6)
        assertEquals((0..5).toSet(), plucks.map { it.string }.toSet())
    }

    @Test
    fun theRhythmAlternatesDownAndUp() {
        val strummer = FixedStringsStrummer()
        val plucks = run(strummer, shake(3))
        val strums = plucks.chunked(6).map { chunk -> chunk.map { it.string } }
        assertTrue("saíram só ${strums.size} batida(s)", strums.size >= 2)
        for ((index, strum) in strums.withIndex()) {
            val down = strum == listOf(0, 1, 2, 3, 4, 5)
            val up = strum == listOf(5, 4, 3, 2, 1, 0)
            assertTrue("batida $index fora de ordem: $strum", down || up)
            if (index > 0) {
                val previousDown = strums[index - 1] == listOf(0, 1, 2, 3, 4, 5)
                assertTrue("duas batidas seguidas no mesmo sentido", down != previousDown)
            }
        }
    }

    @Test
    fun aStillHandMakesNoRhythm() {
        val strummer = FixedStringsStrummer()
        val path = (0..60).map { step -> (0.30 + (if (step % 2 == 0) 0.0005 else -0.0005)) to step / 30.0 }
        assertTrue(run(strummer, path).isEmpty())
    }

    @Test
    fun aTinySwingIsNotAStrum() {
        val strummer = FixedStringsStrummer()
        val plucks = run(strummer, shake(4, reach = 0.008))
        assertTrue(plucks.isEmpty())
    }

    @Test
    fun crossingTheStringsDoesNotDoubleFire() {
        val strummer = FixedStringsStrummer()
        val plucks = run(strummer, listOf(0.44 to 0.0, 0.90 to 0.10, 0.44 to 0.20))
        assertEquals("saíram ${plucks.size} notas em duas passadas", 12, plucks.size)
    }
}
