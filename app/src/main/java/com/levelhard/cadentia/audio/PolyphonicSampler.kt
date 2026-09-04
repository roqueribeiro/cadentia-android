package com.levelhard.cadentia.audio

/**
 * A fachada Kotlin do motor C++ — o papel do `PolyphonicSampler` do iOS:
 * cache de PCM por chave (render uma vez, tocar sempre) e agendamento
 * sample-accurate no relógio compartilhado. Aqui o relógio é o contador de
 * frames do stream Oboe; segundos = frames / sampleRate.
 *
 * Teto do cache em BYTES (44 MB, como o iOS), com evicção LRU: o C++ segura
 * o buffer de uma voz que ainda toca via shared_ptr, então despejar do cache
 * nunca corta som no ar.
 */
class PolyphonicSampler(
    /** Teto do cache em bytes. O Cordas pede 48 MB, como o `CordaEngine` do iOS. */
    private val maxCacheBytes: Long = MAX_CACHE_BYTES,
) {
    private val engine = AudioEngineBridge()

    private data class Entry(val id: Int, val bytes: Int)

    private val cache = LinkedHashMap<String, Entry>(64, 0.75f, true)
    private var cacheBytes = 0L
    private var nextId = 1

    companion object {
        private const val MAX_CACHE_BYTES = 44L * 1024 * 1024

        /** Mono → estéreo intercalado (imagem central), o formato do motor. */
        fun interleave(mono: FloatArray): FloatArray {
            val out = FloatArray(mono.size * 2)
            for (i in mono.indices) {
                out[i * 2] = mono[i]
                out[i * 2 + 1] = mono[i]
            }
            return out
        }
    }

    val sampleRate: Double get() = engine.sampleRate().toDouble()
    val isRunning: Boolean get() = engine.isRunning

    /** Latência estrutural reportável: frames por burst do stream. */
    val framesPerBurst: Int get() = engine.framesPerBurst()

    /** Buffer real do stream (2 bursts no EXCLUSIVE); latência = isto / taxa. */
    val bufferSizeInFrames: Int get() = engine.bufferSizeInFrames()

    /** Underruns desde o start: a prova no aparelho de que o callback cabe no tempo. */
    val xrunCount: Int get() = engine.xrunCount()

    /**
     * Start/stop e o cache moram sob o MESMO lock: o aquecimento insere de
     * uma thread de fundo, e registrar um buffer num motor que a thread
     * principal acabou de destruir seria use-after-free no C++.
     */
    fun startIfNeeded(): Boolean = synchronized(cache) {
        if (engine.isRunning) return true
        if (!engine.start()) return false
        cache.clear()
        cacheBytes = 0
        true
    }

    fun stop() = synchronized(cache) {
        engine.stop()
        cache.clear()
        cacheBytes = 0
    }

    /** O "agora" do relógio compartilhado, em segundos do stream. */
    fun nowSeconds(): Double {
        val rate = engine.sampleRate()
        if (rate <= 0) return 0.0
        return engine.nowFrames().toDouble() / rate
    }

    /** Esvazia o cache (troca de voz): os buffers antigos não valem mais. */
    fun invalidateCache() {
        synchronized(cache) {
            for (entry in cache.values) engine.releaseBuffer(entry.id)
            cache.clear()
            cacheBytes = 0
        }
    }

    /** Bytes de PCM guardados agora (diagnóstico e testes de aquecimento). */
    val cachedBytes: Long get() = synchronized(cache) { cacheBytes }

    /** Já renderizado? Quem aquece pergunta ANTES de renderizar, não dentro do insert. */
    fun hasCached(key: String): Boolean = synchronized(cache) { cache.containsKey(key) }

    /**
     * Renderiza para o cache sem tocar: o primeiro hit sai sem soluço.
     *
     * O `render` roda FORA do lock, de propósito: o aquecimento vem de uma
     * thread de fundo e uma tecla apertada no meio dele não pode esperar o
     * render terminar para descobrir que a nota já estava no cache. Se duas
     * threads renderizarem a mesma chave, a segunda joga o PCM fora.
     */
    fun prewarm(key: String, mono: Boolean = false, render: () -> FloatArray) {
        if (!engine.isRunning) return
        synchronized(cache) { if (cache.containsKey(key)) return }
        val pcm = render()
        synchronized(cache) {
            if (!engine.isRunning || cache.containsKey(key)) return
            insertLocked(key, pcm, if (mono) 1 else 2)
        }
    }

    /** Toca já (atalho de `schedule` no agora do relógio). */
    fun play(key: String, gain: Float = 1f, render: () -> FloatArray): Long =
        schedule(key, atSeconds = 0.0, gain = gain, render = render)

    /**
     * Agenda o PCM da `key` para `atSeconds` no relógio do stream (passado =
     * toca já). `render` roda UMA vez por chave e devolve estéreo intercalado.
     * `pan` vai de -1 (esquerda) a 1 (direita) em potência constante; `rate`
     * é a taxa de leitura (1 = normal), o varispeed que o Cordas usa para a
     * humanização e para o bend; `mono` diz que `render` devolve UM canal (o
     * motor abre a imagem pelo pan, e o cache gasta metade). Devolve a tag da
     * voz (0 = não agendou).
     */
    fun schedule(
        key: String,
        atSeconds: Double,
        gain: Float = 1f,
        pan: Float = 0f,
        rate: Float = 1f,
        mono: Boolean = false,
        render: () -> FloatArray,
    ): Long {
        if (!engine.isRunning) return 0
        val id = synchronized(cache) { cache[key]?.id } ?: run {
            // Render fora do lock (ver `prewarm`); a inserção confere de novo.
            val pcm = render()
            synchronized(cache) {
                if (!engine.isRunning) return 0
                cache[key]?.id ?: insertLocked(key, pcm, if (mono) 1 else 2)
            }
        }
        return synchronized(cache) {
            if (!engine.isRunning) return 0
            val atFrame = (atSeconds * engine.sampleRate()).toLong()
            engine.schedule(id, atFrame, gain, pan, rate)
        }
    }

    fun damp(voiceTag: Long, overSeconds: Float) = engine.damp(voiceTag, overSeconds)

    fun dampAll(overSeconds: Float) = engine.dampAll(overSeconds)

    /** Muda a taxa de uma voz no ar: bend e glissando do Cordas. */
    fun setVoiceRate(voiceTag: Long, rate: Float) = engine.setVoiceRate(voiceTag, rate)

    /** Ganho e pan de uma voz viva (o fader do Gravador valendo na hora). */
    fun setVoiceMix(voiceTag: Long, gain: Float, pan: Float) = engine.setVoiceMix(voiceTag, gain, pan)

    /** Barramento elétrico do Cordas (drive + gabinete) antes do reverb. */
    fun setDrive(enabled: Boolean, amount: Float) = engine.setDrive(enabled, amount)

    /** Volume mestre do bus (0…1), aplicado ao vivo antes do limiter. */
    fun setMasterGain(gain: Float) = engine.setMasterGain(gain)

    fun setReverb(enabled: Boolean, mix: Float) = engine.setReverb(enabled, mix)

    fun setDelay(enabled: Boolean, timeMs: Float, feedback: Float, mix: Float) =
        engine.setDelay(enabled, timeMs, feedback, mix)

    /** Chamar com o lock de `cache`. Registra no motor e devolve o id. */
    private fun insertLocked(key: String, pcm: FloatArray, channels: Int = 2): Int {
        val entry = Entry(id = nextId++, bytes = pcm.size * 4)
        engine.registerBuffer(entry.id, pcm, channels)
        cache[key] = entry
        cacheBytes += entry.bytes
        evictIfNeeded()
        return entry.id
    }

    private fun evictIfNeeded() {
        val it = cache.entries.iterator()
        while (cacheBytes > maxCacheBytes && it.hasNext()) {
            val eldest = it.next()
            engine.releaseBuffer(eldest.value.id)
            cacheBytes -= eldest.value.bytes
            it.remove()
        }
    }
}
