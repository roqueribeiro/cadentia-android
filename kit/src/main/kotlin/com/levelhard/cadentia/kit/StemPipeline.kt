package com.levelhard.cadentia.kit

import kotlin.math.PI
import kotlin.math.sin

/**
 * O laço de janelas da separação — a parte pura do
 * `StemSeparator.separateToFiles` do iOS, com o modelo atrás de uma
 * interface.
 *
 * O modelo roda numa janela de 7,8 s por vez (foi assim que treinou), então
 * a música é processada em janelas sobrepostas com cross-fade sin² nas
 * pontas. Janela só sobrepõe a vizinha imediata: assim que a janela k entra
 * na soma, tudo antes do início da k+1 está definitivo e pode ser escrito —
 * é o que limita a memória a UMA janela por stem, independente do tamanho
 * da música.
 *
 * O backend real (STFT → modelo → iSTFT + ramo de onda) entra por
 * `StemBackend`; o formato dos tensores do iOS está registrado no loop de
 * execução e o backend ONNX só nasce quando houver modelo para validar.
 */
object StemPipeline {
    /** A janela de 7,8 s a 44,1 kHz em que o htdemucs foi treinado. */
    const val SEGMENT_SAMPLES = 343_980
    const val OVERLAP = 0.25
    const val SAMPLE_RATE = 44_100
    val sourceNames = listOf("drums", "bass", "other", "vocals")

    /** Separa UMA janela: entra 2×segment (L, R), saem 4 fontes × 2 canais × segment. */
    interface StemBackend {
        fun separateSegment(chunk: Array<FloatArray>): Array<Array<FloatArray>>
    }

    /** Onde cada pedaço definitivo é entregue (a escrita em disco vive no app). */
    fun interface SettledWriter {
        /** `planes[source][channel]` com `count` amostras definitivas por plano. */
        fun write(planes: Array<Array<FloatArray>>, count: Int)
    }

    /** Os inícios de janela para um total de amostras (o laço do iOS). */
    fun windowStarts(total: Int, segment: Int = SEGMENT_SAMPLES): List<Int> {
        val ramp = (segment * OVERLAP).toInt()
        val stride = segment - ramp
        val starts = mutableListOf<Int>()
        var cursor = 0
        while (cursor < maxOf(total - ramp, 1)) {
            starts.add(cursor)
            cursor += stride
        }
        return starts
    }

    /** A janela de cross-fade: sin² subindo no começo e descendo no fim. */
    fun crossfadeWindow(segment: Int = SEGMENT_SAMPLES): FloatArray {
        val ramp = (segment * OVERLAP).toInt()
        val window = FloatArray(segment) { 1f }
        for (i in 0 until ramp) {
            val value = sin(i / ramp.toDouble() * PI / 2).toFloat().let { it * it }
            window[i] = value
            window[segment - 1 - i] = value
        }
        return window
    }

    /**
     * Processa a música inteira em janelas, streaming: `read` entrega
     * `count` amostras de cada canal a partir de `start` (zero além do fim),
     * e cada trecho definitivo sai por `writer` assim que fecha. Devolve o
     * total de amostras escritas por fonte (== `total`).
     */
    fun separate(
        total: Int,
        segment: Int = SEGMENT_SAMPLES,
        read: (start: Int, count: Int) -> Array<FloatArray>,
        backend: StemBackend,
        writer: SettledWriter,
        onSegment: ((done: Int, totalSegments: Int) -> Unit)? = null,
    ): Int {
        require(total > 0) { "nada para separar" }
        val ramp = (segment * OVERLAP).toInt()
        val stride = segment - ramp
        val window = crossfadeWindow(segment)
        val sources = sourceNames.size

        val pending = Array(sources) { Array(2) { FloatArray(segment) } }
        val pendingWeight = FloatArray(segment)
        val starts = windowStarts(total, segment)
        var written = 0

        for ((index, start) in starts.withIndex()) {
            onSegment?.invoke(index + 1, starts.size)

            val chunk = read(start, minOf(segment, total - start)).let { channels ->
                // O backend sempre vê `segment` amostras por canal; o rabo
                // curto da última janela chega estendido com zeros.
                Array(2) { channel ->
                    val source = channels[minOf(channel, channels.size - 1)]
                    if (source.size == segment) source else source.copyOf(segment)
                }
            }

            val stems = backend.separateSegment(chunk)
            for (source in 0 until sources) {
                for (channel in 0 until 2) {
                    val values = stems[source][channel]
                    for (i in 0 until segment) {
                        pending[source][channel][i] += values[i] * window[i]
                    }
                }
            }
            for (i in 0 until segment) pendingWeight[i] += window[i]

            // Tudo antes do início da próxima janela está definitivo.
            val settled = minOf(stride, maxOf(0, total - start))
            flush(pending, pendingWeight, settled, writer)
            written += settled
            for (source in 0 until sources) {
                for (channel in 0 until 2) {
                    shiftLeft(pending[source][channel], stride)
                }
            }
            shiftLeft(pendingWeight, stride)
        }

        // O que a última janela deixou depois da última fronteira de stride.
        if (written < total) {
            val remaining = total - written
            flush(pending, pendingWeight, remaining, writer)
            written += remaining
        }
        return written
    }

    private fun flush(
        pending: Array<Array<FloatArray>>,
        weight: FloatArray,
        count: Int,
        writer: SettledWriter,
    ) {
        if (count <= 0) return
        val out = Array(pending.size) { source ->
            Array(2) { channel ->
                FloatArray(count) { i ->
                    val w = weight[i]
                    if (w > 1e-6f) pending[source][channel][i] / w else 0f
                }
            }
        }
        writer.write(out, count)
    }

    private fun shiftLeft(buffer: FloatArray, by: Int) {
        val n = buffer.size
        if (by >= n) {
            buffer.fill(0f)
            return
        }
        System.arraycopy(buffer, by, buffer, 0, n - by)
        java.util.Arrays.fill(buffer, n - by, n, 0f)
    }
}

/**
 * A regra de limpeza do cache de faixas separadas — o miolo puro do
 * `StemCache.trim` do iOS: primeiro saem as músicas que não estão mais nas
 * Recentes (nunca mais serão reabertas por um toque), depois as usadas há
 * mais tempo até o total caber no teto.
 */
object StemCachePolicy {
    /** 4 faixas de uma música de 4 min em WAV ≈ 340 MB: o teto guarda poucas. */
    const val MAX_BYTES = 2_000_000_000L

    data class Entry(val songId: String, val bytes: Long, val usedAtEpochMillis: Long)

    /** Ids a apagar, na ordem. */
    fun evict(entries: List<Entry>, keeping: Set<String>, maxBytes: Long = MAX_BYTES): List<String> {
        val doomed = mutableListOf<String>()
        val alive = mutableListOf<Entry>()
        for (entry in entries) {
            if (entry.songId in keeping) alive.add(entry) else doomed.add(entry.songId)
        }
        var total = alive.sumOf { it.bytes }
        if (total <= maxBytes) return doomed
        for (entry in alive.sortedBy { it.usedAtEpochMillis }) {
            if (total <= maxBytes) break
            doomed.add(entry.songId)
            total -= entry.bytes
        }
        return doomed
    }
}

/**
 * Reamostrador para a normalização de entrada (o modelo treinou em
 * 44,1 kHz; música real chega em 48 kHz). Sinc janelado de 32 taps por
 * fase — não é o conversor do sistema, mas mantém o tom exato e o teste
 * prova o pitch pelo YIN. Pular esta etapa não falha alto: a música só
 * sai separada na velocidade errada, o que é pior que um erro.
 */
object StemResampler {
    private const val TAPS = 32

    fun resample(input: FloatArray, fromRate: Double, toRate: Double): FloatArray {
        if (fromRate == toRate || input.isEmpty()) return input.copyOf()
        val ratio = fromRate / toRate
        val outCount = (input.size / ratio).toInt()
        val out = FloatArray(outCount)
        val half = TAPS / 2
        // Passa-baixa em min(1, 1/ratio) de Nyquist para downsample não repor
        // como alias o que estava acima da nova taxa.
        val cutoff = minOf(1.0, 1.0 / ratio)
        for (n in out.indices) {
            val center = n * ratio
            val base = center.toInt()
            var acc = 0.0
            for (k in base - half + 1..base + half) {
                if (k < 0 || k >= input.size) continue
                val x = center - k
                val sinc = if (x == 0.0) 1.0 else sin(PI * cutoff * x) / (PI * x) / cutoff
                // Janela de Hann sobre o suporte do filtro.
                val w = 0.5 + 0.5 * kotlin.math.cos(PI * x / half)
                acc += input[k] * sinc * cutoff * w
            }
            out[n] = acc.toFloat()
        }
        return out
    }
}
