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
 * Quem sai do disco quando o aparelho fica sem espaço — e, principalmente,
 * quando NINGUÉM sai. Port do `StemCachePolicy.swift` (1.16).
 *
 * Isto é a parte da limpeza que dá para errar, então mora aqui, pura, longe
 * do sistema de arquivos: quais pastas apagar é uma decisão; apagá-las é
 * encanamento.
 *
 * A regra anterior tinha dois gatilhos e o primeiro era o errado: apagava tudo
 * que não estivesse na lista de músicas recentes, **mesmo com o disco vazio**.
 * Como a lista tinha teto, uma playlist longa se comia pela cauda enquanto era
 * separada — a música 31 apagava a música 1. Agora só existe um gatilho:
 * espaço. Enquanto houver folga, o que foi separado fica.
 */
object StemCachePolicy {
    /** O quanto o aparelho tem que continuar tendo livre. */
    const val FREE_SPACE_RESERVE = 2_000_000_000L

    data class Entry(val id: String, val bytes: Long, val lastUsedEpochMillis: Long)

    /**
     * Os ids que precisam sair, em ordem, para o espaço livre voltar acima da
     * reserva. **Vazio é o caso normal** e é o ponto inteiro deste arquivo.
     *
     * @param freeBytes espaço livre no aparelho agora; `<= 0` é "não consegui
     *   medir", e não "o disco está cheio" — e aí nada sai.
     * @param protected músicas que estão em algum repertório: **nunca** saem.
     * @param keepNewest quantas das mais recentes são intocáveis. Cinco, pela
     *   música que ACABOU de ser separada: a limpeza roda logo depois de
     *   escrever, e sem isto ela apagava o resultado do trabalho.
     */
    fun evictions(
        entries: List<Entry>,
        freeBytes: Long,
        reserve: Long = FREE_SPACE_RESERVE,
        protected: Set<String> = emptySet(),
        keepNewest: Int = 5,
    ): List<String> {
        if (freeBytes <= 0 || freeBytes >= reserve) return emptyList()

        // Mais recente primeiro, para saber quem são as intocáveis. Empate de
        // data desempata pelo id: senão o mesmo estado protegeria músicas
        // diferentes em execuções diferentes.
        val byAge = entries.sortedWith(compareByDescending<Entry> { it.lastUsedEpochMillis }.thenBy { it.id })
        val untouchable = protected + byAge.take(maxOf(0, keepNewest)).map { it.id }
        val candidates = byAge.filter { it.id !in untouchable }.reversed()

        // Se apagar TUDO que pode ainda não alcança a reserva, não apaga nada:
        // um aparelho cheio por causa de fotos levava a playlist junto e
        // continuava cheio do mesmo jeito.
        val recoverable = candidates.sumOf { it.bytes }
        if (freeBytes + recoverable < reserve) return emptyList()

        var free = freeBytes
        val out = ArrayList<String>()
        for (entry in candidates) {
            if (free >= reserve) break
            free += entry.bytes
            out += entry.id
        }
        return out
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

    /**
     * O mesmo filtro, em blocos: recebe o áudio decodificado aos poucos e
     * devolve o que já dá para calcular, guardando só os `TAPS` últimos
     * samples de história. Existe porque a versão de uma vez só exige a
     * música inteira em memória, e uma música de 3 minutos em `Float`
     * encaixotado passava de 250 MB — o app morria de OutOfMemory ao abrir
     * qualquer música real do RoqueOS (05/09). Para os mesmos dados, produz a
     * mesma saída que [resample] (teste `streamingMatchesOneShot`).
     */
    class Streaming(fromRate: Double, toRate: Double) {
        private val ratio = fromRate / toRate
        private val identity = fromRate == toRate
        private val half = TAPS / 2
        private val cutoff = minOf(1.0, 1.0 / ratio)

        /** Amostras de entrada ainda necessárias; `pending[0]` é a amostra absoluta `pendingStart`. */
        private var pending = FloatArray(0)
        private var pendingSize = 0
        private var pendingStart = 0L
        private var totalInput = 0L
        private var nextOut = 0L

        /** Só para o teste: quantas amostras de entrada estão guardadas. */
        fun pendingForTest(): Int = pendingSize

        /** Entrega `count` amostras e recebe as saídas já completas (pode ser vazio). */
        fun push(input: FloatArray, count: Int): FloatArray {
            if (identity) return input.copyOf(count)
            append(input, count)
            totalInput += count
            val lastAbs = pendingStart + pendingSize - 1
            return produce { base -> base + half <= lastAbs }
        }

        /** Fim da música: o que faltava, com o filtro pulando o que não existe, como o [resample]. */
        fun finish(): FloatArray {
            if (identity) return FloatArray(0)
            val outCount = (totalInput / ratio).toLong()
            return produce { _ -> nextOut < outCount }
        }

        private fun append(input: FloatArray, count: Int) {
            if (pendingSize + count > pending.size) {
                val grown = FloatArray(maxOf(pending.size * 2, pendingSize + count, 1 shl 16))
                System.arraycopy(pending, 0, grown, 0, pendingSize)
                pending = grown
            }
            System.arraycopy(input, 0, pending, pendingSize, count)
            pendingSize += count
        }

        private inline fun produce(canEmit: (base: Long) -> Boolean): FloatArray {
            var emitted = FloatArray(1024)
            var n = 0
            while (true) {
                val center = nextOut * ratio
                val base = center.toLong()
                if (!canEmit(base)) break
                var acc = 0.0
                for (k in base - half + 1..base + half) {
                    if (k < 0 || k >= totalInput) continue
                    val local = (k - pendingStart).toInt()
                    if (local < 0 || local >= pendingSize) continue
                    val x = center - k
                    val sinc = if (x == 0.0) 1.0 else sin(PI * cutoff * x) / (PI * x) / cutoff
                    val w = 0.5 + 0.5 * kotlin.math.cos(PI * x / half)
                    acc += pending[local] * sinc * cutoff * w
                }
                if (n == emitted.size) emitted = emitted.copyOf(emitted.size * 2)
                emitted[n++] = acc.toFloat()
                nextOut++
            }
            // Solta o que nenhuma saída futura alcança mais.
            val keepFrom = (nextOut * ratio).toLong() - half + 1
            val drop = (keepFrom - pendingStart).toInt()
            if (drop > 0) {
                val remaining = maxOf(pendingSize - drop, 0)
                System.arraycopy(pending, minOf(drop, pendingSize), pending, 0, remaining)
                pendingSize = remaining
                pendingStart += drop
            }
            return emitted.copyOf(n)
        }
    }
}
