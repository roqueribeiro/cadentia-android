package com.levelhard.cadentia.kit.cordas

/**
 * O contorno do instrumento desenhado no ar — port do `GuitarProfile.swift`.
 *
 * Um corpo de violão é uma curva contínua, e a única forma honesta de desenhar
 * uma é carregar a própria curva: metade da silhueta, como estações medidas ao
 * longo do corpo do rabo até a junção do braço, com interpolação suave entre
 * elas. A outra metade é o espelho. Mora no Kit porque forma é dado, e porque
 * as coisas que fazem parecer um violão — a cintura mais estreita que os dois
 * bojos, o bojo inferior mais largo que o superior — são propriedades que dá
 * para afirmar.
 */
data class GuitarProfile(val stations: List<Station>) {
    /** `position`: 0 no rabo, 1 na junção do braço. `halfWidth`: fração da meia largura maior. */
    data class Station(val position: Double, val halfWidth: Double)

    private val sorted: List<Station> = stations.sortedBy { it.position }

    /** Catmull-Rom entre as estações: passa por todas e ainda tem tangente contínua. */
    fun halfWidth(position: Double): Double {
        val first = sorted.firstOrNull() ?: return 0.0
        val last = sorted.last()
        if (position <= first.position) return first.halfWidth
        if (position >= last.position) return last.halfWidth
        val upper = sorted.indexOfFirst { it.position > position }
        if (upper <= 0) return last.halfWidth
        val lower = upper - 1
        val p1 = sorted[lower]
        val p2 = sorted[upper]
        val p0 = sorted[maxOf(0, lower - 1)]
        val p3 = sorted[minOf(sorted.size - 1, upper + 1)]
        val span = p2.position - p1.position
        if (span <= 0) return p1.halfWidth
        val t = (position - p1.position) / span
        val t2 = t * t
        val t3 = t2 * t
        val value = 0.5 * (
            2 * p1.halfWidth +
                (-p0.halfWidth + p2.halfWidth) * t +
                (2 * p0.halfWidth - 5 * p1.halfWidth + 4 * p2.halfWidth - p3.halfWidth) * t2 +
                (-p0.halfWidth + 3 * p1.halfWidth - 3 * p2.halfWidth + p3.halfWidth) * t3
            )
        return maxOf(0.0, value)
    }

    /**
     * A silhueta inteira, pronta para traçar: sobe pela direita do rabo à
     * junção, e volta pela esquerda. `samples` pontos por lado.
     */
    fun outline(samples: Int = 60): List<Point> {
        val count = maxOf(8, samples)
        val right = ArrayList<Point>(count + 1)
        val left = ArrayList<Point>(count + 1)
        for (step in 0..count) {
            val position = step.toDouble() / count
            val width = halfWidth(position)
            right.add(Point(position, width))
            left.add(Point(position, -width))
        }
        return right + left.reversed()
    }

    /** Onde o ponto mais largo de cada bojo fica, para colocar cavalete e boca contra a forma. */
    val lowerBout: Double get() = widest(0.05, 0.45)
    val waist: Double get() = narrowest(0.45, 0.68)
    val upperBout: Double get() = widest(0.68, 0.90)

    private fun widest(from: Double, to: Double): Double = extreme(from, to) { a, b -> a > b }
    private fun narrowest(from: Double, to: Double): Double = extreme(from, to) { a, b -> a < b }

    private fun extreme(from: Double, to: Double, better: (Double, Double) -> Boolean): Double {
        var best = from
        var bestWidth = halfWidth(best)
        var position = from
        while (position <= to + 1e-9) {
            val width = halfWidth(position)
            if (better(width, bestWidth)) {
                bestWidth = width
                best = position
            }
            position += 0.005
        }
        return best
    }

    companion object {
        /** Proporções de dreadnought: bojo inferior a um quarto, cintura pouco depois do meio. */
        val acoustic = GuitarProfile(
            listOf(
                Station(0.00, 0.00), Station(0.02, 0.46), Station(0.06, 0.72),
                Station(0.13, 0.91), Station(0.22, 0.995), Station(0.30, 1.00),
                Station(0.40, 0.96), Station(0.48, 0.86), Station(0.55, 0.755),
                Station(0.58, 0.745), Station(0.63, 0.79), Station(0.70, 0.855),
                Station(0.77, 0.875), Station(0.83, 0.855), Station(0.90, 0.75),
                Station(0.955, 0.60), Station(1.00, 0.46),
            ),
        )

        /** Duplo recorte, chifres deslocados — a forma que um corpo maciço lê. */
        val electric = GuitarProfile(
            listOf(
                Station(0.00, 0.00), Station(0.02, 0.42), Station(0.07, 0.70),
                Station(0.15, 0.90), Station(0.26, 0.98), Station(0.34, 1.00),
                Station(0.44, 0.93), Station(0.52, 0.79), Station(0.58, 0.70),
                Station(0.63, 0.72), Station(0.70, 0.83), Station(0.755, 0.88),
                Station(0.80, 0.80), Station(0.86, 0.60), Station(0.92, 0.44),
                Station(0.97, 0.40), Station(1.00, 0.40),
            ),
        )
    }
}
