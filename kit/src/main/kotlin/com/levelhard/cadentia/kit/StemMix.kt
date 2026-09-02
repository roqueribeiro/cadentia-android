package com.levelhard.cadentia.kit

import kotlinx.serialization.Serializable

/**
 * Decide quais faixas separadas são audíveis, dado o que o usuário mutou e
 * soloou — port 1:1 do `StemMix.swift`.
 *
 * Saiu do player porque é a única parte com uma regra que erra sem ninguém
 * notar: solo tem que vencer mute, e qualquer solo em qualquer lugar tem que
 * calar tudo que não está em solo. Inverta isso e "ouvir só o baixo" toca a
 * banda inteira baixinho.
 */
object StemMix {
    data class State(
        val id: String,
        var volume: Float = 1f,
        var isMuted: Boolean = false,
        var isSoloed: Boolean = false,
    )

    /** O ganho de cada faixa, chaveado por id. */
    fun gains(tracks: List<State>): Map<String, Float> {
        val anySolo = tracks.any { it.isSoloed }
        val result = mutableMapOf<String, Float>()
        for (track in tracks) {
            val audible = if (anySolo) track.isSoloed else !track.isMuted
            result[track.id] = if (audible) track.volume else 0f
        }
        return result
    }
}

/**
 * Como a música estava quando você parou: volumes, mudos, solos, tom,
 * velocidade e o loop de estudo — port do `StemMixSnapshot`.
 *
 * É a resposta ao incômodo que o feedback público apontou no concorrente:
 * "tenho que gerar o mp3 e colocar os arquivos num drive". Lá, o resultado
 * do ajuste vira um arquivo solto; aqui, a música reabre exatamente como
 * ficou, porque a configuração mora junto da identidade da música.
 */
@Serializable
data class StemMixSnapshot(
    var volumes: Map<String, Float> = emptyMap(),
    var muted: Set<String> = emptySet(),
    var soloed: Set<String> = emptySet(),
    var semitones: Int = 0,
    var speed: Double = 1.0,
    var loop: PracticeLoop? = null,
) {
    /** Sem nada fora do neutro, não vale a pena guardar (nem restaurar). */
    val isNeutral: Boolean
        get() = muted.isEmpty() && soloed.isEmpty() && semitones == 0 &&
            kotlin.math.abs(speed - 1) < 0.001 && loop == null &&
            volumes.values.all { kotlin.math.abs(it - 1) < 0.001 }
}

/** Guarda um ajuste por música, pela mesma identidade do histórico. */
@Serializable
data class StemMixMemory(
    private val entries: MutableMap<String, Entry> = mutableMapOf(),
) {
    @Serializable
    data class Entry(val snapshot: StemMixSnapshot, val savedAtEpochMillis: Long)

    companion object {
        /**
         * Um ajuste é pequeno (bytes), mas sem teto isto cresceria para
         * sempre. 200 músicas de estudo ativas é mais do que qualquer
         * repertório real.
         */
        const val CAPACITY = 200
    }

    fun snapshot(songId: String): StemMixSnapshot? = entries[songId]?.snapshot

    fun remember(snapshot: StemMixSnapshot, songId: String, atEpochMillis: Long = System.currentTimeMillis()) {
        // Ajuste neutro apaga a entrada: voltar tudo ao normal também é uma
        // decisão que merece sobreviver ao fechamento do app.
        if (snapshot.isNeutral) {
            entries.remove(songId)
            return
        }
        entries[songId] = Entry(snapshot, atEpochMillis)
        if (entries.size > CAPACITY) {
            entries.minByOrNull { it.value.savedAtEpochMillis }?.let { entries.remove(it.key) }
        }
    }

    fun forget(songId: String) {
        entries.remove(songId)
    }

    val count: Int get() = entries.size
}
