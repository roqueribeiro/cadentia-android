package com.levelhard.cadentia.kit

import java.util.UUID
import kotlin.random.Random
import kotlinx.serialization.Serializable

/**
 * Um repertório: as músicas de um show, de uma banda, de um ensaio — port do
 * `Setlists.swift`.
 *
 * O repertório guarda CÓPIAS completas das músicas (identidade + origem),
 * não referências ao histórico: as Recentes têm teto de 30 e giram; o
 * repertório de um show não pode perder uma música porque outras trinta
 * passaram na frente. A configuração de cada música (faixa mutada, tom,
 * velocidade, loop) NÃO mora aqui: mora na memória por música, chaveada pela
 * mesma identidade — é o que faz duplicar um repertório já vir configurado.
 */
@Serializable
data class Setlist(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var songs: MutableList<RecentSong> = mutableListOf(),
    var createdAtEpochMillis: Long = System.currentTimeMillis(),
)

/** A coleção de repertórios, com as regras de mutação num lugar só. */
@Serializable
data class Setlists(
    private val entries: MutableList<Setlist> = mutableListOf(),
) {
    companion object {
        /** Tetos generosos e finitos: cobrem qualquer agenda real. */
        const val MAX_LISTS = 50
        const val MAX_SONGS = 100
    }

    val lists: List<Setlist> get() = entries

    fun create(name: String): Setlist? {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || entries.size >= MAX_LISTS) return null
        val list = Setlist(name = trimmed)
        // Novos em cima: o recém-criado é o que a pessoa vai encher.
        entries.add(0, list)
        return list
    }

    /**
     * Mesma música duas vezes não é repertório maior, é engano de toque: a
     * segunda adição é ignorada em silêncio.
     */
    fun add(song: RecentSong, toList: String) {
        val list = entries.firstOrNull { it.id == toList } ?: return
        if (list.songs.size >= MAX_SONGS || list.songs.any { it.id == song.id }) return
        list.songs.add(song)
    }

    fun removeSong(songId: String, fromList: String) {
        entries.firstOrNull { it.id == fromList }?.songs?.removeAll { it.id == songId }
    }

    /**
     * A cópia para o próximo show: id novo, nome novo, MESMAS músicas. Como
     * a configuração é por música, a cópia já nasce configurada.
     */
    fun duplicate(listId: String, name: String): Setlist? {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || entries.size >= MAX_LISTS) return null
        val source = entries.firstOrNull { it.id == listId } ?: return null
        val copy = Setlist(name = trimmed, songs = source.songs.toMutableList())
        entries.add(0, copy)
        return copy
    }

    fun rename(listId: String, to: String) {
        val trimmed = to.trim()
        if (trimmed.isEmpty()) return
        entries.firstOrNull { it.id == listId }?.name = trimmed
    }

    fun delete(listId: String) {
        entries.removeAll { it.id == listId }
    }
}

/**
 * A fila de reprodução de um repertório: em ordem, como no show, ou
 * embaralhada, para estudar sem viciar na sequência — port do `SetQueue`.
 *
 * O embaralhado é uma PERMUTAÇÃO, não sorteio a cada música: toca cada uma
 * exatamente uma vez. O gerador entra por parâmetro para o teste poder
 * provar a permutação com um embaralhado determinístico.
 */
class SetQueue private constructor(
    val listID: String,
    val listName: String,
    val mode: Mode,
    val order: List<RecentSong>,
    private var indexInternal: Int,
) {
    enum class Mode { Ordered, Shuffled }

    companion object {
        /**
         * Fila em ordem ou embaralhada, opcionalmente começando de uma música
         * específica: tocar do meio do set significa "o show começa aqui",
         * não "toque só essa".
         */
        fun of(
            list: Setlist,
            mode: Mode,
            startAt: String? = null,
            random: Random = Random.Default,
        ): SetQueue? {
            if (list.songs.isEmpty()) return null
            return when (mode) {
                Mode.Ordered -> {
                    val index = startAt?.let { id -> list.songs.indexOfFirst { it.id == id } }
                        ?.takeIf { it >= 0 } ?: 0
                    SetQueue(list.id, list.name, mode, list.songs.toList(), index)
                }
                Mode.Shuffled -> {
                    val shuffled = list.songs.shuffled(random).toMutableList()
                    // Começar de uma música no embaralhado: ela vai para a
                    // frente e o resto fica sorteado.
                    startAt?.let { id ->
                        val position = shuffled.indexOfFirst { it.id == id }
                        if (position > 0) {
                            val tmp = shuffled[0]
                            shuffled[0] = shuffled[position]
                            shuffled[position] = tmp
                        }
                    }
                    SetQueue(list.id, list.name, mode, shuffled, 0)
                }
            }
        }
    }

    val index: Int get() = indexInternal

    val current: RecentSong? get() = order.getOrNull(indexInternal)

    /** Posição humana: "2 de 8". */
    val position: Pair<Int, Int> get() = (indexInternal + 1) to order.size

    /**
     * Próxima música, ou null quando o set acabou. O fim é fim: repetir o
     * set inteiro sozinho seria surpresa às 2h da manhã no ensaio.
     */
    fun advance(): RecentSong? {
        if (indexInternal + 1 >= order.size) return null
        indexInternal += 1
        return order[indexInternal]
    }

    fun goBack(): RecentSong? {
        if (indexInternal <= 0) return null
        indexInternal -= 1
        return order[indexInternal]
    }
}
