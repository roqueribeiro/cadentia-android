package com.levelhard.cadentia.kit

import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Transforma amostras de áudio em números que a tela pode animar sem tremer —
 * port do `LevelMeter.swift`.
 *
 * A parte difícil de um medidor não é medir, é **não parecer nervoso**. Pico
 * instantâneo pula demais e vira ruído visual; média longa demais fica morta e
 * não acompanha a batida. O que funciona é subir rápido e cair devagar, que é
 * como um VU de verdade se comporta e como o olho espera.
 */
class LevelMeter(
    /** Quanto o valor cai por segundo quando o som para. */
    var decayPerSecond: Float = 2.0f,
    /** Piso em decibéis. Abaixo disso é silêncio para efeito visual. */
    var floorDecibels: Float = -60f,
) {
    var level: Float = 0f
        private set

    /**
     * Absorve um bloco de amostras. `seconds` é quanto tempo passou desde a
     * última chamada, e é o que faz a queda ser por tempo e não por bloco:
     * sem isso o medidor cairia mais rápido em aparelho com buffer menor.
     */
    fun absorb(samples: FloatArray, count: Int = samples.size, seconds: Float) {
        if (count <= 0) return
        var sum = 0.0
        for (i in 0 until count) sum += (samples[i] * samples[i]).toDouble()
        val rms = sqrt(sum / count).toFloat()
        val target = normalize(rms = rms, floorDecibels = floorDecibels)

        level = if (target >= level) {
            // Ataque imediato: a batida tem que aparecer no mesmo quadro.
            target
        } else {
            maxOf(target, level - decayPerSecond * seconds)
        }
    }

    fun reset() {
        level = 0f
    }

    companion object {
        /**
         * RMS linear para 0…1 numa escala em decibéis, que é como o ouvido
         * mede. Em escala linear, metade da energia parece quase igual e a
         * barra mal sai do lugar em música normalizada.
         */
        fun normalize(rms: Float, floorDecibels: Float = -60f): Float {
            if (rms.isNaN() || rms <= 0f || !rms.isFinite()) return 0f
            val decibels = 20 * log10(rms)
            if (decibels <= floorDecibels) return 0f
            return minOf(1f, (decibels - floorDecibels) / -floorDecibels)
        }
    }
}

/**
 * Agrupa um espectro em poucas bandas espaçadas como o ouvido escuta — port
 * do `SpectrumBands.swift`. Espaçamento logarítmico (grave, corpo e brilho
 * com largura parecida) e inclinação de +4,5 dB/oitava para a forma usar a
 * largura toda em música real.
 */
class SpectrumBands(
    count: Int,
    binCount: Int,
    sampleRate: Double,
    lowest: Double = 30.0,
    highest: Double = 10000.0,
    tiltPerOctave: Double = 4.5,
) {
    val count: Int = maxOf(1, count)
    private val edges: IntArray
    private val gains: FloatArray

    init {
        val nyquist = sampleRate / 2
        val top = minOf(highest, nyquist * 0.99)
        val edges = IntArray(this.count + 1)
        for (index in 0..this.count) {
            val fraction = index.toDouble() / this.count
            val frequency = lowest * (top / lowest).pow(fraction)
            val bin = ((frequency / nyquist) * binCount).toInt()
            edges[index] = bin.coerceIn(0, binCount - 1)
        }
        // Banda vazia desenharia uma coluna sempre zerada no meio da onda.
        for (index in 1..this.count) {
            if (edges[index] <= edges[index - 1]) {
                edges[index] = minOf(edges[index - 1] + 1, binCount - 1)
            }
        }
        this.edges = edges

        this.gains = FloatArray(this.count) { band ->
            val fraction = (band + 0.5) / this.count
            val frequency = lowest * (top / lowest).pow(fraction)
            val octaves = log2(frequency / lowest)
            10.0.pow(tiltPerOctave * octaves / 20).toFloat()
        }
    }

    /** Energia de cada banda, já em 0…1, pronta para virar altura. */
    fun magnitudes(re: FloatArray, im: FloatArray): FloatArray {
        val result = FloatArray(count)
        for (band in 0 until count) {
            val from = edges[band]
            val to = maxOf(edges[band + 1], from + 1)
            var sum = 0f
            for (bin in from until minOf(to, re.size)) {
                sum += re[bin] * re[bin] + im[bin] * im[bin]
            }
            val rms = sqrt(sum / maxOf(to - from, 1)) * gains[band]
            result[band] = LevelMeter.normalize(rms = rms, floorDecibels = -70f)
        }
        return result
    }
}
