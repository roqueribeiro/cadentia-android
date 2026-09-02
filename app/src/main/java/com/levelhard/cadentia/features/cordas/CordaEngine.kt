package com.levelhard.cadentia.features.cordas

import android.util.Log
import com.levelhard.cadentia.audio.PolyphonicSampler
import com.levelhard.cadentia.kit.SampleBank
import com.levelhard.cadentia.kit.cordas.CordaBody
import com.levelhard.cadentia.kit.cordas.CordaInstrument
import com.levelhard.cadentia.kit.cordas.CordaNoise
import com.levelhard.cadentia.kit.cordas.CordaString
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * O lado de áudio do Cordas — port do `CordaEngine.swift` (1.16) sobre o
 * motor Oboe nosso, via [PolyphonicSampler].
 *
 * Regra da casa, e é o motivo deste arquivo ser curto: **motor não
 * sintetiza.** Ele pede PCM pronto ao Kit e agenda no relógio de áudio. Tudo
 * o que decide como uma corda soa mora em [CordaString] e [CordaBody], onde
 * dá para testar sem motor e sem emulador.
 *
 * Dois barramentos, porque os dois instrumentos não são a mesma máquina:
 *
 * - **acústico** (violão, viola): a caixa já vai cozida no buffer em cache,
 *   porque uma caixa modal é linear e uma convolução por nota sai de graça
 *   depois de guardada;
 * - **elétrico** (guitarra): a caixa não existe, e o drive e o gabinete são
 *   NÃO lineares, então rodam ao vivo na mistura — é o `setDrive` do motor.
 *   Distorcer nota a nota e somar depois perde a intermodulação, que é a
 *   maior parte do que um acorde distorcido soa.
 *
 * Diferenças para o iOS que valem registrar: o iOS tem doze `AVAudioPlayerNode`
 * em rodízio e um `AVAudioUnitVarispeed` por voz; aqui o motor C++ tem 24 vozes
 * com pan e taxa por voz, então a "voz" deste arquivo é só a tag que o motor
 * devolveu, guardada por corda para abafar e dobrar depois. Os buffers ficam em
 * MONO no cache (o pan é da voz), a metade do que o estéreo gastaria.
 */
class CordaEngine {
    companion object {
        /**
         * As dinâmicas que o motor guarda em cache, e que o banco de sample
         * recebe como pedido. Duas, e por que estas duas: `0,30` cai na camada
         * 1–60 do pack do baixo e `0,70` na 61–95; a camada 96–120, o take mais
         * agressivo, deixa de ser pedida pelo Cordas (era o "trasteio" que o
         * founder ouviu). Um terceiro balde não cabe na memória: o cache guarda
         * um buffer por nota por balde, e sem mão direita a janela de
         * aquecimento é de doze notas por corda.
         */
        val velocityBuckets: DoubleArray = doubleArrayOf(0.30, 0.70)

        /** Teto do cache em bytes, como o `CordaEngine` do iOS. */
        const val MAX_CACHED_BYTES: Long = 48L * 1024 * 1024

        /** Um palm mute morre em 0,16 s; depois de 0,8 s (e⁻⁵) não sobra nada que valha guardar. */
        private const val MUTED_SECONDS = 0.8

        /**
         * Cada toque pega o balde MAIS PRÓXIMO. Os cortes derivam do próprio
         * vetor: escrever os limites à mão foi o que deixou o corte em 0,55
         * depois que os baldes mudaram.
         */
        fun bucket(velocity: Double): Int {
            var best = 0
            var bestDistance = Double.MAX_VALUE
            for ((index, nominal) in velocityBuckets.withIndex()) {
                val distance = abs(nominal - velocity)
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = index
                }
            }
            return best
        }

        /**
         * A geração do interruptor de som entra na chave: trocar entre síntese e
         * gravação nas configurações tem que valer na próxima corda tocada.
         */
        fun cacheKey(tone: String, frequency: Double, bucket: Int, sampleRate: Double): String =
            "${SampleBank.shared.soundGeneration}|" + rawCacheKey(tone, frequency, bucket, sampleRate)

        /**
         * A TAXA entra na chave: um buffer renderizado a 48 kHz não pode
         * continuar valendo depois de a saída voltar a 44,1 kHz numa troca de
         * rota (fone entrando ou saindo).
         */
        fun rawCacheKey(tone: String, frequency: Double, bucket: Int, sampleRate: Double): String =
            "$tone|${Math.round(frequency * 100)}|$bucket|${Math.round(sampleRate)}"

        /**
         * Pura, e por isso roda em qualquer thread: é o ponto de manter a
         * síntese no Kit. Devolve MONO, ou `null` quando não há o que tocar.
         *
         * O banco de sample entra ANTES da síntese, e o corpo não é aplicado
         * sobre ele: o sample já foi gravado num violão de verdade, com caixa e
         * tudo — passar por [CordaBody] seria ressoar duas vezes.
         */
        fun makeBuffer(frequency: Double, bucket: Int, instrument: CordaInstrument, sampleRate: Double): FloatArray? {
            val sampled = SampleBank.shared.renderIfEnabled(
                voice = instrument.sampleVoice,
                frequency = frequency,
                duration = instrument.tone.decay,
                velocity = velocityBuckets[bucket].toFloat(),
                gain = 1f,
                sampleRate = sampleRate,
                variation = bucket,
            )
            var samples = if (sampled != null) {
                sampled.summedToMono()
            } else {
                val synthesized = CordaString.render(
                    frequency = frequency,
                    velocity = velocityBuckets[bucket],
                    tone = instrument.tone,
                    sampleRate = sampleRate,
                    seed = (frequency * 1000).toLong() + bucket * 7919L,
                )
                if (synthesized.isEmpty()) return null
                // `bodyStyle`, e não `isElectric`: a caixa é de VIOLÃO. O baixo
                // está no barramento acústico por causa do drive, mas não tem
                // caixa nenhuma — e a caixa punha +2,4 dB numa nota grave, o
                // que fazia a batida das quatro cordas passar do teto e cortar.
                if (instrument.bodyStyle == CordaInstrument.Body.Box) {
                    CordaBody.apply(synthesized, sampleRate)
                } else {
                    synthesized
                }
            }
            if (samples.isEmpty()) return null
            return samples
        }

        /**
         * Palm mute: a corda para quase assim que é tocada. Um envelope curto
         * numa cópia do buffer, que deixa o barramento livre de automação por
         * nota — e encurtado, porque depois de 0,8 s não há mais nada ali.
         */
        fun muffle(source: FloatArray, sampleRate: Double): FloatArray {
            val count = minOf(source.size, maxOf(64, (MUTED_SECONDS * sampleRate).toInt()))
            val tau = 0.16 * sampleRate
            val out = FloatArray(count)
            for (i in 0 until count) out[i] = (source[i] * exp(-i / tau)).toFloat()
            return out
        }

        private fun frequencyOf(midi: Int): Double = 440 * 2.0.pow((midi - 69) / 12.0)
    }

    /** Uma nota no ar: a tag do motor e o que ela é, para abafar e dobrar depois. */
    private class Voice(
        val tag: Long,
        val string: Int,
        val midi: Int,
        val baseRate: Float,
        val endsAt: Double,
    )

    private val sampler = PolyphonicSampler(maxCacheBytes = MAX_CACHED_BYTES)
    private val voices = ArrayList<Voice>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var warmupJob: Job? = null

    /**
     * A janela do último aquecimento, para um religamento no meio de uma
     * música não recomeçar pela casa 0.
     */
    private var lastWarmPosition = 0
    private var lastWarmFrets = 5

    /** O motor está de pé? A pergunta é sobre o stream, não sobre uma bandeira. */
    val running: Boolean get() = sampler.isRunning

    /** `start()` foi chamado e `stop()` ainda não: a tela está aberta. */
    private var started = false

    /** O stream não abriu. A tela mostra o aviso (texto dela, localizado). */
    var failed: Boolean = false
        private set

    var instrument: CordaInstrument = CordaInstrument.violao
        set(value) {
            if (field.id == value.id) return
            field = value
            dampAll(hard = true)
            amplitude = DoubleArray(value.stringCount)
            applyBus()
            warmup(lastWarmPosition, lastWarmFrets)
        }

    var volume: Double = 0.9
        set(value) {
            field = value
            applyLevels()
        }

    var ambience: Double = 0.16
        set(value) {
            field = value
            applyLevels()
        }

    var sustain: Double = 1.0

    var driveAmount: Double = 0.35
        set(value) {
            field = value
            applyBus()
        }

    /**
     * Nível por corda, para as que estão visivelmente vibrando. A tela lê a
     * cada quadro; nada mais lê.
     */
    var amplitude: DoubleArray = DoubleArray(CordaInstrument.violao.stringCount)
        private set

    /** Underruns do stream desde o start: a prova no aparelho de que o callback cabe no tempo. */
    val xrunCount: Int get() = sampler.xrunCount

    /** Latência estrutural do stream, em segundos por burst — para o painel e para a medição. */
    val burstSeconds: Double
        get() {
            val rate = sampler.sampleRate
            return if (rate > 0) sampler.framesPerBurst / rate else 0.0
        }

    // ── ciclo de vida ────────────────────────────────────────────────────

    fun start() {
        started = true
        if (running) return
        if (!sampler.startIfNeeded()) {
            failed = true
            return
        }
        failed = false
        applyLevels()
        applyBus()
        amplitude = DoubleArray(instrument.stringCount)
        voices.clear()
        warmup(lastWarmPosition, lastWarmFrets)
    }

    fun stop() {
        started = false
        warmupJob?.cancel()
        warmupJob = null
        voices.clear()
        sampler.stop()
    }

    /** Solta o escopo. Depois disto o motor não aquece nem toca mais. */
    fun shutdown() {
        stop()
        scope.cancel()
    }

    private fun applyLevels() {
        sampler.setMasterGain(volume.toFloat().coerceIn(0f, 1f))
        sampler.setReverb(ambience > 0.0005, ambience.toFloat().coerceIn(0f, 1f))
    }

    private fun applyBus() {
        if (instrument.isElectric) {
            sampler.setDrive(true, driveAmount.toFloat().coerceIn(0f, 1f))
        } else {
            sampler.setDrive(false, 0f)
        }
    }

    // ── o cache ──────────────────────────────────────────────────────────

    /**
     * Sintetizar uma nota custa alguns milissegundos. Se isso acontece no
     * momento do toque vira latência, então o trabalho anda na frente de quem
     * toca: toda nota alcançável na posição atual fica pronta antes.
     *
     * E acontece FORA da thread principal: renderizar uma centena de buffers
     * nela trava o app por cerca de um segundo — o Cordas do iOS morreu
     * exatamente assim na primeira dedilhada, e o piano daqui congelou 4 s
     * pelo mesmo motivo antes de ir para o `Dispatchers.Default`.
     */
    fun warmup(around: Int = 0, visibleFrets: Int = 5) {
        warmupJob?.cancel()
        lastWarmPosition = around
        lastWarmFrets = visibleFrets
        if (!running) return
        val instrument = instrument
        val sampleRate = sampler.sampleRate
        if (sampleRate <= 0) return
        val low = maxOf(0, around - 1)
        val high = minOf(instrument.frets, around + visibleFrets + 2)
        val notes = HashSet<Int>()
        for (string in instrument.strings) for (fret in low..high) notes += string.midi + fret
        // Cordas soltas primeiro: são o que uma batida acerta, e o que qualquer
        // pessoa toca no primeiro segundo depois de a tela abrir.
        val open = instrument.strings.map { it.midi }.filter { it in notes }
        val ordered = open + notes.sorted().filter { it !in open }
        warmupJob = scope.launch {
            for (midi in ordered) {
                val frequency = frequencyOf(midi)
                for (bucket in velocityBuckets.indices) {
                    ensureActive()
                    val key = cacheKey(instrument.tone.id, frequency, bucket, sampleRate)
                    if (sampler.hasCached(key)) continue
                    val pcm = makeBuffer(frequency, bucket, instrument, sampleRate) ?: continue
                    sampler.prewarm(key, mono = true) { pcm }
                }
                yield()
            }
        }
    }

    // ── tocar ────────────────────────────────────────────────────────────

    /**
     * Uma nota. `midi` é a que soa de fato; `string` é só a corda a que
     * pertence, para o abafamento e para o desenho.
     */
    fun pluck(
        string: Int,
        midi: Int,
        velocity: Double,
        delay: Double = 0.0,
        @Suppress("UNUSED_PARAMETER") nail: Double = 0.5,
        muted: Boolean = false,
    ) {
        // Religa sozinho, como as outras telas fazem: o sistema para o stream
        // numa ligação e não avisa ninguém. Mas só depois de `start()`: antes
        // dele a tela não abriu, e não há por que subir o stream.
        if (!started) return
        if (!running) {
            if (!sampler.startIfNeeded()) return
            applyLevels()
            applyBus()
        }
        if (string < 0 || string >= instrument.stringCount) return
        val sampleRate = sampler.sampleRate
        if (sampleRate <= 0) return
        val frequency = frequencyOf(midi)
        val bucket = bucket(velocity)
        val baseKey = cacheKey(instrument.tone.id, frequency, bucket, sampleRate)
        val key = if (muted) "$baseKey|m" else baseKey
        val instrument = instrument

        // Uma corda de verdade é silenciada pelo toque novo: desvanece o que
        // esta corda estava fazendo em vez de deixar duas notas na mesma ordem.
        takeVoice(string)

        // Não há duas dedilhadas iguais, de propósito: a mesma onda em toda
        // batida é o que o ouvido chama de robótico primeiro.
        val noise = CordaNoise((System.nanoTime() / 10_000L) + string)
        val variation = (1 + (noise.nextUnit() - 0.5) * 0.005).toFloat()

        val spec = instrument.strings[string]
        var level = ((0.52 + 0.48 * spec.gauge * 0.6) * sustain).toFloat()
        level *= (0.45 + velocity * 0.55).toFloat()
        if (muted) level *= 0.55f
        level = level.coerceIn(0f, 1f)
        val pan = ((string.toDouble() / maxOf(1, instrument.stringCount - 1) - 0.5) * 0.42).toFloat()

        val at = if (delay <= 0) 0.0 else sampler.nowSeconds() + delay
        val tag = sampler.schedule(key, at, gain = level, pan = pan, rate = variation, mono = true) {
            // Raro depois do aquecimento; uma nota custa alguns milissegundos e
            // ainda é mais barato que engolir a nota que a pessoa pediu.
            val base = makeBuffer(frequency, bucket, instrument, sampleRate) ?: FloatArray(64)
            if (muted) muffle(base, sampleRate) else base
        }
        if (Log.isLoggable(CordasModel.TELEMETRY_TAG, Log.DEBUG)) {
            Log.d(CordasModel.TELEMETRY_TAG, "pluck corda=$string midi=$midi vel=${"%.2f".format(java.util.Locale.ROOT, velocity)} tag=$tag muted=$muted")
        }
        if (tag == 0L) return
        val length = if (muted) MUTED_SECONDS else minOf(CordaString.MAX_CACHED_DURATION, instrument.tone.decay)
        voices += Voice(tag, string, midi, variation, sampler.nowSeconds() + maxOf(0.0, delay) + length)
        amplitudeBump(string, velocity)
    }

    private fun takeVoice(string: Int) {
        val now = sampler.nowSeconds()
        val it = voices.iterator()
        while (it.hasNext()) {
            val voice = it.next()
            if (voice.endsAt < now) {
                it.remove()
            } else if (voice.string == string) {
                sampler.damp(voice.tag, 0.045f)
                it.remove()
            }
        }
    }

    /**
     * Bend e deslize contínuos. É o que faz arrastar um acorde inteiro pelo
     * braço soar como glissando, e não como notas novas.
     */
    fun bend(string: Int, semitones: Double) {
        val factor = 2.0.pow(semitones / 12).toFloat()
        for (voice in voices) {
            if (voice.string == string) sampler.setVoiceRate(voice.tag, voice.baseRate * factor)
        }
    }

    fun damp(string: Int, hard: Boolean = false) {
        val over = if (hard) 0.05f else 0.22f
        val it = voices.iterator()
        while (it.hasNext()) {
            val voice = it.next()
            if (voice.string == string) {
                sampler.damp(voice.tag, over)
                it.remove()
            }
        }
        if (string in amplitude.indices) amplitude[string] *= 0.2
    }

    fun dampAll(hard: Boolean = false) {
        if (running) sampler.dampAll(if (hard) 0.05f else 0.22f)
        voices.clear()
        amplitude.fill(0.0)
    }

    private fun amplitudeBump(string: Int, velocity: Double) {
        if (amplitude.size != instrument.stringCount) amplitude = DoubleArray(instrument.stringCount)
        amplitude[string] = minOf(1.0, 0.30 + velocity * 0.55)
    }

    /**
     * As cordas assentam em cerca de um terço de segundo. A tela chama uma vez
     * por quadro, então o decaimento é em tempo de parede, não em quadros.
     */
    fun decayAmplitudes(dt: Double) {
        for (i in amplitude.indices) amplitude[i] *= exp(-dt * (3.2 + i * 0.25))
    }

    /**
     * O tapa percussivo: o que soava morre, as cordas soam abafadas e a caixa
     * dá a batida de madeira.
     */
    fun tchac(velocity: Double) {
        val v = velocity.coerceIn(0.45, 1.0)
        for (i in 0 until instrument.stringCount) damp(i, hard = true)
        for (i in 0 until instrument.stringCount) {
            val spec = instrument.strings[i]
            pluck(
                string = i, midi = spec.midi, velocity = v * (0.5 - i * 0.03),
                delay = i * 0.004, nail = 0.4, muted = true,
            )
        }
    }
}
