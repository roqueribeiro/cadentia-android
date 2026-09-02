package com.levelhard.cadentia.features.stems

import android.content.Context
import com.levelhard.cadentia.kit.RecentSongs
import com.levelhard.cadentia.kit.Setlists
import com.levelhard.cadentia.kit.StemMixMemory
import java.io.File
import kotlinx.serialization.json.Json

/**
 * Persistência da área de stems — os papéis de `RecentSongsStore`,
 * `StemMixMemoryStore` e `SetlistStore` do iOS, sobre os tipos puros do
 * :kit. SharedPreferences para as listas (bytes, não segredo). As faixas
 * separadas moram no [StemCache] (1.16: `filesDir`, publicação por rename,
 * limpeza só quando o aparelho pede) — os métodos `*Cache` daqui só
 * delegam, para quem ainda chama pelo nome antigo.
 */
class StemStores(context: Context) {
    private val prefs = context.getSharedPreferences("cadentia.stems", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** As faixas separadas em disco. */
    val cache = StemCache(context)

    private companion object {
        const val KEY_RECENT = "cadentia.stems.recent.v1"
        const val KEY_MIX = "cadentia.stems.mixMemory.v1"
        const val KEY_SETLISTS = "cadentia.stems.setlists.v1"
    }

    // ---- listas ----

    fun loadRecent(): RecentSongs = load(KEY_RECENT, RecentSongs.serializer()) ?: RecentSongs()

    fun saveRecent(recent: RecentSongs) = save(KEY_RECENT, RecentSongs.serializer(), recent)

    fun loadMixMemory(): StemMixMemory = load(KEY_MIX, StemMixMemory.serializer()) ?: StemMixMemory()

    fun saveMixMemory(memory: StemMixMemory) = save(KEY_MIX, StemMixMemory.serializer(), memory)

    fun loadSetlists(): Setlists = load(KEY_SETLISTS, Setlists.serializer()) ?: Setlists()

    fun saveSetlists(lists: Setlists) = save(KEY_SETLISTS, Setlists.serializer(), lists)

    private fun <T> load(key: String, serializer: kotlinx.serialization.KSerializer<T>): T? {
        val raw = prefs.getString(key, null) ?: return null
        // Uma entrada ilegível não pode levar a lista inteira: quem não
        // decodifica volta ao vazio, que é o comportamento de um atalho.
        return runCatching { json.decodeFromString(serializer, raw) }.getOrNull()
    }

    private fun <T> save(key: String, serializer: kotlinx.serialization.KSerializer<T>, value: T) {
        runCatching { prefs.edit().putString(key, json.encodeToString(serializer, value)).apply() }
    }

    // ---- cache das faixas separadas (delegação ao StemCache) ----

    fun cacheDirectory(songId: String): File = cache.directory(songId)

    fun isCacheComplete(songId: String): Boolean = cache.isComplete(songId)

    fun touchCache(songId: String) = cache.touch(songId)

    fun removeCache(songId: String) = cache.remove(songId)

    /** Abre espaço só quando o aparelho pede; `keeping` nunca sai. */
    fun trimCache(keeping: Set<String>) = cache.trim(keeping)
}
