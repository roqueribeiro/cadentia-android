package com.levelhard.cadentia.kit.cordas

import com.levelhard.cadentia.kit.AudioDSP
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow

/**
 * A caixa — port do `makeBodyIR` do `phelipiii/cordas` via `CordaBody.swift`.
 *
 * O web sintetiza uma resposta ao impulso de 0,30 s com vinte senoides
 * decaindo e entrega a um `ConvolverNode`. Nativo, a aritmética corre ao
 * contrário: convoluir um dedilhado de 4 s com 14.400 taps são bilhões de
 * operações, enquanto os mesmos vinte modos como um banco paralelo de
 * passa-bandas ressonantes são vinte multiplica-somas por amostra. **Uma
 * resposta ao impulso modal e um banco de ressonadores nesses modos são o
 * mesmo filtro** — um escrito como resposta ao impulso, o outro como polos.
 *
 * Os modos seguem a acústica de uma caixa real: a ressonância do ar
 * (Helmholtz) perto de 98 Hz, o par de modos do tampo em 190–206 Hz, e a
 * série subindo, ficando mais densa e mais amortecida.
 */
object CordaBody {
    /** (frequência, ganho relativo, taxa de decaimento em nepers por segundo) */
    internal val modes: List<Triple<Double, Double, Double>> = listOf(
        Triple(98.0, 0.95, 26.0), Triple(122.0, 0.34, 24.0), Triple(190.0, 1.00, 20.0),
        Triple(206.0, 0.62, 21.0), Triple(230.0, 0.44, 22.0), Triple(285.0, 0.36, 24.0),
        Triple(335.0, 0.30, 26.0), Triple(400.0, 0.26, 28.0), Triple(480.0, 0.22, 30.0),
        Triple(560.0, 0.19, 32.0), Triple(660.0, 0.17, 34.0), Triple(780.0, 0.15, 36.0),
        Triple(920.0, 0.13, 40.0), Triple(1100.0, 0.11, 44.0), Triple(1350.0, 0.095, 50.0),
        Triple(1650.0, 0.082, 56.0), Triple(2000.0, 0.070, 62.0), Triple(2500.0, 0.060, 70.0),
        Triple(3100.0, 0.050, 80.0), Triple(3900.0, 0.045, 92.0),
    )

    /**
     * Quanto da corda seca sobrevive. Na resposta ao impulso é o pico
     * `d[0] = 0.50`: o som que chega ao ouvido direto da corda, antes de a
     * caixa responder.
     */
    internal const val DIRECT_PATH: Float = 0.50f

    /**
     * Q que reproduz um modo decaindo a `decayRate` nepers/segundo: a amplitude
     * cai como `e^(-αt)`, a largura de banda é `α/π`, e `Q = f / BW`.
     */
    internal fun quality(frequency: Double, decayRate: Double): Double =
        maxOf(0.5, PI * frequency / decayRate)

    /**
     * Passa um dedilhado mono pela caixa. Só instrumentos acústicos — uma
     * guitarra não tem caixa, e assar uma na nota estaria errado duas vezes:
     * não há caixa para assar, e o amplificador dela é não linear, então
     * distorcer nota a nota e somar depois não é o mesmo som que somar e
     * depois distorcer.
     */
    fun apply(signal: FloatArray, sampleRate: Double): FloatArray {
        if (signal.isEmpty() || sampleRate <= 0) return signal

        val dry = signal.copyOf()
        AudioDSP.Biquad(AudioDSP.Biquad.Kind.Highpass, 68.0, 0.707, sampleRate).process(dry)

        val out = FloatArray(dry.size)
        for (i in out.indices) out[i] = DIRECT_PATH * dry[i]

        for ((frequency, gain, decayRate) in modes) {
            if (frequency >= sampleRate * 0.45) continue
            val filter = AudioDSP.Biquad(
                AudioDSP.Biquad.Kind.Bandpass, frequency, quality(frequency, decayRate), sampleRate,
            )
            val g = gain.toFloat()
            for (i in dry.indices) out[i] += g * filter.process(dry[i])
        }

        // O último ar do tampo. No web é um shelf de +2 dB no barramento; aqui
        // vai junto com a nota.
        AudioDSP.Biquad(AudioDSP.Biquad.Kind.HighShelf, 5200.0, 0.707, sampleRate, gainDB = 2.0).process(out)

        var peak = 0f
        for (sample in out) peak = maxOf(peak, abs(sample))
        if (peak > 0f) {
            val normalize = 0.9f / peak
            for (i in out.indices) out[i] *= normalize
        }
        return out
    }
}

/**
 * Ressonância simpática — o halo que faz um acorde soar como se estivesse
 * dentro de uma caixa em vez de seis notas somadas.
 *
 * Toque uma corda num violão de verdade e as OUTRAS respondem: a caixa leva a
 * vibração até elas e cada uma devolve a própria nota, baixinho. Um banco de
 * filtros muito seletivos, um por corda solta, alimentado pela saída do
 * próprio instrumento, produz o mesmo efeito por uma fração do custo de
 * simular seis cordas extras. Esta NÃO dá para assar na nota: é entre cordas
 * por definição, e alimentada pela mistura. Fica viva no barramento.
 */
data class SympatheticSpec(
    val frequencies: List<Double>,
    val q: Double,
    /** Nível de envio. Uma guitarra quase não tem: não há tampo para carregar a vibração. */
    val send: Double,
    /** Segundos que o halo atrasa em relação à nota: o som precisa cruzar a caixa. */
    val delay: Double,
) {
    companion object {
        fun forInstrument(instrument: CordaInstrument, capo: Int): SympatheticSpec {
            val count = 10
            val frequencies = (0 until count).map { k ->
                val spec = instrument.strings[k % instrument.strings.size]
                440 * 2.0.pow((spec.midi + capo - 69) / 12.0)
            }
            return SympatheticSpec(
                frequencies = frequencies,
                q = 60.0,
                send = if (instrument.isElectric) 0.05 else 0.16,
                delay = 0.012,
            )
        }
    }
}
