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
class PolyphonicSampler {
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

    /**
     * Renderiza para o cache sem tocar: o primeiro hit sai sem soluço.
     *
     * O `render` roda FORA do lock, de propósito: o aquecimento vem de uma
     * thread de fundo e uma tecla apertada no meio dele não pode esperar o
     * render terminar para descobrir que a nota já estava no cache. Se duas
     * threads renderizarem a mesma chave, a segunda joga o PCM fora.
     */
    fun prewarm(key: String, render: () -> FloatArray) {
        if (!engine.isRunning) return
        synchronized(cache) { if (cache.containsKey(key)) return }
        val pcm = render()
        synchronized(cache) {
            if (!engine.isRunning || cache.containsKey(key)) return
            insertLocked(key, pcm)
        }
    }

    /** Toca já (atalho de `schedule` no agora do relógio). */
    fun play(key: String, gain: Float = 1f, render: () -> FloatArray): Long =
        schedule(key, atSeconds = 0.0, gain = gain, render = render)

    /**
     * Agenda o PCM da `key` para `atSeconds` no relógio do stream (passado =
     * toca já). `render` roda UMA vez por chave e devolve estéreo intercalado.
     * Devolve a tag da voz (0 = não agendou).
     */
    fun schedule(key: String, atSeconds: Double, gain: Float = 1f, render: () -> FloatArray): Long {
        if (!engine.isRunning) return 0
        val id = synchronized(cache) { cache[key]?.id } ?: run {
            // Render fora do lock (ver `prewarm`); a inserção confere de novo.
            val pcm = render()
            synchronized(cache) {
                if (!engine.isRunning) return 0
                cache[key]?.id ?: insertLocked(key, pcm)
            }
        }
        return synchronized(cache) {
            if (!engine.isRunning) return 0
            val atFrame = (atSeconds * engine.sampleRate()).toLong()
            engine.schedule(id, atFrame, gain)
        }
    }

    fun damp(voiceTag: Long, overSeconds: Float) = engine.damp(voiceTag, overSeconds)

    fun dampAll(overSeconds: Float) = engine.dampAll(overSeconds)

    fun setReverb(enabled: Boolean, mix: Float) = engine.setReverb(enabled, mix)

    fun setDelay(enabled: Boolean, timeMs: Float, feedback: Float, mix: Float) =
        engine.setDelay(enabled, timeMs, feedback, mix)

    /** Chamar com o lock de `cache`. Registra no motor e devolve o id. */
    private fun insertLocked(key: String, pcm: FloatArray): Int {
        val entry = Entry(id = nextId++, bytes = pcm.size * 4)
        engine.registerBuffer(entry.id, pcm)
        cache[key] = entry
        cacheBytes += entry.bytes
        evictIfNeeded()
        return entry.id
    }

    private fun evictIfNeeded() {
        val it = cache.entries.iterator()
        while (cacheBytes > MAX_CACHE_BYTES && it.hasNext()) {
            val eldest = it.next()
            engine.releaseBuffer(eldest.value.id)
            cacheBytes -= eldest.value.bytes
            it.remove()
        }
    }
}
