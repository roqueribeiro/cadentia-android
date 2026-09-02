package com.levelhard.cadentia.kit.cordas

import com.levelhard.cadentia.kit.MusicNotes
import com.levelhard.cadentia.kit.YINPitchDetector
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O modelo de corda, conferido como o `tools/check.mjs` do repo cordas
 * confere: roda a física fora de qualquer motor de áudio, mede o resultado e
 * falha num número — port do `CordaStringTests.swift`.
 *
 * O teste que mais importa é `theDecayHappensInTwoStages`: tudo o mais aqui
 * continuaria passando se alguém "simplificasse" as duas polarizações de volta
 * num laço só.
 */
class CordaStringTest {
    private val sampleRate = 48000.0

    private fun rms(buffer: FloatArray, from: Int, count: Int): Double {
        if (from < 0 || from >= buffer.size) return 0.0
        val end = minOf(from + count, buffer.size)
        if (end <= from) return 0.0
        var sum = 0.0
        for (i in from until end) sum += buffer[i].toDouble() * buffer[i]
        return sqrt(sum / (end - from))
    }

    private fun midiToHz(midi: Int): Double = 440 * 2.0.pow((midi - 69) / 12.0)

    /** Duas notas por instrumento, como o check.mjs: a solta mais grave e cinco casas acima da mais aguda. */
    private fun probeNotes(instrument: CordaInstrument): List<Int> {
        val lowest = instrument.strings.minOf { it.midi }
        val highest = instrument.strings.maxOf { it.midi }
        return listOf(lowest, highest + 5)
    }

    @Test
    fun everyInstrumentPlaysInTune() {
        for (instrument in CordaInstrument.all) {
            for (midi in probeNotes(instrument)) {
                val target = midiToHz(midi)
                val note = CordaString.render(target, 0.8, instrument.tone, sampleRate, seed = 12345)
                val start = (0.05 * sampleRate).toInt()
                val end = minOf(start + 16384, note.size)
                assertTrue("${instrument.id} $midi: buffer curto demais", end > start + 4096)
                val pitch = YINPitchDetector.detect(note.copyOfRange(start, end), sampleRate)
                assertNotNull("${instrument.id} $midi: sem altura detectável", pitch)
                val cents = MusicNotes.centsOff(detected = pitch!!.frequency, target = target)
                assertTrue("${instrument.id} em ${target.toInt()} Hz está $cents cents fora", abs(cents) <= 12)
            }
        }
    }

    /** Uma corda real vibra em dois planos: cai forte primeiro e depois soa baixinho por segundos. */
    @Test
    fun theDecayHappensInTwoStages() {
        for (instrument in CordaInstrument.all) {
            val midi = instrument.strings.minOf { it.midi }
            val note = CordaString.render(midiToHz(midi), 0.9, instrument.tone, sampleRate, seed = 999)
            val window = 6000
            val e0 = rms(note, (0.02 * sampleRate).toInt(), window)
            val e1 = rms(note, (0.20 * sampleRate).toInt(), window)
            val e2 = rms(note, (0.70 * sampleRate).toInt(), window)
            assertTrue("${instrument.id}: envelope vazio", e0 > 0 && e1 > 0)
            val early = -20 * log10(maxOf(e1, 1e-9) / e0) / 0.18
            val late = -20 * log10(maxOf(e2, 1e-9) / e1) / 0.50
            assertTrue(
                "${instrument.id}: queda de um estágio só — ${early.toInt()} vs ${late.toInt()} dB/s",
                early > 8 && early > late + 6,
            )
        }
    }

    @Test
    fun theEnvelopeDecaysAndTheLevelStaysInRange() {
        for (instrument in CordaInstrument.all) {
            val note = CordaString.render(midiToHz(instrument.strings[0].midi), 0.8, instrument.tone, sampleRate, seed = 7)
            val early = rms(note, (0.01 * sampleRate).toInt(), 12288)
            val late = rms(note, (note.size * 0.75).toInt(), 12288)
            assertTrue("${instrument.id}: ataque mudo ($early)", early > 0.008)
            assertTrue("${instrument.id}: não decai", late < early * 0.8)
            val peak = note.maxOf { abs(it) }
            assertTrue("${instrument.id}: pico fora de faixa ($peak)", peak > 0.1f && peak <= 1.001f)
        }
    }

    @Test
    fun theSameSeedGivesTheSameStringAndAnotherSeedDoesNot() {
        val tone = CordaInstrument.violao.tone
        val a = CordaString.render(220.0, 0.7, tone, sampleRate, seed = 42)
        val b = CordaString.render(220.0, 0.7, tone, sampleRate, seed = 42)
        val c = CordaString.render(220.0, 0.7, tone, sampleRate, seed = 43)
        assertTrue("mesma semente devia dar a mesma corda", a.contentEquals(b))
        assertFalse("sementes diferentes deviam dar cordas diferentes", a.contentEquals(c))
    }

    /** A dinâmica tem que mudar o TIMBRE, não só o nível: palhetada mais forte abre o filtro. */
    @Test
    fun aHarderPluckIsBrighterAndNotJustLouder() {
        val tone = CordaInstrument.violao.tone
        fun brightness(velocity: Double): Double {
            val note = CordaString.render(196.0, velocity, tone, sampleRate, seed = 5150)
            val start = (0.02 * sampleRate).toInt()
            val end = minOf(start + 8192, note.size)
            if (end <= start + 2) return 0.0
            var crossings = 0.0
            for (i in start + 1 until end) if ((note[i] < 0) != (note[i - 1] < 0)) crossings += 1
            return crossings / ((end - start) / sampleRate)
        }
        assertTrue("a dinâmica não abriu o brilho", brightness(0.95) > brightness(0.25) * 1.1)
    }

    @Test
    fun theBodyKeepsThePitchItWasGiven() {
        val target = midiToHz(45)
        val dry = CordaString.render(target, 0.8, CordaInstrument.violao.tone, sampleRate, seed = 314)
        val wet = CordaBody.apply(dry, sampleRate)
        assertEquals(dry.size, wet.size)
        val start = (0.05 * sampleRate).toInt()
        val end = minOf(start + 16384, wet.size)
        val pitch = YINPitchDetector.detect(wet.copyOfRange(start, end), sampleRate)
        assertNotNull("corpo: sem altura detectável", pitch)
        val cents = MusicNotes.centsOff(detected = pitch!!.frequency, target = target)
        assertTrue("o corpo desafinou a nota em $cents cents", abs(cents) <= 12)
        val peak = wet.maxOf { abs(it) }
        assertTrue("corpo: pico fora de faixa ($peak)", peak > 0.1f && peak <= 1.001f)
    }

    /** O Q de cada modo tem que vir da taxa de decaimento, não de um chute. */
    @Test
    fun modeQualityFollowsTheDecayRate() {
        assertTrue(abs(CordaBody.quality(98.0, 26.0) - 11.84) < 0.1)
        assertTrue(abs(CordaBody.quality(190.0, 20.0) - 29.85) < 0.1)
        assertEquals(20, CordaBody.modes.size)
    }

    @Test
    fun theViolaCaipiraHasFiveCoursesAndTenStrings() {
        val viola = CordaInstrument.viola
        assertEquals(10, viola.stringCount)
        assertEquals(5, viola.courseCount)
        // Cebolão em Ré: a quinta ordem é a mais grave e fica em cima.
        assertEquals(45, viola.strings[0].midi)
        // A quarta e a quinta ordens são uníssonos, não oitavas.
        assertEquals(viola.strings[6].midi, viola.strings[7].midi)
        assertEquals(viola.strings[8].midi, viola.strings[9].midi)
    }

    /**
     * SplitMix64 canônica (Steele, Lea & Flood): a semente é contrato entre o
     * Swift e o Kotlin, então os 64 bits têm que bater com a referência —
     * calculada fora daqui, em Python, com as mesmas constantes. Semente 0 vira
     * a constante dourada, como no Swift; a primeira saída é então mix(2φ).
     */
    @Test
    fun theNoiseIsSplitMix64() {
        assertEquals(7960286522194355700L, CordaNoise(0).next())
        val seeded = CordaNoise(12345)
        assertEquals(2454886589211414944L, seeded.next())
        assertEquals(3778200017661327597L, seeded.next())
        assertEquals(0.5665615751722809, CordaNoise(1).nextUnit(), 1e-15)
        val bipolar = CordaNoise(2).nextBipolar()
        assertTrue(bipolar in -1.0..1.0)
    }
}
