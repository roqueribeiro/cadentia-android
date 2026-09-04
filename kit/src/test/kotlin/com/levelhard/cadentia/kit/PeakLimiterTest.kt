package com.levelhard.cadentia.kit

import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O limitador do player de stems: transparente abaixo do teto, nada acima
 * dele, e voltando com o tempo (não com a contagem de blocos).
 */
class PeakLimiterTest {
    private val rate = 44_100

    private fun tone(amplitude: Float, frames: Int) =
        FloatArray(frames) { (amplitude * sin(2 * Math.PI * 220 * it / rate)).toFloat() }

    @Test fun belowCeilingIsUntouched() {
        val limiter = PeakLimiter(rate)
        val left = tone(0.5f, 4096)
        val right = tone(0.5f, 4096)
        val original = left.copyOf()
        limiter.process(left, right, left.size)
        for (i in left.indices) assertEquals(original[i], left[i], 1e-6f)
        assertEquals(1f, limiter.gain, 1e-6f)
    }

    // Tolerância de 1e-3 (0,01 dB): é o arredondamento de float32 em ceiling/peak*peak.
    @Test fun nothingPassesTheCeiling() {
        val limiter = PeakLimiter(rate)
        // Faixa isolada com pico de 1,6 (o caso real que motivou o AAC no iOS).
        val left = tone(1.6f, 8192)
        val right = tone(1.2f, 8192)
        limiter.process(left, right, left.size)
        for (i in left.indices) {
            assertTrue("L[$i]=${left[i]}", abs(left[i]) <= limiter.ceiling + 1e-3f)
            assertTrue("R[$i]=${right[i]}", abs(right[i]) <= limiter.ceiling + 1e-3f)
        }
    }

    @Test fun bothChannelsShareTheGain() {
        val limiter = PeakLimiter(rate)
        val left = floatArrayOf(2f)
        val right = floatArrayOf(0.5f)
        limiter.process(left, right, 1)
        // L cai para o teto; R cai na MESMA proporção (0,5 × 0,49).
        assertEquals(limiter.ceiling, left[0], 1e-6f)
        assertEquals(0.5f * limiter.ceiling / 2f, right[0], 1e-6f)
    }

    @Test fun releasesWithTimeAfterThePeak() {
        val limiter = PeakLimiter(rate, releaseSeconds = 0.1)
        limiter.process(floatArrayOf(4f), floatArrayOf(4f), 1)
        val afterPeak = limiter.gain
        assertEquals(limiter.ceiling / 4f, afterPeak, 1e-6f)
        // 50 ms de silêncio: metade da constante de tempo, ganho a caminho de 1.
        val silence = FloatArray(rate / 20)
        limiter.process(silence, silence.copyOf(), silence.size)
        val halfway = limiter.gain
        assertTrue(halfway > afterPeak && halfway < 1f)
        // Mais 500 ms: já praticamente transparente.
        val more = FloatArray(rate / 2)
        limiter.process(more, more.copyOf(), more.size)
        assertEquals(1f, limiter.gain, 0.01f)
    }
}
