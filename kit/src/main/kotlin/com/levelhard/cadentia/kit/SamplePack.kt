package com.levelhard.cadentia.kit

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

/**
 * Um banco de samples: o manifesto que `scripts/fetch-samples.mjs` escreve —
 * port do `SamplePack.swift` (1.16).
 *
 * O SFZ original fica no cache de build, fora do repo. O que viaja no app é
 * isto: uma lista de regiões já podada, para o Kit não precisar de um parser
 * de SFZ em tempo de execução. Os nomes dos campos são curtos de propósito:
 * são milhares de regiões, e `"pitch_keycenter"` repetido 3.000 vezes é meio
 * megabyte de JSON. O JSON é o MESMO que o iOS lê — um pack gerado uma vez
 * serve às duas plataformas.
 */
@Serializable
data class SamplePack(
    val id: String,
    /** Id da voz do Cadentia que este pack substitui, ou `drums:<kit>`. */
    val voice: String,
    val name: String,
    val license: String,
    val licenseURL: String,
    val source: String,
    val kind: Kind,
    val sampleRate: Double,
    /** Só na bateria: pad do Cadentia → nota GM de percussão. */
    val padNotes: Map<String, Int>? = null,
    val regions: List<Region>,
) {
    @Serializable
    data class Region(
        /** Arquivo de áudio, relativo à pasta do pack. */
        val f: String,
        /** Faixa de teclas que esta região atende, e a nota em que foi gravada. */
        val lo: Int,
        val hi: Int,
        val root: Int,
        /** Faixa de dinâmica, 1…127 como no MIDI. */
        val vlo: Int,
        val vhi: Int,
        /** Índice do round robin dentro da mesma zona e dinâmica. */
        val rr: Int,
        /** Desafinação fina em cents. */
        val tune: Double? = null,
        /** -1…1. */
        val pan: Double? = null,
        /** `[início, fim]` em frames, quando a nota sustenta. */
        val loop: List<Int>? = null,
    )

    @Serializable
    @Suppress("EnumEntryName")
    enum class Kind { melodic, drums }

    /** A família a que o pack pertence, para a tela de configurações agrupar. */
    val family: SampleFamily get() = SampleFamily.of(voice)
}

/**
 * A granularidade da escolha "síntese ou sample" nas configurações.
 *
 * Não é uma por voz: ninguém quer decidir isso 21 vezes. É uma por família,
 * que é como a pessoa pensa no instrumento.
 */
enum class SampleFamily(val id: String) {
    Guitar("guitar"),
    Bass("bass"),
    Drums("drums"),
    Keys("keys");

    val nameKey: String get() = "cadentia.sound.families.$id"

    companion object {
        fun of(voice: String): SampleFamily = when {
            voice.startsWith("drums") -> Drums
            voice.startsWith("bass") -> Bass
            voice.startsWith("guitar") -> Guitar
            else -> Keys
        }

        fun from(id: String): SampleFamily? = entries.firstOrNull { it.id == id }
    }
}

/**
 * As famílias que devem tocar com sample. Ids desconhecidos são ignorados:
 * uma versão futura pode ter uma família que esta não tem.
 */
val AppSettings.enabledSampleFamilies: Set<SampleFamily>
    get() = sound.sampled.mapNotNull(SampleFamily::from).toSet()

/**
 * Preserva os ids que esta versão não conhece.
 *
 * A leitura acima já era tolerante; a escrita não era. Ela reconstruía a
 * lista a partir do conjunto tipado, que descarta o desconhecido — então uma
 * versão antiga do app que virasse QUALQUER interruptor apagava, de vez, a
 * família que só a versão nova tem. Compatibilidade que só vale para ler não
 * é compatibilidade.
 */
fun AppSettings.setSampled(family: SampleFamily, on: Boolean) {
    val all = enabledSampleFamilies.toMutableSet()
    if (on) all.add(family) else all.remove(family)
    val known = SampleFamily.entries.map { it.id }.toSet()
    val strangers = sound.sampled.filter { it !in known }
    sound.sampled = (strangers + all.map { it.id }).sorted()
}

/**
 * Escolhe a região que responde por uma nota, uma dinâmica e uma variação.
 *
 * É lógica pura de propósito: dá para testar a escolha inteira sem tocar em
 * arquivo, que é onde os erros de mapeamento realmente moram.
 */
object SampleSelection {
    /** 0…1 → 1…127, como o MIDI conta. */
    fun midiVelocity(velocity: Float): Int = (velocity.coerceIn(0f, 1f) * 126f).roundToInt() + 1

    fun region(pack: SamplePack, note: Int, velocity: Float, variation: Int): SamplePack.Region? {
        if (pack.regions.isEmpty()) return null

        // 1. Zona. Se a nota cai fora de todas, a mais próxima transpõe — é o
        //    que qualquer sampler faz nas pontas do teclado.
        var inZone = pack.regions.filter { note >= it.lo && note <= it.hi }
        if (inZone.isEmpty()) {
            val nearest = pack.regions.minByOrNull { abs(it.root - note) } ?: return null
            inZone = pack.regions.filter { it.root == nearest.root }
        }

        // 2. Dinâmica. Sem camada que cubra, vai a mais próxima em vez de
        //    silêncio — pack podado tem buraco de velocity por construção.
        val vel = midiVelocity(velocity)
        var inVel = inZone.filter { vel >= it.vlo && vel <= it.vhi }
        if (inVel.isEmpty()) {
            val nearest = inZone.minByOrNull { abs(midpoint(it) - vel) } ?: return null
            inVel = inZone.filter { it.vlo == nearest.vlo && it.vhi == nearest.vhi }
        }

        // 3. Round robin.
        val variants = inVel.sortedBy { it.rr }
        if (variants.isEmpty()) return null
        val index = ((variation % variants.size) + variants.size) % variants.size
        return variants[index]
    }

    private fun midpoint(region: SamplePack.Region): Int = (region.vlo + region.vhi) / 2
}
