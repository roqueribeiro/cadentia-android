package com.levelhard.cadentia.features.stems

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.levelhard.cadentia.kit.StemPipeline
import com.levelhard.cadentia.kit.StemResampler
import java.io.File
import java.nio.ByteOrder

/**
 * Põe qualquer arquivo de áudio no formato que o separador (e o player de
 * stems) exige: 44,1 kHz, estéreo, WAV — o papel do `AudioNormalizer.swift`.
 *
 * O modelo treinou em 44,1 kHz e as transformadas assumem isso; música real
 * chega em 48 kHz, mono, MP3, AAC. Pular esta etapa não falha alto: a
 * música só sai na velocidade errada, o que é pior do que um erro. O
 * MediaExtractor+MediaCodec decodificam pelo CONTEÚDO, não pela extensão —
 * a mesma lição do AVAssetReader do iOS (biblioteca real está cheia de
 * `.mp3` que é AAC por dentro).
 */
object StemAudioNormalizer {
    private const val TARGET_RATE = StemPipeline.SAMPLE_RATE

    /** Decodifica `uri` para um WAV estéreo 44,1 k em `output`. false = formato não suportado. */
    fun normalize(context: Context, uri: Uri, output: File): Boolean {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return false
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return false

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            var sourceRate = format.getIntOrDefault(MediaFormat.KEY_SAMPLE_RATE, TARGET_RATE)
            var channels = format.getIntOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 2)
            var pcmEncoding = format.getIntOrDefault(
                MediaFormat.KEY_PCM_ENCODING,
                android.media.AudioFormat.ENCODING_PCM_16BIT,
            )

            // Tudo em fluxo: decodifica um buffer, reamostra o que já dá,
            // escreve no WAV e solta. A versão anterior guardava a música
            // inteira em `ArrayList<Float>` (Float encaixotado: 20 bytes por
            // amostra) e uma música de 3 minutos passava de 250 MB — o app
            // morria de OutOfMemory ao abrir qualquer música real (05/09).
            output.parentFile?.mkdirs()
            val writer = StereoWavWriter(output, TARGET_RATE)
            var resamplers: Pair<StemResampler.Streaming, StemResampler.Streaming>? = null
            var frames = 0L
            var left = FloatArray(0)
            var right = FloatArray(0)

            fun emit(l: FloatArray, r: FloatArray) {
                val count = minOf(l.size, r.size)
                if (count == 0) return
                writer.write(l, r, count)
                frames += count
            }

            fun sink(l: FloatArray, r: FloatArray, count: Int) {
                if (count == 0) return
                if (sourceRate == TARGET_RATE) {
                    emit(l.copyOf(count), r.copyOf(count))
                    return
                }
                val pair = resamplers ?: Pair(
                    StemResampler.Streaming(sourceRate.toDouble(), TARGET_RATE.toDouble()),
                    StemResampler.Streaming(sourceRate.toDouble(), TARGET_RATE.toDouble()),
                ).also { resamplers = it }
                emit(pair.first.push(l, count), pair.second.push(r, count))
            }

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            try {
                while (!outputDone) {
                    if (!inputDone) {
                        val inputIndex = codec.dequeueInputBuffer(10_000)
                        if (inputIndex >= 0) {
                            val buffer = codec.getInputBuffer(inputIndex)!!
                            val read = extractor.readSampleData(buffer, 0)
                            if (read < 0) {
                                codec.queueInputBuffer(
                                    inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, read, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val fresh = codec.outputFormat
                            sourceRate = fresh.getIntOrDefault(MediaFormat.KEY_SAMPLE_RATE, sourceRate)
                            channels = fresh.getIntOrDefault(MediaFormat.KEY_CHANNEL_COUNT, channels)
                            pcmEncoding = fresh.getIntOrDefault(MediaFormat.KEY_PCM_ENCODING, pcmEncoding)
                        }
                        in 0..Int.MAX_VALUE -> {
                            val buffer = codec.getOutputBuffer(outputIndex)!!
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            if (info.size > 0) {
                                val count = frameCount(info.size, pcmEncoding, channels)
                                if (left.size < count) {
                                    left = FloatArray(count)
                                    right = FloatArray(count)
                                }
                                drain(buffer.order(ByteOrder.LITTLE_ENDIAN), pcmEncoding, channels, left, right, count)
                                sink(left, right, count)
                            }
                            codec.releaseOutputBuffer(outputIndex, false)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                outputDone = true
                            }
                        }
                    }
                }
                resamplers?.let { emit(it.first.finish(), it.second.finish()) }
            } finally {
                runCatching { codec.stop() }
                codec.release()
                writer.finish()
            }
            if (frames == 0L) {
                output.delete()
                return false
            }
            true
        } catch (_: Exception) {
            output.delete()
            false
        } finally {
            extractor.release()
        }
    }

    private fun frameCount(bytes: Int, pcmEncoding: Int, channels: Int): Int {
        val bytesPerSample = if (pcmEncoding == android.media.AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
        return bytes / (bytesPerSample * maxOf(channels, 1))
    }

    /** Intercalado (16-bit ou float) → dois planos; >2 canais somam nos dois primeiros. */
    private fun drain(
        buffer: java.nio.ByteBuffer,
        pcmEncoding: Int,
        channels: Int,
        left: FloatArray,
        right: FloatArray,
        frames: Int,
    ) {
        val stereo = channels >= 2
        when (pcmEncoding) {
            android.media.AudioFormat.ENCODING_PCM_FLOAT -> {
                val floats = buffer.asFloatBuffer()
                for (i in 0 until frames) {
                    val base = i * channels
                    val l = floats.get(base)
                    left[i] = l
                    right[i] = if (stereo) floats.get(base + 1) else l
                }
            }
            else -> {
                val shorts = buffer.asShortBuffer()
                for (i in 0 until frames) {
                    val base = i * channels
                    val l = shorts.get(base) / 32768f
                    left[i] = l
                    right[i] = if (stereo) shorts.get(base + 1) / 32768f else l
                }
            }
        }
    }

    private fun MediaFormat.getIntOrDefault(key: String, fallback: Int): Int =
        if (containsKey(key)) getInteger(key) else fallback
}
