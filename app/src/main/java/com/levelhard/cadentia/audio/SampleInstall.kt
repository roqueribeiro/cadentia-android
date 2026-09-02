package com.levelhard.cadentia.audio

import android.content.Context
import android.util.Log
import com.levelhard.cadentia.kit.SampleBank
import com.levelhard.cadentia.kit.SamplePack
import java.io.File

/**
 * Onde os bancos de sample moram no aparelho e como entram no `SampleBank`.
 *
 * Os ~65 MB de FLAC dos sete packs CC0 ficam FORA do APK, sempre (ver goal,
 * fase 7). O app procura a pasta `samples/` (com `packs.json` e um
 * `<id>/manifest.json` por pack — o que `scripts/fetch-samples.mjs` escreve)
 * em dois lugares, nesta ordem:
 *
 *  1. `filesDir/samples` — o destino de uma entrega gerenciada pelo app
 *     (download ou asset pack), quando ela existir;
 *  2. `externalFilesDir/samples` — o caminho que o QA alcança por
 *     `adb push` sem root (`/sdcard/Android/data/<app>/files/samples`).
 *
 * Pasta ausente não é erro: o app toca com síntese e a tela de Som diz isso.
 */
object SampleInstall {
    private const val TAG = "Cadentia/Samples"

    fun candidates(context: Context): List<File> = listOfNotNull(
        File(context.filesDir, "samples"),
        context.getExternalFilesDir(null)?.let { File(it, "samples") },
    )

    /** A primeira pasta com `packs.json`, ou null. */
    fun directory(context: Context): File? =
        candidates(context).firstOrNull { File(it, "packs.json").isFile }

    /**
     * Instala os packs no banco do app. Lê um índice e alguns manifestos —
     * kilobytes de JSON, sem áudio nenhum; o PCM só sai do disco na primeira
     * nota de cada zona. Devolve o que entrou.
     */
    fun install(context: Context, bank: SampleBank = SampleBank.shared): List<SamplePack> {
        bank.decoder = MediaCodecSampleDecoder()
        val dir = directory(context)
        if (dir == null) {
            Log.i(TAG, "nenhum packs.json em ${candidates(context).joinToString { it.path }}")
            return emptyList()
        }
        val loaded = bank.install(dir)
        Log.i(TAG, "${loaded.size} packs instalados de ${dir.path}: ${loaded.joinToString { it.id }}")
        return loaded
    }
}
