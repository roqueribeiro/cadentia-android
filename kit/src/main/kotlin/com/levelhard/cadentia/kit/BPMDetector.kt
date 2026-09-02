package com.levelhard.cadentia.kit

import kotlin.math.roundToInt

/**
 * Detector de BPM — port 1:1 do `BPMDetector.swift` (que porta o
 * `utils/music/bpmDetector.js` do web): detecção de onset sobre um envelope
 * de RMS + mediana dos intervalos entre onsets. Funciona para andamento
 * estável (palmas, bumbo, clique); não é análise de grade de batida.
 */
class BPMDetector {
    private companion object {
        const val ENVELOPE_HISTORY = 60 // ~2 s a ~30 Hz de atualização
        const val ONSET_HISTORY = 32
        const val MIN_INTERVAL_MS = 250.0 // > 240 BPM = ruído
        const val MAX_INTERVAL_MS = 1500.0 // < 40 BPM = nota sustentada
        const val PEAK_THRESHOLD = 1.25 // valor > 1,25 × média móvel = pico
    }

    private val envelopeBuffer = ArrayDeque<Double>()
    private val onsets = ArrayDeque<Double>() // timestamps em ms
    private var lastOnsetTime = 0.0

    /**
     * Alimenta uma amostra de RMS (0…1) com seu timestamp; devolve a
     * estimativa atual de BPM, ou null enquanto não há tempo estável.
     */
    fun processSample(rms: Double, nowMs: Double): Int? {
        envelopeBuffer.addLast(rms)
        if (envelopeBuffer.size > ENVELOPE_HISTORY) envelopeBuffer.removeFirst()
        if (envelopeBuffer.size < 5) return null

        // Threshold dinâmico = média móvel × PEAK_THRESHOLD.
        val avg = envelopeBuffer.sum() / envelopeBuffer.size
        val threshold = maxOf(0.02, avg * PEAK_THRESHOLD)

        // Pico = acima do threshold E subindo sobre as duas amostras anteriores.
        val count = envelopeBuffer.size
        val prev = if (count >= 2) envelopeBuffer.elementAt(count - 2) else 0.0
        val prevPrev = if (count >= 3) envelopeBuffer.elementAt(count - 3) else 0.0
        val isPeak = rms > threshold && rms > prev && prev > prevPrev

        if (isPeak && nowMs - lastOnsetTime > MIN_INTERVAL_MS / 2) {
            onsets.addLast(nowMs)
            lastOnsetTime = nowMs
            if (onsets.size > ONSET_HISTORY) onsets.removeFirst()
        }

        return computeBpm()
    }

    fun reset() {
        envelopeBuffer.clear()
        onsets.clear()
        lastOnsetTime = 0.0
    }

    private fun computeBpm(): Int? {
        if (onsets.size < 4) return null
        val intervals = mutableListOf<Double>()
        val list = onsets.toList()
        for (i in 1 until list.size) {
            val dt = list[i] - list[i - 1]
            if (dt in MIN_INTERVAL_MS..MAX_INTERVAL_MS) intervals.add(dt)
        }
        if (intervals.size < 3) return null

        // Mediana ganha da média contra outliers.
        intervals.sort()
        val mid = intervals.size / 2
        val medianMs = if (intervals.size % 2 == 0) {
            (intervals[mid - 1] + intervals[mid]) / 2
        } else {
            intervals[mid]
        }

        val bpm = (60000 / medianMs).roundToInt()
        return if (bpm in 40..240) bpm else null
    }
}
