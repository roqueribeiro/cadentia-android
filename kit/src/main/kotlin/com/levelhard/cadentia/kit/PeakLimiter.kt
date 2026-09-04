package com.levelhard.cadentia.kit

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/**
 * O teto da saída do player de stems — o papel do `kAudioUnitSubType_PeakLimiter`
 * da Apple no `StemPlayer.swift`, que o Android não tem pronto.
 *
 * Uma faixa isolada passa de 0 dBFS com facilidade: baixo e voz que se
 * cancelam parcialmente na mistura aparecem inteiros quando separados. Com a
 * cadeia em ganho unitário, tirar o solo do baixo bastava para saturar. Este
 * limitador não faz nada com material abaixo do teto e só age acima dele.
 *
 * Um ganho só para os dois canais (senão a imagem estéreo pula de lado quando
 * um canal estoura), ataque instantâneo por amostra (é limitador de PICO, não
 * compressor: nada pode passar do teto) e volta exponencial com constante de
 * tempo de [releaseSeconds]. Sem lookahead: o ataque instantâneo já garante o
 * teto, e lookahead custaria latência que o loop A/B sentiria no seek.
 */
class PeakLimiter(
    private val sampleRate: Int,
    val ceiling: Float = 0.98f,
    releaseSeconds: Double = 0.12,
) {
    private val releaseCoefficient = exp(-1.0 / (releaseSeconds * sampleRate)).toFloat()

    /** Ganho em vigor (1 = transparente). Exposto para teste e medidor. */
    var gain = 1f
        private set

    /** Processa `count` quadros de L/R no lugar. */
    fun process(left: FloatArray, right: FloatArray, count: Int) {
        var g = gain
        val ceiling = ceiling
        val release = releaseCoefficient
        for (i in 0 until count) {
            val peak = max(abs(left[i]), abs(right[i]))
            // O ganho que este quadro exige; se for menor que o atual, cai já.
            val needed = if (peak > ceiling) ceiling / peak else 1f
            g = if (needed < g) needed else 1f - (1f - g) * release
            left[i] *= g
            right[i] *= g
        }
        gain = g
    }

    fun reset() {
        gain = 1f
    }
}
