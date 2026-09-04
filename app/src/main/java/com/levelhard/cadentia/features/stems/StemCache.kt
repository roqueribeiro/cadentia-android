package com.levelhard.cadentia.features.stems

import android.content.Context
import android.os.StatFs
import android.util.Log
import com.levelhard.cadentia.kit.StemCachePolicy
import com.levelhard.cadentia.kit.StemPipeline
import java.io.File

/**
 * Guarda as faixas já separadas de cada música, para reabrir ser instantâneo
 * em vez de esperar a separação de novo — port do `StemCache.swift` (1.16).
 *
 * ## Onde mora, e por que mudou
 *
 * Isto ficava em `cacheDir`, pelo argumento correto de que faixa separada é
 * dado regenerável e o sistema pode jogar fora sob pressão de espaço. O
 * argumento tinha um furo: regenerar custa **baixar a música de novo**, e o
 * founder separa uma playlist inteira para estudar. Então passou para
 * `filesDir` (o Application Support do iOS), fora do backup automático
 * (`res/xml/backup_rules.xml`: são gigabytes derivados de música que a pessoa
 * já tem). O que o sistema deixou de decidir sozinho, este arquivo decide
 * explicitamente, em [trim]. A pasta velha em `cacheDir` é migrada na
 * primeira vez que alguém pergunta onde as faixas estão.
 *
 * ## Escrever num lugar e publicar no outro
 *
 * Uma separação é ESCRITA em `<id>.parcial` e vira `<id>` com um `rename`:
 * ou a pasta está inteira, ou não está. Sem isto uma separação interrompida
 * (o app morto, a leva cancelada, o disco cheio) deixava quatro arquivos com
 * tamanho maior que zero, e a música ficava "pronta" para sempre tocando
 * quarenta segundos dos quatro minutos.
 */
class StemCache(context: Context) {
    private val appContext = context.applicationContext
    val root: File = File(appContext.filesDir, "stems")
    private val legacyRoot = File(appContext.cacheDir, "stems")
    private var migrated = false

    /** Quantas músicas a última limpeza precisou apagar. Zero é o caso normal. */
    var lastTrimEvicted: Int = 0
        private set

    /** Traz o que já estava separado para o lugar novo, uma vez só. Move, não copia: são gigabytes. */
    @Synchronized
    private fun migrate() {
        if (migrated) return
        migrated = true
        if (!legacyRoot.isDirectory) return
        root.parentFile?.mkdirs()
        if (!root.exists()) {
            if (legacyRoot.renameTo(root)) {
                Log.i(TAG, "faixas migradas de cacheDir para filesDir")
                return
            }
        }
        root.mkdirs()
        for (folder in legacyRoot.listFiles() ?: emptyArray()) {
            val target = File(root, folder.name)
            if (!target.exists()) folder.renameTo(target)
        }
        legacyRoot.deleteRecursively()
        Log.i(TAG, "faixas migradas de cacheDir para filesDir (pasta a pasta)")
    }

    fun directory(songId: String): File {
        migrate()
        return File(root, songId)
    }

    /** Onde uma separação é ESCRITA antes de ser publicada. */
    fun stagingDirectory(songId: String): File {
        migrate()
        return File(root, "$songId.$STAGING_EXTENSION")
    }

    /**
     * Publica a pasta parcial como a pasta de verdade: um `rename` no mesmo
     * volume — ou a música está inteira, ou não está. Devolve `false` se o
     * sistema de arquivos recusou.
     */
    fun publish(songId: String): Boolean {
        val staging = stagingDirectory(songId)
        val target = directory(songId)
        target.deleteRecursively()
        return staging.renameTo(target)
    }

    /** A faixa `name` na pasta, no formato que estiver lá (WAV das separações antigas, AAC das novas). */
    fun existingTrack(folder: File, name: String): File? = Companion.existingTrack(folder, name)

    /**
     * Só conta como pronta se as **quatro** faixas estiverem lá. Uma separação
     * interrompida deixa a pasta pela metade, e reusar isso daria uma música
     * sem baixo, sem erro nenhum na tela.
     */
    fun isComplete(songId: String): Boolean {
        val folder = directory(songId)
        return StemPipeline.sourceNames.all { existingTrack(folder, it) != null }
    }

    /** Marca a pasta como usada agora, para a limpeza saber o que preservar. */
    fun touch(songId: String) {
        directory(songId).setLastModified(System.currentTimeMillis())
    }

    fun remove(songId: String) {
        directory(songId).deleteRecursively()
        stagingDirectory(songId).deleteRecursively()
    }

    fun removeAll() {
        migrate()
        root.deleteRecursively()
    }

    /** Quantas músicas estão separadas em disco, e quanto ocupam. */
    fun usage(): Usage {
        val entries = folders()
        return Usage(entries.size, entries.sumOf { sizeOf(it) })
    }

    data class Usage(val songs: Int, val bytes: Long)

    /**
     * Espaço livre no volume das faixas, em bytes. Zero quando não deu para
     * medir — e zero, para a regra, é "não apague nada".
     */
    fun freeBytes(): Long {
        migrate()
        root.mkdirs()
        return runCatching { StatFs(root.path).availableBytes }.getOrDefault(0L)
    }

    /**
     * Joga fora separações que ficaram pela metade e revisa o espaço. Chamado
     * quando a tela aparece — momento em que nenhuma separação está em curso,
     * então toda pasta `.parcial` que existir é entulho de uma tentativa que
     * morreu. Fora da thread principal: é uma volta em todas as pastas.
     */
    fun sweep(protected: Set<String> = emptySet()) {
        migrate()
        for (file in root.listFiles() ?: emptyArray()) {
            if (file.extension == STAGING_EXTENSION) file.deleteRecursively()
        }
        trim(protected)
    }

    /**
     * Abre espaço **só quando o aparelho pede**. A regra pura mora em
     * [StemCachePolicy]; aqui só se apaga. `lastTrimEvicted` guarda quantas
     * saíram, para a tela poder dizer em vez de a pessoa descobrir sozinha.
     */
    fun trim(protected: Set<String> = emptySet()) {
        val entries = folders().map { folder ->
            StemCachePolicy.Entry(id = folder.name, bytes = sizeOf(folder), lastUsedEpochMillis = folder.lastModified())
        }
        val doomed = StemCachePolicy.evictions(entries, freeBytes(), StemCachePolicy.FREE_SPACE_RESERVE, protected)
        for (id in doomed) File(root, id).deleteRecursively()
        lastTrimEvicted = doomed.size
        if (doomed.isNotEmpty()) Log.i(TAG, "limpeza apagou ${doomed.size} música(s) para voltar à reserva")
    }

    private fun folders(): List<File> {
        migrate()
        return (root.listFiles() ?: emptyArray()).filter { it.isDirectory && it.extension != STAGING_EXTENSION }
    }

    private fun sizeOf(folder: File): Long = folder.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    companion object {
        const val STAGING_EXTENSION = "parcial"
        private const val TAG = "CadentiaStems"

        /**
         * O que o player daqui abre, na ordem de preferência. O iOS 1.16 grava
         * AAC (33 MB por música contra 323 em WAV float32) e aceita os dois;
         * aqui o separador grava `.m4a` (`StemTrackCodec`) e as separações
         * antigas continuam em `.wav`.
         */
        val TRACK_EXTENSIONS = listOf(StemTrackCodec.EXTENSION, "wav")

        /** O `StemSeparator.existingTrackURL` do iOS: a primeira extensão da lista que existe com conteúdo. */
        fun existingTrack(folder: File, name: String): File? =
            TRACK_EXTENSIONS.map { File(folder, "$name.$it") }.firstOrNull { it.isFile && it.length() > 0 }
    }
}
