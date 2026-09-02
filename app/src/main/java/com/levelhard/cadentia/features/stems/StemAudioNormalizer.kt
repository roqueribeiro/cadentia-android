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

            val left = ArrayList<Float>(1 shl 20)
            val right = ArrayList<Float>(1 shl 20)
            var sourceRate = format.getIntOrDefault(MediaFormat.KEY_SAMPLE_RATE, TARGET_RATE)
            var channels = format.getIntOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 2)
            var pcmEncoding = format.getIntOrDefault(
                MediaFormat.KEY_PCM_ENCODING,
                android.media.AudioFormat.ENCODING_PCM_16BIT,
            )

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
                                drain(buffer.order(ByteOrder.LITTLE_ENDIAN), pcmEncoding, channels, left, right)
                            }
                            codec.releaseOutputBuffer(outputIndex, false)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                outputDone = true
                            }
                        }
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
            if (left.isEmpty()) return false

            var l = left.toFloatArray()
            var r = right.toFloatArray()
            if (sourceRate != TARGET_RATE) {
                l = StemResampler.resample(l, sourceRate.toDouble(), TARGET_RATE.toDouble())
                r = StemResampler.resample(r, sourceRate.toDouble(), TARGET_RATE.toDouble())
            }
            output.parentFile?.mkdirs()
            StereoWav.write(l, r, TARGET_RATE, output)
            true
        } catch (_: Exception) {
            false
        } finally {
            extractor.release()
        }
    }

    /** Intercalado (16-bit ou float) → dois planos; >2 canais somam nos dois primeiros. */
    private fun drain(
        buffer: java.nio.ByteBuffer,
        pcmEncoding: Int,
        channels: Int,
        left: ArrayList<Float>,
        right: ArrayList<Float>,
    ) {
        val stereo = channels >= 2
        when (pcmEncoding) {
            android.media.AudioFormat.ENCODING_PCM_FLOAT -> {
                val floats = buffer.asFloatBuffer()
                val frames = floats.remaining() / channels
                for (i in 0 until frames) {
                    val base = i * channels
                    val l = floats.get(base)
                    left.add(l)
                    right.add(if (stereo) floats.get(base + 1) else l)
                }
            }
            else -> {
                val shorts = buffer.asShortBuffer()
                val frames = shorts.remaining() / channels
                for (i in 0 until frames) {
                    val base = i * channels
                    val l = shorts.get(base) / 32768f
                    left.add(l)
                    right.add(if (stereo) shorts.get(base + 1) / 32768f else l)
                }
            }
        }
    }

    private fun MediaFormat.getIntOrDefault(key: String, fallback: Int): Int =
        if (containsKey(key)) getInteger(key) else fallback
}
