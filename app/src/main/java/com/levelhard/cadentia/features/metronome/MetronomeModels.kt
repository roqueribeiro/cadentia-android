package com.levelhard.cadentia.features.metronome

import com.levelhard.cadentia.features.tuner.TunerAudioEngine
import com.levelhard.cadentia.kit.BPMDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Microfone → envelope de RMS → estimativa de BPM por onset, reusando o
 * stream de janelas do engine do afinador — port do `BpmDetectorModel`.
 * A permissão é responsabilidade da tela (o mesmo prompt do afinador).
 */
class BpmDetectorModel {
    data class State(val active: Boolean = false, val detected: Int? = null)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val engine = TunerAudioEngine()
    private val detector = BPMDetector()
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (_state.value.active) return
        detector.reset()
        _state.value = State(active = true, detected = null)
        job = scope.launch {
            try {
                engine.start(scope).collect { frame ->
                    val nowMs = System.nanoTime() / 1e6
                    detector.processSample(rms = frame.rms.toDouble(), nowMs = nowMs)?.let {
                        _state.value = _state.value.copy(detected = it)
                    }
                }
            } catch (_: Exception) {
                _state.value = State()
            }
        }
    }

    fun stop() {
        if (!_state.value.active) return
        job?.cancel()
        job = null
        engine.stop()
        _state.value = State()
    }
}

/**
 * Contagem regressiva para estudo focado — para junto com o metrônomo e
 * dispara um alarme audível no zero — port do `PracticeTimerModel`.
 */
class PracticeTimerModel {
    data class State(val running: Boolean = false, val remainingMs: Double = 0.0) {
        val label: String
            get() {
                val total = kotlin.math.ceil(remainingMs / 1000).toInt()
                return "%02d:%02d".format(total / 60, total % 60)
            }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var job: Job? = null

    fun start(scope: CoroutineScope, minutes: Int, onFinish: () -> Unit) {
        stop()
        val totalMs = minutes * 60_000.0
        _state.value = State(running = true, remainingMs = totalMs)
        val startedAt = System.nanoTime()
        job = scope.launch {
            while (_state.value.running) {
                delay(200)
                val elapsed = (System.nanoTime() - startedAt) / 1e6
                val remaining = maxOf(0.0, totalMs - elapsed)
                _state.value = _state.value.copy(remainingMs = remaining)
                if (remaining <= 0) {
                    stop()
                    onFinish()
                    return@launch
                }
            }
        }
    }

    fun stop() {
        _state.value = State()
        job?.cancel()
        job = null
    }
}
