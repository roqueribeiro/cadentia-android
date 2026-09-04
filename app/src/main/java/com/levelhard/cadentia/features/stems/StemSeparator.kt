package com.levelhard.cadentia.features.stems

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.levelhard.cadentia.kit.DemucsSpectrogram
import com.levelhard.cadentia.kit.StemPipeline
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Onde o modelo de separação mora no aparelho — o papel do
 * `Separator.mlmodelc` no bundle do iOS (`StemsModel.swift:176`).
 *
 * O arquivo tem 174 MB (htdemucs em ONNX fp32, `scripts/convert-stem-model.py`)
 * e não vai no pacote: chega por download na primeira separação
 * ([StemModelDownloader]) ou, no QA, por `adb push` + `run-as cp` para
 * `filesDir/models/`. Ausente, a tela mostra `modelMissing`, como o iOS sem o
 * `.mlmodelc`.
 */
object StemModelStore {
    const val FILE_NAME = "separator.onnx"

    fun directory(context: Context): File = File(context.filesDir, "models")

    fun file(context: Context): File = File(directory(context), FILE_NAME)

    /** O iOS: `isAvailable` é só "o arquivo existe". Aqui também exige que não seja um download pela metade. */
    fun isAvailable(context: Context): Boolean = file(context).let { it.isFile && it.length() > 1_000_000 }
}

/**
 * Port do `StemSeparator.swift`: uma janela de 7,8 s entra, quatro fontes
 * saem, com o modelo em ONNX Runtime no lugar do Core ML e as transformadas
 * no `DemucsSpectrogram` do Kit (paridade < 1e-5 com o PyTorch).
 *
 *     onda -> STFT -> [L.re, L.im, R.re, R.im] como canais ---+
 *                                                             +--> ONNX --+
 *     onda ------------------------------------------------- +            |
 *     espectro por fonte -> iSTFT -> + ramo temporal <----------------------+
 *
 * As duas saídas são SOMADAS, não escolhidas (a mesma linha do iOS).
 */
class OnnxStemBackend(modelFile: File) : StemPipeline.StemBackend, AutoCloseable {
    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val spectrogram = DemucsSpectrogram()

    /** Tempo gasto no modelo e nas transformadas, para o log medir o que custa. */
    var modelNanos = 0L
        private set
    var transformNanos = 0L
        private set

    init {
        val options = OrtSession.SessionOptions().apply {
            // Um núcleo fica para a UI e para o áudio; o resto é do modelo.
            val cores = Runtime.getRuntime().availableProcessors()
            setIntraOpNumThreads(maxOf(1, cores - 1))
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            // Sessão serial: uma janela por vez, sem alocar no laço.
            setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            // Memória, medida com o modelo real (janela de 7,8 s, ORT 1.25 em
            // x86_64, VmHWM do processo):
            //   padrão (ConstantFolding + mem pattern)   1,9 GB na 1ª janela,
            //                                            3,4 GB na 2ª
            //   sem ConstantFolding, sem mem pattern     0,72 GB / 0,83 GB
            // O ConstantFolding deixa todas as formas estáticas e com isso o
            // planejador de memória encadeia reuso de buffer do encoder até o
            // decoder (os tensores de 66 MB do dconv ficam vivos a janela
            // toda) em vez de liberar e realocar. Sem ele o pico cai 2,6x e o
            // tempo por janela não muda (4,2 s nos dois). Foi este pico que o
            // lowmemorykiller derrubou no emulador de 4 GB (04/09/2026).
            addConfigEntry("optimization.disable_specified_optimizers", "ConstantFolding")
            // O mem pattern pré-aloca na 2ª execução um bloco do tamanho do
            // plano inteiro (+460 MB medidos) sem ganhar tempo.
            setMemoryPatternOptimization(false)
        }
        session = environment.createSession(modelFile.absolutePath, options)
        Log.i(TAG, "modelo aberto: ${modelFile.name} (${modelFile.length() / 1_000_000} MB), entradas ${session.inputNames}")
    }

    override fun separateSegment(chunk: Array<FloatArray>): Array<Array<FloatArray>> {
        val segment = StemPipeline.SEGMENT_SAMPLES
        val frames = spectrogram.frameCount(segment)
        val bins = DemucsSpectrogram.BINS
        val plane = bins * frames

        var clock = System.nanoTime()
        val specs = Array(2) { spectrogram.forward(chunk[it]) }
        transformNanos += System.nanoTime() - clock

        // mag [1, 4, bins, frames]: canais L.re, L.im, R.re, R.im, cada plano
        // em ordem frequência-major, exatamente o que o `forward` devolve.
        val mag = FloatBuffer.allocate(4 * plane)
        for (channel in 0 until 2) {
            mag.put(specs[channel].re)
            mag.put(specs[channel].im)
        }
        mag.rewind()
        val mix = FloatBuffer.allocate(2 * segment)
        mix.put(chunk[0], 0, segment)
        mix.put(chunk[1], 0, segment)
        mix.rewind()

        clock = System.nanoTime()
        val stems: Array<Array<FloatArray>>
        OnnxTensor.createTensor(environment, mag, longArrayOf(1, 4, bins.toLong(), frames.toLong())).use { magTensor ->
            OnnxTensor.createTensor(environment, mix, longArrayOf(1, 2, segment.toLong())).use { mixTensor ->
                session.run(mapOf("mix" to mixTensor, "mag" to magTensor)).use { result ->
                    modelNanos += System.nanoTime() - clock
                    clock = System.nanoTime()
                    // spec [1, S*4, bins, frames] e wave [1, S*2, segment],
                    // contíguos (o ORT não acolchoa o eixo interno como o
                    // Core ML fazia).
                    val spec = (result[0] as OnnxTensor).floatBuffer
                    val wave = (result[1] as OnnxTensor).floatBuffer
                    val sources = StemPipeline.sourceNames.size
                    val re = FloatArray(plane)
                    val im = FloatArray(plane)
                    stems = Array(sources) { source ->
                        Array(2) { channel ->
                            // Por fonte: [L.re, L.im, R.re, R.im].
                            val base = (source * 4 + channel * 2) * plane
                            spec.position(base)
                            spec.get(re, 0, plane)
                            spec.position(base + plane)
                            spec.get(im, 0, plane)
                            val values = spectrogram.inverse(re, im, frames, segment)
                            val timeBase = (source * 2 + channel) * segment
                            wave.position(timeBase)
                            for (i in 0 until segment) values[i] += wave.get()
                            values
                        }
                    }
                    transformNanos += System.nanoTime() - clock
                }
            }
        }
        return stems
    }

    override fun close() {
        session.close()
    }

    companion object {
        const val TAG = "CadentiaStems"
    }
}

/**
 * A separação de uma música inteira, do WAV normalizado para as quatro
 * faixas na pasta parcial: o `separateToFiles` do iOS. A entrada é lida por
 * janela e cada trecho definitivo é escrito assim que fecha, então a memória
 * é UMA janela por fonte, independente do tamanho da música.
 */
object StemSeparator {
    /**
     * @param input WAV estéreo 16-bit 44,1 k (o que o `StemAudioNormalizer` escreve).
     * @param into pasta parcial; sai `drums.wav`, `bass.wav`, `other.wav`, `vocals.wav`.
     * @param progress `(janela, total)` a cada janela.
     * @param shouldContinue consultado a cada janela; `false` interrompe com [InterruptedException].
     */
    fun separate(
        backend: StemPipeline.StemBackend,
        input: File,
        into: File,
        progress: (Int, Int) -> Unit,
        shouldContinue: () -> Boolean = { true },
    ) {
        val reader = StereoWavReader(input)
        try {
            val total = reader.frames
            val writers = StemPipeline.sourceNames.map { StereoWavWriter(File(into, "$it.wav"), StemPipeline.SAMPLE_RATE) }
            val started = System.nanoTime()
            try {
                StemPipeline.separate(
                    total = total,
                    read = { start, count -> reader.read(start, count) },
                    backend = backend,
                    writer = { planes, count ->
                        for ((source, writer) in writers.withIndex()) {
                            writer.write(planes[source][0], planes[source][1], count)
                        }
                    },
                    onSegment = { done, segments ->
                        if (!shouldContinue()) throw InterruptedException("separação cancelada")
                        progress(done, segments)
                    },
                )
            } finally {
                writers.forEach { it.finish() }
            }
            val seconds = (System.nanoTime() - started) / 1e9
            val audioSeconds = total / StemPipeline.SAMPLE_RATE.toDouble()
            val backendLog = (backend as? OnnxStemBackend)?.let {
                " (modelo ${it.modelNanos / 1e9}s, transformadas ${it.transformNanos / 1e9}s)"
            } ?: ""
            Log.i(
                OnnxStemBackend.TAG,
                "separados ${"%.0f".format(audioSeconds)} s de áudio em ${"%.1f".format(seconds)} s$backendLog",
            )
        } finally {
            reader.close()
        }
    }
}

/** Leitor por trecho de um WAV PCM 16-bit; mono vira estéreo duplicado, zero além do fim. */
internal class StereoWavReader(file: File) : AutoCloseable {
    private val raf = RandomAccessFile(file, "r")
    private val channels: Int
    private val dataOffset: Long
    val frames: Int

    init {
        val header = ByteArray(12)
        raf.readFully(header)
        require(String(header, 0, 4, Charsets.US_ASCII) == "RIFF" && String(header, 8, 4, Charsets.US_ASCII) == "WAVE") {
            "não é WAV"
        }
        var channelCount = 2
        var bits = 16
        var offset = -1L
        var dataBytes = 0L
        val chunk = ByteArray(8)
        while (raf.filePointer + 8 <= raf.length()) {
            raf.readFully(chunk)
            val id = String(chunk, 0, 4, Charsets.US_ASCII)
            val size = ByteBuffer.wrap(chunk, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            if (id == "fmt ") {
                val fmt = ByteArray(size.toInt())
                raf.readFully(fmt)
                val bb = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                bb.short // formato
                channelCount = bb.short.toInt()
                bb.int // taxa
                bb.int // bytes/s
                bb.short // alinhamento
                bits = bb.short.toInt()
            } else if (id == "data") {
                offset = raf.filePointer
                dataBytes = minOf(size, raf.length() - offset)
                break
            } else {
                raf.seek(raf.filePointer + size + (size and 1))
            }
        }
        require(offset >= 0 && bits == 16) { "WAV sem bloco data ou não é 16-bit" }
        channels = channelCount
        dataOffset = offset
        frames = (dataBytes / (2L * channels)).toInt()
    }

    fun read(start: Int, count: Int): Array<FloatArray> {
        val left = FloatArray(count)
        val right = FloatArray(count)
        val available = (frames - start).coerceIn(0, count)
        if (available > 0) {
            val bytes = ByteArray(available * 2 * channels)
            raf.seek(dataOffset + start.toLong() * 2 * channels)
            raf.readFully(bytes)
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until available) {
                val l = bb.short / 32768f
                val r = if (channels > 1) bb.short / 32768f else l
                for (extra in 2 until channels) bb.short
                left[i] = l
                right[i] = r
            }
        }
        return arrayOf(left, right)
    }

    override fun close() = raf.close()
}

/** Escritor estéreo 16-bit em streaming: header provisório, `finish` corrige os tamanhos. */
internal class StereoWavWriter(file: File, private val sampleRate: Int) {
    private val raf = RandomAccessFile(file, "rw")
    private var dataBytes = 0L

    init {
        raf.setLength(0)
        raf.write(header(0))
    }

    private fun header(dataSize: Int): ByteArray {
        val bb = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + dataSize).put("WAVE".toByteArray(Charsets.US_ASCII))
        bb.put("fmt ".toByteArray(Charsets.US_ASCII)).putInt(16).putShort(1).putShort(2)
            .putInt(sampleRate).putInt(sampleRate * 4).putShort(4).putShort(16)
        bb.put("data".toByteArray(Charsets.US_ASCII)).putInt(dataSize)
        return bb.array()
    }

    fun write(left: FloatArray, right: FloatArray, count: Int) {
        val bytes = ByteBuffer.allocate(count * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) {
            bytes.putShort((left[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            bytes.putShort((right[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        }
        raf.write(bytes.array())
        dataBytes += count * 4L
    }

    fun finish() {
        raf.seek(0)
        raf.write(header(dataBytes.toInt()))
        raf.close()
    }
}
