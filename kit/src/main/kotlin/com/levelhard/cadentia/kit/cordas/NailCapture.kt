package com.levelhard.cadentia.kit.cordas

import kotlin.math.abs
import kotlin.math.pow

/**
 * Pegar uma unha numa tela de toque — port do `NailCapture.swift`.
 *
 * Uma unha não é uma ponta de dedo: o contato é minúsculo e duro, a tela perde
 * o dedo no meio da passada e devolve um gesto picado. Três coisas consertam:
 * alvo por FAIXA (cada corda é dona da metade do vão), MEMÓRIA do gesto (uma
 * passada partida em duas é costurada e as cordas do meio soam), e o tamanho
 * do contato vira timbre (fino é unha, largo é carne).
 *
 * E o refratário é por POSIÇÃO, não por tempo: uma corda só soa de novo
 * quando o dedo a deixou DE VERDADE, por mais de meio vão. Tremor não sai;
 * passada de verdade sai.
 */
class NailCapture {
    data class Pluck(
        val string: Int,
        val velocity: Double,
        val delay: Double,
        /** 0 = carne, 1 = unha. Abre o brilho da nota. */
        val nail: Double,
        val muted: Boolean,
    )

    private var armed = BooleanArray(0)
    private var lift: Triple<Double, Double, Double>? = null // x, y, time

    fun reset(stringCount: Int) {
        armed = BooleanArray(stringCount) { true }
        lift = null
    }

    /** Cordas que o dedo deixou de verdade voltam para a mesa. */
    private fun rearm(x: Double, layout: FretboardLayout) {
        val halfGap = layout.laneHalfGap
        for (i in layout.stringX.indices) {
            if (i < armed.size && !armed[i] && abs(x - layout.stringX[i]) > halfGap) armed[i] = true
        }
    }

    /** A unha pousou dentro da faixa de uma corda: uma nota leve e única. */
    fun touchDown(x: Double, y: Double, time: Double, nail: Double, muted: Boolean, layout: FretboardLayout): List<Pluck> {
        if (armed.size != layout.stringX.size) reset(layout.stringX.size)

        // A tela perdeu a unha no meio da passada? Costura o gesto e toca as
        // cordas que ficaram penduradas, em vez de começar um toque novo.
        val lift = this.lift
        if (lift != null && time - lift.third < STITCH_WINDOW &&
            abs(x - lift.first) < STITCH_DISTANCE && abs(y - lift.second) < STITCH_DISTANCE * 1.4
        ) {
            val dt = maxOf(0.008, time - lift.third)
            val from = lift.first
            this.lift = null
            return sweep(from, x, dt, nail, muted, layout)
        }
        this.lift = null

        val index = layout.stringAt(x)
        if (abs(layout.stringX[index] - x) >= layout.laneRadius) return emptyList()
        if (index >= armed.size) return emptyList()

        // UM DEDO QUE SAIU DA TELA E VOLTOU É UM ATAQUE NOVO: não pergunta se a
        // corda está armada. O refratário posicional existe para o tremor com o
        // dedo AINDA em baixo, e levantar não é tremor.
        armed[index] = false
        return listOf(Pluck(string = index, velocity = 0.34 + nail * 0.24, delay = 0.0, nail = nail, muted = muted))
    }

    /**
     * A passada, com tempo real: entre duas amostras do toque a unha cruzou
     * cada corda num instante diferente. Interpolamos a fração do caminho e
     * agendamos cada nota no cruzamento exato — é o que separa "seis notas de
     * uma vez" de uma batida com começo, meio e fim.
     */
    fun sweep(previousX: Double, x: Double, dt: Double, nail: Double, muted: Boolean, layout: FretboardLayout): List<Pluck> {
        if (armed.size != layout.stringX.size) reset(layout.stringX.size)
        if (previousX == x) return emptyList()
        rearm(x, layout)

        val speed = abs(x - previousX) / maxOf(dt, 0.004)
        val base = velocity(speed)
        val down = x > previousX

        val hits = ArrayList<Pair<Int, Double>>()
        for (i in layout.stringX.indices) {
            val position = layout.stringX[i]
            if ((position > previousX && position <= x) || (position < previousX && position >= x)) {
                hits.add(i to (position - previousX) / (x - previousX))
            }
        }
        if (hits.isEmpty()) return emptyList()
        hits.sortBy { it.second }
        val first = hits[0].second

        val out = ArrayList<Pluck>()
        var k = 0
        for ((index, fraction) in hits) {
            val slot = k
            k += 1
            if (index >= armed.size || !armed[index]) continue
            armed[index] = false
            val v = base * (1 - slot * 0.035) * (if (down) 1.0 else 0.88)
            out.add(Pluck(string = index, velocity = v.coerceIn(0.1, 1.0), delay = (fraction - first) * dt, nail = nail, muted = muted))
        }
        return out
    }

    /** Lembra onde o contato se perdeu, para uma passada partida ser costurada. */
    fun touchUp(x: Double, y: Double, time: Double) {
        lift = Triple(x, y, time)
    }

    val armedForTesting: List<Boolean> get() = armed.toList()

    companion object {
        /** Quanto tempo um contato perdido pode ficar perdido e ainda ser o mesmo gesto. */
        const val STITCH_WINDOW: Double = 0.160
        /** E de quão longe ele pode voltar. */
        const val STITCH_DISTANCE: Double = 90.0

        /** Contato fino é unha, largo é carne. Sem tamanho reportado, assume unha. */
        fun nailness(contactWidth: Double?, enabled: Boolean): Double {
            if (!enabled) return 0.35
            if (contactWidth == null || contactWidth <= 0) return 0.8
            return (1 - (contactWidth - 8) / 26).coerceIn(0.0, 1.0)
        }

        /** A velocidade do gesto vira a dinâmica. */
        fun velocity(pixelsPerSecond: Double): Double {
            val clamped = pixelsPerSecond.coerceIn(0.0, 4200.0)
            return (0.16 + (clamped / 2600).pow(0.68) * 0.9).coerceIn(0.12, 1.0)
        }
    }
}
