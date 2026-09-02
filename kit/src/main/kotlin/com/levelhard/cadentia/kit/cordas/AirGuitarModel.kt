package com.levelhard.cadentia.kit.cordas

import kotlin.math.abs

/** Por que uma pose não está sendo aceita como alguém segurando um instrumento. */
enum class PoseHint { None, TooUpright, TooFlat }

/**
 * Como o instrumento se senta, o que muda as proporções na tela. Sentado, a
 * pessoa está perto da câmera: as mãos parecem enormes e ficam longe uma da
 * outra. Em pé, a proporção inteira cabe.
 */
data class AirGuitarPosture(
    /**
     * Vão entre cordas, em comprimentos de braço. **O número mais importante
     * do modo inteiro.** As cordas tomam o CURSO NATURAL da mão — cerca de dois
     * terços do vão entre as mãos — para qualquer passada achar uma corda.
     */
    val spacing: Double,
    val bridge: Double,
    val soundhole: Double,
    val nut: Double,
    val head: Double,
    val id: String,
) {
    val flipped: AirGuitarPosture get() = if (id == "depe") seated else standing

    companion object {
        val standing = AirGuitarPosture(spacing = 0.075, bridge = -0.50, soundhole = 0.05, nut = 1.02, head = 1.18, id = "depe")
        val seated = AirGuitarPosture(spacing = 0.090, bridge = -0.38, soundhole = 0.04, nut = 0.99, head = 1.14, id = "sentado")
    }
}

/**
 * O instrumento desenhado entre as mãos, como uma forma só com uma escala só —
 * port do `AirGuitarModel.swift`. Tudo deriva de duas coisas: onde estão as
 * cordas (não negociável: é o que as mãos tocam) e a proporção de um corpo de
 * violão. Uma escala, uma forma.
 */
class AirGuitarModel(posture: AirGuitarPosture, stringCount: Int) {
    /** Metade do vão das cordas. Tudo no sentido transversal se mede contra ela. */
    val stringHalf: Double = posture.spacing * maxOf(1, stringCount - 1) / 2 + 0.028
    val bodyHalf: Double = stringHalf * BODY_SPREAD
    val bodyLength: Double
    val neckHalfAtJoin: Double = stringHalf * 1.22
    val neckHalfAtNut: Double = stringHalf * 1.06
    val headHalf: Double = stringHalf * 1.30

    /** Marcos ao longo do eixo, em comprimentos de braço — as coordenadas de `AirGuitarGeometry.point`. */
    val tail: Double
    val bridge: Double
    val soundhole: Double
    val neckJoin: Double
    val nut: Double
    val head: Double

    init {
        // O COMPRIMENTO DO CORPO SAI DA ESCALA, NÃO DA PRÓPRIA LARGURA: um corpo
        // de violão tem ~0,78 da escala; a largura é o que contém as cordas,
        // com um piso na proporção para nunca virar uma bacia.
        val scale = posture.nut - (posture.bridge + 0.10)
        bodyLength = maxOf(scale * BODY_OVER_SCALE, bodyHalf * 2 * MINIMUM_ASPECT)
        // A única âncora que não é nossa: as cordas começam no cavalete.
        bridge = posture.bridge + 0.10
        tail = bridge - BRIDGE_ALONG_BODY * bodyLength
        neckJoin = tail + bodyLength
        soundhole = tail + SOUNDHOLE_ALONG_BODY * bodyLength
        nut = posture.nut
        head = nut + HEAD_BEYOND_NUT
    }

    /** Fração ao longo do corpo, do rabo, para um ponto no eixo. */
    fun bodyFraction(along: Double): Double = if (bodyLength > 0) (along - tail) / bodyLength else 0.0

    /** Meia largura do braço num ponto entre o corpo e a pestana. */
    fun neckHalf(along: Double): Double {
        val span = maxOf(0.001, nut - neckJoin)
        val travelled = ((along - neckJoin) / span).coerceIn(0.0, 1.0)
        return neckHalfAtJoin + (neckHalfAtNut - neckHalfAtJoin) * travelled
    }

    /** O instrumento inteiro, do rabo à cabeça, em comprimentos de braço. */
    val totalLength: Double get() = head - tail

    /** Do cavalete à pestana — a régua do próprio instrumento. */
    val scaleLength: Double get() = nut - bridge

    /**
     * O maior comprimento de braço cujo instrumento inteiro ainda cabe numa
     * view deste tamanho neste ângulo. Quando isto manda, o braço para antes da
     * mão do braço — custo real e o menor deles.
     */
    fun fittingLength(axis: Vector, anchor: Point, viewSize: Size): Double {
        val width = viewSize.width
        val height = viewSize.height
        if (width <= 0 || height <= 0) return Double.MAX_VALUE
        val perpendicular = Vector(-axis.dy, axis.dx)
        val margin = 4.0
        var limit = Double.MAX_VALUE
        for (along in doubleArrayOf(tail, head)) {
            for (across in doubleArrayOf(-bodyHalf, bodyHalf)) {
                val vx = axis.dx * along + perpendicular.dx * across
                val vy = axis.dy * along + perpendicular.dy * across
                for ((component, origin, extent) in listOf(Triple(vx, anchor.x, width), Triple(vy, anchor.y, height))) {
                    if (abs(component) <= 1e-6) continue
                    val room = if (component > 0) (extent - margin - origin) / component else (margin - origin) / component
                    if (room > 0) limit = minOf(limit, room)
                }
            }
        }
        return limit
    }

    companion object {
        /** Onde o cavalete fica ao longo do corpo, do rabo. Num dreadnought, a um terço. */
        const val BRIDGE_ALONG_BODY: Double = 0.34
        /** E a boca, logo abaixo do bojo superior. */
        const val SOUNDHOLE_ALONG_BODY: Double = 0.72
        /** Comprimento do corpo sobre a escala. Um dreadnought real tem ~0,78. */
        const val BODY_OVER_SCALE: Double = 0.78
        /** E nunca mais quadrado que isto. */
        const val MINIMUM_ASPECT: Double = 1.30
        /** Quanto o corpo é mais largo que as cordas. A única mentira honesta: no real é ~7. */
        const val BODY_SPREAD: Double = 1.7
        /** A cabeça, depois da pestana. */
        const val HEAD_BEYOND_NUT: Double = 0.20
    }
}
