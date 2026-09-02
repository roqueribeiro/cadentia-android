package com.levelhard.cadentia.features.tuner

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.levelhard.cadentia.kit.InstrumentPreset
import com.levelhard.cadentia.kit.MusicNotes
import com.levelhard.cadentia.kit.TunerSession
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Estado do afinador — port do `TunerViewModel` do iOS: ciclo de vida do
 * microfone + o pipeline de suavização provado no roqueos: EMA (alpha 0,3)
 * mata o tremor com sinal vivo e o último valor SEGURA por 2,5 s depois que
 * ele cai, para dar tempo de ler o resultado. Análise de sessão: tee do
 * áudio + linha do tempo de pitch a 15 Hz com teto de 60 s.
 */
class TunerViewModel : ViewModel() {
    enum class MicStatus { Starting, Denied, Active, Error }

    data class State(
        val status: MicStatus = MicStatus.Starting,
        val heldFrequency: Double? = null,
        val isLive: Boolean = false,
        val clarity: Double = 0.0,
        val rms: Float = 0f,
        val isRecording: Boolean = false,
        val recordingElapsedMs: Double = 0.0,
        val session: TunerSession? = null,
        val showSessionModal: Boolean = false,
    ) {
        val isWeakSignal: Boolean get() = isLive && clarity < 0.5

        val recordingElapsedLabel: String
            get() {
                val seconds = (recordingElapsedMs / 1000).toInt()
                return "%02d:%02d".format(seconds / 60, seconds % 60)
            }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** Lê as configurações vivas para a linha do tempo bater com a tela. */
    var settingsProvider: (() -> Pair<Double, InstrumentPreset>)? = null

    private val engine = TunerAudioEngine()
    private var listenJob: Job? = null
    private var holdJob: Job? = null
    private var ema: Double? = null

    private var recordingFile: File? = null
    private var recordingStart: Long = 0L
    private var timeline = mutableListOf<TunerSession.Point>()
    private var lastTimelineMs = Double.NEGATIVE_INFINITY
    private var recordingTicker: Job? = null

    private companion object {
        const val SMOOTH_ALPHA = 0.3
        const val HOLD_MS = 2500L
        const val MAX_RECORDING_MS = 60_000.0
        const val TIMELINE_INTERVAL_MS = 1000.0 / 15
    }

    /**
     * App nativo não pergunta duas vezes: quem chama já resolveu a permissão
     * (o prompt do sistema é a única tela). Aqui é só ligar e escutar.
     */
    fun activate() {
        if (_state.value.status == MicStatus.Active) return
        _state.value = _state.value.copy(status = MicStatus.Starting)
        listenJob?.cancel()
        listenJob = viewModelScope.launch {
            try {
                engine.start(viewModelScope).collect { frame -> ingest(frame) }
            } catch (e: Exception) {
                _state.value = _state.value.copy(status = MicStatus.Error)
            }
        }
        _state.value = _state.value.copy(status = MicStatus.Active)
    }

    /** QA: a tela inteira sem encostar no microfone (1.16). */
    fun activateSilentlyForQa() {
        _state.value = _state.value.copy(status = MicStatus.Active)
    }

    fun permissionDenied() {
        _state.value = _state.value.copy(status = MicStatus.Denied)
    }

    fun deactivate() {
        if (_state.value.isRecording) stopRecording()
        listenJob?.cancel()
        listenJob = null
        holdJob?.cancel()
        holdJob = null
        engine.stop()
        ema = null
        // O iOS zera só o estado do microfone; a sessão (e o modal, se a
        // parada aconteceu na saída) sobrevive para ser lida na volta.
        _state.value = _state.value.copy(
            status = MicStatus.Starting,
            heldFrequency = null,
            isLive = false,
            clarity = 0.0,
            rms = 0f,
        )
    }

    // ── análise de sessão ──────────────────────────────────────────────────

    fun startRecording(cacheDir: File) {
        val current = _state.value
        if (current.status != MicStatus.Active || current.isRecording) return
        recordingFile?.delete() // a sessão anterior já foi vista; não acumula lixo
        val dest = File(cacheDir, "tuner-session-${UUID.randomUUID()}.wav")
        if (!engine.startRecording(dest)) return
        recordingFile = dest
        recordingStart = SystemClock.elapsedRealtime()
        timeline = mutableListOf()
        lastTimelineMs = Double.NEGATIVE_INFINITY
        _state.value = current.copy(isRecording = true, recordingElapsedMs = 0.0)
        recordingTicker = viewModelScope.launch {
            while (_state.value.isRecording) {
                delay(250)
                if (!_state.value.isRecording) return@launch
                val elapsed = (SystemClock.elapsedRealtime() - recordingStart).toDouble()
                _state.value = _state.value.copy(recordingElapsedMs = elapsed)
                if (elapsed >= MAX_RECORDING_MS) stopRecording()
            }
        }
    }

    fun stopRecording() {
        val current = _state.value
        if (!current.isRecording) return
        recordingTicker?.cancel()
        recordingTicker = null
        val written = engine.stopRecording()
        val duration = (SystemClock.elapsedRealtime() - recordingStart).toDouble()
        _state.value = _state.value.copy(
            isRecording = false,
            session = TunerSession(
                audioPath = written?.absolutePath,
                timeline = timeline.toList(),
                durationMs = duration,
            ),
            showSessionModal = true,
        )
    }

    fun dismissSessionModal() {
        _state.value = _state.value.copy(showSessionModal = false)
    }

    private fun ingest(frame: TunerFrame) {
        val current = _state.value

        val pitch = frame.pitch
        if (pitch == null) {
            ema = null
            holdJob?.cancel()
            holdJob = viewModelScope.launch {
                delay(HOLD_MS)
                _state.value = _state.value.copy(heldFrequency = null)
            }
            _state.value = current.copy(isLive = false, rms = frame.rms)
            return
        }

        holdJob?.cancel()
        holdJob = null
        val smoothed = ema?.let { SMOOTH_ALPHA * pitch.frequency + (1 - SMOOTH_ALPHA) * it }
            ?: pitch.frequency
        ema = smoothed
        _state.value = current.copy(
            isLive = true,
            clarity = pitch.clarity,
            rms = frame.rms,
            heldFrequency = smoothed,
        )
        sampleTimelineIfRecording()
    }

    /**
     * Amostragem presa ao sinal vivo — o valor segurado depois que a nota
     * morre poluiria as métricas da sessão.
     */
    private fun sampleTimelineIfRecording() {
        val current = _state.value
        if (!current.isRecording || !current.isLive) return
        val hz = current.heldFrequency ?: return
        val t = (SystemClock.elapsedRealtime() - recordingStart).toDouble()
        if (t - lastTimelineMs < TIMELINE_INTERVAL_MS) return
        lastTimelineMs = t

        val (referenceA, instrument) = settingsProvider?.invoke()
            ?: (440.0 to InstrumentPreset.find("chromatic"))
        val note = MusicNotes.noteFromFrequency(hz, referenceA) ?: return
        val target = instrument.nearestString(hz, referenceA)
        val cents = if (target != null) {
            MusicNotes.centsOff(detected = hz, target = target.frequency)
        } else {
            note.cents
        }
        timeline.add(
            TunerSession.Point(
                t = t,
                frequency = hz,
                cents = cents,
                note = "${note.name}${note.octave}",
            ),
        )
    }

    override fun onCleared() {
        engine.stop()
        recordingFile?.delete()
    }
}
