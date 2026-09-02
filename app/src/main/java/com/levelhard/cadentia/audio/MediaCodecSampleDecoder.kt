package com.levelhard.cadentia.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import com.levelhard.cadentia.kit.SampleDecoder
import com.levelhard.cadentia.kit.StereoBuffer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * O decodificador dos packs no Android — o papel do `AVAudioFile` no iOS.
 *
 * FLAC (o formato que o `fetch-samples.mjs` escreve: 44,1 kHz, estéreo,
 * 16 bits) passa pelo MediaExtractor + MediaCodec do sistema, síncrono, num
 * laço só: os arquivos têm 4–6 s e o banco já chama isto fora da thread
 * principal (aquecimento em Default, primeira nota de uma zona no render).
 * WAV cai no leitor do Kit. Qualquer falha devolve null, e o banco cai na
 * síntese — arquivo ruim não é crash.
 */
class MediaCodecSampleDecoder : SampleDecoder {
    override fun decode(file: File): StereoBuffer? {
        if (!file.isFile) return null
        if (file.extension.equals("wav", ignoreCase = true)) return SampleDecoder.Wav.decode(file)
        return try {
            val started = SystemClock.elapsedRealtime()
            val decoded = decodeCompressed(file)
            if (decoded == null) {
                Log.w(TAG, "decodificação vazia: ${file.parentFile?.name}/${file.name}")
            } else if (Log.isLoggable(TAG, Log.DEBUG)) {
                // Prova de que o caminho de sample foi tomado, para o QA por
                // logcat: `adb shell setprop log.tag.CadentiaSamples DEBUG`.
                Log.d(
                    TAG,
                    "${file.parentFile?.name}/${file.name}: ${decoded.frameCount} frames em " +
                        "${SystemClock.elapsedRealtime() - started} ms",
                )
            }
            decoded
        } catch (e: Exception) {
            Log.w(TAG, "falha ao decodificar ${file.name}: ${e.message}")
            null
        }
    }

    private fun decodeCompressed(file: File): StereoBuffer? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i
                    format = f
                    break
                }
            }
            val inputFormat = format ?: return null
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return null
            extractor.selectTrack(track)

            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            val left = FloatList()
            val right = FloatList()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var idleRounds = 0

            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inIndex) ?: return null
                        val read = extractor.readSampleData(buffer, 0)
                        if (read < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, read, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex >= 0 -> {
                        idleRounds = 0
                        val out = decoder.getOutputBuffer(outIndex)
                        if (out != null && info.size > 0) {
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            append(out, channels, encoding, left, right)
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFormat = decoder.outputFormat
                        channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        encoding = if (outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else {
                            AudioFormat.ENCODING_PCM_16BIT
                        }
                    }
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // Um codec que parou de responder não pode segurar a
                        // thread para sempre: 200 rodadas de 10 ms é um segundo
                        // sem nada, e um FLAC de 6 s decodifica em bem menos.
                        if (++idleRounds > 200) return null
                    }
                }
            }
            if (left.size == 0) return null
            return StereoBuffer(left.toArray(), right.toArray())
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            codec?.release()
            extractor.release()
        }
    }

    private fun append(buffer: ByteBuffer, channels: Int, encoding: Int, left: FloatList, right: FloatList) {
        buffer.order(ByteOrder.nativeOrder())
        val ch = maxOf(1, channels)
        when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val floats = buffer.asFloatBuffer()
                val frames = floats.remaining() / ch
                for (i in 0 until frames) {
                    val l = floats.get(i * ch)
                    left.add(l)
                    right.add(if (ch > 1) floats.get(i * ch + 1) else l)
                }
            }
            AudioFormat.ENCODING_PCM_8BIT -> {
                val frames = buffer.remaining() / ch
                for (i in 0 until frames) {
                    val l = ((buffer.get(i * ch).toInt() and 0xFF) - 128) / 128f
                    left.add(l)
                    right.add(if (ch > 1) ((buffer.get(i * ch + 1).toInt() and 0xFF) - 128) / 128f else l)
                }
            }
            else -> {
                val shorts = buffer.asShortBuffer()
                val frames = shorts.remaining() / ch
                for (i in 0 until frames) {
                    val l = shorts.get(i * ch) / 32768f
                    left.add(l)
                    right.add(if (ch > 1) shorts.get(i * ch + 1) / 32768f else l)
                }
            }
        }
    }

    /** Lista de float sem boxing: um FLAC de 6 s são 265 mil frames por canal. */
    private class FloatList {
        private var data = FloatArray(1 shl 16)
        var size = 0
            private set

        fun add(value: Float) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = value
        }

        fun toArray(): FloatArray = data.copyOf(size)
    }

    private companion object {
        const val TAG = "CadentiaSamples"
        const val TIMEOUT_US = 10_000L
    }
}
