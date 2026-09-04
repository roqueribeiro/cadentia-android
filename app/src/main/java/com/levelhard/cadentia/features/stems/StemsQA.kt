package com.levelhard.cadentia.features.stems

import android.util.Log
import com.levelhard.cadentia.kit.RecentSong
import com.levelhard.cadentia.kit.StemPipeline
import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Semeia quatro faixas separadas direto no cache, para o player poder ser
 * testado de verdade sem passar pela separação — port do `StemsQA.swift`.
 *
 * No iOS existe porque o simulador não tem Core ML por GPU. Aqui a separação
 * roda no emulador, mas custa 12 s por música e um modelo de 174 MB no
 * aparelho: para a captura de loja e para o teste do player, da onda, dos
 * medidores e do mixer, quatro faixas sintéticas em 1 s valem mais.
 *
 * As quatro faixas têm conteúdos deliberadamente diferentes (grave contínuo,
 * pulso seco, acorde, voz simulada), então isolar uma muda o nível e o
 * espectro de um jeito verificável, e não só "toca alguma coisa".
 *
 * As duas demos são semeadas em formatos DIFERENTES, de propósito: a primeira
 * no que o separador escreve hoje (AAC `.m4a`), a segunda no WAV que ele
 * escrevia antes. Quem separou músicas na versão anterior tem pastas de WAV,
 * e elas têm que continuar tocando.
 */
object StemsQA {
    private const val TAG = "CadentiaStems"

    /** O nome aparece na tela, e a tela vira captura de loja: nada de "QA Demo" no topo. */
    val song = RecentSong(
        title = "Cadentia Demo",
        source = RecentSong.Source.Device(persistedUri = "qa://demo/1", filename = "qa-demo.wav"),
        lastOpenedEpochMillis = 0L,
    )

    /** Segunda música, para o QA do repertório: avanço automático só se prova com DUAS na fila. */
    val secondSong = RecentSong(
        title = "Cadentia Demo 2",
        source = RecentSong.Source.Device(persistedUri = "qa://demo/2", filename = "qa-demo-2.wav"),
        lastOpenedEpochMillis = 1_000L,
    )

    /**
     * 20 s e não 8: com 8 a demo acabava no meio de uma verificação à mão e o
     * player voltava para o começo, o que parece "parou sozinho".
     */
    private const val SECONDS = 20
    private const val RATE = StemPipeline.SAMPLE_RATE

    /** Garante as faixas da demo no cache (e da segunda, se pedida). Devolve false se falhou. */
    fun seed(cache: StemCache, second: Boolean): Boolean {
        var ok = true
        if (!cache.isComplete(song.id)) ok = seed(cache, song, legacyWav = false)
        if (second && !cache.isComplete(secondSong.id)) ok = seed(cache, secondSong, legacyWav = true) && ok
        return ok
    }

    private fun seed(cache: StemCache, song: RecentSong, legacyWav: Boolean): Boolean {
        val staging = cache.stagingDirectory(song.id)
        staging.deleteRecursively()
        staging.mkdirs()
        val started = System.nanoTime()
        for (name in StemPipeline.sourceNames) {
            val wav = File(staging, "$name.wav")
            write(name, wav)
        }
        if (!legacyWav) StemTrackCodec.encodeFolder(staging, StemPipeline.sourceNames)
        val published = cache.publish(song.id)
        Log.i(TAG, "demo ${song.title} semeada (${if (legacyWav) "wav" else "aac"}) em ${(System.nanoTime() - started) / 1_000_000} ms: $published")
        return published
    }

    private fun write(name: String, out: File) {
        val frames = RATE * SECONDS
        val writer = StereoWavWriter(out, RATE)
        val block = 8192
        val left = FloatArray(block)
        val right = FloatArray(block)
        var frame = 0
        while (frame < frames) {
            val count = minOf(block, frames - frame)
            for (i in 0 until count) {
                val value = sample(name, (frame + i).toDouble() / RATE).toFloat()
                left[i] = value
                right[i] = value
            }
            writer.write(left, right, count)
            frame += count
        }
        writer.finish()
    }

    /** Cada faixa mora numa região diferente do espectro (os mesmos sinais do iOS). */
    private fun sample(name: String, time: Double): Double = when (name) {
        "bass" -> 0.35 * sin(2 * PI * 80 * time)
        "drums" -> {
            // Pulso curto a cada meio segundo, com uma componente alta para
            // o terço direito do espectro não ficar vazio.
            val since = time % 0.5
            if (since >= 0.05) 0.0 else {
                0.30 * exp(-since * 70) * sin(2 * PI * 2200 * time) +
                    0.18 * exp(-since * 160) * sin(2 * PI * 7800 * time)
            }
        }
        "vocals" -> 0.25 * sin(2 * PI * 440 * time) * (0.6 + 0.4 * sin(2 * PI * 5 * time))
        else -> {
            // Acorde MAIS um colchão de harmônicos: só com senoides puras o
            // espectro fica quase vazio e a onda não se parece com música.
            val chord = (sin(2 * PI * 330 * time) + sin(2 * PI * 494 * time)) / 2
            var harmonics = 0.0
            for (partial in 2..9) harmonics += sin(2 * PI * 330 * partial * time + partial) / partial
            0.16 * chord + 0.06 * harmonics
        }
    }
}
