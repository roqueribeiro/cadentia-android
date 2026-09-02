package com.levelhard.cadentia.kit

import java.text.Normalizer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Uma música que já foi aberta, e o suficiente para achá-la de novo — port
 * do `RecentSongs.swift`.
 */
@Serializable
data class RecentSong(
    val id: String,
    var title: String,
    var source: Source,
    var lastOpenedEpochMillis: Long,
) {
    /**
     * De onde a música veio. Guardar isto (e não o áudio) é o que permite
     * reabrir sem o usuário procurar de novo.
     */
    @Serializable
    sealed class Source {
        /**
         * Arquivo do próprio aparelho. No Android o equivalente do bookmark
         * do iOS é a URI persistível do SAF (takePersistableUriPermission):
         * o caminho cru não sobrevive ao reinício, a URI persistida sim.
         */
        @Serializable
        @SerialName("device")
        data class Device(val persistedUri: String, val filename: String) : Source()

        @Serializable
        @SerialName("roqueOSFile")
        data class RoqueOSFile(val path: String, val downloadURL: String) : Source()

        /**
         * Servidor e disco SEPARADOS: o id de um disco só é único dentro do
         * servidor dele, e juntar os dois numa string obrigava a reparsear.
         */
        @Serializable
        @SerialName("mappedDisk")
        data class MappedDisk(val serverID: String, val diskID: String, val path: String) : Source()

        /**
         * A pasta `/shared` do servidor. Não tem disco: ela mora no disco do
         * próprio roqueos-server e sai por outro módulo (`/fs`).
         */
        @Serializable
        @SerialName("serverShared")
        data class ServerShared(val serverID: String, val path: String) : Source()

        /**
         * Google Drive. Guarda o id do arquivo, que é o que a API entende; o
         * nome vai junto porque o id não diz nada a um humano.
         */
        @Serializable
        @SerialName("googleDrive")
        data class GoogleDrive(val fileID: String, val name: String) : Source()

        /**
         * Identidade estável da música, independente de onde ela está no
         * disco agora. É por ela que as faixas separadas são reencontradas.
         */
        val identity: String
            get() = when (this) {
                is Device -> "device:$filename"
                is RoqueOSFile -> "roqueos:$path"
                is MappedDisk -> "disco:$serverID:$diskID:$path"
                is ServerShared -> "shared:$serverID:$path"
                is GoogleDrive -> "drive:$fileID"
            }
    }

    constructor(title: String, source: Source, lastOpenedEpochMillis: Long) : this(
        id = identifier(source), title = title, source = source,
        lastOpenedEpochMillis = lastOpenedEpochMillis,
    )

    companion object {
        /** Chave curta e estável, segura para nome de pasta (FNV-1a em base 36). */
        fun identifier(source: Source): String {
            var hash = 0xcbf29ce484222325UL
            for (byte in source.identity.encodeToByteArray()) {
                hash = (hash xor byte.toUByte().toULong()) * 0x00000100000001b3UL
            }
            return hash.toString(36)
        }
    }
}

/**
 * A lista de músicas recentes: sem repetição, mais recente primeiro,
 * limitada. Pura de propósito: as três regras que importam (não duplicar,
 * reordenar ao reabrir, e não crescer para sempre) são fáceis de errar de um
 * jeito que só aparece semanas depois.
 */
@Serializable
data class RecentSongs(
    private val entries: MutableList<RecentSong> = mutableListOf(),
) {
    companion object {
        /** Trinta cobre meses de uso sem virar uma lista que ninguém percorre. */
        const val LIMIT = 30

        /** Quem caiu fora do limite: não precisa mais de faixas guardadas em disco. */
        fun evicted(songs: List<RecentSong>): List<RecentSong> =
            if (songs.size > LIMIT) songs.drop(LIMIT) else emptyList()
    }

    val songs: List<RecentSong> get() = entries

    /** Reabrir uma música que já está na lista MOVE para o topo, sem duplicar. */
    fun remember(song: RecentSong) {
        entries.removeAll { it.id == song.id }
        entries.add(0, song)
        while (entries.size > LIMIT) entries.removeAt(entries.size - 1)
    }

    fun forget(id: String) {
        entries.removeAll { it.id == id }
    }

    fun clear() {
        entries.clear()
    }
}

/**
 * Busca de música por título, do jeito que músico digita: sem acento, sem
 * caixa, no meio do nome. "agua" acha "Água de Beber".
 */
object SongSearch {
    private fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase()

    fun filter(songs: List<RecentSong>, query: String): List<RecentSong> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return songs
        val needle = fold(trimmed)
        return songs.filter { fold(it.title).contains(needle) }
    }
}
