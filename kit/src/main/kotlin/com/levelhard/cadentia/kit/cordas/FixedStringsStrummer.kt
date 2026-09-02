package com.levelhard.cadentia.kit.cordas

import kotlin.math.abs
import kotlin.math.pow

/**
 * A batida numa faixa de cordas PARADAS na tela — port do
 * `FixedStringsStrummer.swift`.
 *
 * O modo câmera desenhava um violão inteiro, posicionado e escalado pelo
 * rastreamento das mãos, e as cordas viviam dentro desse desenho. Quando o
 * rastreamento errava a escala, o violão encolhia e a passada caía fora da
 * faixa ("tem hora que ele fica minúsculo e fica tocando notas abafadas"). A
 * causa era a escala vir do rastreamento. Aqui as cordas ficam num lugar fixo
 * da tela e não se mexem nunca; o rastreamento responde uma pergunta só: onde
 * está a mão da batida agora. Cruzou uma corda, aquela corda soa.
 */
class FixedStringsStrummer {
    /** Onde a faixa de cordas mora, em fração da altura da tela. Meio para baixo. */
    var top: Double = 0.50
    var bottom: Double = 0.84

    /**
     * Velocidade mínima da mão, em alturas de tela por segundo, para uma
     * passagem contar como batida. 0,45: uma travessia da faixa inteira em
     * menos de 0,75 s conta; mão parada tremendo não dispara.
     */
    var minimumSpeed: Double = 0.45

    /**
     * Quanto a mão precisa andar num sentido, em fração de tela, para a volta
     * dela contar como uma sacudida. 0,04 é pequeno de propósito.
     */
    var shakeTravel: Double = 0.04

    data class Pluck(
        val string: Int,
        val velocity: Double,
        /** Atraso em segundos: a palheta chega numa corda de cada vez. */
        val delay: Double,
    )

    private var lastY: Double? = null
    private var lastTime: Double? = null
    /** +1 descendo, -1 subindo, 0 sem sentido ainda. */
    private var direction = 0
    private var swingStart: Double? = null
    private var shakeDown = true

    fun reset() {
        lastY = null
        lastTime = null
        direction = 0
        swingStart = null
        shakeDown = true
    }

    /** Onde a corda `index` mora, em fração da altura. A corda 0 é a mais grave e fica em CIMA. */
    fun stringY(index: Int, count: Int): Double {
        if (count <= 1) return (top + bottom) / 2
        return top + (bottom - top) * index / (count - 1)
    }

    /**
     * A mão passou por onde, desde o quadro anterior. A unidade é PONTO: quem
     * chama entrega a altura da mão e a altura da tela nas mesmas unidades, e
     * não existe mais uma fração para alguém esquecer de calcular.
     *
     * @param handY altura da mão da batida em pontos; `null` quando ela sumiu.
     */
    fun update(handY: Double?, viewHeight: Double, time: Double, stringCount: Int): List<Pluck> {
        if (viewHeight <= 0) return emptyList()
        if (handY == null) {
            reset()
            return emptyList()
        }
        val y = handY / viewHeight
        val previous = lastY
        val previousTime = lastTime
        lastY = y
        lastTime = time

        if (previous == null || previousTime == null) return emptyList()
        val elapsed = time - previousTime
        // Quadro repetido ou relógio andando para trás: não dá para medir velocidade.
        if (!(elapsed > 0.001 && elapsed < 0.5)) return emptyList()

        val travel = y - previous
        val speed = abs(travel) / elapsed
        if (speed < minimumSpeed) return emptyList()

        // Quais cordas ficaram ENTRE onde a mão estava e onde ela está.
        val low = minOf(previous, y)
        val high = maxOf(previous, y)
        val crossed = ArrayList<Int>()
        for (index in 0 until stringCount) {
            val sy = stringY(index, stringCount)
            if (sy > low && sy <= high) crossed.add(index)
        }
        val nowDirection = if (travel > 0) 1 else -1
        // A SACUDIDA: chacoalhar a mão vira levada, mesmo longe das cordas. Só
        // quando a ida NÃO cruzou corda nenhuma.
        if (crossed.isEmpty()) {
            var shaken: List<Pluck> = emptyList()
            val start = swingStart
            if (direction != 0 && nowDirection != direction && start != null && abs(previous - start) >= shakeTravel) {
                shaken = fullStrum(shakeDown, speed, stringCount)
                shakeDown = !shakeDown
            }
            if (nowDirection != direction) swingStart = previous
            direction = nowDirection
            return shaken
        }
        if (nowDirection != direction) swingStart = previous
        direction = nowDirection
        shakeDown = travel < 0

        // Para baixo, do grave para o agudo; para cima, ao contrário.
        if (travel < 0) crossed.reverse()
        return strum(crossed, speed)
    }

    private fun fullStrum(down: Boolean, speed: Double, stringCount: Int): List<Pluck> {
        val order = if (down) (0 until stringCount).toList() else (0 until stringCount).reversed().toList()
        return strum(order, speed)
    }

    /**
     * A intensidade e o espalhamento, dado quanto a mão correu. A faixa inteira
     * de velocidades reais (0,9 a 5 alturas de tela por segundo) vira 0,12 a
     * 1,0 de intensidade — oito vezes entre a levada mais leve e a batida mais
     * forte, que é a ordem de grandeza de uma dinâmica de verdade.
     */
    private fun strum(order: List<Int>, speed: Double): List<Pluck> {
        val reach = ((speed - minimumSpeed) / (4.0 - minimumSpeed)).coerceIn(0.0, 1.0)
        val velocity = 0.12 + reach.pow(0.8) * 0.88
        val spread = (0.020 / maxOf(speed, 0.4)).coerceIn(0.006, 0.030)
        return order.mapIndexed { position, string -> Pluck(string, velocity, position * spread) }
    }
}
