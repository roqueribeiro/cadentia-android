package com.levelhard.cadentia.kit

import kotlin.math.sqrt

/**
 * Detecção de pitch YIN (Cheveigné & Kawahara 2002) — port 1:1 do
 * `YINPitchDetector.swift` (que porta o `utils/music/pitchAlgorithms.js` do
 * web). Domínio do tempo, ~1 cent de precisão em nota musical. Mesmo tuning
 * do web: threshold 0,15, banda 50–2000 Hz, gate de RMS 0,005.
 *
 * O iOS vetoriza a função de diferença com vDSP; aqui o laço é direto — em
 * ART/JVM um buffer de 2048 fica na casa de milissegundo, e o contrato (mesmos
 * números) é o que os testes prendem.
 */
object YINPitchDetector {
    const val DEFAULT_THRESHOLD: Float = 0.15f
    internal const val MIN_FREQUENCY: Double = 50.0
    internal const val MAX_FREQUENCY: Double = 2000.0
    internal const val MIN_RMS: Float = 0.005f

    data class Pitch(
        val frequency: Double,
        /** 1 - profundidade do vale: quão periódica (confiável) é a detecção, 0…1. */
        val clarity: Double,
    )

    fun detect(
        buffer: FloatArray,
        sampleRate: Double,
        threshold: Float = DEFAULT_THRESHOLD,
    ): Pitch? {
        val bufferSize = buffer.size
        if (bufferSize < 256) return null
        val halfSize = bufferSize / 2

        // Gate de RMS: não caçar pitch no silêncio.
        var sumSquares = 0.0
        for (x in buffer) sumSquares += (x * x).toDouble()
        val rms = sqrt(sumSquares / bufferSize).toFloat()
        if (rms < MIN_RMS) return null

        // Passo 1: função de diferença d(tau) = Σ (x[i] - x[i+tau])²
        val yinBuffer = FloatArray(halfSize)
        for (tau in 0 until halfSize) {
            var sum = 0.0f
            for (i in 0 until halfSize) {
                val delta = buffer[i] - buffer[i + tau]
                sum += delta * delta
            }
            yinBuffer[tau] = sum
        }

        // Passo 2: diferença normalizada pela média acumulada
        yinBuffer[0] = 1f
        var runningSum = 0f
        for (tau in 1 until halfSize) {
            runningSum += yinBuffer[tau]
            yinBuffer[tau] = if (runningSum > 0) yinBuffer[tau] * tau / runningSum else 1f
        }

        // Passo 3: primeiro vale abaixo do threshold, caminhado até o mínimo local
        var tauEstimate = -1
        var tau = 2
        while (tau < halfSize) {
            if (yinBuffer[tau] < threshold) {
                while (tau + 1 < halfSize && yinBuffer[tau + 1] < yinBuffer[tau]) {
                    tau += 1
                }
                tauEstimate = tau
                break
            }
            tau += 1
        }
        if (tauEstimate == -1) return null

        // Passo 4: interpolação parabólica para precisão sub-amostra
        var betterTau = tauEstimate.toDouble()
        if (tauEstimate > 0 && tauEstimate < halfSize - 1) {
            val s0 = yinBuffer[tauEstimate - 1].toDouble()
            val s1 = yinBuffer[tauEstimate].toDouble()
            val s2 = yinBuffer[tauEstimate + 1].toDouble()
            val denominator = 2 * (2 * s1 - s2 - s0)
            if (denominator != 0.0) {
                betterTau = tauEstimate + (s2 - s0) / denominator
            }
        }

        val frequency = sampleRate / betterTau
        if (frequency < MIN_FREQUENCY || frequency > MAX_FREQUENCY) return null

        val clarity = (1 - yinBuffer[tauEstimate].toDouble()).coerceIn(0.0, 1.0)
        return Pitch(frequency = frequency, clarity = clarity)
    }
}
