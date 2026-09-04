package com.levelhard.cadentia.features.stems

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * As faixas separadas em AAC, como no iOS 1.16 (`StemSeparator.trackExtension
 * = "m4a"`, 256 kbps): quatro WAV de uma música de 4 minutos são 170 MB, em
 * AAC são 31 MB, e é com esse tamanho que a política do cache (reserva de
 * 2 GB) foi pensada.
 *
 * Ida: o separador escreve WAV por janela (memória de UMA janela) e, com a
 * música inteira pronta, [encode] passa cada WAV para `.m4a` pelo MediaCodec
 * (`audio/mp4a-latm`, AAC-LC) e pelo MediaMuxer; o WAV vai embora. Volta: o
 * player lê PCM com seek por quadro, então [decode] devolve o `.m4a` a um
 * WAV 16-bit no `cacheDir` na abertura da música (o iOS faz isso por baixo no
 * `AVAudioFile`); o Android não tem decodificador de AAC com seek por quadro
 * pronto para quatro faixas travadas.
 *
 * Diferença deliberada do iOS: o PCM de entrada do encoder é 16-bit, então um
 * pico acima de 0 dBFS numa faixa isolada é cortado no arquivo (o iOS grava
 * float e preserva picos de 1,6). O limitador do player continua necessário
 * para a SOMA das faixas, que passa do teto do mesmo jeito.
 */
object StemTrackCodec {
    const val EXTENSION = "m4a"
    private const val TAG = "CadentiaStems"
    private const val MIME = MediaFormat.MIMETYPE_AUDIO_AAC
    private const val BIT_RATE = 256_000
    private const val TIMEOUT_US = 10_000L

    /** WAV 16-bit estéreo → `.m4a`. Lança em falha de codec; o chamador decide. */
    fun encode(wav: File, out: File) {
        val reader = StereoWavReader(wav)
        val sampleRate = reader.sampleRate
        val format = MediaFormat.createAudioFormat(MIME, sampleRate, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        }
        val codec = MediaCodec.createEncoderByType(MIME)
        out.delete()
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var muxing = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var framesIn = 0L
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                // Entrada sem espera e saída só espera quando a entrada não
                // andou: com 10 ms de espera em cada lado, cada quadro AAC
                // (23 ms de áudio) custava 20 ms de relógio e a codificação
                // rodava a 0,5x do tempo real (medido: 80 s de áudio em 40 s).
                var fed = false
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(0)
                    if (index >= 0) {
                        fed = true
                        val buffer = codec.getInputBuffer(index) ?: throw IOException("encoder sem buffer de entrada")
                        buffer.clear()
                        val frames = reader.readRawInto(buffer, framesIn)
                        val presentationUs = framesIn * 1_000_000L / sampleRate
                        if (frames <= 0) {
                            codec.queueInputBuffer(index, 0, 0, presentationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(index, 0, frames * 4, presentationUs, 0)
                            framesIn += frames
                        }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, if (fed) 0 else TIMEOUT_US)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxing = true
                    }
                    index >= 0 -> {
                        val buffer = codec.getOutputBuffer(index) ?: throw IOException("encoder sem buffer de saída")
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                            if (!muxing) throw IOException("amostra antes do formato de saída")
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buffer, info)
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            if (muxing) runCatching { muxer.stop() }
            muxer.release()
            reader.close()
        }
    }

    /**
     * `.m4a` (ou qualquer coisa que o MediaExtractor abra) → WAV 16-bit
     * estéreo 44,1 k. Devolve false se o arquivo não tem áudio decodificável.
     * Mono vira estéreo duplicado; outra taxa é recusada (as faixas nascem a
     * 44,1 k aqui mesmo).
     */
    fun decode(source: File, out: File): Boolean {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var writer: StereoWavWriter? = null
        try {
            extractor.setDataSource(source.path)
            var trackIndex = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i
                    inputFormat = f
                    break
                }
            }
            val format = inputFormat ?: return false
            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return false
            codec = MediaCodec.createDecoderByType(mime).also {
                it.configure(format, null, null, 0)
                it.start()
            }
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var frames = 0L
            while (!outputDone) {
                var fed = false
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(0)
                    if (index >= 0) {
                        fed = true
                        val buffer = codec.getInputBuffer(index) ?: return false
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, if (fed) 0 else TIMEOUT_US)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFormat = codec.outputFormat
                        channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        rate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) != android.media.AudioFormat.ENCODING_PCM_16BIT
                        ) {
                            Log.w(TAG, "decoder devolveu PCM que não é 16-bit; faixa recusada")
                            return false
                        }
                    }
                    index >= 0 -> {
                        val buffer = codec.getOutputBuffer(index) ?: return false
                        if (info.size > 0) {
                            if (writer == null) {
                                if (rate != StereoWavWriter.EXPECTED_RATE) {
                                    Log.w(TAG, "faixa a $rate Hz; o player espera ${StereoWavWriter.EXPECTED_RATE}")
                                    return false
                                }
                                writer = StereoWavWriter(out, rate)
                            }
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            frames += writer.writeRaw16(buffer, channels)
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            return frames > 0
        } catch (error: Exception) {
            Log.w(TAG, "decodificar ${source.name}: ${error.message}")
            return false
        } finally {
            runCatching { codec?.stop() }
            codec?.release()
            extractor.release()
            writer?.finish()
        }
    }

    /**
     * Troca os WAV de uma pasta parcial pelos `.m4a`. Falhou uma faixa, a
     * pasta continua com os WAV que faltaram (o player lê os dois) e o erro é
     * só registrado: perder a separação inteira por causa do codec de um
     * emulador seria pior do que 40 MB a mais no cache.
     */
    fun encodeFolder(folder: File, names: List<String>) {
        val started = System.nanoTime()
        val before = java.util.concurrent.atomic.AtomicLong()
        val after = java.util.concurrent.atomic.AtomicLong()
        // As quatro em paralelo: são encoders de software independentes e a
        // separação já deixou os núcleos livres. Uma por vez custava 4x.
        val workers = names.map { name ->
            Thread({
                val wav = File(folder, "$name.wav")
                if (!wav.isFile) return@Thread
                val aac = File(folder, "$name.$EXTENSION")
                try {
                    encode(wav, aac)
                    if (aac.length() <= 0) throw IOException("saída vazia")
                    before.addAndGet(wav.length())
                    after.addAndGet(aac.length())
                    wav.delete()
                } catch (error: Exception) {
                    Log.w(TAG, "AAC de $name falhou (${error.message}); faixa fica em WAV")
                    aac.delete()
                }
            }, "stem-aac-$name").apply { start() }
        }
        workers.forEach { it.join() }
        val ms = (System.nanoTime() - started) / 1_000_000
        Log.i(TAG, "faixas em AAC: ${before.get() / 1_000_000} MB → ${after.get() / 1_000_000} MB em $ms ms")
    }
}

/** Leitura crua de quadros 16-bit estéreo direto para o buffer do codec. */
internal fun StereoWavReader.readRawInto(buffer: ByteBuffer, start: Long): Int {
    val available = (frames - start).coerceIn(0L, (buffer.remaining() / 4).toLong()).toInt()
    if (available <= 0) return 0
    val bytes = readRaw(start.toInt(), available)
    buffer.put(bytes)
    return available
}

/** Grava PCM 16-bit cru vindo do decoder (mono duplica para os dois lados). Devolve quadros. */
internal fun StereoWavWriter.writeRaw16(buffer: ByteBuffer, channels: Int): Int {
    val bytesPerFrame = 2 * channels
    val frames = buffer.remaining() / bytesPerFrame
    if (frames <= 0) return 0
    if (channels == 2) {
        val bytes = ByteArray(frames * 4)
        buffer.get(bytes)
        writeRawStereo16(bytes)
        return frames
    }
    val source = buffer.order(ByteOrder.LITTLE_ENDIAN)
    val out = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
    for (i in 0 until frames) {
        val l = source.short
        for (extra in 1 until channels) source.short
        out.putShort(l).putShort(l)
    }
    writeRawStereo16(out.array())
    return frames
}
