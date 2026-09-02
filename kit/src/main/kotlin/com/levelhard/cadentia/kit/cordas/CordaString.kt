package com.levelhard.cadentia.kit.cordas

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sin

/**
 * A corda em si — Karplus-Strong estendido, port do `renderPluck` do
 * `phelipiii/cordas` (`public/src/audio.js`) via `CordaString.swift` (1.16).
 *
 * O algoritmo de 1983 puro dá "alguma coisa dedilhada". Cinco coisas fazem
 * dele um violão, e o app já tinha quatro em `StringVoices`. A que faltava é a
 * primeira, e é a que mais importa:
 *
 * 1. **Duas polarizações.** Uma corda não vibra num plano só. A vertical
 *    empurra o cavalete de frente, entrega a energia ao tampo depressa e MORRE
 *    RÁPIDO; a horizontal desliza de lado, mal move o tampo e SUSTENTA. É por
 *    isso que uma nota real cai forte no primeiro instante e depois canta
 *    baixinho por segundos. Dois laços — um com perda, outro quase sem perda e
 *    dois cents acima, o que também produz o batimento vivo — dão o decaimento
 *    em dois estágios e o batimento ao mesmo tempo.
 * 2. **Interpolação allpass fracionária, descontando o atraso de fase do
 *    filtro do laço.** Sem o desconto a corda toca baixa no agudo.
 * 3. **Perda dependente da frequência** — os parciais agudos morrem antes.
 * 4. **Um pente da posição da palheta** na excitação.
 * 5. **Um corpo**, que mora em `CordaBody` e quem chama mistura.
 *
 * Tudo aqui é puro e determinístico: o ruído vem de um gerador com semente,
 * não de `Random`. É o que deixa os testes afirmarem algo sobre a saída.
 */
object CordaString {
    /** O maior buffer que vale guardar: a cauda depois de três segundos é muito quieta. */
    const val MAX_CACHED_DURATION: Double = 3.2

    /**
     * Renderiza um dedilhado, mono, normalizado.
     *
     * @param frequency fundamental em Hz.
     * @param velocity 0…1: brilho, comprimento da excitação e nível.
     * @param tone o caráter de corda do instrumento.
     * @param seed qualquer valor; mesma semente e mesmos argumentos, mesmas amostras.
     */
    fun render(
        frequency: Double,
        velocity: Double,
        tone: CordaTone,
        sampleRate: Double,
        seed: Long = -0x61c8864680b583ebL, // 0x9E3779B97F4A7C15 como Long com sinal
    ): FloatArray {
        if (!(frequency > 20 && frequency < sampleRate / 2 && sampleRate > 0)) return FloatArray(0)
        val v = velocity.coerceIn(0.06, 1.0)

        // Notas mais agudas soam por menos tempo, como numa corda de verdade.
        val scaled = tone.decay * (1 - 0.34 * log2(frequency / 82) / 3)
        val duration = minOf(MAX_CACHED_DURATION, maxOf(0.6, minOf(scaled, tone.decay)))
        val length = maxOf(64, (duration * sampleRate).toInt())

        // Filtro do laço: uma palhetada mais forte guarda mais agudo por volta.
        val bright = (tone.bright + v * 0.34).coerceIn(0.05, 0.97)
        val b = (1 - bright).coerceIn(0.02, 0.96)
        val rho = (tone.loss - (frequency / 1400) * tone.lossHi).coerceIn(0.90, 0.99995)

        val period = sampleRate / frequency
        val rng = CordaNoise(seed)

        // ── excitação ──────────────────────────────────────────────────────
        val exciteLength = minOf(length, maxOf(8, (period * tone.excite).toInt()))
        val excitation = FloatArray(exciteLength + period.toInt() + 2)
        val soft = (tone.pickSoft - v * 0.35).coerceIn(0.02, 0.95)
        var lowpassed = 0.0
        for (i in 0 until exciteLength) {
            val window = sin(PI * i / exciteLength)
            lowpassed += (rng.nextBipolar() - lowpassed) * (1 - soft)
            excitation[i] = (lowpassed * window).toFloat()
        }
        // O clique do ataque: a unha raspando, a palheta escapando.
        val clickLength = (sampleRate * 0.0016).toInt()
        for (i in 0 until minOf(clickLength, excitation.size)) {
            val fall = (1 - i.toDouble() / clickLength).pow(3)
            excitation[i] += (rng.nextBipolar() * fall * tone.click).toFloat()
        }
        // Pente da posição da palheta: tocar sobre um nó cancela aquele harmônico.
        val combOffset = maxOf(1, Math.round(period * tone.pickPos).toInt())
        if (combOffset < excitation.size) {
            var i = excitation.size - 1
            while (i >= combOffset) {
                excitation[i] -= excitation[i - combOffset] * 0.86f
                i -= 1
            }
        }

        // ── as duas polarizações ───────────────────────────────────────────
        val out = FloatArray(length)
        val drive = tone.tension
        val amplitude = v * 0.9

        // (desafinação, ganho, fator de perda). A que sustenta perde a METADE
        // por volta e fica dois cents acima para o par bater.
        val polarizations = arrayOf(
            doubleArrayOf(1.0, 0.66, 1.0),
            doubleArrayOf(1.0012, 0.40, 0.50),
        )

        for (pol in polarizations) {
            val detune = pol[0]
            val gain = pol[1]
            val lossFactor = pol[2]
            // Desconta o atraso de fase do filtro do laço, ou o agudo fica baixo.
            val target = maxOf(4.0, period / detune - b / (1 - b))
            var delayCount = (target - 0.5).toInt()
            if (delayCount < 2) delayCount = 2
            val fractional = (target - delayCount).coerceIn(0.1, 1.9)
            val allpassCoeff = (1 - fractional) / (1 + fractional)
            val polRho = 1 - (1 - rho) * lossFactor

            val line = DoubleArray(delayCount)
            var writeIndex = 0
            var apX = 0.0
            var apY = 0.0
            var lpY = 0.0
            var dcX = 0.0
            var dcY = 0.0

            for (n in 0 until length) {
                val s = line[writeIndex]
                val ap = allpassCoeff * (s - apY) + apX
                apX = s
                apY = ap
                lpY = (1 - b) * ap + b * lpY
                var value = polRho * lpY
                if (n < excitation.size) value += excitation[n] * amplitude
                if (drive != 0.0) value -= drive * value * value * value
                dcY = value - dcX + 0.9985 * dcY
                dcX = value
                line[writeIndex] = dcY
                out[n] += (gain * ap).toFloat()
                writeIndex += 1
                if (writeIndex >= delayCount) writeIndex = 0
            }
        }

        // ── normaliza e desvanece ──────────────────────────────────────────
        var peak = 0f
        for (sample in out) peak = maxOf(peak, abs(sample))
        if (peak <= 0f) return out
        val gain = (0.82 / peak * (0.45 + v * 0.55)).toFloat()
        val fade = minOf(length, (sampleRate * 0.09).toInt())
        for (n in 0 until length) {
            var m = gain
            if (n > length - fade) m *= (length - n).toFloat() / fade
            out[n] *= m
        }
        return out
    }
}

/**
 * Uma fonte de ruído com semente. `Random` deixaria cada render diferente, e
 * aí nenhum teste conseguiria afirmar nada sobre o som. SplitMix64 são três
 * linhas e passa em tudo que precisamos.
 */
class CordaNoise(seed: Long) {
    private var state: Long = if (seed == 0L) -0x61c8864680b583ebL else seed

    fun next(): Long {
        state += -0x61c8864680b583ebL // 0x9E3779B97F4A7C15
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L // 0xBF58476D1CE4E5B9
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L // 0x94D049BB133111EB
        return z xor (z ushr 31)
    }

    /** Uniforme em -1…1. */
    fun nextBipolar(): Double = (next() ushr 11).toDouble() / (1L shl 53).toDouble() * 2 - 1

    /** Uniforme em 0…1. */
    fun nextUnit(): Double = (next() ushr 11).toDouble() / (1L shl 53).toDouble()
}
