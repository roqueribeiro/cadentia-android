package com.levelhard.cadentia.kit

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Uma sessão gravada do afinador: linha do tempo de pitch + o arquivo de
 * áudio a que pertence — port do `TunerSession.swift`. As métricas espelham
 * o computed do `TunerSessionModal` do web (nota dominante, % afinado,
 * desvio médio).
 */
data class TunerSession(
    /** Caminho do arquivo de áudio da sessão (null quando só linha do tempo). */
    val audioPath: String?,
    val timeline: List<Point>,
    val durationMs: Double,
) {
    data class Point(
        /** Milissegundos desde o início da gravação. */
        val t: Double,
        val frequency: Double,
        val cents: Int,
        /** Nome exibido ("A4") naquele instante. */
        val note: String,
    )

    data class Metrics(
        val dominantNote: String?,
        val inTunePercent: Int,
        /** Média de |cents| na linha do tempo; null quando vazia. */
        val averageDriftCents: Double?,
    )

    val metrics: Metrics
        get() {
            if (timeline.isEmpty()) {
                return Metrics(dominantNote = null, inTunePercent = 0, averageDriftCents = null)
            }
            val noteCounts = mutableMapOf<String, Int>()
            var totalCents = 0.0
            var inTuneCount = 0
            for (point in timeline) {
                noteCounts[point.note] = (noteCounts[point.note] ?: 0) + 1
                totalCents += abs(point.cents).toDouble()
                if (abs(point.cents) <= 5) inTuneCount += 1
            }
            // Contagem vence; empate desempata pelo nome alfabeticamente menor
            // (o mesmo comparador do Swift).
            val dominant = noteCounts.entries.maxWithOrNull(
                Comparator { l, r ->
                    if (l.value != r.value) l.value.compareTo(r.value)
                    else r.key.compareTo(l.key)
                },
            )?.key
            return Metrics(
                dominantNote = dominant,
                inTunePercent = (inTuneCount.toDouble() / timeline.size * 100).roundToInt(),
                averageDriftCents = totalCents / timeline.size,
            )
        }
}
