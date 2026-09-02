package com.levelhard.cadentia.kit

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port 1:1 do `MetronomeClickTests.swift`. */
class MetronomeClickTest {
    private val sampleRate = 44100.0

    @Test fun rendersEverySoundNonSilent() {
        for (sound in MetronomeClick.Sound.entries) {
            for (accent in listOf(true, false)) {
                val samples = MetronomeClick.render(sound, accent, volume = 0.7, sampleRate = sampleRate)
                assertTrue("$sound accent=$accent rendered empty", samples.isNotEmpty())
                val peak = samples.maxOfOrNull { abs(it) } ?: 0f
                assertTrue("$sound accent=$accent is silent", peak > 0.01f)
                // Os dois osciladores empilhados do cowbell ainda ficam no headroom.
                assertTrue("$sound accent=$accent clips", peak <= 1.0f)
            }
        }
    }

    @Test fun durationMatchesWebEnvelope() {
        // click = 40 ms + 20 ms de cauda; cowbell = 180 ms + 20 ms de cauda.
        val click = MetronomeClick.render(MetronomeClick.Sound.Click, accent = false, volume = 1.0, sampleRate = sampleRate)
        assertTrue(abs(click.size / sampleRate - 0.06) < 0.005)
        val cowbell = MetronomeClick.render(MetronomeClick.Sound.Cowbell, accent = true, volume = 1.0, sampleRate = sampleRate)
        assertTrue(abs(cowbell.size / sampleRate - 0.20) < 0.005)
    }

    @Test fun envelopeDecaysToNearSilence() {
        val samples = MetronomeClick.render(MetronomeClick.Sound.Beep, accent = false, volume = 1.0, sampleRate = sampleRate)
        // Os últimos 5 ms têm que estar no piso de -60 dB do web.
        val tail = samples.takeLast((0.005 * sampleRate).toInt())
        assertTrue((tail.maxOfOrNull { abs(it) } ?: 1f) < 0.01f)
    }

    @Test fun zeroVolumeIsSilent() {
        val samples = MetronomeClick.render(MetronomeClick.Sound.Click, accent = true, volume = 0.0, sampleRate = sampleRate)
        assertEquals(0f, samples.maxOfOrNull { abs(it) } ?: 1f, 0f)
    }

    @Test fun accentIsLouderThanRegular() {
        for (sound in listOf(MetronomeClick.Sound.Click, MetronomeClick.Sound.Woodblock, MetronomeClick.Sound.Beep)) {
            val accent = MetronomeClick.render(sound, accent = true, volume = 1.0, sampleRate = sampleRate)
            val regular = MetronomeClick.render(sound, accent = false, volume = 1.0, sampleRate = sampleRate)
            assertTrue((accent.maxOfOrNull { abs(it) } ?: 0f) > (regular.maxOfOrNull { abs(it) } ?: 0f))
        }
    }
}
