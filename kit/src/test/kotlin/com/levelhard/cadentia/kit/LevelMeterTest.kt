package com.levelhard.cadentia.kit

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O medidor existe para animar a tela. Um medidor "correto" que pisca ou que
 * nunca zera arruína a apresentação do mesmo jeito que um errado — port 1:1
 * do `LevelMeterTests.swift`.
 */
class LevelMeterTest {
    private fun absorb(meter: LevelMeter, amplitude: Float, count: Int = 512, seconds: Float = 0.01f) {
        meter.absorb(FloatArray(count) { amplitude }, count, seconds)
    }

    @Test fun silenceReadsZero() {
        val meter = LevelMeter()
        absorb(meter, amplitude = 0f)
        assertEquals(0f, meter.level, 0f)
    }

    @Test fun loudAudioReadsHigh() {
        val meter = LevelMeter()
        absorb(meter, amplitude = 0.9f)
        assertTrue(meter.level > 0.8f)
    }

    /** Ataque imediato: a batida precisa aparecer no mesmo quadro. */
    @Test fun risesInstantly() {
        val meter = LevelMeter()
        absorb(meter, amplitude = 0.8f, seconds = 0.001f)
        assertTrue(meter.level > 0.7f)
    }

    /** E cai devagar: queda instantânea faz a barra piscar entre as batidas. */
    @Test fun fallsGraduallyNotInstantly() {
        val meter = LevelMeter(decayPerSecond = 2f)
        absorb(meter, amplitude = 0.9f)
        val peak = meter.level

        absorb(meter, amplitude = 0f, seconds = 0.05f)
        assertTrue("tem que cair", meter.level < peak)
        assertTrue("mas não pode zerar de uma vez", meter.level > 0f)

        // Depois de tempo suficiente, chega a zero de verdade.
        absorb(meter, amplitude = 0f, seconds = 1.0f)
        assertEquals(0f, meter.level, 0f)
    }

    /** A queda é por TEMPO, não por bloco. */
    @Test fun decayFollowsTimeNotBlockCount() {
        val rapido = LevelMeter(decayPerSecond = 2f)
        val lento = LevelMeter(decayPerSecond = 2f)
        absorb(rapido, amplitude = 0.9f)
        absorb(lento, amplitude = 0.9f)

        // Mesmo tempo total, número diferente de blocos.
        repeat(10) { absorb(rapido, amplitude = 0f, seconds = 0.01f) }
        absorb(lento, amplitude = 0f, seconds = 0.1f)

        assertTrue(abs(rapido.level - lento.level) < 0.01f)
    }

    /** Escala em decibéis: em linear, música normalizada mal move a barra. */
    @Test fun usesDecibelScaleSoQuietDetailIsVisible() {
        val alto = LevelMeter.normalize(rms = 0.5f)
        val baixo = LevelMeter.normalize(rms = 0.05f)
        assertTrue(alto > 0.7f)
        assertTrue(baixo > 0.4f)
        assertTrue(baixo < alto)
    }

    @Test fun nonsenseInputDoesNotProduceNonsenseOutput() {
        assertEquals(0f, LevelMeter.normalize(rms = 0f), 0f)
        assertEquals(0f, LevelMeter.normalize(rms = -1f), 0f)
        assertEquals(0f, LevelMeter.normalize(rms = Float.NaN), 0f)
        assertTrue(LevelMeter.normalize(rms = Float.POSITIVE_INFINITY) <= 1f)
    }
}

class SpectrumBandsTest {
    /** O espaçamento é logarítmico para o baixo não virar uma coluna só. */
    @Test fun lowFrequenciesGetRoomOnScreen() {
        val bands = SpectrumBands(count = 24, binCount = 1024, sampleRate = 44100.0)
        val re = FloatArray(1024)
        val im = FloatArray(1024)
        // Energia só numa raia grave (~86 Hz com FFT de 2048 em 44,1 kHz).
        re[4] = 1f

        val magnitudes = bands.magnitudes(re, im)
        assertEquals(24, magnitudes.size)
        val loudest = magnitudes.indices.maxByOrNull { magnitudes[it] } ?: 0
        // Grave tem que acender no terço inicial, não na primeira banda apenas.
        assertTrue(loudest < 8)
    }

    @Test fun everyBandIsRealAndNeverEmpty() {
        val bands = SpectrumBands(count = 64, binCount = 128, sampleRate = 44100.0)
        val magnitudes = bands.magnitudes(FloatArray(128) { 0.5f }, FloatArray(128))
        assertEquals(64, magnitudes.size)
        assertTrue("nenhuma banda pode ficar morta", magnitudes.all { it > 0f })
    }

    @Test fun silenceGivesAFlatSpectrum() {
        val bands = SpectrumBands(count = 16, binCount = 512, sampleRate = 44100.0)
        val magnitudes = bands.magnitudes(FloatArray(512), FloatArray(512))
        assertTrue(magnitudes.all { it == 0f })
    }

    @Test fun magnitudesStayInRange() {
        val bands = SpectrumBands(count = 16, binCount = 512, sampleRate = 44100.0)
        val magnitudes = bands.magnitudes(FloatArray(512) { 10f }, FloatArray(512) { 10f })
        assertTrue(magnitudes.all { it in 0f..1f })
    }

    /** Duas raias de mesma energia, grave e aguda, chegam com alturas parecidas. */
    @Test fun tiltEvensOutLowAndHighAtEqualEnergy() {
        val binCount = 1024
        val plain = SpectrumBands(count = 32, binCount = binCount, sampleRate = 44100.0, tiltPerOctave = 0.0)
        val tilted = SpectrumBands(count = 32, binCount = binCount, sampleRate = 44100.0)
        val im = FloatArray(binCount)

        fun height(bands: SpectrumBands, bin: Int): Float {
            val re = FloatArray(binCount)
            re[bin] = 0.05f
            return bands.magnitudes(re, im).max()
        }

        // ~86 Hz contra ~5,5 kHz na mesma FFT.
        val lowPlain = height(plain, 4)
        val highPlain = height(plain, 256)
        val lowTilted = height(tilted, 4)
        val highTilted = height(tilted, 256)

        assertTrue("sem inclinação, o agudo fica abaixo do grave", highPlain < lowPlain)
        assertTrue(
            "a inclinação tem que aproximar o agudo do grave",
            (highTilted - lowTilted) > (highPlain - lowPlain),
        )
        assertTrue(highTilted <= 1f && lowTilted <= 1f)
    }

    /** A inclinação não pode inventar sinal onde não há. */
    @Test fun tiltKeepsSilenceSilent() {
        val bands = SpectrumBands(count = 24, binCount = 512, sampleRate = 44100.0)
        val magnitudes = bands.magnitudes(FloatArray(512), FloatArray(512))
        assertTrue(magnitudes.all { it == 0f })
    }
}
