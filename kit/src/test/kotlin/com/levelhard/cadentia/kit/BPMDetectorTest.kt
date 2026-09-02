package com.levelhard.cadentia.kit

import kotlin.math.abs
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Envelopes de RMS sintéticos — mesmas expectativas do `BPMDetectorTests.swift`
 * e do `bpmDetector.spec.js` do web (palmas estáveis detectam; ruído e
 * silêncio, não).
 */
class BPMDetectorTest {
    /**
     * Simula um envelope de ~30 Hz com ataque de duas amostras (rampa e pico)
     * a cada `intervalMs` — o pico do detector exige borda de subida em três
     * amostras consecutivas, como o transiente de uma palma real.
     */
    private fun feed(detector: BPMDetector, intervalMs: Double, beats: Int, tickMs: Double = 33.0): Int? {
        var result: Int? = null
        val peaks = (0 until beats).map { 200 + intervalMs * it }
        var now = 0.0
        while (now <= (peaks.lastOrNull() ?: 0.0) + 200) {
            var rms = 0.01
            for (peak in peaks) {
                if (abs(now - (peak - tickMs)) < tickMs / 2) rms = maxOf(rms, 0.05)
                if (abs(now - peak) < tickMs / 2) rms = maxOf(rms, 0.6)
            }
            detector.processSample(rms = rms, nowMs = now)?.let { result = it }
            now += tickMs
        }
        return result
    }

    @Test fun detects120BpmClaps() {
        val bpm = feed(BPMDetector(), intervalMs = 500.0, beats = 8)!!
        assertTrue(abs(bpm - 120) <= 3)
    }

    @Test fun detectsSlow60Bpm() {
        val bpm = feed(BPMDetector(), intervalMs = 1000.0, beats = 6)!!
        assertTrue(abs(bpm - 60) <= 2)
    }

    @Test fun silenceNeverDetects() {
        val detector = BPMDetector()
        for (i in 0 until 300) {
            assertNull(detector.processSample(rms = 0.005, nowMs = i * 33.0))
        }
    }

    @Test fun resetClearsState() {
        val detector = BPMDetector()
        feed(detector, intervalMs = 500.0, beats = 8)!!
        detector.reset()
        // Depois do reset, as próximas amostras ainda não produzem BPM.
        assertNull(detector.processSample(rms = 0.6, nowMs = 100_000.0))
    }
}
