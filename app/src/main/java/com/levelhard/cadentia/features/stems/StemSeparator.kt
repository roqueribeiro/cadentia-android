package com.levelhard.cadentia.features.stems

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.util.Log
import com.levelhard.cadentia.kit.DemucsSpectrogram
import com.levelhard.cadentia.kit.StemPipeline
import java.io.File
import java.io.RandomAccessFile
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.FloatBuffer

/**
 * Onde o modelo de separação mora no aparelho — o papel do
 * `Separator.mlmodelc` no bundle do iOS (`StemsModel.swift:176`).
 *
 * O arquivo tem 174 MB (htdemucs em ONNX fp32, `scripts/convert-stem-model.py`)
 * e tem três caminhos, nesta ordem:
 *
 * 1. **Dentro do pacote**, em `assets/separator.onnx`, que é o caminho normal
 *    e o mesmo do iOS (`Separator.mlpackage`, 103 MB, dentro do bundle). Fica
 *    fora do git; o build copia de `model/separator.onnx` (ver
 *    `app/build.gradle.kts`). Guardado sem deflate, então o `openFd` devolve
 *    deslocamento e tamanho e o modelo abre por mmap, sem cópia para o disco.
 * 2. **Baixado** para `filesDir/models/` na primeira separação
 *    ([StemModelDownloader]), que é como uma build sideloaded sem o asset
 *    ainda consegue separar.
 * 3. **QA**, por `adb push` + `run-as cp` para o mesmo `filesDir/models/`.
 *
 * Sem nenhum dos três a tela mostra `modelMissing`, como o iOS sem o
 * `.mlmodelc`.
 */
object StemModelStore {
    const val FILE_NAME = "separator.onnx"

    fun directory(context: Context): File = File(context.filesDir, "models")

    fun file(context: Context): File = File(directory(context), FILE_NAME)

    /**
     * O descritor do modelo embarcado, ou null se esta build não o tem. O
     * `openFd` só funciona porque o `.onnx` está em `noCompress`: para um
     * asset deflatado ele lança `FileNotFoundException`, que aqui vira
     * "não tem", não erro.
     */
    fun bundled(context: Context): AssetFileDescriptor? =
        runCatching { context.assets.openFd(FILE_NAME) }.getOrNull()

    fun isBundled(context: Context): Boolean =
        bundled(context)?.use { it.declaredLength > 1_000_000 } ?: false

    /** O iOS: `isAvailable` é só "o arquivo existe". Aqui também exige que não seja um download pela metade. */
    fun isAvailable(context: Context): Boolean =
        isBundled(context) || file(context).let { it.isFile && it.length() > 1_000_000 }
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
class OnnxStemBackend private constructor(
    private val origin: String,
    private val sizeBytes: Long,
    /**
     * Quando o modelo vem do pacote: a região do APK mapeada em memória de
     * onde a sessão lê os pesos. Soltada assim que a sessão abre — ver o
     * `init`.
     */
    private var mapped: ByteBuffer?,
    private val path: String?,
) : StemPipeline.StemBackend, AutoCloseable {
    /** O modelo baixado ou empurrado pelo QA, em `filesDir/models/`. */
    constructor(modelFile: File) :
        this(modelFile.name, modelFile.length(), null, modelFile.absolutePath)

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
        val fromPackage = mapped != null
        session = when {
            mapped != null -> environment.createSession(mapped, options)
            path != null -> environment.createSession(path, options)
            else -> error("OnnxStemBackend sem modelo")
        }
        // O ORT copia os pesos para as estruturas dele durante o
        // createSession, então o mapeamento não serve mais para nada e só
        // inflaria o pico. Medido na JVM do container (x86_64, mesmo modelo,
        // mesma janela, VmHWM do processo):
        //   modelo em arquivo solto                     pico 670 MB
        //   mmap do pacote, mapeamento mantido vivo     pico 835 MB
        //   mmap do pacote, mapeamento solto aqui       pico 673 MB
        // No emulador arm64 (ART, não a JVM) o mesmo caminho deu 983 MB
        // contra os 801 MB medidos com o modelo em arquivo, ou seja: aqui o
        // `System.gc()` NÃO garantiu o desmapeamento. São páginas de arquivo
        // limpas, que o kernel recupera sob pressão antes de matar o
        // processo, então isto é pico de RSS, não risco de OOM — mas é honesto
        // dizer que na JVM o truque funciona e no ART ainda não foi provado.
        mapped = null
        System.gc()
        Log.i(
            TAG,
            "modelo aberto: $origin (${sizeBytes / 1_000_000} MB, " +
                "${if (fromPackage) "mmap do pacote" else "arquivo em disco"}), entradas ${session.inputNames}",
        )
    }

    companion object {
        const val TAG = "CadentiaStems"

        /**
         * Abre o modelo de onde ele estiver. O do pacote vem primeiro: é o
         * caminho normal, e mapear a região do APK evita a cópia de 174 MB
         * que a alternativa (extrair o asset para o filesDir) custaria em
         * disco e em tempo na primeira separação.
         */
        fun open(context: Context): OnnxStemBackend {
            val fd = StemModelStore.bundled(context)
            if (fd != null) {
                fd.use { descriptor ->
                    val buffer = FileInputStream(descriptor.fileDescriptor).use { stream ->
                        stream.channel.map(
                            FileChannel.MapMode.READ_ONLY,
                            descriptor.startOffset,
                            descriptor.declaredLength,
                        )
                    }
                    return OnnxStemBackend(
                        origin = StemModelStore.FILE_NAME,
                        sizeBytes = descriptor.declaredLength,
                        mapped = buffer,
                        path = null,
                    )
                }
            }
            return OnnxStemBackend(StemModelStore.file(context))
        }
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
            // Os contadores do backend acumulam pela vida da sessão (uma leva
            // inteira); o log desta música é a diferença.
            val modelBefore = (backend as? OnnxStemBackend)?.modelNanos ?: 0L
            val transformBefore = (backend as? OnnxStemBackend)?.transformNanos ?: 0L
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
                " (modelo ${"%.1f".format((it.modelNanos - modelBefore) / 1e9)} s, transformadas ${"%.1f".format((it.transformNanos - transformBefore) / 1e9)} s)"
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
    val sampleRate: Int

    init {
        val header = ByteArray(12)
        raf.readFully(header)
        require(String(header, 0, 4, Charsets.US_ASCII) == "RIFF" && String(header, 8, 4, Charsets.US_ASCII) == "WAVE") {
            "não é WAV"
        }
        var channelCount = 2
        var bits = 16
        var rate = StemPipeline.SAMPLE_RATE
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
                rate = bb.int
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
        sampleRate = rate
        frames = (dataBytes / (2L * channels)).toInt()
    }

    /** Quadros crus 16-bit estéreo (mono duplicado), para alimentar um codec sem passar por float. */
    fun readRaw(start: Int, count: Int): ByteArray {
        val available = (frames - start).coerceIn(0, count)
        if (channels == 2) {
            val bytes = ByteArray(available * 4)
            raf.seek(dataOffset + start.toLong() * 4)
            raf.readFully(bytes)
            return bytes
        }
        val source = ByteArray(available * 2 * channels)
        raf.seek(dataOffset + start.toLong() * 2 * channels)
        raf.readFully(source)
        val bb = ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN)
        val out = ByteBuffer.allocate(available * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until available) {
            val l = bb.short
            for (extra in 1 until channels) bb.short
            out.putShort(l).putShort(l)
        }
        return out.array()
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

    /** Bytes já em PCM 16-bit estéreo little-endian (saída de decoder). */
    fun writeRawStereo16(bytes: ByteArray) {
        raf.write(bytes)
        dataBytes += bytes.size.toLong()
    }

    fun finish() {
        raf.seek(0)
        raf.write(header(dataBytes.toInt()))
        raf.close()
    }

    companion object {
        /** A taxa das faixas: o separador normaliza para ela e o player assume. */
        const val EXPECTED_RATE = StemPipeline.SAMPLE_RATE
    }
}
