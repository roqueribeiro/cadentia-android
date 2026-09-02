package com.levelhard.cadentia.features.recorder

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Picos para desenhar a forma de onda de um clipe — port do
 * `WaveformCache.swift`: o take é lido uma vez, reduzido a um pico por
 * fatia, e guardado em memória e num sidecar `.peaks` ao lado do áudio.
 * Redesenhar durante um arrasto tem que ser de graça.
 */
data class WaveformPeaks(
    /** Magnitude de pico (0…1) por fatia, na ordem do arquivo. */
    val values: FloatArray,
    /** Fatias por segundo de áudio. */
    val resolution: Double,
) {
    /** Pico numa janela do arquivo de origem, para uma coluna do desenho. */
    fun peak(fromSeconds: Double, toSeconds: Double): Float {
        if (values.isEmpty() || toSeconds <= fromSeconds) return 0f
        val first = maxOf(0, (fromSeconds * resolution).toInt())
        val last = minOf(values.size - 1, (toSeconds * resolution).toInt())
        if (first > last) return values[first.coerceIn(0, values.size - 1)]
        var peak = 0f
        for (i in first..last) peak = maxOf(peak, values[i])
        return peak
    }

    override fun equals(other: Any?): Boolean =
        other is WaveformPeaks && resolution == other.resolution && values.contentEquals(other.values)

    override fun hashCode(): Int = 31 * values.contentHashCode() + resolution.hashCode()
}

/** Carrega e cacheia picos fora do main — o papel do `WaveformStore` ator. */
class WaveformStore(private val engine: RecorderEngine) {
    private companion object {
        /**
         * 120 fatias/s é mais fino que qualquer zoom da UI e ainda dá só
         * ~29 KB por minuto de áudio.
         */
        const val RESOLUTION = 120.0
    }

    private val mutex = Mutex()
    private val cache = mutableMapOf<String, WaveformPeaks>()

    suspend fun peaks(fileName: String): WaveformPeaks? {
        mutex.withLock { cache[fileName] }?.let { return it }
        val computed = withContext(Dispatchers.IO) { compute(fileName) } ?: return null
        mutex.withLock { cache[fileName] = computed }
        return computed
    }

    suspend fun invalidate(fileName: String) {
        mutex.withLock { cache.remove(fileName) }
    }

    private fun sidecar(fileName: String): File = engine.takeFile("$fileName.peaks")

    private fun compute(fileName: String): WaveformPeaks? {
        val cached = sidecar(fileName)
        if (cached.exists() && cached.length() >= 4) {
            runCatching {
                val bytes = cached.readBytes()
                val floats = FloatArray(bytes.size / 4)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
                if (floats.isNotEmpty()) return WaveformPeaks(floats, RESOLUTION)
            }
        }

        val reader = TakeReader.open(engine.takeFile(fileName)) ?: return null
        reader.use {
            val framesPerSlice = maxOf(1, (reader.sampleRate / RESOLUTION).toInt())
            val values = ArrayList<Float>((reader.frames / framesPerSlice + 1).toInt())
            var frame = 0L
            while (frame < reader.frames) {
                val slice = reader.read(frame, framesPerSlice) ?: break
                var peak = 0f
                for (sample in slice) peak = maxOf(peak, abs(sample))
                values.add(minOf(1f, peak))
                frame += framesPerSlice
            }
            if (values.isEmpty()) return null
            val floats = values.toFloatArray()
            runCatching {
                val bytes = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                bytes.asFloatBuffer().put(floats)
                sidecar(fileName).writeBytes(bytes.array())
            }
            return WaveformPeaks(floats, RESOLUTION)
        }
    }
}
