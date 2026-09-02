package com.levelhard.cadentia.kit

import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Quem transforma um arquivo do pack em PCM. O Kit é JVM puro e não conhece
 * FLAC; o app pluga o decodificador do Android (MediaCodec), e os testes
 * usam WAV.
 */
fun interface SampleDecoder {
    /** PCM do arquivo, na taxa do arquivo, ou null quando não dá para ler. */
    fun decode(file: File): StereoBuffer?

    companion object {
        /** WAV 16-bit/float32, 1–2 canais — o padrão do Kit. */
        val Wav: SampleDecoder = SampleDecoder { file ->
            if (!file.isFile) return@SampleDecoder null
            try {
                file.inputStream().use { WavIO.readStereo(it)?.buffer }
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * O banco de samples — port do `SampleBank.swift` e do `SampleBank+Switch`
 * (1.16): carrega os packs, escolhe a região e devolve um `StereoBuffer` com a
 * mesma cara do que a síntese devolve.
 *
 * É essa simetria que faz a troca ser barata. O app inteiro já pede som por
 * dois lugares — `InstrumentSynth.render` e `DrumSynth.renderStereo` — e os
 * dois devolvem `StereoBuffer`. O banco entra na frente dos dois; quem não
 * tem sample cai na síntese sem saber que existe um banco.
 *
 * Estado compartilhado com trava porque o render acontece fora da thread
 * principal (aquecimento em Default, sequenciador da bateria).
 */
class SampleBank(@Volatile var decoder: SampleDecoder = SampleDecoder.Wav) {
    private val lock = Any()
    private val packsByVoice = HashMap<String, SamplePack>()
    private val baseDirs = HashMap<String, File>()

    /** LRU por acesso: um `get` promove, e é isso que segura bumbo, caixa e chimbal no cache. */
    private val cache = LinkedHashMap<String, StereoBuffer>(64, 0.75f, true)
    private var cachedBytes = 0L
    private var decodedEver = 0L

    // A chave por família. Estado de INSTÂNCIA, não estático: com um
    // singleton de verdade, um `SampleBank()` criado num teste escreveria no
    // interruptor que os outros testes leem.
    private val switchLock = Any()
    private var enabledFamilies: Set<SampleFamily> = emptySet()
    private var generation = 0

    /**
     * Teto do cache decodificado, em bytes.
     *
     * Contar ENTRADAS não funciona aqui: um bumbo tem 0,3 s e uma nota de
     * piano tem 6 s — vinte vezes mais memória pela mesma "entrada". Por
     * bytes o teto é o teto. 96 MB cabe a bateria inteira decodificada com
     * folga.
     */
    @Volatile
    var cacheLimitBytes: Long = 96L * 1024 * 1024

    // ── instalação ─────────────────────────────────────────────────────────

    @Serializable
    private data class PackIndex(val packs: List<String>)

    /**
     * Lê `packs.json` e os manifestos ao lado dele.
     *
     * Falha em silêncio de propósito: um pack ausente é o app tocando com
     * síntese, não o app quebrando. O que não pode acontecer é a tela de som
     * prometer um banco que não existe — por isso `installedFamilies`.
     */
    fun install(directory: File): List<SamplePack> {
        val index = File(directory, "packs.json")
        val list = try {
            json.decodeFromString<PackIndex>(index.readText())
        } catch (_: Exception) {
            return emptyList()
        }
        val loaded = ArrayList<SamplePack>()
        for (id in list.packs) {
            val dir = File(directory, id)
            val pack = try {
                json.decodeFromString<SamplePack>(File(dir, "manifest.json").readText())
            } catch (_: Exception) {
                continue
            }
            synchronized(lock) {
                packsByVoice[pack.voice] = pack
                baseDirs[pack.id] = dir
            }
            loaded.add(pack)
        }
        return loaded
    }

    val installed: List<SamplePack>
        get() = synchronized(lock) { packsByVoice.values.sortedBy { it.id } }

    /** As famílias que têm pelo menos um pack — é o que a tela de som oferece. */
    val installedFamilies: Set<SampleFamily>
        get() = installed.map { it.family }.toSet()

    fun pack(voice: String): SamplePack? = synchronized(lock) { packsByVoice[voice] }

    // ── render ─────────────────────────────────────────────────────────────

    /** Uma nota. `null` quando não há pack para a voz — o chamador sintetiza. */
    fun render(
        voice: String,
        frequency: Double,
        duration: Double,
        velocity: Float,
        gain: Float,
        sampleRate: Double,
        variation: Int = 0,
    ): StereoBuffer? {
        if (frequency <= 0 || duration <= 0) return null
        val pack = pack(voice) ?: return null
        val note = (69 + 12 * log2(frequency / 440)).roundToInt()
        val region = SampleSelection.region(pack, note, velocity, variation) ?: return null

        // A razão sai da frequência real, não da nota arredondada: assim um
        // A4 em 442 Hz toca em 442, e o afinador do próprio app concorda.
        val rootHz = 440 * 2.0.pow((region.root - 69) / 12.0)
        val cents = (region.tune ?: 0.0) / 100
        val ratio = (frequency / rootHz) * 2.0.pow(cents / 12) * (pack.sampleRate / sampleRate)

        val source = source(pack, region) ?: return null
        val frames = (duration * sampleRate).toInt() + (0.35 * sampleRate).toInt()
        val buffer = resample(source, ratio, frames, region.loop)
        // A rampa TEM que cair no fim do sinal, não no fim do buffer. Uma nota
        // transposta para cima consome o material antes do prazo e o resto do
        // buffer é zero; sem aparar, o fade suavizava silêncio e o corte real
        // ficava sem rampa — e ainda sobravam zeros no cache.
        buffer.trimTail()
        applyRelease(buffer, sampleRate)
        buffer.applyGain(gain)
        region.pan?.let { if (it != 0.0) pan(buffer, it.toFloat()) }
        return buffer
    }

    /** Uma pancada da bateria. `null` quando o kit não tem pack. */
    fun renderDrum(
        kit: String,
        pad: String,
        velocity: Float,
        variation: Int,
        sampleRate: Double,
        gain: Float,
    ): StereoBuffer? {
        // Percussão não transpõe. Um pad sem zona própria no pack cai na
        // síntese em vez de pegar emprestada a zona vizinha — senão o pad de
        // shaker toca uma caixa, que é pior do que o som que já existe.
        val pack = pack("drums:$kit") ?: return null
        val note = pack.padNotes?.get(pad) ?: return null
        if (pack.regions.none { note >= it.lo && note <= it.hi }) return null
        val region = SampleSelection.region(pack, note, velocity, variation) ?: return null
        val source = source(pack, region) ?: return null

        val ratio = pack.sampleRate / sampleRate
        val out = if (abs(ratio - 1) > 0.0001) {
            resample(source, ratio, (source.frameCount / ratio).toInt(), loop = null)
        } else {
            source.copy()
        }
        out.applyGain(gain)
        region.pan?.let { if (it != 0.0) pan(out, it.toFloat()) }
        return out
    }

    // ── leitura de disco ───────────────────────────────────────────────────

    private fun source(pack: SamplePack, region: SamplePack.Region): StereoBuffer? {
        val key = "${pack.id}/${region.f}"
        val dir = synchronized(lock) {
            // `get` num LinkedHashMap por acesso promove a entrada: sem isto a
            // fila é FIFO, e o aquecimento da bateria (que insere na ordem dos
            // pads) expulsava bumbo, caixa e chimbal para dar lugar a cowbell.
            cache[key]?.let { return it }
            baseDirs[pack.id]
        } ?: return null
        val decoded = decoder.decode(File(dir, region.f)) ?: return null
        if (decoded.isEmpty) return null
        synchronized(lock) {
            // Recheca: a trava foi solta para decodificar, e duas threads que
            // erraram a mesma chave chegam aqui as duas. Sem isto `cachedBytes`
            // somaria o mesmo buffer duas vezes e a evicção só subtrairia uma.
            cache[key]?.let { return it }
            val bytes = decoded.frameCount * 2L * 4
            cache[key] = decoded
            cachedBytes += bytes
            decodedEver += bytes
            val it = cache.entries.iterator()
            while (cachedBytes > cacheLimitBytes && it.hasNext()) {
                val eldest = it.next()
                cachedBytes -= eldest.value.frameCount * 2L * 4
                it.remove()
            }
        }
        return decoded
    }

    /**
     * Solta todo o PCM decodificado — para o aviso de memória do sistema
     * (`onTrimMemory`): o áudio é a coisa mais cara e a mais barata de
     * reconstruir aqui; a próxima nota decodifica de novo.
     */
    fun purge() = synchronized(lock) {
        cache.clear()
        cachedBytes = 0
    }

    /** Quanto o cache decodificado está ocupando agora. */
    val cachedMegabytes: Double
        get() = synchronized(lock) { cachedBytes / 1024.0 / 1024.0 }

    /**
     * Pré-carrega as amostras de uma voz, para a primeira nota não pagar o
     * disco, e devolve quantos bytes isso custou.
     *
     * O orçamento existe porque aquecer sem ele é pior do que não aquecer:
     * passar do teto do cache faz o fim da fila expulsar o começo, e o que
     * sobra é o último pack lido em vez do que a pessoa vai tocar. O
     * orçamento se mede em bytes DECODIFICADOS (contador que só sobe), não no
     * que está guardado — a evicção segura `cachedBytes` no teto e o orçamento
     * nunca estouraria.
     */
    fun warmUp(
        voice: String,
        limit: Int = 48,
        byteBudget: Long = Long.MAX_VALUE,
        isCancelled: () -> Boolean = { false },
    ): Long {
        val pack = pack(voice) ?: return 0
        val before = decodedBytesEver
        for (region in pack.regions.take(limit)) {
            if (isCancelled()) break
            source(pack, region)
            if (decodedBytesEver - before >= byteBudget) break
        }
        return decodedBytesEver - before
    }

    private val decodedBytesEver: Long
        get() = synchronized(lock) { decodedEver }

    // ── a chave por família (SampleBank+Switch) ────────────────────────────

    /**
     * Chamado quando o app abre e a cada mudança nas configurações. Muda a
     * `generation`, e é isso que faz a troca valer na hora: os caches de
     * buffer do app carregam a geração na chave, então virar o interruptor
     * invalida o que foi renderizado com a escolha anterior.
     */
    fun setEnabled(families: Set<SampleFamily>) = synchronized(switchLock) {
        if (families == enabledFamilies) return
        enabledFamilies = families
        generation += 1
    }

    fun isEnabled(family: SampleFamily): Boolean = synchronized(switchLock) { family in enabledFamilies }

    val enabled: Set<SampleFamily>
        get() = synchronized(switchLock) { enabledFamilies }

    /** Muda a cada mudança de escolha. Entra na chave de cache de quem guarda buffer renderizado. */
    val soundGeneration: Int
        get() = synchronized(switchLock) { generation }

    /** Uma nota, se a família estiver ligada e houver pack. Senão `null`, e o chamador sintetiza. */
    fun renderIfEnabled(
        voice: String,
        frequency: Double,
        duration: Double,
        velocity: Float,
        gain: Float,
        sampleRate: Double,
        variation: Int = 0,
    ): StereoBuffer? {
        if (!isEnabled(SampleFamily.of(voice))) return null
        return render(voice, frequency, duration, velocity, gain, sampleRate, variation)
    }

    fun renderDrumIfEnabled(
        kit: String,
        pad: String,
        velocity: Float,
        variation: Int,
        sampleRate: Double,
        gain: Float,
    ): StereoBuffer? {
        if (!isEnabled(SampleFamily.Drums)) return null
        return renderDrum(kit, pad, velocity, variation, sampleRate, gain)
    }

    /**
     * Qual ARQUIVO uma pancada vai usar — `null` quando ela não vem de sample.
     *
     * Existe para o cache do app poder guardar por arquivo em vez de por
     * pedido: o app pede 15 pads × 4 dinâmicas × 4 variações, e o pack
     * responde com muito menos arquivos, porque a seleção dobra a variação
     * sobre o número real de round robins e a dinâmica sobre as camadas que
     * existem. Medido no iOS: 33,2 MB dos 49,6 MB do cache eram cópias
     * idênticas.
     */
    fun drumSlot(kit: String, pad: String, velocity: Float, variation: Int): String? {
        if (!isEnabled(SampleFamily.Drums)) return null
        val pack = pack("drums:$kit") ?: return null
        val note = pack.padNotes?.get(pad) ?: return null
        if (pack.regions.none { note >= it.lo && note <= it.hi }) return null
        val region = SampleSelection.region(pack, note, velocity, variation) ?: return null
        return "${pack.id}/${region.f}"
    }

    companion object {
        /** O banco do app. `InstrumentSynth` e `DrumSynth` consultam este. */
        val shared = SampleBank()

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * O ganho contínuo que falta entre uma camada de dinâmica e a próxima
         * (`amp_veltrack`). Só vale para sample: a síntese aplica a própria
         * curva dentro do buffer, e somar as duas contaria a dinâmica duas
         * vezes.
         */
        fun drumVelocityGain(velocity: Float): Float = velocity.coerceIn(0f, 1f).pow(0.75f)

        /**
         * Reamostragem com Hermite de 4 pontos. Linear seria dez linhas a menos
         * e soaria metálico — o erro de interpolação vira brilho falso justo
         * nas notas transpostas para cima. Devolve SEMPRE um buffer novo: o de
         * entrada pode morar no cache.
         */
        fun resample(input: StereoBuffer, ratio: Double, frames: Int, loop: List<Int>?): StereoBuffer {
            if (ratio <= 0 || input.isEmpty || frames <= 0) return input.copy()
            val out = StereoBuffer(frames)
            val last = input.frameCount - 1
            val loopStart = loop?.firstOrNull()
            val loopEnd = loop?.lastOrNull()
            var position = 0.0
            for (i in 0 until frames) {
                if (loopStart != null && loopEnd != null && loopEnd > loopStart && position > loopEnd) {
                    position -= (loopEnd - loopStart).toDouble()
                }
                val index = position.toInt()
                if (index > last) break
                val frac = (position - index).toFloat()
                out.left[i] = hermite(input.left, index, frac, last)
                out.right[i] = hermite(input.right, index, frac, last)
                position += ratio
            }
            return out
        }

        private fun hermite(samples: FloatArray, index: Int, frac: Float, last: Int): Float {
            val x0 = samples[maxOf(0, index - 1)]
            val x1 = samples[minOf(last, index)]
            val x2 = samples[minOf(last, index + 1)]
            val x3 = samples[minOf(last, index + 2)]
            val c0 = x1
            val c1 = 0.5f * (x2 - x0)
            val c2 = x0 - 2.5f * x1 + 2f * x2 - 0.5f * x3
            val c3 = 0.5f * (x3 - x0) + 1.5f * (x1 - x2)
            return ((c3 * frac + c2) * frac + c1) * frac + c0
        }

        /**
         * Rampa curta no fim para a nota não terminar num degrau. Um corte seco
         * num sample estoura como clique, e num acorde de seis cordas são seis
         * cliques ao mesmo tempo.
         */
        fun applyRelease(buffer: StereoBuffer, sampleRate: Double) {
            val fade = minOf(buffer.frameCount, (0.02 * sampleRate).toInt())
            if (fade <= 1) return
            val start = buffer.frameCount - fade
            for (i in 0 until fade) {
                val gain = (1 - i.toDouble() / fade).toFloat()
                buffer.left[start + i] *= gain
                buffer.right[start + i] *= gain
            }
        }

        fun pan(buffer: StereoBuffer, pan: Float) {
            val clamped = pan.coerceIn(-1f, 1f)
            val angle = (clamped + 1) * PI.toFloat() / 4
            val l = cos(angle)
            val r = sin(angle)
            for (i in buffer.left.indices) {
                buffer.left[i] *= l * 1.414f
                buffer.right[i] *= r * 1.414f
            }
        }
    }
}
