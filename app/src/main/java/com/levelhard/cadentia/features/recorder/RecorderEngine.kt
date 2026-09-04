package com.levelhard.cadentia.features.recorder

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.levelhard.cadentia.audio.PlaybackSession
import com.levelhard.cadentia.audio.PolyphonicSampler
import com.levelhard.cadentia.kit.MetronomeClick
import com.levelhard.cadentia.kit.RecorderMix
import com.levelhard.cadentia.kit.RecorderProject
import com.levelhard.cadentia.kit.WavIO
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * O motor do Gravador — o papel do `MultitrackRecorderEngine.swift` sobre o
 * nosso stream Oboe em vez do AVAudioEngine.
 *
 * Reprodução: cada clipe audível é lido do WAV em CHUNKS de meio segundo e
 * agendado no relógio de frames do PolyphonicSampler por um loop de
 * lookahead — o mesmo desenho do metrônomo e das tablaturas. Um take de
 * minutos nunca vira um buffer inteiro no cache (estouraria os 44 MB); só o
 * horizonte de ~1,5 s vive na memória por vez e o LRU recolhe o resto.
 * Envelope (fades × ganho do clipe) e pan entram no render do chunk; o
 * volume da trilha vai no parâmetro `gain` do schedule, lido na hora.
 *
 * "Modo estúdio" = VOICE_COMMUNICATION, a pilha de chamada do Android: AEC
 * (o que torna possível fazer overdub pela caixa), supressão de ruído e AGC
 * — o papel do Voice Processing do iOS. Desligado, a fonte é UNPROCESSED.
 */
class RecorderEngine(context: Context) {
    private companion object {
        const val SAMPLE_RATE = 48000
        const val CHUNK_SECONDS = 0.5
        const val TICK_MS = 100L
        const val HORIZON_SECONDS = 1.5
    }

    private val sampler = PolyphonicSampler()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val folder: File = File(context.filesDir, "Recorder").apply { mkdirs() }

    @Volatile var studioMode = true
    @Volatile var isRunning = false
        private set
    @Volatile var isRecording = false
        private set

    private var playbackOrigin = 0.0
    private var musicStartSeconds = 0.0
    private var schedulerJob: Job? = null

    private var record: AudioRecord? = null
    private var recordThread: Thread? = null
    @Volatile private var recordStop = false

    fun projectFile(): File = File(folder, "project.json")

    fun takeFile(name: String): File = File(folder, name)

    /** Onde o playhead está agora, em segundos da linha do tempo. */
    val currentTime: Double
        get() = if (isRunning) {
            playbackOrigin + maxOf(0.0, sampler.nowSeconds() - musicStartSeconds)
        } else {
            playbackOrigin
        }

    data class RecordRequest(val trackId: String, val punchIn: Double)

    /**
     * Toca a partir de `from` e, com `record`, arma um take novo na trilha
     * pedida. Devolve o nome do arquivo do take, ou null quando só toca —
     * e null com `record` se o stream ou o microfone não abrirem.
     */
    /** O que a notificação de reprodução mostra; a tela define com a string traduzida. */
    var sessionLabel: String = "Recorder"

    /** A sessão de áudio parou isto por fora (ligação, outro app, "Parar" na notificação). */
    var onSessionStopped: (() -> Unit)? = null
    private var lease: PlaybackSession.Lease? = null

    fun start(project: RecorderProject, from: Double, record: RecordRequest?): String? {
        stopAll()
        if (!sampler.startIfNeeded()) return null

        val beatSeconds = 60.0 / maxOf(project.bpm, 1)
        val countInSeconds = if (record != null) project.countInBars * 4 * beatSeconds else 0.0

        val originSeconds = sampler.nowSeconds() + 0.12
        val startWritingAt = originSeconds + countInSeconds
        playbackOrigin = from
        musicStartSeconds = startWritingAt

        var takeName: String? = null
        if (record != null) {
            takeName = "take-${UUID.randomUUID()}.wav"
            if (!startCapture(takeFile(takeName), startWritingAt)) {
                return null
            }
        }

        // Clipes audíveis com leitor aberto; cada um anda por chunks.
        val audible = project.audibleTracks()
        val players = mutableListOf<ClipPlayer>()
        for (track in audible) {
            for (clip in track.clips) {
                if (clip.end <= from + 0.001) continue
                val reader = TakeReader.open(takeFile(clip.fileName)) ?: continue
                players.add(ClipPlayer(clip = clip.copy(), track = track, reader = reader))
            }
        }

        val wantsClicks = record != null && (project.metronomeEnabled || project.countInBars > 0)
        val countInBeats = if (countInSeconds > 0) (countInSeconds / beatSeconds).toInt() else 0
        val runBeats = if (record != null && project.metronomeEnabled) {
            (maxOf(0.0, project.duration - from + 8) / beatSeconds).toInt()
        } else {
            0
        }
        val totalClickBeats = if (wantsClicks) countInBeats + runBeats else 0

        isRunning = true
        // Gravando ou tocando, uma ligação para tudo; não volta sozinho (uma
        // gravação retomada no meio de uma chamada seria pior que parada).
        lease = PlaybackSession.begin(sessionLabel, onInterrupt = {
            stopAll()
            onSessionStopped?.invoke()
        })
        isRecording = record != null

        schedulerJob = scope.launch {
            var nextClickBeat = 0
            while (isRunning) {
                val now = sampler.nowSeconds()
                val horizon = now + HORIZON_SECONDS

                // Cliques do count-in e do metrônomo, no mesmo relógio.
                while (nextClickBeat < totalClickBeats) {
                    val at = originSeconds + nextClickBeat * beatSeconds
                    if (at > horizon) break
                    val accent = nextClickBeat % 4 == 0
                    sampler.schedule(key = "rec-click/$accent", atSeconds = at) {
                        PolyphonicSampler.interleave(
                            MetronomeClick.render(
                                sound = MetronomeClick.Sound.Click, accent = accent,
                                volume = 0.55, sampleRate = sampler.sampleRate,
                            ),
                        )
                    }
                    nextClickBeat++
                }

                for (player in players) {
                    player.scheduleUpTo(horizon, from, startWritingAt)
                }
                delay(TICK_MS)
            }
        }
        return takeName
    }

    // Fader ao vivo: os players guardam a REFERÊNCIA da trilha, então
    // volume (gain do schedule) e pan valem no próximo chunk (~0,5 s) sem
    // chamada extra — o que o updateMix do iOS fazia nos mixers.

    /** Um clipe tocando: anda o próprio relógio de chunks. */
    private inner class ClipPlayer(
        val clip: RecorderProject.Clip,
        val track: RecorderProject.Track,
        val reader: TakeReader,
    ) {
        private var nextChunk = 0

        fun scheduleUpTo(horizonSeconds: Double, from: Double, musicStart: Double) {
            while (true) {
                val clipTime = nextChunk * CHUNK_SECONDS
                if (clipTime >= clip.duration) return
                // Onde este chunk cai na linha do tempo e no relógio.
                val timelineAt = clip.start + clipTime
                if (timelineAt + CHUNK_SECONDS <= from) {
                    nextChunk++
                    continue
                }
                val at = musicStart + (timelineAt - from)
                if (at > horizonSeconds) return

                val chunkIdx = nextChunk
                nextChunk++
                val length = minOf(CHUNK_SECONDS, clip.duration - clipTime)
                val pcm = renderChunk(chunkIdx, clipTime, length) ?: continue
                // O playhead pode já ter comido o começo do chunk (partida no
                // meio): agendar no passado toca já, então corta o excesso.
                sampler.schedule(
                    key = "rec/${clip.id}/$chunkIdx",
                    atSeconds = at,
                    gain = track.volume.toFloat(),
                ) { pcm }
            }
        }

        /** Chunk estéreo com fades, ganho do clipe e pan da trilha. */
        private fun renderChunk(chunkIdx: Int, clipTime: Double, length: Double): FloatArray? {
            val frames = (length * reader.sampleRate).toInt()
            if (frames <= 0) return null
            val startFrame = ((clip.trimStart + clipTime) * reader.sampleRate).toLong()
            val mono = reader.read(startFrame, frames) ?: return null

            val angle = (track.pan + 1) * Math.PI / 4
            val panLeft = cos(angle).toFloat()
            val panRight = sin(angle).toFloat()

            val out = FloatArray(mono.size * 2)
            for (i in mono.indices) {
                val envelope = clip.envelope(clipTime + i / reader.sampleRate.toDouble()).toFloat()
                val sample = mono[i] * envelope
                out[2 * i] = sample * panLeft
                out[2 * i + 1] = sample * panRight
            }
            return out
        }
    }

    // ---- captura ----

    @SuppressLint("MissingPermission") // a tela só chama com RECORD_AUDIO dado
    private fun startCapture(file: File, startWritingAt: Double): Boolean {
        val source = if (studioMode) {
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        } else {
            MediaRecorder.AudioSource.UNPROCESSED
        }
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT,
        )
        if (minBuffer <= 0) return false
        val record = try {
            AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBuffer, SAMPLE_RATE / 4 * 4))
                .build()
        } catch (_: Exception) {
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        this.record = record
        recordStop = false

        val writer = WavTakeWriter(file, SAMPLE_RATE)
        record.startRecording()
        recordThread = thread(name = "recorder-capture") {
            val buffer = FloatArray(2048)
            while (!recordStop) {
                val read = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (read <= 0) continue
                // Só grava depois do count-in, para o take não nascer com
                // quatro cliques dentro — o mesmo gate do tap do iOS.
                if (sampler.nowSeconds() < startWritingAt) continue
                writer.write(buffer, read)
            }
            writer.finish()
        }
        return true
    }

    /** Fecha o take atual; a reprodução segue até stopAll. */
    fun finishTake() {
        if (!isRecording) return
        recordStop = true
        recordThread?.join(2000)
        recordThread = null
        record?.let { r ->
            runCatching { r.stop() }
            r.release()
        }
        record = null
        isRecording = false
    }

    fun stopAll() {
        // Parqueia o playhead ANTES de derrubar isRunning, senão currentTime
        // já devolve a origem velha.
        val parkedAt = currentTime
        if (isRecording) finishTake()
        isRunning = false
        schedulerJob?.cancel()
        schedulerJob = null
        playbackOrigin = parkedAt
        // Chunks já agendados no futuro seriam ~1,5 s de fantasma: cala tudo.
        sampler.dampAll(0.05f)
        lease?.let { PlaybackSession.end(it) }
        lease = null
    }

    fun shutdown() {
        stopAll()
        scope.cancel()
        sampler.stop()
    }

    fun seek(to: Double) {
        playbackOrigin = maxOf(0.0, to)
    }

    // ---- arquivos ----

    fun duration(fileName: String): Double = TakeReader.open(takeFile(fileName))?.use {
        it.frames / it.sampleRate.toDouble()
    } ?: 0.0

    /** Apaga takes (e sidecars de forma de onda) que nenhum clipe usa mais. */
    fun collectGarbage(keeping: Set<String>) {
        val files = folder.listFiles() ?: return
        for (file in files) {
            val name = file.name
            if (name.startsWith("take-") && !keeping.contains(name.removeSuffix(".peaks"))) {
                if (name.endsWith(".peaks") || name.endsWith(".wav")) file.delete()
            }
        }
    }

    /**
     * Mixdown para M4A: a soma pura do :kit + AAC do Android. Bloqueante —
     * chame fora do main. null quando não há nada audível para exportar.
     */
    fun mixdown(context: Context, project: RecorderProject, enhance: Boolean): File? {
        val pcm = RecorderMix.render(project, SAMPLE_RATE.toDouble(), enhance) { fileName ->
            TakeReader.open(takeFile(fileName))?.use { it.readAll() }
        } ?: return null
        val out = File(context.cacheDir, "share").apply { mkdirs() }
            .resolve("Cadentia-mix-${System.currentTimeMillis() / 1000}.m4a")
        return runCatching { AacEncoder.encode(pcm, SAMPLE_RATE, out) }.getOrNull()
    }
}

/**
 * Leitor de janela sobre um take WAV nosso (PCM 16-bit mono, header de 44
 * bytes do WavIO): um chunk é um seek + uma leitura, sem carregar o arquivo.
 */
internal class TakeReader private constructor(
    private val file: RandomAccessFile,
    val sampleRate: Int,
    val frames: Long,
    private val dataOffset: Long,
) : AutoCloseable {
    companion object {
        fun open(file: File): TakeReader? {
            if (!file.exists()) return null
            return runCatching {
                val raf = RandomAccessFile(file, "r")
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
                if (shortLE(20) != 1 || shortLE(22) != 1 || shortLE(34) != 16) {
                    raf.close()
                    return null
                }
                val rate = intLE(24)
                val dataBytes = minOf(intLE(40).toLong(), raf.length() - 44)
                TakeReader(raf, rate, dataBytes / 2, 44)
            }.getOrNull()
        }
    }

    /** Lê `count` frames a partir de `startFrame`, zero além do fim. */
    fun read(startFrame: Long, count: Int): FloatArray? = runCatching {
        if (startFrame >= frames) return null
        val available = minOf(count.toLong(), frames - startFrame).toInt()
        if (available <= 0) return null
        val bytes = ByteArray(available * 2)
        synchronized(this) {
            file.seek(dataOffset + startFrame * 2)
            file.readFully(bytes)
        }
        val out = FloatArray(available)
        for (i in 0 until available) {
            val lo = bytes[2 * i].toInt() and 0xFF
            val hi = bytes[2 * i + 1].toInt()
            out[i] = ((lo or (hi shl 8)).toShort()) / 32768f
        }
        out
    }.getOrNull()

    fun readAll(): FloatArray? = read(0, frames.toInt())

    override fun close() {
        runCatching { file.close() }
    }
}

/**
 * Escritor de take: header WAV com tamanhos provisórios, amostras conforme
 * chegam, e o `finish` volta e corrige os tamanhos — o arquivo nunca fica
 * inteiro na RAM.
 */
internal class WavTakeWriter(file: File, private val sampleRate: Int) {
    private val raf = RandomAccessFile(file, "rw")
    private var dataBytes = 0L

    init {
        raf.write(WavIO.toByteArray(FloatArray(0), sampleRate)) // header base
    }

    fun write(samples: FloatArray, count: Int) {
        val bytes = ByteArray(count * 2)
        for (i in 0 until count) {
            val value = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt()
            bytes[2 * i] = (value and 0xFF).toByte()
            bytes[2 * i + 1] = ((value shr 8) and 0xFF).toByte()
        }
        runCatching {
            raf.write(bytes)
            dataBytes += bytes.size
        }
    }

    fun finish() {
        runCatching {
            fun putIntLE(offset: Long, value: Int) {
                raf.seek(offset)
                raf.write(
                    byteArrayOf(
                        (value and 0xFF).toByte(),
                        ((value shr 8) and 0xFF).toByte(),
                        ((value shr 16) and 0xFF).toByte(),
                        ((value shr 24) and 0xFF).toByte(),
                    ),
                )
            }
            putIntLE(4, (36 + dataBytes).toInt())
            putIntLE(40, dataBytes.toInt())
            raf.close()
        }
    }
}
