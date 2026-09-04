package com.levelhard.cadentia.features.stems

import android.content.Context
import android.util.Log
import com.levelhard.cadentia.BuildConfig
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Traz o `separator.onnx` (174 MB) para `filesDir/models` na primeira
 * separação — a entrega que o iOS não precisa porque leva o `.mlmodelc` no
 * bundle, e que aqui fica fora do pacote para o app não pesar 300 MB na loja.
 *
 * Regras: baixa em `.parcial` e retoma de onde parou (`Range`), confere o
 * SHA-256 quando ele é conhecido, e só então publica por rename. A URL e o
 * hash vêm do `BuildConfig` (`STEM_MODEL_URL`/`STEM_MODEL_SHA256`, definidos em
 * `gradle.properties` ou no ambiente do build); sem URL, o app fica no
 * `modelMissing` honesto de sempre.
 */
object StemModelDownloader {
    private const val TAG = "CadentiaStems"

    val isConfigured: Boolean get() = BuildConfig.STEM_MODEL_URL.isNotBlank()

    class DownloadFailed(message: String, cause: Throwable? = null) : IOException(message, cause)

    /**
     * Garante o modelo no disco. `progress(bytes, total)` a cada bloco (total
     * pode ser -1 quando o servidor não informa). Lança [DownloadFailed] em
     * rede/hash; cancelamento chega por `shouldContinue`.
     */
    fun ensure(context: Context, shouldContinue: () -> Boolean, progress: (Long, Long) -> Unit) {
        if (StemModelStore.isAvailable(context)) return
        if (!isConfigured) throw DownloadFailed("sem URL de modelo nesta build")
        val target = StemModelStore.file(context)
        val partial = File(target.path + ".parcial")
        target.parentFile?.mkdirs()

        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        val have = if (partial.isFile) partial.length() else 0L
        val request = Request.Builder().url(BuildConfig.STEM_MODEL_URL).apply {
            if (have > 0) header("Range", "bytes=$have-")
        }.build()

        val started = System.nanoTime()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw DownloadFailed("HTTP ${response.code} ao baixar o modelo")
                val body = response.body ?: throw DownloadFailed("resposta sem corpo")
                val resumed = response.code == 206
                val total = if (resumed) have + body.contentLength() else body.contentLength()
                RandomAccessFile(partial, "rw").use { out ->
                    if (resumed) out.seek(have) else out.setLength(0)
                    var written = if (resumed) have else 0L
                    val buffer = ByteArray(256 * 1024)
                    body.byteStream().use { input ->
                        while (true) {
                            if (!shouldContinue()) throw InterruptedException("download cancelado")
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            written += n
                            progress(written, total)
                        }
                    }
                }
            }
        } catch (error: IOException) {
            throw DownloadFailed(error.message ?: "falha de rede", error)
        }

        val expected = BuildConfig.STEM_MODEL_SHA256.lowercase()
        if (expected.isNotBlank()) {
            val digest = sha256(partial)
            if (digest != expected) {
                partial.delete()
                throw DownloadFailed("hash do modelo não bate ($digest)")
            }
        }
        if (!partial.renameTo(target)) throw DownloadFailed("não deu para publicar o modelo")
        val seconds = (System.nanoTime() - started) / 1e9
        Log.i(TAG, "modelo baixado: ${target.length() / 1_000_000} MB em ${"%.0f".format(seconds)} s")
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                md.update(buffer, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
