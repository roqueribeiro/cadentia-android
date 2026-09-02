package com.levelhard.cadentia.audio

/**
 * A ponte JNI para o motor de saída (cpp/AudioEngine.*): um stream Oboe de
 * baixa latência com mixer próprio de 24 vozes. O relógio compartilhado do
 * app é o contador de frames do stream — agendar é "toque no frame N",
 * sample-accurate por construção (o papel do hostTime no iOS).
 *
 * Os engines de feature NÃO sintetizam aqui: pedem PCM ao :kit, registram o
 * buffer uma vez (cache por chave do lado Kotlin) e só agendam.
 */
class AudioEngineBridge {
    private var handle: Long = 0

    val isRunning: Boolean get() = handle != 0L

    /** Abre e inicia o stream. Devolve false quando o dispositivo recusa. */
    fun start(): Boolean {
        if (handle == 0L) handle = nativeCreate()
        return nativeStart(handle)
    }

    fun stop() {
        if (handle == 0L) return
        nativeStop(handle)
        nativeDestroy(handle)
        handle = 0
    }

    /** Frames já entregues ao stream — o "agora" do relógio compartilhado. */
    fun nowFrames(): Long = if (handle != 0L) nativeNowFrames(handle) else 0

    fun sampleRate(): Int = if (handle != 0L) nativeSampleRate(handle) else 48000

    fun framesPerBurst(): Int = if (handle != 0L) nativeFramesPerBurst(handle) else 0

    /** Registra (ou substitui) um buffer PCM estéreo intercalado por id. */
    fun registerBuffer(id: Int, interleaved: FloatArray) {
        check(interleaved.size % 2 == 0) { "buffer estéreo intercalado tem tamanho par" }
        if (handle != 0L) nativeRegisterBuffer(handle, id, interleaved)
    }

    fun releaseBuffer(id: Int) {
        if (handle != 0L) nativeReleaseBuffer(handle, id)
    }

    /** Agenda o buffer para o frame dado (<= agora toca já). Devolve a voz. */
    fun schedule(bufferId: Int, atFrame: Long, gain: Float = 1f): Long =
        if (handle != 0L) nativeSchedule(handle, bufferId, atFrame, gain) else 0

    fun damp(voiceTag: Long, overSeconds: Float) {
        if (handle != 0L) nativeDamp(handle, voiceTag, overSeconds)
    }

    fun dampAll(overSeconds: Float) {
        if (handle != 0L) nativeDampAll(handle, overSeconds)
    }

    fun setReverb(enabled: Boolean, mix: Float) {
        if (handle != 0L) nativeSetReverb(handle, enabled, mix)
    }

    fun setDelay(enabled: Boolean, timeMs: Float, feedback: Float, mix: Float) {
        if (handle != 0L) nativeSetDelay(handle, enabled, timeMs, feedback, mix)
    }

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeStart(handle: Long): Boolean
    private external fun nativeStop(handle: Long)
    private external fun nativeNowFrames(handle: Long): Long
    private external fun nativeSampleRate(handle: Long): Int
    private external fun nativeFramesPerBurst(handle: Long): Int
    private external fun nativeRegisterBuffer(handle: Long, id: Int, interleaved: FloatArray)
    private external fun nativeReleaseBuffer(handle: Long, id: Int)
    private external fun nativeSchedule(handle: Long, bufferId: Int, atFrame: Long, gain: Float): Long
    private external fun nativeDamp(handle: Long, voiceTag: Long, overSeconds: Float)
    private external fun nativeDampAll(handle: Long, overSeconds: Float)
    private external fun nativeSetReverb(handle: Long, enabled: Boolean, mix: Float)
    private external fun nativeSetDelay(handle: Long, enabled: Boolean, timeMs: Float, feedback: Float, mix: Float)

    companion object {
        init {
            System.loadLibrary("cadentia_audio")
        }
    }
}
