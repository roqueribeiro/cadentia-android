package com.levelhard.cadentia.features.stems

import android.content.Context
import com.levelhard.cadentia.kit.RecentSongs
import com.levelhard.cadentia.kit.Setlists
import com.levelhard.cadentia.kit.StemCachePolicy
import com.levelhard.cadentia.kit.StemMixMemory
import com.levelhard.cadentia.kit.StemPipeline
import java.io.File
import kotlinx.serialization.json.Json

/**
 * Persistência da área de stems — os papéis de `RecentSongsStore`,
 * `StemMixMemoryStore`, `SetlistStore` e `StemCache` do iOS, sobre os tipos
 * puros do :kit. SharedPreferences para as listas (bytes, não segredo) e
 * cacheDir para as faixas separadas (dado regenerável: o sistema pode
 * apagar sob pressão, e o app trata ausência como "separar de novo").
 */
class StemStores(context: Context) {
    private val prefs = context.getSharedPreferences("cadentia.stems", Context.MODE_PRIVATE)
    private val cacheRoot = File(context.cacheDir, "stems")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

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

    // ---- cache das faixas separadas ----

    fun cacheDirectory(songId: String): File = File(cacheRoot, songId)

    /**
     * Só conta como pronta com as QUATRO faixas lá: uma separação
     * interrompida deixa a pasta pela metade, e reusar isso daria uma música
     * sem baixo, sem erro nenhum na tela.
     */
    fun isCacheComplete(songId: String): Boolean {
        val folder = cacheDirectory(songId)
        return StemPipeline.sourceNames.all { name ->
            File(folder, "$name.wav").let { it.exists() && it.length() > 0 }
        }
    }

    fun touchCache(songId: String) {
        cacheDirectory(songId).setLastModified(System.currentTimeMillis())
    }

    fun removeCache(songId: String) {
        cacheDirectory(songId).deleteRecursively()
    }

    /** A regra pura do :kit decide quem sai; aqui só se apaga. */
    fun trimCache(keeping: Set<String>) {
        val folders = cacheRoot.listFiles() ?: return
        val entries = folders.filter { it.isDirectory }.map { folder ->
            StemCachePolicy.Entry(
                songId = folder.name,
                bytes = folder.listFiles()?.sumOf { it.length() } ?: 0L,
                usedAtEpochMillis = folder.lastModified(),
            )
        }
        for (doomed in StemCachePolicy.evict(entries, keeping)) {
            File(cacheRoot, doomed).deleteRecursively()
        }
    }
}
