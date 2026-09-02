package com.levelhard.cadentia.kit

import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O banco de samples: escolha de região, DSP e a chave por família — port
 * do `SampleBankTests.swift` (1.16), mais um pack de verdade em WAV para a
 * cadeia inteira (manifesto → arquivo → nota) rodar no JVM.
 *
 * Tudo com pack sintético. O que quebra na prática é o mapeamento — nota que
 * cai na zona errada, dinâmica que some, round robin que não gira — e isso é
 * aritmética, não áudio.
 */
class SampleBankTest {
    private fun region(
        f: String, lo: Int, hi: Int, root: Int,
        vel: IntRange = 1..127, rr: Int = 0,
    ) = SamplePack.Region(f = f, lo = lo, hi = hi, root = root, vlo = vel.first, vhi = vel.last, rr = rr)

    private fun pack(regions: List<SamplePack.Region>, kind: SamplePack.Kind = SamplePack.Kind.melodic) =
        SamplePack(
            id = "t", voice = "guitar-clean", name = "T", license = "CC0-1.0",
            licenseURL = "", source = "", kind = kind, sampleRate = 44100.0,
            padNotes = null, regions = regions,
        )

    private fun pick(p: SamplePack, note: Int, velocity: Float, variation: Int) =
        SampleSelection.region(p, note, velocity, variation)?.f

    // ── escolha de região ──────────────────────────────────────────────────

    @Test
    fun picksTheZoneThatCoversTheNote() {
        val p = pack(listOf(region("baixo", 40, 47, 43), region("alto", 48, 55, 51)))
        assertEquals("baixo", pick(p, 45, 0.8f, 0))
        assertEquals("alto", pick(p, 48, 0.8f, 0))
    }

    /** Fora de todas as zonas, transpõe da mais próxima: pack podado tem buraco nas pontas por construção. */
    @Test
    fun outsideEveryZoneTransposesFromTheNearest() {
        val p = pack(listOf(region("baixo", 40, 47, 43), region("alto", 48, 55, 51)))
        assertEquals("baixo", pick(p, 20, 0.8f, 0))
        assertEquals("alto", pick(p, 100, 0.8f, 0))
    }

    @Test
    fun picksTheVelocityLayerThatCoversTheHit() {
        val p = pack(
            listOf(
                region("suave", 40, 60, 50, vel = 1..63),
                region("forte", 40, 60, 50, vel = 64..127),
            ),
        )
        assertEquals("suave", pick(p, 50, 0.1f, 0))
        assertEquals("forte", pick(p, 50, 0.9f, 0))
    }

    @Test
    fun missingVelocityLayerFallsBackToTheNearest() {
        val p = pack(listOf(region("só-forte", 40, 60, 50, vel = 100..127)))
        assertEquals("só-forte", pick(p, 50, 0.05f, 0))
    }

    @Test
    fun roundRobinCyclesAndWrapsOnNegatives() {
        val p = pack(
            listOf(
                region("a", 40, 60, 50, rr = 0),
                region("b", 40, 60, 50, rr = 1),
                region("c", 40, 60, 50, rr = 2),
            ),
        )
        assertEquals(listOf("a", "b", "c", "a", "b", "c"), (0 until 6).map { pick(p, 50, 0.8f, it) })
        assertEquals("c", pick(p, 50, 0.8f, -1))
    }

    @Test
    fun emptyPackAnswersNothing() {
        assertNull(SampleSelection.region(pack(emptyList()), 50, 0.8f, 0))
    }

    @Test
    fun velocityMapsToTheMidiRange() {
        assertEquals(1, SampleSelection.midiVelocity(0f))
        assertEquals(127, SampleSelection.midiVelocity(1f))
        assertEquals(1, SampleSelection.midiVelocity(-5f))
        assertEquals(127, SampleSelection.midiVelocity(9f))
    }

    // ── famílias ───────────────────────────────────────────────────────────

    @Test
    fun voiceIdDecidesTheFamily() {
        assertEquals(SampleFamily.Guitar, SampleFamily.of("guitar-nylon"))
        assertEquals(SampleFamily.Bass, SampleFamily.of("bass-slap"))
        assertEquals(SampleFamily.Drums, SampleFamily.of("drums:acoustic"))
        assertEquals(SampleFamily.Keys, SampleFamily.of("acoustic-piano"))
        assertEquals(SampleFamily.Keys, SampleFamily.of("organ"))
    }

    /** Gravação real é o som que o app quer entregar: deixar atrás de uma chave desligada é entregar a versão pior por padrão. */
    @Test
    fun everyFamilyStartsOnSamples() {
        assertEquals(SampleFamily.entries.toSet(), AppSettings().enabledSampleFamilies)
    }

    /** Mas quem desligou continua desligado: `sampled: []` é escolha, não ausência. */
    @Test
    fun anExplicitOptOutSurvivesTheDefault() {
        val decoded = SettingsCodec.decode("""{"sound":{"sampled":[]}}""")
        assertTrue(decoded.enabledSampleFamilies.isEmpty())
        // E um arquivo escrito antes desta versão, sem a seção, ganha o padrão.
        val old = SettingsCodec.decode("""{"tuner":{}}""")
        assertEquals(SampleFamily.entries.toSet(), old.enabledSampleFamilies)
    }

    @Test
    fun settingsRoundTripThroughStrings() {
        val settings = AppSettings()
        settings.sound.sampled = emptyList()
        assertTrue(settings.enabledSampleFamilies.isEmpty())
        settings.setSampled(SampleFamily.Guitar, true)
        settings.setSampled(SampleFamily.Drums, true)
        settings.setSampled(SampleFamily.Guitar, true) // idempotente
        assertEquals(setOf(SampleFamily.Guitar, SampleFamily.Drums), settings.enabledSampleFamilies)
        assertEquals(listOf("drums", "guitar"), settings.sound.sampled)
        settings.setSampled(SampleFamily.Guitar, false)
        assertEquals(setOf(SampleFamily.Drums), settings.enabledSampleFamilies)
    }

    /** Uma família que esta versão não conhece não pode derrubar a decodificação — nem sumir na escrita. */
    @Test
    fun unknownFamilyIsIgnoredNotFatal() {
        val settings = AppSettings()
        settings.sound.sampled = listOf("drums", "teremin")
        assertEquals(setOf(SampleFamily.Drums), settings.enabledSampleFamilies)
        settings.setSampled(SampleFamily.Keys, true)
        assertEquals(listOf("drums", "keys", "teremin"), settings.sound.sampled)
    }

    @Test
    fun theCacheCeilingIsMeasuredInBytes() {
        val bank = SampleBank()
        bank.cacheLimitBytes = 1024
        assertEquals(0.0, bank.cachedMegabytes, 0.0)
        assertEquals(1024L, bank.cacheLimitBytes)
    }

    // ── DSP ────────────────────────────────────────────────────────────────

    @Test
    fun resamplingAtRatioOneIsTheSameSignal() {
        val input = StereoBuffer(FloatArray(100) { it / 100f })
        val out = SampleBank.resample(input, 1.0, 100, null)
        assertEquals(100, out.frameCount)
        for (i in 0 until 100) assertTrue(abs(out.left[i] - input.left[i]) < 0.0001f)
    }

    /** Uma oitava acima consome o dobro do material no mesmo tempo. */
    @Test
    fun anOctaveUpConsumesTwiceTheSource() {
        val input = StereoBuffer(FloatArray(200) { 0.5f })
        val out = SampleBank.resample(input, 2.0, 200, null)
        val audible = out.left.indexOfLast { it != 0f }
        assertTrue("deveria acabar perto do frame 100, acabou no $audible", audible < 105)
    }

    @Test
    fun loopKeepsFeedingWhileTheNoteIsHeld() {
        val input = StereoBuffer(FloatArray(100) { 0.5f })
        val out = SampleBank.resample(input, 1.0, 400, listOf(20, 90))
        assertEquals(400, out.frameCount)
        assertTrue("com loop a nota não pode ter morrido no frame 100", out.left[399] != 0f)
    }

    /** O buffer de entrada pode morar no cache: a reamostragem nunca o devolve nem o altera. */
    @Test
    fun resampleNeverHandsBackTheCachedInput() {
        val input = StereoBuffer(FloatArray(10) { 1f })
        val same = SampleBank.resample(input, 1.0, 0, null)
        assertFalse(same === input)
        same.applyGain(0f)
        assertEquals(1f, input.left[0], 0f)
    }

    @Test
    fun releaseEndsAtSilence() {
        val buffer = StereoBuffer(FloatArray(4410) { 1f })
        SampleBank.applyRelease(buffer, 44100.0)
        assertTrue("o último frame tem que estar em silêncio", buffer.left[4409] < 0.01f)
        assertEquals("o ataque não pode ser tocado", 1f, buffer.left[0], 0f)
    }

    @Test
    fun panningPreservesPowerAndPicksASide() {
        val left = StereoBuffer(floatArrayOf(1f, 1f, 1f))
        SampleBank.pan(left, -1f)
        assertTrue(left.left[0] > 1.4f)
        assertTrue(left.right[0] < 0.01f)

        val center = StereoBuffer(floatArrayOf(1f, 1f, 1f))
        SampleBank.pan(center, 0f)
        assertTrue(abs(center.left[0] - center.right[0]) < 0.001f)
    }

    @Test
    fun drumVelocityGainIsAContinuousCurve() {
        assertEquals(1f, SampleBank.drumVelocityGain(1f), 1e-6f)
        assertEquals(0f, SampleBank.drumVelocityGain(0f), 1e-6f)
        assertTrue(SampleBank.drumVelocityGain(0.5f) in 0.5f..0.7f)
    }

    // ── a chave ────────────────────────────────────────────────────────────

    /** Sem família ligada, o banco não responde — e é isso que devolve a palavra para a síntese. */
    @Test
    fun theSwitchIsWhatDecides() {
        val bank = SampleBank()
        bank.setEnabled(emptySet())
        assertFalse(bank.isEnabled(SampleFamily.Guitar))
        assertNull(bank.renderIfEnabled("guitar-clean", 440.0, 1.0, 0.8f, 1f, 48000.0))

        bank.setEnabled(setOf(SampleFamily.Guitar))
        assertTrue(bank.isEnabled(SampleFamily.Guitar))
        assertFalse(bank.isEnabled(SampleFamily.Drums))
        // Ligada mas sem pack instalado ainda é null — a síntese continua.
        assertNull(bank.renderIfEnabled("guitar-clean", 440.0, 1.0, 0.8f, 1f, 48000.0))
    }

    @Test
    fun theSwitchBumpsTheGenerationOnlyWhenSomethingChanges() {
        val bank = SampleBank()
        bank.setEnabled(setOf(SampleFamily.Guitar, SampleFamily.Drums))
        val after = bank.soundGeneration
        bank.setEnabled(setOf(SampleFamily.Guitar, SampleFamily.Drums))
        assertEquals("conjunto igual não pode invalidar cache", after, bank.soundGeneration)
        bank.setEnabled(setOf(SampleFamily.Guitar))
        assertTrue("trocar a escolha tem que valer na próxima nota", bank.soundGeneration > after)
    }

    /** O banco do app começa desligado até o app ler as configurações; a síntese continua igual à 1.14. */
    @Test
    fun sharedBankStartsSilentAndSynthIsUntouched() {
        assertTrue(SampleBank.shared.installed.isEmpty())
        val synth = InstrumentSynth.render(InstrumentVoice.AcousticPiano, 440.0, 0.2, sampleRate = 48000.0)
        assertTrue(synth.frameCount > 0)
        val hit = DrumSynth.renderStereo("acoustic", "kick", sampleRate = 48000.0)
        assertTrue(hit.frameCount > 0)
    }
}

/**
 * O que acontece quando o disco não coopera, e a cadeia inteira com um pack
 * de verdade em WAV. `install` é o portão de toda a funcionalidade de sample:
 * pack quebrado sai da lista, o resto entra, e quem não tem pack cai na
 * síntese.
 */
class SampleBankInstallTest {
    private fun scratch(): File {
        val root = Files.createTempDirectory("sample-bank-").toFile()
        File(root, "ok").mkdirs()
        File(root, "quebrado").mkdirs()
        val good = SamplePack(
            id = "ok", voice = "guitar-clean", name = "Bom", license = "CC0-1.0",
            licenseURL = "https://exemplo", source = "https://exemplo",
            kind = SamplePack.Kind.melodic, sampleRate = 44100.0,
            regions = listOf(SamplePack.Region(f = "0000.wav", lo = 40, hi = 40, root = 40, vlo = 1, vhi = 127, rr = 0)),
        )
        File(root, "ok/manifest.json").writeText(Json.encodeToString(good))
        File(root, "quebrado/manifest.json").writeText("""{"id":"quebrado","voice":""")
        File(root, "packs.json").writeText("""{"packs":["ok","quebrado","sumiu"]}""")
        return root
    }

    @Test
    fun installSkipsABrokenPackAndKeepsTheRest() {
        val root = scratch()
        try {
            val bank = SampleBank()
            bank.install(root)
            assertEquals(listOf("ok"), bank.installed.map { it.id })
            assertEquals("ok", bank.pack("guitar-clean")?.id)
            assertNull(bank.pack("organ"))
            assertEquals(setOf(SampleFamily.Guitar), bank.installedFamilies)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun renderFallsBackWhenTheFileIsMissing() {
        val root = scratch()
        try {
            val bank = SampleBank()
            bank.install(root)
            bank.setEnabled(setOf(SampleFamily.Guitar))
            // O manifesto aponta para `0000.wav`, que nunca foi escrito.
            assertNull(bank.renderIfEnabled("guitar-clean", 440.0, 1.0, 0.8f, 1f, 44100.0))
            assertEquals(0.0, bank.cachedMegabytes, 0.0)
        } finally {
            root.deleteRecursively()
        }
    }

    /** O manifesto que o `fetch-samples.mjs` escreve (campos curtos, opcionais ausentes) decodifica tal qual. */
    @Test
    fun readsTheManifestTheBuildScriptWrites() {
        val json = """{"id":"drums-acoustic","voice":"drums:acoustic","name":"V","license":"CC0-1.0",
            "licenseURL":"u","source":"s","kind":"drums","sampleRate":44100,
            "padNotes":{"kick":36,"snare":38},
            "regions":[{"f":"0000.flac","lo":36,"hi":36,"root":36,"vlo":35,"vhi":80,"rr":0},
                       {"f":"0001.flac","lo":36,"hi":36,"root":36,"vlo":81,"vhi":127,"rr":0,"pan":-0.25,"tune":3.5}]}"""
        val pack = Json { ignoreUnknownKeys = true }.decodeFromString<SamplePack>(json)
        assertEquals(SamplePack.Kind.drums, pack.kind)
        assertEquals(SampleFamily.Drums, pack.family)
        assertEquals(36, pack.padNotes?.get("kick"))
        assertNull(pack.regions[0].pan)
        assertEquals(-0.25, pack.regions[1].pan!!, 0.0)
        assertEquals(3.5, pack.regions[1].tune!!, 0.0)
    }

    /** A cadeia inteira: um lá 440 gravado em WAV toca em 440 e transpõe para 880 consumindo o dobro. */
    @Test
    fun aRealPackRendersAndTransposes() {
        val root = Files.createTempDirectory("sample-pack-").toFile()
        try {
            val dir = File(root, "p").apply { mkdirs() }
            val rate = 44100
            val tone = StereoBuffer(FloatArray(rate) { (0.8 * sin(2 * Math.PI * 440 * it / rate)).toFloat() })
            File(dir, "a4.wav").outputStream().use { WavIO.writeStereo(tone, rate, it) }
            val pack = SamplePack(
                id = "p", voice = "acoustic-piano", name = "P", license = "CC0-1.0", licenseURL = "", source = "",
                kind = SamplePack.Kind.melodic, sampleRate = rate.toDouble(),
                regions = listOf(SamplePack.Region(f = "a4.wav", lo = 60, hi = 80, root = 69, vlo = 1, vhi = 127, rr = 0)),
            )
            File(dir, "manifest.json").writeText(Json.encodeToString(pack))
            File(root, "packs.json").writeText("""{"packs":["p"]}""")

            val bank = SampleBank()
            bank.install(root)
            bank.setEnabled(setOf(SampleFamily.Keys))

            val same = bank.renderIfEnabled("acoustic-piano", 440.0, 0.5, 0.8f, 1f, 44100.0)
            assertNotNull(same)
            val detected = YINPitchDetector.detect(same!!.summedToMono().copyOfRange(2000, 2000 + 4096), 44100.0)
            assertNotNull(detected)
            assertEquals(440.0, detected!!.frequency, 2.0)

            val octave = bank.renderIfEnabled("acoustic-piano", 880.0, 0.5, 0.8f, 1f, 44100.0)
            assertNotNull(octave)
            val up = YINPitchDetector.detect(octave!!.summedToMono().copyOfRange(2000, 2000 + 4096), 44100.0)
            assertEquals(880.0, up!!.frequency, 4.0)
            // O material de 1 s transposto uma oitava acaba antes de 0,5 s + cauda.
            assertTrue(octave.frameCount < same.frameCount)

            // Uma leitura só do disco para as duas notas: o cache guardou o arquivo.
            assertTrue(bank.cachedMegabytes > 0.3)
            bank.purge()
            assertEquals(0.0, bank.cachedMegabytes, 0.0)
        } finally {
            root.deleteRecursively()
        }
    }
}
