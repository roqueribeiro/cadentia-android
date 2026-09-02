package com.levelhard.cadentia.kit

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O manifesto REAL do pack de bateria (Virtuosity, gerado pelo
 * fetch-samples.mjs) contra os 16 pads do app: todo pad menos o clap tem
 * zona própria, e a pancada passa pelo decodificador uma vez por arquivo.
 */
class SampleBankDrumsTest {
    private fun installed(decoder: SampleDecoder): SampleBank {
        val root = Files.createTempDirectory("drums-pack-").toFile()
        val dir = File(root, "drums-acoustic").apply { mkdirs() }
        val manifest = javaClass.getResourceAsStream("/drums-acoustic-manifest.json")!!.readBytes()
        File(dir, "manifest.json").writeBytes(manifest)
        File(root, "packs.json").writeText("""{"packs":["drums-acoustic"]}""")
        val bank = SampleBank(decoder)
        bank.install(root)
        bank.setEnabled(setOf(SampleFamily.Drums))
        return bank
    }

    @Test
    fun everyPadButClapResolvesToAFile() {
        val bank = installed(SampleDecoder.Wav)
        assertEquals(listOf("drums-acoustic"), bank.installed.map { it.id })
        val slots = DrumSynth.padIDs.associateWith { bank.drumSlot("acoustic", it, 1f, 0) }
        assertEquals(null, slots["clap"])
        for ((pad, slot) in slots) {
            if (pad == "clap") continue
            assertNotNull("pad $pad sem arquivo", slot)
            assertTrue(slot!!.startsWith("drums-acoustic/"))
        }
        // Acentos diferentes do sequenciador caem em arquivos diferentes onde há camada.
        assertTrue(bank.drumSlot("acoustic", "kick", 1f, 0) != bank.drumSlot("acoustic", "kick", 0.62f, 0))
    }

    @Test
    fun aHitGoesThroughTheDecoderOncePerFile() {
        val asked = mutableListOf<String>()
        val bank = installed(
            SampleDecoder { file ->
                asked.add(file.name)
                StereoBuffer(FloatArray(4410) { 0.5f })
            },
        )
        val first = bank.renderDrumIfEnabled("acoustic", "kick", 1f, 0, 48000.0, 1f)
        assertNotNull(first)
        val again = bank.renderDrumIfEnabled("acoustic", "kick", 1f, 0, 48000.0, 1f)
        assertNotNull(again)
        assertEquals("o segundo pedido do mesmo arquivo sai do cache", 1, asked.size)
        // 44,1 kHz → 48 kHz: reamostrado, mais frames que a fonte.
        assertTrue(first!!.frameCount > 4410)
    }
}
