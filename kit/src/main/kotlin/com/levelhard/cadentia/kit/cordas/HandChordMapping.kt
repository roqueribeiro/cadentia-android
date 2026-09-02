package com.levelhard.cadentia.kit.cordas

import kotlinx.serialization.Serializable

/**
 * O acorde é um DESENHO da mão, não uma contagem de dedos — port do
 * `HandChordMapping.swift`.
 *
 * Um bit por dedo — indicador 1, médio 2, anelar 4, mindinho 8. O polegar fica
 * de fora porque a câmera lê mal a flexão dele. Contar dedos dá quatro
 * acordes; a COMBINAÇÃO dá quinze, e cada uma é uma forma que se faz de
 * propósito em vez de um número que se tem de acertar.
 */
object HandChordMapping {
    object Finger {
        const val INDEX = 1
        const val MIDDLE = 2
        const val RING = 4
        const val LITTLE = 8
    }

    /**
     * Só as formas que uma mão faz sem contorção, na ordem em que saem mais
     * fácil: um a quatro dedos em fila, depois os chifres, depois as variantes
     * ainda possíveis. Anelar sozinho e mindinho sozinho ficam de fora.
     */
    val shapes: List<Int> = listOf(
        0b0001, 0b0011, 0b0111, 0b1111,
        0b1001,
        0b0010, 0b0110, 0b1110, 0b1000,
    )

    fun fingerCount(mask: Int): Int =
        (mask and 1) + ((mask shr 1) and 1) + ((mask shr 2) and 1) + ((mask shr 3) and 1)

    /**
     * Toda forma pela qual uma mão real passa cai na listada mais próxima: mesma
     * contagem de dedos primeiro, depois o menor número de bits diferentes.
     * Ninguém fica sem acorde.
     */
    fun nearestShape(mask: Int): Int {
        val m = mask and 0b1111
        if (m in shapes) return m
        val count = fingerCount(m)
        var best = shapes[0]
        var bestCost = Int.MAX_VALUE
        for (shape in shapes) {
            val cost = fingerCount(m xor shape) + (if (fingerCount(shape) == count) 0 else 2)
            if (cost < bestCost) {
                bestCost = cost
                best = shape
            }
        }
        return best
    }

    /**
     * Qual acorde uma forma de mão significa, dados os acordes em oferta. O
     * índice dá a volta, para toda forma sempre tocar alguma coisa.
     */
    fun chord(mask: Int, names: List<String>): String? {
        if (names.isEmpty()) return null
        val index = shapes.indexOf(nearestShape(mask))
        if (index < 0) return null
        return names[index % names.size]
    }

    /** Índice em `shapes`, para a legenda da tela. */
    fun shapeIndex(mask: Int): Int = shapes.indexOf(nearestShape(mask)).coerceAtLeast(0)

    /** Rótulo curto — "ind+méd". Máscara vazia é punho. */
    fun label(mask: Int, fingerNames: List<String>): String {
        if (mask and 0b1111 == 0) return ""
        return fingerNames.withIndex()
            .filter { mask and (1 shl it.index) != 0 }
            .joinToString("+") { it.value }
    }
}

/**
 * Qual acorde cada gesto da mão esquerda toca — escolhido por quem toca, e
 * guardado por INSTRUMENTO (as formas de viola caipira não são as do violão).
 */
@Serializable
data class HandChordAssignment(
    /** Um nome de acorde por forma de mão, na ordem de `HandChordMapping.shapes`. Vazio cai no padrão. */
    var chords: List<String> = emptyList(),
) {
    /**
     * A lista final para a câmera: o que a pessoa escolheu, com o padrão do
     * instrumento tapando os buracos. Sempre do tamanho de `shapes`.
     */
    fun resolved(fallback: List<String>): List<String> {
        val count = HandChordMapping.shapes.size
        return (0 until count).map { index ->
            val chosen = if (index < chords.size) chords[index] else ""
            if (chosen.isNotEmpty()) chosen else if (fallback.isEmpty()) "" else fallback[index % fallback.size]
        }
    }

    /** Troca o acorde de um gesto, crescendo a lista se preciso. */
    fun set(chord: String, shapeIndex: Int) {
        val count = HandChordMapping.shapes.size
        if (shapeIndex < 0 || shapeIndex >= count) return
        val grown = chords.toMutableList()
        while (grown.size < count) grown.add("")
        grown[shapeIndex] = chord
        chords = grown
    }
}

/**
 * O acorde escolhido CONTANDO os dedos das duas mãos — port do `TwoHandChords`.
 *
 * `HandChordMapping` lê a mão como um desenho; o founder pensa em NÚMERO: "1
 * dedo levantado eu posso configurar o acorde Am, dois dedos o F e assim vai".
 * Com a mão direita liberada do ritmo, duas mãos contando dão 25 combinações.
 * A leitura é a de um número de dois algarismos: a esquerda é o de cima, a
 * direita o de baixo. Combinação sem acorde configurado **não troca nada**.
 */
@Serializable
data class TwoHandChords(
    /** Um nome por combinação, indexado por `slot(left, right)`. Vazio = não troca. */
    var chords: List<String> = emptyList(),
) {
    /** O acorde de uma combinação, ou `null` quando não foi configurada. */
    fun chord(left: Int, right: Int): String? {
        val index = slot(left, right)
        if (index >= chords.size) return null
        val name = chords[index]
        return name.ifEmpty { null }
    }

    fun set(chord: String, left: Int, right: Int) {
        val grown = chords.toMutableList()
        while (grown.size < SLOTS) grown.add("")
        grown[slot(left, right)] = chord
        chords = grown
    }

    companion object {
        /** 0 a 4 dedos em cada mão — o polegar fica de fora de propósito. */
        const val COUNTS = 5
        const val SLOTS = COUNTS * COUNTS

        fun slot(left: Int, right: Int): Int {
            val l = left.coerceIn(0, COUNTS - 1)
            val r = right.coerceIn(0, COUNTS - 1)
            return l * COUNTS + r
        }

        /**
         * O padrão: o conjunto do instrumento espalhado pela mão esquerda com a
         * direita fechada, depois com um dedo, e assim por diante.
         */
        fun standard(names: List<String>): TwoHandChords {
            val out = MutableList(SLOTS) { "" }
            var next = 0
            for (right in 0 until COUNTS) {
                for (left in 0 until COUNTS) {
                    if (next >= names.size) break
                    out[slot(left, right)] = names[next]
                    next += 1
                }
            }
            return TwoHandChords(out)
        }
    }
}

/**
 * Espera a contagem das duas mãos ficar parada antes de trocar o acorde. Uma
 * mão indo de três dedos para um passa por dois no caminho; sem espera, o
 * acorde do meio toca. `hold` é o mesmo tempo da pinça (190 ms).
 */
class HandCountConfirmer(var hold: Double = 0.190) {
    private var pending: Pair<Int, Int>? = null
    private var since = 0.0
    private var confirmed: Pair<Int, Int>? = null

    fun reset() {
        pending = null
        confirmed = null
        since = 0.0
    }

    /** A contagem confirmada, ou `null` enquanto ainda muda. Devolve o par só na TROCA. */
    fun update(left: Int?, right: Int?, time: Double): Pair<Int, Int>? {
        if (left == null || right == null) {
            pending = null
            return null
        }
        val now = left to right
        if (pending != now) {
            pending = now
            since = time
            return null
        }
        if (time - since < hold) return null
        if (confirmed == now) return null
        confirmed = now
        return now
    }
}
