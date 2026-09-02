package com.levelhard.cadentia.kit

import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes do motor HD — port 1:1 do `HDAudioTests.swift`. Qualidade de som é
 * julgamento, mas quase tudo que fazia o motor antigo soar barato é
 * mensurável: mono, hits repetidos idênticos, velocity que só muda nível,
 * notas fora do tom e buffers que estouram somados.
 */
class StereoBufferTest {
    @Test fun panningIsEqualPower() {
        val mono = FloatArray(64) { 1f }
        val centre = StereoBuffer.panned(mono, pan = 0f)
        assertTrue(abs(centre.left[0] - centre.right[0]) < 0.001f)

        val hardLeft = StereoBuffer.panned(mono, pan = -1f)
        assertTrue(hardLeft.left[0] > 1.3f)
        assertTrue(hardLeft.right[0] < 0.001f)

        // Potência igual: a energia total segura enquanto a imagem anda.
        val energyCentre = centre.left[0] * centre.left[0] + centre.right[0] * centre.right[0]
        val energyLeft = hardLeft.left[0] * hardLeft.left[0] + hardLeft.right[0] * hardLeft.right[0]
        assertTrue(abs(energyCentre - energyLeft) < 0.01f)
    }

    @Test fun mixGrowsTheBufferAndSums() {
        val base = StereoBuffer(FloatArray(10) { 0.25f })
        base.mix(StereoBuffer(FloatArray(10) { 0.25f }), atFrame = 5)
        assertEquals(15, base.frameCount)
        assertEquals(0.25f, base.left[0], 0.0001f)
        assertEquals(0.5f, base.left[7], 0.0001f)
        assertEquals(0.25f, base.left[14], 0.0001f)
    }

    @Test fun trimTailDropsSilenceButKeepsSignal() {
        val buffer = StereoBuffer(floatArrayOf(0.5f, 0.4f, 0.3f, 0f, 0f, 0f, 0f))
        buffer.trimTail()
        assertEquals(4, buffer.frameCount)
    }

    @Test fun interleavedLayoutAlternatesChannels() {
        val buffer = StereoBuffer(floatArrayOf(1f, 3f), floatArrayOf(2f, 4f))
        assertTrue(buffer.interleaved().contentEquals(floatArrayOf(1f, 2f, 3f, 4f)))
    }
}

class AudioDspTest {
    private val sampleRate = 44100.0

    @Test fun resonatorDecaysBySixtyDecibelsOverItsDecayTime() {
        val decay = 0.25
        val rendered = AudioDSP.renderModes(
            listOf(AudioDSP.Mode(300.0, decay, 1f)),
            excitation = floatArrayOf(1f),
            sampleRate = sampleRate,
            length = (decay * 2 * sampleRate).toInt(),
        )
        fun peak(range: IntRange): Float {
            var p = 0f
            for (i in range) p = maxOf(p, abs(rendered[i]))
            return p
        }
        val start = peak(0 until 2000)
        val decaySample = (decay * sampleRate).toInt()
        val atDecay = peak(decaySample until decaySample + 2000)
        // -60 dB é fator 1000; folga generosa para a janela.
        assertTrue(atDecay < start / 300)
        assertTrue(atDecay > 0f)
    }

    @Test fun lowpassRemovesHighFrequencyEnergy() {
        val high = FloatArray(4096) { i -> sin(2 * Math.PI * 8000 * i / sampleRate).toFloat() }
        val before = high.maxOf { abs(it) }
        AudioDSP.Biquad(AudioDSP.Biquad.Kind.Lowpass, 500.0, 0.7, sampleRate).process(high)
        val after = high.takeLast(2048).maxOf { abs(it) }
        assertTrue(after < before / 10)
    }

    @Test fun compressorPullsDownPeaksAboveThreshold() {
        val loud = FloatArray(8192) { i -> sin(2 * Math.PI * 200 * i / sampleRate).toFloat() }
        AudioDSP.compress(
            loud, thresholdDB = -20f, ratio = 8f, attack = 0.001, release = 0.05,
            makeupDB = 0f, sampleRate = sampleRate,
        )
        val settled = loud.takeLast(2048).maxOf { abs(it) }
        assertTrue(settled < 0.5f)
        assertTrue(settled > 0.02f)
    }

    @Test fun deClickRampsBothEdgesToZero() {
        val buffer = FloatArray(4410) { 1f }
        AudioDSP.deClick(buffer, sampleRate, milliseconds = 2.0)
        assertTrue(buffer[0] < 0.01f)
        assertTrue(buffer[buffer.size - 1] < 0.01f)
        assertEquals(1f, buffer[2205], 0f)
    }

    @Test fun noiseIsDeterministicPerSeed() {
        assertTrue(AudioDSP.whiteNoise(64, 7uL).contentEquals(AudioDSP.whiteNoise(64, 7uL)))
        assertFalse(AudioDSP.whiteNoise(64, 7uL).contentEquals(AudioDSP.whiteNoise(64, 8uL)))
    }
}

class DrumKitHDTest {
    private val sampleRate = 44100.0

    @Test fun everyPadInEveryKitIsAudibleAndBounded() {
        for (kit in DrumSynth.kitIDs) {
            for (pad in DrumSynth.padIDs) {
                val hit = DrumSynth.renderStereo(
                    kit, pad, velocity = 1f, variation = 0, sampleRate = sampleRate, gain = 1f,
                )
                assertFalse("$kit/$pad empty", hit.isEmpty)
                assertTrue("$kit/$pad silent", hit.peak > 0.05f)
                // Headroom é o ponto do gain staging: nenhum hit sozinho
                // chega ao fundo de escala.
                assertTrue("$kit/$pad peaks at ${hit.peak}", hit.peak < 0.95f)
            }
        }
    }

    /** O teste da metralhadora: 4 hits do mesmo pad são renders diferentes. */
    @Test fun roundRobinVariationsDiffer() {
        var previous: FloatArray? = null
        for (variation in 0 until DrumSynth.roundRobinCount) {
            val hit = DrumSynth.renderStereo(
                "acoustic", "snare", velocity = 0.9f, variation = variation,
                sampleRate = sampleRate, gain = 0.9f,
            ).summedToMono()
            previous?.let { prev ->
                val count = minOf(prev.size, hit.size)
                var difference = 0f
                for (i in 0 until count) difference += abs(prev[i] - hit[i])
                assertTrue("variation $variation is a duplicate", difference / count > 0.001f)
            }
            previous = hit
        }
    }

    @Test fun roundRobinIsReproducible() {
        val first = DrumSynth.renderStereo("acoustic", "kick", 0.8f, 2, sampleRate)
        val second = DrumSynth.renderStereo("acoustic", "kick", 0.8f, 2, sampleRate)
        assertEquals(first, second)
    }

    /** Velocity muda o timbre, não só o fader. */
    @Test fun velocityChangesTimbreNotJustLevel() {
        fun brightness(velocity: Float): Double {
            val hit = DrumSynth.renderStereo(
                "acoustic", "snare", velocity = velocity, variation = 0,
                sampleRate = sampleRate, gain = 1f,
            ).summedToMono()
            var total = 0.0
            var high = 0.0
            var previous = 0f
            for (sample in hit.take(8192)) {
                total += (sample * sample).toDouble()
                val difference = sample - previous
                high += (difference * difference).toDouble()
                previous = sample
            }
            return if (total > 0) high / total else 0.0
        }
        val soft = brightness(0.25f)
        val hard = brightness(1.0f)
        assertTrue("soft $soft vs hard $hard", hard > soft * 1.15)
    }

    @Test fun stereoImageIsNotCollapsedToMono() {
        // O chimbal senta à direita, o bumbo fica no centro.
        val hat = DrumSynth.renderStereo("acoustic", "hihat-c", 0.9f, 0, sampleRate)
        val hatLeft = hat.left.maxOf { abs(it) }
        val hatRight = hat.right.maxOf { abs(it) }
        assertTrue("hi-hat is not placed in the image", hatRight > hatLeft * 1.2f)

        val kick = DrumSynth.renderStereo("acoustic", "kick", 0.9f, 0, sampleRate)
        val kickLeft = kick.left.maxOf { abs(it) }
        val kickRight = kick.right.maxOf { abs(it) }
        assertTrue("kick drifted off centre", abs(kickLeft - kickRight) < kickLeft * 0.35f)
    }

    @Test fun hitsDecayToSilence() {
        for (pad in listOf("kick", "snare", "hihat-c", "cowbell", "crash")) {
            val hit = DrumSynth.renderStereo("acoustic", pad, 1f, 0, sampleRate).summedToMono()
            val tail = hit.takeLast((0.01 * sampleRate).toInt())
            assertTrue("$pad tail rings", (tail.maxOfOrNull { abs(it) } ?: 1f) < 0.05f)
        }
    }

    /**
     * Regressão: parciais em razão fixa formam série regular, série regular
     * é período, e período é PITCH — o crash saiu um sino (o próprio YIN leu
     * 133 Hz com confiança). Prato não pode ter nota achável.
     */
    @Test fun cymbalsHaveNoDefinitePitch() {
        for (pad in listOf("crash", "ride", "hihat-o", "hihat-c")) {
            val hit = DrumSynth.renderStereo("acoustic", pad, 0.95f, 0, sampleRate).summedToMono()
            val start = (0.05 * sampleRate).toInt()
            val end = minOf(start + 8192, hit.size)
            if (end <= start + 2048) continue
            val pitch = YINPitchDetector.detect(hit.copyOfRange(start, end), sampleRate)
            if (pitch != null) {
                assertTrue(
                    "$pad soa como sino: ${pitch.frequency} Hz com clareza ${pitch.clarity}",
                    pitch.clarity < 0.5,
                )
            }
        }
    }

    /** O contraponto: cowbell É um sino afinado e tem que manter a nota. */
    @Test fun cowbellKeepsItsPitch() {
        val hit = DrumSynth.renderStereo("acoustic", "cowbell", 0.95f, 0, sampleRate).summedToMono()
        val start = (0.02 * sampleRate).toInt()
        val end = minOf(start + 8192, hit.size)
        val pitch = YINPitchDetector.detect(hit.copyOfRange(start, end), sampleRate)
        assertNotNull(pitch)
        assertTrue((pitch?.clarity ?: 0.0) > 0.5)
    }

    @Test fun openHatRingsLongerThanClosed() {
        val closed = DrumSynth.renderStereo("acoustic", "hihat-c", 0.9f, 0, sampleRate)
        val open = DrumSynth.renderStereo("acoustic", "hihat-o", 0.9f, 0, sampleRate)
        assertTrue(open.frameCount > closed.frameCount * 3)
    }
}

class DrumPatternsTest {
    @Test fun catalogHasTwentyFiveCuratedGrooves() {
        assertEquals(25, DrumPattern.all.size)
        assertEquals(DrumPattern.all.size, DrumPattern.all.map { it.id }.toSet().size)
    }

    @Test fun everyPatternUsesKnownPadsAndSixteenSteps() {
        for (pattern in DrumPattern.all) {
            assertTrue(pattern.category in DrumPattern.categories)
            assertTrue(pattern.bpm in 40..240)
            for ((pad, steps) in pattern.pads) {
                assertTrue("${pattern.id}: pad $pad desconhecido", pad in DrumSynth.padIDs)
                assertEquals("${pattern.id}/$pad", 16, steps.size)
            }
        }
    }

    @Test fun nameKeyCamelizesLikeTheWeb() {
        assertEquals("music.drums.patternName.afro68", DrumPattern.find("afro-6-8")!!.nameKey)
        assertEquals("music.drums.patternName.rockBasic", DrumPattern.find("rock-basic")!!.nameKey)
    }

    @Test fun padLabelKeysSpellOutTheHats() {
        assertEquals("music.drums.pads.hihatClosed", DrumSynth.labelKey("hihat-c"))
        assertEquals("music.drums.pads.hihatOpen", DrumSynth.labelKey("hihat-o"))
        assertEquals("music.drums.pads.tomLow", DrumSynth.labelKey("tom-low"))
        assertEquals("music.drums.pads.kick", DrumSynth.labelKey("kick"))
    }

    @Test fun differentKitsRenderDifferentInstrumentsForTheSamePad() {
        val sampleRate = 44100.0
        val acoustic = DrumSynth.renderStereo("acoustic", "tom-high", 0.9f, 0, sampleRate).summedToMono()
        val latin = DrumSynth.renderStereo("latin", "tom-high", 0.9f, 0, sampleRate).summedToMono()
        assertNotEquals(acoustic.size, latin.size)
    }
}
