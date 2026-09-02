package com.levelhard.cadentia.kit

import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Valida o port do YIN contra sinais sintetizados — mesmas expectativas do
 * `YINPitchDetectorTests.swift` e do `pitchAlgorithms.spec.js` do web.
 */
class YINPitchDetectorTest {
    private val sampleRate = 48000.0

    private fun sine(frequency: Double, amplitude: Double = 0.5, count: Int = 2048): FloatArray =
        FloatArray(count) { i ->
            (amplitude * sin(2 * Math.PI * frequency * i / sampleRate)).toFloat()
        }

    /** Tom rico em harmônicos (fundamental + 3 harmônicos), perto de uma corda real. */
    private fun pluck(frequency: Double, count: Int = 2048): FloatArray =
        FloatArray(count) { i ->
            val t = i / sampleRate
            (
                0.5 * sin(2 * Math.PI * frequency * t) +
                    0.25 * sin(2 * Math.PI * 2 * frequency * t) +
                    0.12 * sin(2 * Math.PI * 3 * frequency * t) +
                    0.06 * sin(2 * Math.PI * 4 * frequency * t)
                ).toFloat()
        }

    @Test fun detectsA440WithinOneCent() {
        val pitch = YINPitchDetector.detect(sine(440.0), sampleRate)!!
        assertTrue(abs(MusicNotes.centsOff(detected = pitch.frequency, target = 440.0)) <= 1)
        assertTrue(pitch.clarity > 0.8)
    }

    /**
     * O afinador enxerga baixo (1.16): mi grave em 41,20 Hz e o si de um cinco
     * cordas em 30,87 Hz, com a janela de 4096 que o `TunerAudioEngine` usa.
     * Com 2048 o detector MENTIA: devolvia 43,1 Hz (o próprio teto) para o mi.
     */
    @Test fun detectsBassStringsWithTheDoubledWindow() {
        val e1 = 41.2034
        val pitch = YINPitchDetector.detect(pluck(e1, count = 4096), sampleRate)!!
        assertTrue("mi grave: ${pitch.frequency}", abs(MusicNotes.centsOff(detected = pitch.frequency, target = e1)) <= 5)
        val b0 = 30.8677
        val low = YINPitchDetector.detect(pluck(b0, count = 4096), sampleRate)!!
        assertTrue("si do cinco cordas: ${low.frequency}", abs(MusicNotes.centsOff(detected = low.frequency, target = b0)) <= 8)
    }

    @Test fun detectsLowE2GuitarString() {
        val e2 = 82.41
        val pitch = YINPitchDetector.detect(sine(e2), sampleRate)!!
        assertTrue(abs(MusicNotes.centsOff(detected = pitch.frequency, target = e2)) <= 3)
    }

    @Test fun detectsHarmonicRichFundamental() {
        val g3 = 196.0
        val pitch = YINPitchDetector.detect(pluck(g3), sampleRate)!!
        assertTrue(abs(MusicNotes.centsOff(detected = pitch.frequency, target = g3)) <= 3)
    }

    @Test fun silenceReturnsNull() {
        assertNull(YINPitchDetector.detect(FloatArray(2048), sampleRate))
    }

    @Test fun belowRmsGateReturnsNull() {
        // Amplitude 0,002 < MIN_RMS 0,005: a guarda de "processar silêncio".
        assertNull(YINPitchDetector.detect(sine(440.0, amplitude = 0.002), sampleRate))
    }

    @Test fun whiteNoiseReturnsNull() {
        // Ruído LCG determinístico: aperiódico, nenhum vale sob o threshold.
        var seed = 0x2545F491uL
        val noise = FloatArray(2048) {
            seed = seed * 6364136223846793005uL + 1442695040888963407uL
            ((seed shr 33).toDouble() / UInt.MAX_VALUE.toDouble()).toFloat() - 0.5f
        }
        assertNull(YINPitchDetector.detect(noise, sampleRate))
    }

    @Test fun tooShortBufferReturnsNull() {
        assertNull(YINPitchDetector.detect(sine(440.0, count = 128), sampleRate))
    }

    @Test fun outOfBandFrequencyReturnsNull() {
        // 3 kHz fica acima do teto de 2 kHz (o YIN perde confiabilidade lá).
        assertNull(YINPitchDetector.detect(sine(3000.0), sampleRate))
    }
}
