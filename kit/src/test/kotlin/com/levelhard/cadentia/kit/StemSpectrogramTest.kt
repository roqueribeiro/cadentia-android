package com.levelhard.cadentia.kit

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port 1:1 do `StemSpectrogramTests.swift`. O espectrograma entregue ao
 * modelo tem que ser bit a bit o do treino: as referências vêm do PyTorch
 * real (scripts/gen-stem-fixtures.py do iOS), fixtures copiadas verbatim.
 * Regenerar só se o modelo upstream mudar, nunca para um teste passar.
 */
class StemSpectrogramTest {
    private companion object {
        const val SAMPLES = 8192
        const val FRAMES = 8
        const val BINS = DemucsSpectrogram.BINS
    }

    private fun fixture(name: String): FloatArray {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/$name.f32")) {
            "$name.f32 não está no classpath"
        }.readBytes()
        val floats = FloatArray(bytes.size / 4)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
        return floats
    }

    @Test fun forwardMatchesPyTorch() {
        val input = fixture("stft-input")
        val expected = fixture("stft-expected")
        assertEquals(SAMPLES, input.size)
        assertEquals(2 * BINS * FRAMES, expected.size)

        val result = DemucsSpectrogram().forward(input)
        assertEquals(FRAMES, result.frames)

        // Layout dos canais é [real, imag], a ordem que o _magnitude produz.
        var worst = 0f
        for (index in expected.indices) {
            val mine = if (index < BINS * FRAMES) result.re[index] else result.im[index - BINS * FRAMES]
            worst = maxOf(worst, abs(mine - expected[index]))
        }
        assertTrue("STFT divergiu do PyTorch em $worst", worst < 1e-5f)
    }

    @Test fun inverseMatchesPyTorch() {
        val input = fixture("stft-input")
        val expected = fixture("stft-roundtrip")

        val spectrogram = DemucsSpectrogram()
        val forward = spectrogram.forward(input)
        val back = spectrogram.inverse(forward.re, forward.im, forward.frames, SAMPLES)

        assertEquals(SAMPLES, back.size)
        var worst = 0f
        for (index in 0 until SAMPLES) {
            worst = maxOf(worst, abs(back[index] - expected[index]))
        }
        assertTrue("iSTFT divergiu do PyTorch em $worst", worst < 1e-5f)
    }

    /**
     * O sinal da parte imaginária é a armadilha clássica da FFT real, e é
     * invisível num gráfico de magnitude: um espectrograma conjugado faz
     * round-trip perfeito e ainda entrega ao modelo uma fase invertida.
     */
    @Test fun imaginaryPartHasTheRightSign() {
        val input = fixture("stft-input")
        val expected = fixture("stft-expected")
        val result = DemucsSpectrogram().forward(input)

        var asIs = 0f
        var flipped = 0f
        for (index in 0 until BINS * FRAMES) {
            val want = expected[BINS * FRAMES + index]
            asIs = maxOf(asIs, abs(result.im[index] - want))
            flipped = maxOf(flipped, abs(-result.im[index] - want))
        }
        assertTrue(asIs < 1e-5f)
        assertTrue("sinal invertido daria o mesmo resultado, o teste nao prova nada", flipped > 1e-3f)
    }

    /** Um tom puro tem que cair no bin da frequência dele (pega off-by-one no hop). */
    @Test fun pureToneLandsInTheExpectedBin() {
        val sampleRate = 44100.0
        val frequency = 440.0
        val tone = FloatArray(8192) {
            (0.5 * sin(2 * Math.PI * frequency * it / sampleRate)).toFloat()
        }

        val result = DemucsSpectrogram().forward(tone)
        val middle = result.frames / 2
        var loudest = 0
        var loudestEnergy = 0f
        for (bin in 0 until BINS) {
            val index = bin * result.frames + middle
            val energy = result.re[index] * result.re[index] + result.im[index] * result.im[index]
            if (energy > loudestEnergy) {
                loudestEnergy = energy
                loudest = bin
            }
        }

        val expectedBin = Math.round(frequency / (sampleRate / DemucsSpectrogram.NFFT)).toInt()
        assertTrue("pico no bin $loudest, esperado $expectedBin", abs(loudest - expectedBin) <= 1)
    }

    /** Silêncio entra, silêncio sai — guarda a divisão pela soma de janelas. */
    @Test fun silenceStaysSilentAndFinite() {
        val silence = FloatArray(8192)
        val spectrogram = DemucsSpectrogram()
        val forward = spectrogram.forward(silence)
        val back = spectrogram.inverse(forward.re, forward.im, forward.frames, silence.size)
        assertTrue(back.all { it.isFinite() })
        assertTrue(back.all { abs(it) < 1e-6f })
    }

    /** Reflect não repete a borda: [1,2,3] com 2 de cada lado é [3,2,1,2,3,2,1]. */
    @Test fun reflectPaddingExcludesTheEdge() {
        val padded = reflectPad(floatArrayOf(1f, 2f, 3f), left = 2, right = 2)
        assertTrue(padded.contentEquals(floatArrayOf(3f, 2f, 1f, 2f, 3f, 2f, 1f)))
    }
}
