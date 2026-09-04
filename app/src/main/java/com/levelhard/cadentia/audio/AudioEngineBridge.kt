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
    /** Lido de threads de fundo (aquecimento); escrito só por start/stop. */
    @Volatile
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

    /** Underruns do stream desde o start (Oboe). -1 = sem stream. */
    fun xrunCount(): Int = if (handle != 0L) nativeXRunCount(handle) else -1

    /** Quantas vezes o stream caiu (troca de rota) e foi reaberto sozinho. */
    fun restartCount(): Int = if (handle != 0L) nativeRestartCount(handle) else 0

    /** Buffer real do stream em frames: a latência estrutural é isto sobre a taxa. */
    fun bufferSizeInFrames(): Int = if (handle != 0L) nativeBufferSizeInFrames(handle) else 0

    /** Registra (ou substitui) um buffer PCM estéreo intercalado por id. */
    /** `channels` 2 = estéreo intercalado; 1 = mono (o pan da voz faz a imagem). */
    fun registerBuffer(id: Int, interleaved: FloatArray, channels: Int = 2) {
        check(channels == 1 || interleaved.size % 2 == 0) { "buffer estéreo intercalado tem tamanho par" }
        if (handle != 0L) nativeRegisterBuffer(handle, id, interleaved, channels)
    }

    fun releaseBuffer(id: Int) {
        if (handle != 0L) nativeReleaseBuffer(handle, id)
    }

    /**
     * Agenda o buffer para o frame dado (<= agora toca já). Devolve a voz.
     * `pan` -1…1 em potência constante; `rate` é a taxa de leitura (1 = normal).
     */
    fun schedule(bufferId: Int, atFrame: Long, gain: Float = 1f, pan: Float = 0f, rate: Float = 1f): Long =
        if (handle != 0L) nativeSchedule(handle, bufferId, atFrame, gain, pan, rate) else 0

    /** Muda a taxa de uma voz que já toca: bend e glissando. */
    fun setVoiceRate(voiceTag: Long, rate: Float) {
        if (handle != 0L) nativeSetVoiceRate(handle, voiceTag, rate)
    }

    /** O barramento elétrico do Cordas (drive + gabinete), antes do reverb. */
    fun setDrive(enabled: Boolean, amount: Float) {
        if (handle != 0L) nativeSetDrive(handle, enabled, amount)
    }

    /** Volume mestre do bus (0…1), ao vivo. */
    fun setMasterGain(gain: Float) {
        if (handle != 0L) nativeSetMasterGain(handle, gain)
    }

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
    private external fun nativeXRunCount(handle: Long): Int
    private external fun nativeRestartCount(handle: Long): Int
    private external fun nativeBufferSizeInFrames(handle: Long): Int
    private external fun nativeRegisterBuffer(handle: Long, id: Int, interleaved: FloatArray, channels: Int)
    private external fun nativeReleaseBuffer(handle: Long, id: Int)
    private external fun nativeSchedule(handle: Long, bufferId: Int, atFrame: Long, gain: Float, pan: Float, rate: Float): Long
    private external fun nativeSetVoiceRate(handle: Long, voiceTag: Long, rate: Float)
    private external fun nativeSetDrive(handle: Long, enabled: Boolean, amount: Float)
    private external fun nativeSetMasterGain(handle: Long, gain: Float)
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
