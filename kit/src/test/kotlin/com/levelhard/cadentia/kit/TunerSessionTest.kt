package com.levelhard.cadentia.kit

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contrato das métricas de sessão — espelho do computed do TunerSessionModal do web. */
class TunerSessionTest {
    private fun point(t: Double, cents: Int, note: String) =
        TunerSession.Point(t = t, frequency = 440.0, cents = cents, note = note)

    @Test fun emptyTimelineHasNoMetrics() {
        val session = TunerSession(audioPath = null, timeline = emptyList(), durationMs = 0.0)
        assertNull(session.metrics.dominantNote)
        assertEquals(0, session.metrics.inTunePercent)
        assertNull(session.metrics.averageDriftCents)
    }

    @Test fun computesDominantNoteInTunePercentAndDrift() {
        val session = TunerSession(
            audioPath = null,
            timeline = listOf(
                point(t = 0.0, cents = 2, note = "A4"),
                point(t = 66.0, cents = -3, note = "A4"),
                point(t = 133.0, cents = 12, note = "A4"),
                point(t = 200.0, cents = -40, note = "E2"),
            ),
            durationMs = 266.0,
        )
        val metrics = session.metrics
        assertEquals("A4", metrics.dominantNote)
        assertEquals(50, metrics.inTunePercent) // 2 de 4 dentro de ±5
        val drift = metrics.averageDriftCents!!
        assertTrue(abs(drift - 14.25) < 0.001) // (2+3+12+40)/4
    }
}
