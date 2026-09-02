package com.levelhard.cadentia.kit

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Buffer PCM estéreo: a moeda do motor HD — port do `StereoBuffer` do
 * `AudioDSP.swift`. Toda voz HD renderiza num destes offline e o sampler
 * agenda direto. Render mono era o maior motivo de o motor antigo soar
 * chapado: bateria com toda peça no centro da imagem lê como brinquedo.
 */
class StereoBuffer {
    var left: FloatArray
        private set
    var right: FloatArray
        private set

    constructor(left: FloatArray, right: FloatArray) {
        val count = maxOf(left.size, right.size)
        this.left = if (left.size == count) left else left.copyOf(count)
        this.right = if (right.size == count) right else right.copyOf(count)
    }

    constructor(count: Int) {
        val safe = maxOf(count, 0)
        left = FloatArray(safe)
        right = FloatArray(safe)
    }

    /** O mesmo sinal dos dois lados (imagem central). */
    constructor(mono: FloatArray) {
        left = mono.copyOf()
        right = mono.copyOf()
    }

    val frameCount: Int get() = left.size
    val isEmpty: Boolean get() = left.isEmpty()

    /**
     * Cópia independente. O Swift copia o `struct` sozinho; aqui a classe é
     * referência, e um buffer que mora em cache NUNCA pode ser alterado no
     * lugar — ganho, pan e rampa entram numa cópia.
     */
    fun copy(): StereoBuffer = StereoBuffer(left.copyOf(), right.copyOf())

    val peak: Float
        get() {
            var peak = 0f
            for (i in left.indices) {
                peak = maxOf(peak, abs(left[i]), abs(right[i]))
            }
            return peak
        }

    fun applyGain(gain: Float) {
        if (gain == 1f) return
        for (i in left.indices) {
            left[i] *= gain
            right[i] *= gain
        }
    }

    /** Mistura `other` a partir de um offset de frames, crescendo se precisar. */
    fun mix(other: StereoBuffer, atFrame: Int = 0, gain: Float = 1f) {
        if (other.isEmpty || gain == 0f) return
        val start = maxOf(0, atFrame)
        val needed = start + other.frameCount
        if (needed > frameCount) {
            left = left.copyOf(needed)
            right = right.copyOf(needed)
        }
        for (i in 0 until other.frameCount) {
            left[start + i] += other.left[i] * gain
            right[start + i] += other.right[i] * gain
        }
    }

    /**
     * Alargamento estilo Haas: alguns samples de atraso mais um peso num
     * lado. Mantém compatibilidade mono (atraso < 12 ms) e abre a imagem em
     * pratos, pads e violões dobrados.
     */
    fun widen(byFrames: Int, amount: Float = 0.5f) {
        if (byFrames <= 0 || byFrames >= frameCount || amount <= 0f) return
        val delayed = FloatArray(frameCount)
        for (i in byFrames until frameCount) {
            delayed[i] = right[i - byFrames]
        }
        for (i in right.indices) {
            right[i] = right[i] * (1 - amount * 0.5f) + delayed[i] * amount
        }
    }

    /** Apara a cauda silenciosa: buffer em cache não carrega frame morto. */
    fun trimTail(threshold: Float = 1e-5f) {
        var last = frameCount - 1
        while (last > 0 && abs(left[last]) < threshold && abs(right[last]) < threshold) {
            last -= 1
        }
        val keep = minOf(frameCount, last + 2)
        if (keep < frameCount) {
            left = left.copyOf(keep)
            right = right.copyOf(keep)
        }
    }

    /** L/R intercalado, o layout que o motor de saída quer para estéreo. */
    fun interleaved(): FloatArray {
        val out = FloatArray(frameCount * 2)
        for (i in 0 until frameCount) {
            out[i * 2] = left[i]
            out[i * 2 + 1] = right[i]
        }
        return out
    }

    fun summedToMono(): FloatArray {
        val out = FloatArray(frameCount)
        for (i in 0 until frameCount) {
            out[i] = (left[i] + right[i]) * 0.5f
        }
        return out
    }

    override fun equals(other: Any?): Boolean =
        other is StereoBuffer && left.contentEquals(other.left) && right.contentEquals(other.right)

    override fun hashCode(): Int = 31 * left.contentHashCode() + right.contentHashCode()

    companion object {
        /** Pan de potência igual de um sinal mono. `pan` vai de -1 a +1. */
        fun panned(mono: FloatArray, pan: Float, gain: Float = 1f): StereoBuffer {
            val clamped = pan.coerceIn(-1f, 1f)
            val angle = (clamped + 1) * Math.PI.toFloat() / 4
            val gainL = cos(angle) * gain * 1.41421356f
            val gainR = sin(angle) * gain * 1.41421356f
            val left = FloatArray(mono.size)
            val right = FloatArray(mono.size)
            for (i in mono.indices) {
                left[i] = mono[i] * gainL
                right[i] = mono[i] * gainR
            }
            return StereoBuffer(left, right)
        }
    }
}
