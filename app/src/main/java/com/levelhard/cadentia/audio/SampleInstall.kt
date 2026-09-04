package com.levelhard.cadentia.audio

import android.content.Context
import android.util.Log
import com.levelhard.cadentia.BuildConfig
import com.levelhard.cadentia.kit.SampleBank
import com.levelhard.cadentia.kit.SamplePack
import java.io.File

/**
 * Onde os bancos de sample moram no aparelho e como entram no `SampleBank`.
 *
 * Desde 04/09/2026 os 38 MB de FLAC dos sete packs CC0 vão DENTRO do pacote,
 * como no iOS (`App/Resources/Samples` no bundle): o Roque abriu o app da
 * loja e não tinha os sons em alta definição. O build copia `samples/` (o que
 * `scripts/fetch-samples.mjs` escreve: `packs.json` + `<id>/manifest.json` +
 * FLAC) para os assets, e a primeira abertura de cada versão copia os assets
 * para `filesDir/samples` ([copyBundled]). O app procura a pasta em dois
 * lugares, nesta ordem:
 *
 *  1. `filesDir/samples` — o que veio do pacote, ou o caminho do QA no
 *     emulador (`adb push samples.tgz /data/local/tmp/` e
 *     `adb shell run-as <app> tar xzf /data/local/tmp/samples.tgz -C files`);
 *  2. `externalFilesDir/samples` — para quem copia a pasta à mão.
 *
 * Pasta ausente continua não sendo erro: o app toca com síntese e a tela de
 * Som diz isso. Mas com os assets no pacote isso só acontece numa build sem
 * `samples/`, e o Gradle recusa essa build.
 */
object SampleInstall {
    private const val TAG = "CadentiaSamples"

    /** Pasta dos packs dentro do APK (`app/build.gradle.kts` copia `samples/`). */
    private const val ASSET_ROOT = "samples"

    /**
     * Carimbo do que já foi copiado dos assets para `filesDir/samples`. Muda
     * com o `versionCode`: uma atualização do app com packs novos recopia; a
     * mesma versão abre direto.
     */
    private const val STAMP = ".bundled"

    fun candidates(context: Context): List<File> = listOfNotNull(
        File(context.filesDir, "samples"),
        context.getExternalFilesDir(null)?.let { File(it, "samples") },
    )

    /** O carimbo que esta build espera encontrar em `filesDir/samples`. */
    private fun expectedStamp(): String = "versionCode=${BuildConfig.VERSION_CODE}"

    /**
     * Os packs desta build já estão em `filesDir/samples`? Se não, a primeira
     * abertura precisa copiar os assets antes de instalar.
     */
    fun isBundledInstalled(context: Context): Boolean {
        val stamp = File(File(context.filesDir, "samples"), STAMP)
        return stamp.isFile && stamp.readText() == expectedStamp()
    }

    /**
     * Copia os packs dos assets para `filesDir/samples` — o equivalente
     * Android de ler `App/Resources/Samples` do bundle no iOS. O
     * `MediaExtractor` decodifica por caminho, então os FLAC precisam existir
     * como arquivo; os 38 MB são copiados UMA vez por versão, em `.parcial` e
     * publicados por rename, como o `StemCache` faz. Devolve false quando o
     * pacote não tem os assets (build sem `samples/`) — aí vale o que houver
     * em `filesDir` ou a síntese.
     */
    fun copyBundled(context: Context): Boolean {
        val assets = context.assets
        val index = runCatching { assets.open("$ASSET_ROOT/packs.json").use { it.readBytes() } }.getOrNull()
        if (index == null) {
            Log.i(TAG, "pacote sem $ASSET_ROOT/packs.json nos assets")
            return false
        }
        val target = File(context.filesDir, "samples")
        val staging = File(context.filesDir, "samples.parcial")
        staging.deleteRecursively()
        staging.mkdirs()
        val started = System.nanoTime()
        var files = 0
        var bytes = 0L
        fun copyTree(path: String, into: File) {
            val children = assets.list(path).orEmpty()
            if (children.isEmpty()) {
                into.parentFile?.mkdirs()
                assets.open(path).use { input -> into.outputStream().use { bytes += input.copyTo(it) } }
                files++
                return
            }
            for (child in children) copyTree("$path/$child", File(into, child))
        }
        copyTree(ASSET_ROOT, staging)
        File(staging, STAMP).writeText(expectedStamp())
        // Publicação por rename: quem ler `filesDir/samples` vê tudo ou nada.
        target.deleteRecursively()
        if (!staging.renameTo(target)) {
            Log.w(TAG, "rename de ${staging.name} para ${target.name} falhou")
            return false
        }
        val ms = (System.nanoTime() - started) / 1_000_000
        Log.i(TAG, "$files arquivos ($bytes bytes) copiados dos assets em $ms ms")
        return true
    }

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
