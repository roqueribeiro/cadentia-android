package com.levelhard.cadentia.features.stems

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import android.util.Log
import com.levelhard.cadentia.audio.PlaybackSession
import com.levelhard.cadentia.kit.PeakLimiter
import com.levelhard.cadentia.kit.PracticeLoop
import com.levelhard.cadentia.kit.RealFFT
import com.levelhard.cadentia.kit.SpectrumBands
import com.levelhard.cadentia.kit.StemMix
import com.levelhard.cadentia.kit.StemMixSnapshot
import java.io.File
import java.io.RandomAccessFile
import kotlin.concurrent.thread
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Toca as quatro faixas separadas travadas juntas, com mixer e mudança de
 * tom na saída — o papel do `StemPlayer.swift`.
 *
 * O iOS usava 4 players → mixer → AVAudioUnitTimePitch. Aqui as quatro
 * faixas são MIXADAS POR NÓS num único AudioTrack em streaming: sincronia
 * perfeita por construção (é um stream só), e o `setPlaybackParams` do
 * Android entrega o mesmo par do TimePitch — velocidade 0,5–1,5x sem mudar
 * a afinação (timestretch do sistema) e tom em semitons sem mudar o tempo.
 * Nada disso passa pelo PolyphonicSampler: reprodução de música não é o
 * caminho de latência crítica, e o sampler fica intocado.
 *
 * `currentTime` deriva do playbackHeadPosition do AudioTrack (frames de
 * SAÍDA × velocidade = frames de origem): aproximação boa para tela e loop
 * de estudo; o flush no seek zera o head e o relógio recomeça da origem
 * nova. O loop A/B usa o MESMO caminho do seek do dedo, como no iOS — a
 * volta custa um respiro curto no ponto do loop, aceitável para estudo e um
 * caminho só no código.
 *
 * As faixas em `.m4a` (as que o separador grava desde a 1.16) são
 * decodificadas para WAV 16-bit em [scratch] ao abrir a música — o leitor
 * precisa de seek por quadro e o AAC não dá isso de graça; o iOS faz o mesmo
 * por baixo no `AVAudioFile`. O `.wav` antigo abre direto.
 *
 * @param scratch pasta de cache para o PCM decodificado (uma música por vez).
 */
class StemPlayerEngine(private val scratch: File? = null) {
    data class Track(
        val id: String,
        var volume: Float = 1f,
        var isMuted: Boolean = false,
        var isSoloed: Boolean = false,
    )

    private companion object {
        const val TAG = "CadentiaStems"
        const val RATE = 44_100
        const val FEED_FRAMES = 4096
        const val SCRATCH_SONGS = 3
        const val FFT_SIZE = 2048
        const val BAND_COUNT = 48
    }

    @Volatile var tracks: List<Track> = emptyList()
        private set
    @Volatile var isPlaying = false
        private set
    @Volatile var duration = 0.0
        private set

    /** Velocidade 0,5–1,5x, sem mudar a afinação. */
    @Volatile var speed = 1.0
        set(value) {
            field = value.coerceIn(0.5, 1.5)
            applyPlaybackParams()
        }

    /** Tom em semitons (o ouvido perdoa a grade; o slider contínuo não). */
    @Volatile var semitones = 0
        set(value) {
            field = value.coerceIn(-12, 12)
            applyPlaybackParams()
        }

    /** Trecho de estudo; o ticker da tela chama [tickLoop] e a volta é um seek. */
    @Volatile var practiceLoop: PracticeLoop? = null

    /** Chamado quando a música TERMINA sozinha (gancho do repertório). */
    @Volatile var onFinished: (() -> Unit)? = null

    /** O que a notificação de reprodução mostra (o título da música). */
    @Volatile var sessionLabel: String = ""
    private var lease: PlaybackSession.Lease? = null

    /** Nível 0…1 por faixa, para a tela animar. */
    @Volatile var levels: Map<String, Float> = emptyMap()
        private set

    /** Espectro do mix em 48 bandas, pronto para virar a onda. */
    @Volatile var spectrum: FloatArray = FloatArray(BAND_COUNT)
        private set

    private var readers = mapOf<String, StemWavReader>()
    /**
     * O teto da saída, ANTES do tom/velocidade como no iOS (`StemPlayer.swift`:
     * mixer → limitador → TimePitch). Aqui o tom é do AudioTrack, então a
     * ordem é a mesma: o limitador age sobre a soma que os faders controlam.
     */
    private val limiter = PeakLimiter(RATE)
    private var audioTrack: AudioTrack? = null
    private var feeder: Thread? = null
    @Volatile private var feederStop = false

    /** Posição de origem (segundos) de onde o head atual partiu. */
    @Volatile private var seekOrigin = 0.0
    @Volatile private var pausedAt = 0.0
    @Volatile private var sourceExhausted = false

    // Análise do mix (janela deslizante + FFT), como o StemAnalysis do iOS.
    private val fft = RealFFT(FFT_SIZE)
    private var bands: SpectrumBands? = null
    private val slidingWindow = FloatArray(FFT_SIZE)
    private var slidingFilled = 0
    private val hann = FloatArray(FFT_SIZE) {
        (0.5 - 0.5 * cos(2 * Math.PI * it / FFT_SIZE)).toFloat()
    }
    private val smoothed = FloatArray(BAND_COUNT)
    private val meterDecay = mutableMapOf<String, Float>()

    val currentTime: Double
        get() {
            val track = audioTrack ?: return pausedAt
            if (!isPlaying) return pausedAt
            val head = track.playbackHeadPosition.toLong().coerceAtLeast(0)
            return (seekOrigin + head.toDouble() / RATE * speed).coerceAtMost(duration)
        }

    // ---- carga ----

    /**
     * Carrega as faixas presentes em `directory` (`name.m4a` ou `name.wav` por
     * fonte). Devolve false quando não há faixa nenhuma OU quando os
     * comprimentos diferem — as quatro são a mesma música cortada em quatro;
     * com `max`, uma faixa mais curta sumiria da mistura em silêncio (o iOS
     * lança `separationFailed` nos dois casos).
     */
    fun load(directory: File, names: List<String>): Boolean {
        stop()
        closeReaders()
        val loaded = mutableMapOf<String, StemWavReader>()
        val started = System.nanoTime()
        val present = names.mapNotNull { name -> StemCache.existingTrack(directory, name)?.let { name to it } }
        // As faixas AAC decodificam em paralelo (quatro decoders de software
        // independentes); a pasta de scratch é preparada uma vez antes.
        val aac = present.filter { !it.second.extension.equals("wav", ignoreCase = true) }
        if (aac.isNotEmpty()) prepareScratch(directory)
        val decodedFiles = java.util.concurrent.ConcurrentHashMap<String, File>()
        aac.map { (name, file) ->
            Thread({ decodedCopy(directory, file)?.let { decodedFiles[name] = it } }, "stem-decode-$name").apply { start() }
        }.forEach { it.join() }
        for ((name, file) in present) {
            val pcm = if (file.extension.equals("wav", ignoreCase = true)) file else decodedFiles[name] ?: continue
            val reader = StemWavReader.open(pcm) ?: continue
            loaded[name] = reader
        }
        val decoded = decodedFiles.size
        if (loaded.isEmpty()) return false
        val lengths = loaded.values.map { it.frames }.toSet()
        if (lengths.size != 1) {
            Log.w(TAG, "faixas com comprimentos diferentes em ${directory.name}: $lengths")
            loaded.values.forEach { it.close() }
            return false
        }
        if (decoded > 0) {
            Log.i(TAG, "$decoded faixas AAC decodificadas em ${(System.nanoTime() - started) / 1_000_000} ms")
        }
        readers = loaded
        tracks = loaded.keys.map { Track(id = it) }
        duration = lengths.first().toDouble() / RATE
        practiceLoop = null
        seekOrigin = 0.0
        pausedAt = 0.0
        smoothed.fill(0f)
        meterDecay.clear()
        limiter.reset()
        return true
    }

    /**
     * O WAV decodificado de uma faixa AAC, em `scratch/<música>/<faixa>.wav`.
     * Reaproveita o que já está lá para a mesma música (reabrir não decodifica
     * de novo) e limpa as outras músicas: é cache, e o PCM de uma música de
     * 4 minutos são 170 MB.
     */
    /**
     * O PCM decodificado fica para as últimas [SCRATCH_SONGS] músicas, não
     * só para a atual: decodificar custa ~25x o tempo real por faixa no
     * MediaCodec (IPC por bloco de 1024 quadros; medido no emulador: 4 × 40 s
     * em 1,6 s), e reabrir a música de ontem esperando 10 s não é o iOS. É
     * `cacheDir`: o sistema pode limpar, e a próxima abertura decodifica de
     * novo.
     */
    private fun prepareScratch(directory: File) {
        val root = scratch ?: return
        val songDir = File(root, directory.name)
        val others = root.listFiles()?.filter { it.isDirectory && it.name != songDir.name }.orEmpty()
        others.sortedByDescending { it.lastModified() }.drop(SCRATCH_SONGS - 1).forEach { it.deleteRecursively() }
        songDir.mkdirs()
        songDir.setLastModified(System.currentTimeMillis())
    }

    private fun decodedCopy(directory: File, track: File): File? {
        val root = scratch ?: return null
        val songDir = File(root, directory.name)
        val target = File(songDir, "${track.nameWithoutExtension}.wav")
        if (target.isFile && target.length() > 44 && target.lastModified() >= track.lastModified()) return target
        val partial = File(songDir, "${track.nameWithoutExtension}.parcial")
        partial.delete()
        if (!StemTrackCodec.decode(track, partial) || !partial.renameTo(target)) {
            partial.delete()
            return null
        }
        return target
    }

    // ---- mix ----

    fun setVolume(volume: Float, id: String) {
        tracks.firstOrNull { it.id == id }?.volume = volume.coerceIn(0f, 1f)
    }

    fun toggleMute(id: String) {
        tracks.firstOrNull { it.id == id }?.let { it.isMuted = !it.isMuted }
    }

    fun toggleSolo(id: String) {
        tracks.firstOrNull { it.id == id }?.let { it.isSoloed = !it.isSoloed }
    }

    /** A regra mora no :kit (StemMix); aqui só se lê o resultado por bloco. */
    private fun currentGains(): Map<String, Float> =
        StemMix.gains(tracks.map { StemMix.State(it.id, it.volume, it.isMuted, it.isSoloed) })

    // ---- transporte ----

    fun play() {
        if (tracks.isEmpty() || isPlaying) return
        val minBuffer = AudioTrack.getMinBufferSize(
            RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT,
        )
        if (minBuffer <= 0) return
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBuffer * 2, FEED_FRAMES * 2 * 4 * 3))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull() ?: return

        audioTrack = track
        applyPlaybackParams()
        seekOrigin = pausedAt
        sourceExhausted = false
        for (reader in readers.values) reader.seekSeconds(pausedAt)
        feederStop = false
        track.play()
        isPlaying = true
        // Foco de áudio + serviço de reprodução: ligação pausa e, terminada,
        // volta a tocar de onde parou; outro app de música pausa e não volta.
        if (lease == null) {
            lease = PlaybackSession.begin(sessionLabel, onInterrupt = { pause() }, onResume = { play() })
        }
        feeder = thread(name = "stem-feeder") { feedLoop(track) }
    }

    fun pause() {
        if (!isPlaying) return
        pausedAt = currentTime
        stopTransport()
    }

    fun stop() {
        pausedAt = 0.0
        seekOrigin = 0.0
        stopTransport()
        levels = emptyMap()
        spectrum = FloatArray(BAND_COUNT)
        smoothed.fill(0f)
        meterDecay.clear()
    }

    fun seek(to: Double) {
        val target = to.coerceIn(0.0, duration)
        val wasPlaying = isPlaying
        if (wasPlaying) {
            // Para o feeder e descarta o que já estava no buffer.
            stopTransport()
        }
        pausedAt = target
        if (wasPlaying) play()
    }

    /**
     * Chamado pelo ticker da tela (~50 ms): aplica o loop A/B e detecta o
     * fim natural. Devolve true quando terminou sozinho (onFinished já foi).
     */
    fun tickLoop() {
        if (!isPlaying) return
        val now = currentTime
        practiceLoop?.let { loop ->
            if (now >= loop.end) {
                seek(loop.start)
                return
            }
        }
        if (sourceExhausted && now >= duration - 0.05) {
            stop()
            onFinished?.invoke()
        }
    }

    fun shutdown() {
        stop()
        closeReaders()
    }

    // ---- memória de ajustes ----

    fun snapshot(): StemMixSnapshot = StemMixSnapshot(
        volumes = tracks.associate { it.id to it.volume },
        muted = tracks.filter { it.isMuted }.map { it.id }.toSet(),
        soloed = tracks.filter { it.isSoloed }.map { it.id }.toSet(),
        semitones = semitones,
        speed = speed,
        loop = practiceLoop,
    )

    /** Restaura um ajuste salvo; faixas que a música não tem são ignoradas. */
    fun apply(snapshot: StemMixSnapshot) {
        for (track in tracks) {
            snapshot.volumes[track.id]?.let { track.volume = it.coerceIn(0f, 1f) }
            track.isMuted = track.id in snapshot.muted
            track.isSoloed = track.id in snapshot.soloed
        }
        semitones = snapshot.semitones
        speed = snapshot.speed
        practiceLoop = snapshot.loop?.clamped(duration)
    }

    // ---- interno ----

    private fun stopTransport() {
        isPlaying = false
        lease?.let { PlaybackSession.end(it) }
        lease = null
        feederStop = true
        feeder?.join(1500)
        feeder = null
        audioTrack?.let { track ->
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.stop() }
            track.release()
        }
        audioTrack = null
    }

    private fun applyPlaybackParams() {
        val track = audioTrack ?: return
        runCatching {
            track.playbackParams = PlaybackParams()
                .setSpeed(speed.toFloat())
                .setPitch(2.0.pow(semitones / 12.0).toFloat())
        }
    }

    private fun closeReaders() {
        for (reader in readers.values) reader.close()
        readers = emptyMap()
        tracks = emptyList()
        duration = 0.0
    }

    /** Mixa e alimenta o AudioTrack; níveis e espectro saem daqui. */
    private fun feedLoop(track: AudioTrack) {
        val left = FloatArray(FEED_FRAMES)
        val right = FloatArray(FEED_FRAMES)
        val interleaved = FloatArray(FEED_FRAMES * 2)
        val stemL = FloatArray(FEED_FRAMES)
        val stemR = FloatArray(FEED_FRAMES)

        while (!feederStop) {
            java.util.Arrays.fill(left, 0f)
            java.util.Arrays.fill(right, 0f)
            val gains = currentGains()
            var anyData = false
            val freshLevels = mutableMapOf<String, Float>()

            for ((id, reader) in readers) {
                val read = reader.read(stemL, stemR, FEED_FRAMES)
                if (read <= 0) {
                    freshLevels[id] = decayLevel(id, 0f)
                    continue
                }
                anyData = true
                val gain = gains[id] ?: 0f
                var sum = 0f
                for (i in 0 until read) {
                    left[i] += stemL[i] * gain
                    right[i] += stemR[i] * gain
                    val mono = (stemL[i] + stemR[i]) * 0.5f
                    sum += mono * mono
                }
                val rms = sqrt(sum / read) * gain
                // O medidor do iOS via LevelMeter: sobe rápido, desce suave.
                freshLevels[id] = decayLevel(id, minOf(1f, rms * 2.2f))
            }
            levels = freshLevels

            if (!anyData) {
                sourceExhausted = true
                return
            }

            // O teto: uma faixa isolada (baixo sem o resto que o cancelava)
            // passa de 0 dBFS com facilidade, e a cadeia toda está em ganho 1.
            limiter.process(left, right, FEED_FRAMES)
            absorbSpectrum(left, right)

            for (i in 0 until FEED_FRAMES) {
                interleaved[2 * i] = left[i]
                interleaved[2 * i + 1] = right[i]
            }
            var offset = 0
            while (offset < interleaved.size && !feederStop) {
                val written = track.write(
                    interleaved, offset, interleaved.size - offset,
                    AudioTrack.WRITE_BLOCKING,
                )
                if (written < 0) return
                offset += written
            }
        }
    }

    private fun decayLevel(id: String, fresh: Float): Float {
        val previous = meterDecay[id] ?: 0f
        val next = if (fresh > previous) fresh else previous * 0.82f + fresh * 0.18f
        meterDecay[id] = next
        return next
    }

    /** Janela deslizante + Hann + FFT + bandas, como o StemAnalysis do iOS. */
    private fun absorbSpectrum(left: FloatArray, right: FloatArray) {
        val count = left.size
        val fresh = minOf(count, FFT_SIZE)
        val keep = FFT_SIZE - fresh
        if (keep > 0) System.arraycopy(slidingWindow, fresh, slidingWindow, 0, keep)
        for (i in 0 until fresh) {
            val source = count - fresh + i
            slidingWindow[keep + i] = (left[source] + right[source]) * 0.5f
        }
        slidingFilled = minOf(slidingFilled + fresh, FFT_SIZE)
        if (slidingFilled < FFT_SIZE) return

        if (bands == null) {
            bands = SpectrumBands(count = BAND_COUNT, binCount = FFT_SIZE / 2, sampleRate = RATE.toDouble())
        }
        val block = FloatArray(FFT_SIZE) { slidingWindow[it] * hann[it] }
        val re = FloatArray(FFT_SIZE / 2 + 1)
        val im = FloatArray(FFT_SIZE / 2 + 1)
        fft.forward(block, re, im)
        // Escala 2/N: sem normalizar, qualquer banda audível chapava no teto.
        val scale = 2f / FFT_SIZE
        for (i in re.indices) {
            re[i] *= scale
            im[i] *= scale
        }
        val magnitudes = bands?.magnitudes(re, im) ?: return
        for (i in 0 until minOf(magnitudes.size, smoothed.size)) {
            smoothed[i] = if (magnitudes[i] > smoothed[i]) {
                magnitudes[i]
            } else {
                smoothed[i] * 0.55f + magnitudes[i] * 0.45f
            }
        }
        spectrum = smoothed.copyOf()
    }
}

/**
 * Leitor de stem WAV (PCM 16-bit, mono ou estéreo, header canônico de 44
 * bytes) com seek por frame — o irmão estéreo do TakeReader do Gravador.
 */
internal class StemWavReader private constructor(
    private val file: RandomAccessFile,
    val sampleRate: Int,
    val channels: Int,
    val frames: Long,
    private val dataOffset: Long,
) : AutoCloseable {
    private var position = 0L

    companion object {
        fun open(source: File): StemWavReader? {
            if (!source.exists()) return null
            return runCatching {
                val raf = RandomAccessFile(source, "r")
                val header = ByteArray(44)
                raf.readFully(header)
                fun ascii(offset: Int, length: Int) = String(header, offset, length, Charsets.US_ASCII)
                fun intLE(offset: Int) =
                    (header[offset].toInt() and 0xFF) or
                        ((header[offset + 1].toInt() and 0xFF) shl 8) or
                        ((header[offset + 2].toInt() and 0xFF) shl 16) or
                        ((header[offset + 3].toInt() and 0xFF) shl 24)
                fun shortLE(offset: Int) =
                    (header[offset].toInt() and 0xFF) or ((header[offset + 1].toInt() and 0xFF) shl 8)
                if (ascii(0, 4) != "RIFF" || ascii(8, 4) != "WAVE" || ascii(36, 4) != "data") {
                    raf.close()
                    return null
                }
                if (shortLE(20) != 1 || shortLE(34) != 16) {
                    raf.close()
                    return null
                }
                val channels = shortLE(22).coerceIn(1, 2)
                val rate = intLE(24)
                val dataBytes = minOf(intLE(40).toLong(), raf.length() - 44)
                StemWavReader(raf, rate, channels, dataBytes / (2L * channels), 44)
            }.getOrNull()
        }
    }

    fun seekSeconds(seconds: Double) {
        position = (seconds * sampleRate).toLong().coerceIn(0, frames)
    }

    /** Lê até `count` frames para L/R (mono duplica); devolve frames lidos. */
    fun read(left: FloatArray, right: FloatArray, count: Int): Int = runCatching {
        if (position >= frames) return 0
        val available = minOf(count.toLong(), frames - position).toInt()
        val bytes = ByteArray(available * 2 * channels)
        synchronized(this) {
            file.seek(dataOffset + position * 2L * channels)
            file.readFully(bytes)
        }
        position += available
        for (i in 0 until available) {
            val base = i * 2 * channels
            val l = (((bytes[base].toInt() and 0xFF) or (bytes[base + 1].toInt() shl 8)).toShort()) / 32768f
            left[i] = l
            right[i] = if (channels == 2) {
                (((bytes[base + 2].toInt() and 0xFF) or (bytes[base + 3].toInt() shl 8)).toShort()) / 32768f
            } else {
                l
            }
        }
        available
    }.getOrDefault(0)

    override fun close() {
        runCatching { file.close() }
    }
}

/** Escreve WAV PCM 16-bit estéreo (para o normalizador e os stems futuros). */
internal object StereoWav {
    fun write(left: FloatArray, right: FloatArray, sampleRate: Int, out: File) {
        val frames = minOf(left.size, right.size)
        val dataBytes = frames * 4
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
        putAscii(0, "RIFF"); putIntLE(4, 36 + dataBytes); putAscii(8, "WAVE")
        putAscii(12, "fmt "); putIntLE(16, 16); putShortLE(20, 1); putShortLE(22, 2)
        putIntLE(24, sampleRate); putIntLE(28, sampleRate * 4); putShortLE(32, 4)
        putShortLE(34, 16); putAscii(36, "data"); putIntLE(40, dataBytes)

        out.outputStream().buffered().use { stream ->
            stream.write(header)
            val chunk = ByteArray(8192 * 4)
            var frame = 0
            while (frame < frames) {
                val batch = minOf(8192, frames - frame)
                for (i in 0 until batch) {
                    val l = (left[frame + i].coerceIn(-1f, 1f) * 32767f).toInt()
                    val r = (right[frame + i].coerceIn(-1f, 1f) * 32767f).toInt()
                    chunk[4 * i] = (l and 0xFF).toByte()
                    chunk[4 * i + 1] = ((l shr 8) and 0xFF).toByte()
                    chunk[4 * i + 2] = (r and 0xFF).toByte()
                    chunk[4 * i + 3] = ((r shr 8) and 0xFF).toByte()
                }
                stream.write(chunk, 0, batch * 4)
                frame += batch
            }
        }
    }
}
