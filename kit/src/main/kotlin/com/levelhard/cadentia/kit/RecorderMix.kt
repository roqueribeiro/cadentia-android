package com.levelhard.cadentia.kit

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.cos
import kotlin.math.sin

/**
 * Mixdown offline do projeto do Gravador — o equivalente puro do render
 * offline do `MultitrackRecorderEngine.mixdown` do iOS (lá o AVAudioEngine
 * fazia o grafo; aqui a soma é nossa, testável sem Android): posição, aparo,
 * ganho e fades por clipe, volume e pan por trilha, mute e solo. O encode
 * AAC/M4A fica no app (MediaCodec); isto devolve PCM estéreo intercalado.
 */
object RecorderMix {
    /**
     * Soma o projeto em estéreo intercalado a `sampleRate`. `readMono`
     * entrega as amostras mono de um take JÁ na taxa pedida (os takes são
     * gravados na taxa do stream e não passam por resample aqui).
     * `enhance` aplica a cadeia de masterização do iOS: passa-alta 70 Hz +
     * compressor. Um segundo de cauda para o decay não ser cortado no fim.
     */
    fun render(
        project: RecorderProject,
        sampleRate: Double,
        enhance: Boolean,
        readMono: (fileName: String) -> FloatArray?,
    ): FloatArray? {
        val tracks = project.audibleTracks()
        val total = project.duration
        if (total <= 0 || tracks.isEmpty()) return null

        val frames = ((total + 1) * sampleRate).toInt()
        val left = FloatArray(frames)
        val right = FloatArray(frames)
        var wroteAnything = false

        for (track in tracks) {
            // Pan de potência constante: centro mantém o nível, os extremos
            // não somam 6 dB.
            val angle = (track.pan + 1) * Math.PI / 4
            val panLeft = cos(angle).toFloat()
            val panRight = sin(angle).toFloat()
            val volume = track.volume.toFloat()

            for (clip in track.clips) {
                val source = readMono(clip.fileName) ?: continue
                val startFrame = (clip.start * sampleRate).toInt()
                val clipFrames = (clip.duration * sampleRate).toInt()
                val trimOffset = (clip.trimStart * sampleRate).toInt()
                if (clipFrames <= 0) continue
                wroteAnything = true

                for (i in 0 until clipFrames) {
                    val sourceIdx = trimOffset + i
                    if (sourceIdx >= source.size) break
                    val outIdx = startFrame + i
                    if (outIdx >= frames) break
                    val clipTime = i / sampleRate
                    val envelope = clip.envelope(clipTime).toFloat()
                    val sample = source[sourceIdx] * envelope * volume
                    left[outIdx] += sample * panLeft
                    right[outIdx] += sample * panRight
                }
            }
        }
        if (!wroteAnything) return null

        if (enhance) {
            // A cadeia do iOS: passa-alta 70 Hz + DynamicsProcessor. O
            // compressor do kit faz o papel do processor com uma curva
            // equivalente declarada aqui (não há "default da Apple" no JVM).
            for (channel in listOf(left, right)) {
                AudioDSP.Biquad(
                    AudioDSP.Biquad.Kind.Highpass,
                    frequency = 70.0, q = 0.707, sampleRate = sampleRate,
                ).process(channel)
                AudioDSP.compress(
                    channel, thresholdDB = -20f, ratio = 3f,
                    attack = 0.003, release = 0.1, makeupDB = 0f,
                    sampleRate = sampleRate,
                )
            }
        }

        // Soma de trilhas pode passar de 0 dBFS; o teto duro equivale ao
        // clamp do conversor, não a uma normalização.
        val out = FloatArray(frames * 2)
        for (i in 0 until frames) {
            out[2 * i] = left[i].coerceIn(-1f, 1f)
            out[2 * i + 1] = right[i].coerceIn(-1f, 1f)
        }
        return out
    }
}

/**
 * WAV PCM 16-bit mono — o formato dos takes no Android (o iOS gravava CAF
 * pelo AVAudioFile; aqui o arquivo é nosso, e WAV é o container mais burro
 * que todo mundo lê). Escrita e leitura por streams, puras e testáveis.
 */
object WavIO {
    /** Escreve o cabeçalho + amostras. `samples` em -1…1, mono. */
    fun write(samples: FloatArray, sampleRate: Int, out: OutputStream) {
        val dataBytes = samples.size * 2
        val header = ByteArray(44)
        fun putAscii(offset: Int, text: String) {
            for (i in text.indices) header[offset + i] = text[i].code.toByte()
        }
        fun putIntLE(offset: Int, value: Int) {
            header[offset] = (value and 0xFF).toByte()
            header[offset + 1] = ((value shr 8) and 0xFF).toByte()
            header[offset + 2] = ((value shr 16) and 0xFF).toByte()
            header[offset + 3] = ((value shr 24) and 0xFF).toByte()
        }
        fun putShortLE(offset: Int, value: Int) {
            header[offset] = (value and 0xFF).toByte()
            header[offset + 1] = ((value shr 8) and 0xFF).toByte()
        }
        putAscii(0, "RIFF")
        putIntLE(4, 36 + dataBytes)
        putAscii(8, "WAVE")
        putAscii(12, "fmt ")
        putIntLE(16, 16)
        putShortLE(20, 1) // PCM
        putShortLE(22, 1) // mono
        putIntLE(24, sampleRate)
        putIntLE(28, sampleRate * 2)
        putShortLE(32, 2)
        putShortLE(34, 16)
        putAscii(36, "data")
        putIntLE(40, dataBytes)
        out.write(header)

        val buffer = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val value = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt()
            buffer[2 * i] = (value and 0xFF).toByte()
            buffer[2 * i + 1] = ((value shr 8) and 0xFF).toByte()
        }
        out.write(buffer)
        out.flush()
    }

    fun toByteArray(samples: FloatArray, sampleRate: Int): ByteArray {
        val out = ByteArrayOutputStream(44 + samples.size * 2)
        write(samples, sampleRate, out)
        return out.toByteArray()
    }

    data class Wav(val samples: FloatArray, val sampleRate: Int) {
        val durationSeconds: Double get() = samples.size / sampleRate.toDouble()

        override fun equals(other: Any?): Boolean =
            other is Wav && sampleRate == other.sampleRate && samples.contentEquals(other.samples)

        override fun hashCode(): Int = 31 * samples.contentHashCode() + sampleRate
    }

    /**
     * Lê WAV PCM 16-bit ou float32, qualquer nº de canais (mistura para
     * mono), pulando chunks desconhecidos. null = não é WAV que entendemos.
     */
    fun read(stream: InputStream): Wav? {
        val bytes = stream.readBytes()
        if (bytes.size < 44) return null
        fun ascii(offset: Int, length: Int) = String(bytes, offset, length, Charsets.US_ASCII)
        fun intLE(offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)
        fun shortLE(offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

        if (ascii(0, 4) != "RIFF" || ascii(8, 4) != "WAVE") return null

        var format = 0
        var channels = 1
        var sampleRate = 0
        var bitsPerSample = 0
        var dataOffset = -1
        var dataSize = 0

        var cursor = 12
        while (cursor + 8 <= bytes.size) {
            val chunkId = ascii(cursor, 4)
            val chunkSize = intLE(cursor + 4)
            val body = cursor + 8
            when (chunkId) {
                "fmt " -> {
                    if (body + 16 > bytes.size) return null
                    format = shortLE(body)
                    channels = maxOf(1, shortLE(body + 2))
                    sampleRate = intLE(body + 4)
                    bitsPerSample = shortLE(body + 14)
                }
                "data" -> {
                    dataOffset = body
                    dataSize = minOf(chunkSize, bytes.size - body)
                }
            }
            cursor = body + chunkSize + (chunkSize and 1)
        }
        if (dataOffset < 0 || sampleRate <= 0) return null

        val bytesPerSample = bitsPerSample / 8
        if (bytesPerSample == 0) return null
        val frameCount = dataSize / (bytesPerSample * channels)
        val samples = FloatArray(frameCount)

        for (frame in 0 until frameCount) {
            var sum = 0f
            for (channel in 0 until channels) {
                val at = dataOffset + (frame * channels + channel) * bytesPerSample
                sum += when {
                    format == 1 && bitsPerSample == 16 ->
                        (((bytes[at].toInt() and 0xFF) or (bytes[at + 1].toInt() shl 8)).toShort() / 32768f)
                    format == 3 && bitsPerSample == 32 ->
                        Float.fromBits(intLE(at))
                    else -> return null
                }
            }
            samples[frame] = sum / channels
        }
        return Wav(samples, sampleRate)
    }
}
