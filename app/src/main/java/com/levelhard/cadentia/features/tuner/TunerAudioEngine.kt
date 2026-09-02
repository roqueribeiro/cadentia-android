package com.levelhard.cadentia.features.tuner

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.levelhard.cadentia.kit.YINPitchDetector
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Uma janela analisada do microfone: pitch do YIN + nível RMS (o nível
 * alimenta a dica de sinal fraco e o detector de BPM do metrônomo).
 */
data class TunerFrame(
    val pitch: YINPitchDetector.Pitch?,
    /** RMS linear 0…1 da janela. */
    val rms: Float,
)

/**
 * Pipeline microfone → YIN: `AudioRecord` alimentando o `YINPitchDetector`
 * puro — o papel do `TunerAudioEngine.swift`. A análise roda fora da thread
 * de leitura de áudio e volta como `Flow` que o ViewModel consome no main.
 *
 * Fonte MIC com o processamento padrão do aparelho (AGC ligado): a lição do
 * iOS atravessa — o modo "measurement"/UNPROCESSED derruba o ganho cru para
 * baixo do gate de RMS do YIN.
 */
class TunerAudioEngine {
    private var record: AudioRecord? = null
    private var readJob: Job? = null

    /** ~43 ms a 48 kHz — piso do YIN ≈ 50 Hz. */
    private companion object {
        const val WINDOW_SIZE = 2048
        const val SAMPLE_RATE = 48000
    }

    val isRunning: Boolean get() = record != null

    /**
     * Começa a escutar e devolve o stream de janelas (um elemento por janela
     * analisada; `pitch` null em silêncio/ruído/fora da banda). Termina no
     * `stop()`. Lança quando o microfone não abre (sem permissão, sem input).
     */
    @SuppressLint("MissingPermission") // quem chama garante a permissão (VM)
    fun start(scope: CoroutineScope): Flow<TunerFrame> = callbackFlow {
        stop()

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        if (minBuffer <= 0) error("sem input de áudio")

        val record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuffer, WINDOW_SIZE * 4 * 2))
            .build()

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            error("microfone não inicializou")
        }
        this@TunerAudioEngine.record = record
        record.startRecording()

        readJob = scope.launch(Dispatchers.Default) {
            val window = FloatArray(WINDOW_SIZE)
            while (isActive && this@TunerAudioEngine.record === record) {
                val read = record.read(window, 0, WINDOW_SIZE, AudioRecord.READ_BLOCKING)
                if (read < 1024) continue
                val samples = if (read == WINDOW_SIZE) window.copyOf() else window.copyOf(read)
                val pitch = YINPitchDetector.detect(samples, SAMPLE_RATE.toDouble())
                var sum = 0.0
                for (s in samples) sum += (s * s).toDouble()
                val rms = sqrt(sum / samples.size).toFloat()
                trySend(TunerFrame(pitch = pitch, rms = rms))
            }
        }

        awaitClose { stop() }
    }

    fun stop() {
        readJob?.cancel()
        readJob = null
        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        record = null
    }
}
