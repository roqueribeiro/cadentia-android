package com.levelhard.cadentia.kit

import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O reamostrador em blocos tem que produzir EXATAMENTE o que a versão de uma
 * vez só produz: é ela que o teste de pitch (YIN) já prova. Blocos de tamanho
 * irregular, 48 k → 44,1 k e o caminho contrário.
 */
class StemResamplerStreamingTest {
    private fun signal(frames: Int, rate: Int): FloatArray {
        val random = Random(7)
        return FloatArray(frames) { i ->
            (0.6 * sin(2 * Math.PI * 440 * i / rate) + 0.1 * (random.nextFloat() - 0.5)).toFloat()
        }
    }

    private fun streamed(input: FloatArray, from: Int, to: Int, blocks: List<Int>): FloatArray {
        val streaming = StemResampler.Streaming(from.toDouble(), to.toDouble())
        val out = ArrayList<Float>()
        var at = 0
        var b = 0
        while (at < input.size) {
            val size = minOf(blocks[b % blocks.size], input.size - at)
            val block = input.copyOfRange(at, at + size)
            streaming.push(block, size).forEach { out.add(it) }
            at += size
            b++
        }
        streaming.finish().forEach { out.add(it) }
        return out.toFloatArray()
    }

    @Test fun streamingMatchesOneShot() {
        for ((from, to) in listOf(48_000 to 44_100, 44_100 to 48_000, 22_050 to 44_100)) {
            val input = signal(48_000 * 2 + 137, from)
            val expected = StemResampler.resample(input, from.toDouble(), to.toDouble())
            val actual = streamed(input, from, to, listOf(4096, 1, 777, 8192, 3))
            assertEquals("tamanho $from→$to", expected.size, actual.size)
            var worst = 0f
            for (i in expected.indices) worst = maxOf(worst, abs(expected[i] - actual[i]))
            assertTrue("pior diferença $from→$to: $worst", worst < 1e-6f)
        }
    }

    @Test fun identityRateIsAPassThrough() {
        val input = signal(10_000, 44_100)
        val out = streamed(input, 44_100, 44_100, listOf(1000))
        assertEquals(input.size, out.size)
        for (i in input.indices) assertEquals(input[i], out[i], 0f)
    }

    @Test fun historyStaysSmall() {
        // 20 s de áudio em blocos de 4096: a história guardada não cresce com a música.
        val streaming = StemResampler.Streaming(48_000.0, 44_100.0)
        val block = FloatArray(4096) { 0.1f }
        repeat(48_000 * 20 / 4096) { streaming.push(block, block.size) }
        assertTrue(streaming.pendingForTest() <= 4096 + 64)
    }
}
