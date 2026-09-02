package com.levelhard.cadentia.kit

import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O laço de janelas da separação: com um backend identidade, o cross-fade
 * tem que devolver o sinal de entrada sem costura — qualquer erro de janela,
 * stride ou peso aparece como degrau audível na fronteira das janelas.
 */
class StemPipelineTest {
    /** Backend que "separa" devolvendo o próprio chunk nas quatro fontes. */
    private object IdentityBackend : StemPipeline.StemBackend {
        override fun separateSegment(chunk: Array<FloatArray>): Array<Array<FloatArray>> =
            Array(4) { Array(2) { channel -> chunk[channel].copyOf() } }
    }

    private class CollectingWriter(sources: Int = 4) : StemPipeline.SettledWriter {
        val collected = Array(sources) { Array(2) { mutableListOf<Float>() } }

        override fun write(planes: Array<Array<FloatArray>>, count: Int) {
            for (source in planes.indices) {
                for (channel in 0 until 2) {
                    for (i in 0 until count) collected[source][channel].add(planes[source][channel][i])
                }
            }
        }
    }

    /** Segmento pequeno para o teste não custar uma música inteira. */
    private val segment = 4096

    private fun separateAll(input: Array<FloatArray>): CollectingWriter {
        val writer = CollectingWriter()
        val written = StemPipeline.separate(
            total = input[0].size,
            segment = segment,
            read = { start, count ->
                Array(2) { channel ->
                    FloatArray(count) { i -> input[channel].getOrElse(start + i) { 0f } }
                }
            },
            backend = IdentityBackend,
            writer = writer,
        )
        assertEquals(input[0].size, written)
        return writer
    }

    @Test fun identityBackendReconstructsTheSignalWithoutSeams() {
        // Comprimento que NÃO é múltiplo do stride, com rabo curto.
        val total = (segment * 3.7).toInt()
        val random = Random(7)
        val input = Array(2) { FloatArray(total) { random.nextFloat() * 2 - 1 } }

        val writer = separateAll(input)
        for (source in 0 until 4) {
            for (channel in 0 until 2) {
                val out = writer.collected[source][channel]
                assertEquals(total, out.size)
                var worst = 0f
                // A amostra 0 é a única com peso de janela exatamente zero
                // (window[0] = 0 e nenhuma janela anterior a cobre); o iOS
                // zera do mesmo jeito. Do 1 em diante, a divisão pelo peso
                // reconstrói exato.
                assertEquals(0f, out[0], 1e-6f)
                for (i in 1 until total) {
                    worst = maxOf(worst, abs(out[i] - input[channel][i]))
                }
                assertTrue("fonte $source canal $channel divergiu $worst", worst < 1e-4f)
            }
        }
    }

    @Test fun aToneCrossesWindowBoundariesSmoothly() {
        val total = segment * 2 // duas janelas inteiras + sobreposição
        val input = Array(2) { channel ->
            FloatArray(total) { (0.5 * sin(2 * Math.PI * 220.0 * it / 44100.0 + channel)).toFloat() }
        }
        val writer = separateAll(input)
        val out = writer.collected[0][0]
        // Sem costura: o maior salto entre amostras vizinhas do resultado tem
        // que ser o mesmo do sinal original (um degrau na emenda dobraria).
        var worstJump = 0f
        var worstInputJump = 0f
        for (i in 1 until total) {
            worstJump = maxOf(worstJump, abs(out[i] - out[i - 1]))
            worstInputJump = maxOf(worstInputJump, abs(input[0][i] - input[0][i - 1]))
        }
        assertTrue("salto $worstJump > entrada $worstInputJump", worstJump <= worstInputJump * 1.05f + 1e-4f)
    }

    @Test fun windowCountMatchesTheiOSLoop() {
        val ramp = (segment * StemPipeline.OVERLAP).toInt()
        val stride = segment - ramp
        // Total menor que uma janela: uma janela só.
        assertEquals(1, StemPipeline.windowStarts(segment / 2, segment).size)
        // Total exatamente uma janela: o laço do iOS anda enquanto
        // cursor < max(total - ramp, 1).
        val one = StemPipeline.windowStarts(segment, segment)
        assertEquals(1, one.size)
        // Um pouco mais que uma janela: duas.
        val two = StemPipeline.windowStarts(segment + stride / 2, segment)
        assertEquals(2, two.size)
        assertEquals(stride, two[1])
    }

    @Test fun progressReportsEverySegment() {
        val total = (segment * 2.5).toInt()
        val seen = mutableListOf<Pair<Int, Int>>()
        StemPipeline.separate(
            total = total,
            segment = segment,
            read = { _, count -> Array(2) { FloatArray(count) } },
            backend = IdentityBackend,
            writer = { _, _ -> },
            onSegment = { done, totalSegments -> seen.add(done to totalSegments) },
        )
        assertTrue(seen.isNotEmpty())
        assertEquals(seen.size, seen.last().first)
        assertTrue(seen.all { it.second == seen.size })
    }

    @Test fun crossfadeWindowIsSymmetricAndFlatInTheMiddle() {
        val window = StemPipeline.crossfadeWindow(segment)
        val ramp = (segment * StemPipeline.OVERLAP).toInt()
        assertEquals(0f, window[0], 1e-6f)
        assertEquals(1f, window[ramp], 1e-3f)
        assertEquals(1f, window[segment / 2], 0f)
        for (i in 0 until ramp) {
            assertEquals(window[i], window[segment - 1 - i], 1e-6f)
        }
        // sin² nas pontas: complementares somam ≈1. O iOS tem o mesmo
        // off-by-one de meia amostra (window[segment-1-i] espelha o índice i,
        // não i+1), então a soma fica a ~1,6e-3 de 1 — e é a divisão pelo
        // PESO acumulado no flush que garante a reconstrução exata, não a
        // soma das janelas.
        for (i in 0 until ramp) {
            val a = window[i]
            val b = window[segment - 1 - (ramp - 1 - i)]
            assertEquals(1f, a + b, 3e-3f)
        }
    }
}

/** A regra de limpeza do cache (o miolo puro do StemCache.trim). */
class StemCachePolicyTest {
    private fun entry(id: String, bytes: Long, usedAt: Long) =
        StemCachePolicy.Entry(id, bytes, usedAt)

    @Test fun evictsWhatIsNoLongerRecentFirst() {
        val doomed = StemCachePolicy.evict(
            entries = listOf(
                entry("viva", 100, 10),
                entry("morta", 100, 99),
            ),
            keeping = setOf("viva"),
            maxBytes = 1_000,
        )
        assertEquals(listOf("morta"), doomed)
    }

    @Test fun overCapTheOldestGoFirst() {
        // Total 1800 com teto 1000: sai a antiga (fica 1200, ainda acima),
        // sai a média (fica 600, cabe), a nova sobrevive.
        val doomed = StemCachePolicy.evict(
            entries = listOf(
                entry("antiga", 600, 1),
                entry("media", 600, 2),
                entry("nova", 600, 3),
            ),
            keeping = setOf("antiga", "media", "nova"),
            maxBytes = 1_000,
        )
        assertEquals(listOf("antiga", "media"), doomed)
    }

    @Test fun underTheCapNothingAliveIsTouched() {
        val doomed = StemCachePolicy.evict(
            entries = listOf(entry("a", 100, 1), entry("b", 100, 2)),
            keeping = setOf("a", "b"),
            maxBytes = 1_000,
        )
        assertTrue(doomed.isEmpty())
    }
}

/** O reamostrador da normalização: o tom não pode mudar. */
class StemResamplerTest {
    @Test fun keepsThePitchFrom48kTo44k1() {
        val fromRate = 48000.0
        val toRate = 44100.0
        val tone = FloatArray((fromRate * 1).toInt()) {
            (0.6 * sin(2 * Math.PI * 440.0 * it / fromRate)).toFloat()
        }
        val resampled = StemResampler.resample(tone, fromRate, toRate)
        assertEquals((tone.size / (fromRate / toRate)).toInt(), resampled.size)

        val start = (0.1 * toRate).toInt()
        val slice = resampled.copyOfRange(start, start + 16384)
        val pitch = YINPitchDetector.detect(slice, toRate)
        assertNotNull("YIN não achou o tom reamostrado", pitch)
        val cents = MusicNotes.centsOff(detected = pitch!!.frequency, target = 440.0)
        assertTrue("resample mudou o tom em $cents cents", abs(cents) <= 3)
    }

    @Test fun sameRateIsAPassThrough() {
        val input = FloatArray(1000) { it.toFloat() / 1000 }
        val out = StemResampler.resample(input, 44100.0, 44100.0)
        assertTrue(out.contentEquals(input))
    }
}
