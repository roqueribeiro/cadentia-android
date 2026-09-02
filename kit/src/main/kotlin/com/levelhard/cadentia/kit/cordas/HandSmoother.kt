package com.levelhard.cadentia.kit.cordas

import kotlin.math.PI
import kotlin.math.abs

/**
 * Tapa os buracos que um rastreador deixa e tira o tremor do que sobra — port
 * do `HandSmoother.swift`.
 *
 * **Os buracos.** O MediaPipe sempre devolve os 21 pontos, chutando quando
 * precisa; o Vision omite os que não vê. A geometria indexa juntas direto, e
 * um buraco ali não dá erro: dá `NaN` que envenena a máscara e o espalhamento.
 * Segurar o último valor bom por um instante, e desistir da mão inteira
 * depois, é o comportamento honesto.
 *
 * **O tremor.** Um passa-baixa fixo não resolve, porque as duas coisas que se
 * quer aqui são opostas: mão parada precisa de suavização pesada, e uma mão
 * cruzando as cordas a dois mil pontos por segundo não pode atrasar nem um
 * quadro. Este é o **filtro One Euro**, cujo corte sobe com a velocidade.
 */
class HandSmoother {
    /** Um eixo de uma junta. */
    private class OneEuro {
        var value: Double? = null
        var speed = 0.0

        fun filter(sample: Double, dt: Double): Double {
            val previous = value ?: run {
                value = sample
                return sample
            }
            val raw = (sample - previous) / maxOf(dt, 1e-4)
            speed += alpha(DERIVATIVE_CUTOFF, dt) * (raw - speed)
            val cutoff = MINIMUM_CUTOFF + SPEED_COUPLING * abs(speed)
            val filtered = previous + alpha(cutoff, dt) * (sample - previous)
            value = filtered
            return filtered
        }

        fun jump(sample: Double) {
            value = sample
            speed = 0.0
        }

        companion object {
            fun alpha(cutoff: Double, dt: Double): Double {
                val tau = 1 / (2 * PI * cutoff)
                return 1 / (1 + tau / maxOf(dt, 1e-4))
            }
        }
    }

    private var last = arrayOfNulls<Point>(21)
    private var lastSeen = DoubleArray(21) { -1e9 }
    private var filterX = Array(21) { OneEuro() }
    private var filterY = Array(21) { OneEuro() }
    private var lastTime: Double? = null

    fun reset() {
        last = arrayOfNulls(21)
        lastSeen = DoubleArray(21) { -1e9 }
        filterX = Array(21) { OneEuro() }
        filterY = Array(21) { OneEuro() }
        lastTime = null
    }

    /**
     * @param observed uma entrada por junta, na ordem de `HandJoint`; `null`
     *   quer dizer que o rastreador não a reportou neste quadro.
     * @return uma mão completa, ou null quando falta demais para confiar.
     */
    fun smooth(observed: List<Point?>, time: Double, chirality: HandChirality = HandChirality.Unknown): HandLandmarks? {
        if (observed.size != 21) return null
        // Um buraco longo é uma mão nova, não uma mão rápida.
        val elapsed = time - (lastTime ?: time)
        val dt = if (elapsed > HOLD) 0.0 else elapsed.coerceIn(1e-3, 0.2)
        lastTime = time

        // O pulso é a única junta sem a qual a mão não se reconstrói.
        val wrist = HandJoint.Wrist.index
        if (observed[wrist] == null && !(last[wrist] != null && time - lastSeen[wrist] <= HOLD)) {
            reset()
            return null
        }

        val out = Array(21) { Point.zero }
        var held = 0
        var invented = 0
        for (i in 0 until 21) {
            val raw: Point
            val point = observed[i]
            if (point != null) {
                last[i] = point
                lastSeen[i] = time
                raw = point
            } else {
                val previous = last[i]
                val parent = PARENT[i]
                if (previous != null && time - lastSeen[i] <= HOLD) {
                    raw = previous
                    held += 1
                } else if (parent != null && out[parent] != Point.zero) {
                    // Uma junta que o rastreador nunca mostrou cai na que ela
                    // pende. Jogar a mão INTEIRA fora porque uma ponta saiu do
                    // quadro é como um rastreador fica cego enquanto funciona.
                    raw = out[parent]
                    invented += 1
                } else {
                    return null // a mão sumiu de verdade
                }
            }
            if (dt <= 0) {
                filterX[i].jump(raw.x)
                filterY[i].jump(raw.y)
                out[i] = raw
            } else {
                out[i] = Point(filterX[i].filter(raw.x, dt), filterY[i].filter(raw.y, dt))
            }
        }
        // Segurar metade da mão não é mais uma mão; é uma lembrança.
        if (held > 10 || invented > 8) return null
        return HandLandmarks(out.toList(), chirality)
    }

    companion object {
        /** Por quanto tempo uma junta pode guardar a última posição conhecida. */
        const val HOLD: Double = 0.25
        /** Abaixo disto o Vision está chutando e preferimos segurar. */
        const val MINIMUM_CONFIDENCE: Double = 0.3
        /** Corte em repouso, em Hz. Menor é mais calmo e mais preguiçoso. */
        const val MINIMUM_CUTOFF: Double = 1.6
        /** Quão rápido o corte abre com a velocidade, em Hz por ponto por segundo. */
        const val SPEED_COUPLING: Double = 0.022
        /** Corte da própria estimativa de velocidade. */
        const val DERIVATIVE_CUTOFF: Double = 1.0

        /** De que cada junta pende, andando para o pulso. A ordem garante pai antes de filho. */
        internal val PARENT: List<Int?> = listOf(
            null,
            HandJoint.Wrist.index, HandJoint.ThumbCMC.index, HandJoint.ThumbMCP.index, HandJoint.ThumbIP.index,
            HandJoint.Wrist.index, HandJoint.IndexMCP.index, HandJoint.IndexPIP.index, HandJoint.IndexDIP.index,
            HandJoint.Wrist.index, HandJoint.MiddleMCP.index, HandJoint.MiddlePIP.index, HandJoint.MiddleDIP.index,
            HandJoint.Wrist.index, HandJoint.RingMCP.index, HandJoint.RingPIP.index, HandJoint.RingDIP.index,
            HandJoint.Wrist.index, HandJoint.LittleMCP.index, HandJoint.LittlePIP.index, HandJoint.LittleDIP.index,
        )
    }
}
