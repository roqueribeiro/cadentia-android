package com.levelhard.cadentia.features.tuner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Estado do afinador — port do `TunerViewModel` do iOS: ciclo de vida do
 * microfone + o pipeline de suavização provado no roqueos: EMA (alpha 0,3)
 * mata o tremor com sinal vivo e o último valor SEGURA por 2,5 s depois que
 * ele cai, para dar tempo de ler o resultado.
 */
class TunerViewModel : ViewModel() {
    enum class MicStatus { Starting, Denied, Active, Error }

    data class State(
        val status: MicStatus = MicStatus.Starting,
        val heldFrequency: Double? = null,
        val isLive: Boolean = false,
        val clarity: Double = 0.0,
        val rms: Float = 0f,
    ) {
        val isWeakSignal: Boolean get() = isLive && clarity < 0.5
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val engine = TunerAudioEngine()
    private var listenJob: Job? = null
    private var holdJob: Job? = null
    private var ema: Double? = null

    private companion object {
        const val SMOOTH_ALPHA = 0.3
        const val HOLD_MS = 2500L
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

    fun permissionDenied() {
        _state.value = _state.value.copy(status = MicStatus.Denied)
    }

    fun deactivate() {
        listenJob?.cancel()
        listenJob = null
        holdJob?.cancel()
        holdJob = null
        engine.stop()
        ema = null
        _state.value = State()
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
    }

    override fun onCleared() {
        engine.stop()
    }
}
