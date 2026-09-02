package com.levelhard.cadentia.kit

import java.io.ByteArrayInputStream
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port 1:1 do `RecorderProjectTests.swift`. */
class RecorderProjectTest {
    private fun clip(
        id: String,
        file: String = "a.caf",
        start: Double = 0.0,
        trimStart: Double = 0.0,
        duration: Double,
        source: Double = 10.0,
    ) = RecorderProject.Clip(
        id = id, fileName = file, start = start, trimStart = trimStart,
        duration = duration, sourceDuration = source,
    )

    private fun makeProject() = RecorderProject().apply {
        tracks = mutableListOf(
            RecorderProject.Track(id = "a", name = "Faixa 1", clips = mutableListOf(clip("a1", duration = 3.5)), volume = 1.0),
            RecorderProject.Track(id = "b", name = "Faixa 2", clips = mutableListOf(clip("b1", file = "b.caf", start = 2.0, duration = 2.0)), volume = 0.8),
            RecorderProject.Track(id = "c", name = "Faixa 3", clips = mutableListOf(clip("c1", file = "c.caf", duration = 5.1)), volume = 1.2),
        )
    }

    @Test fun roundTripViaJsonPreservesEverything() {
        val project = makeProject()
        val restored = RecorderProject.load(project.serialized())
        assertEquals(project, restored)
    }

    @Test fun corruptDataFallsBackToEmptyProject() {
        val restored = RecorderProject.load("not json")
        assertTrue(restored.tracks.isEmpty())
    }

    /** Projeto de antes dos clipes tem que abrir: perder take em upgrade não é aceitável. */
    @Test fun legacyProjectMigrates() {
        val legacy = """
        {"tracks":[
          {"id":"old1","name":"Voz","fileName":"take-1.caf","volume":0.9,
           "muted":false,"soloed":true,"durationSeconds":4.25}
        ]}
        """.trimIndent()
        val restored = RecorderProject.load(legacy)
        assertEquals(1, restored.tracks.size)
        val track = restored.tracks.first()
        assertEquals("Voz", track.name)
        assertEquals(0.9, track.volume, 0.0001)
        assertTrue(track.soloed)
        assertEquals(1, track.clips.size)
        assertEquals("take-1.caf", track.clips[0].fileName)
        assertEquals(0.0, track.clips[0].start, 0.0001)
        assertEquals(4.25, track.clips[0].duration, 0.001)
    }

    @Test fun muteHidesOnlyTheMutedTrack() {
        val project = makeProject()
        project.tracks[1].muted = true
        assertEquals(listOf("a", "c"), project.audibleTracks().map { it.id })
    }

    @Test fun soloWinsAndMuteStillSilences() {
        val project = makeProject()
        project.tracks[0].soloed = true
        project.tracks[2].soloed = true
        assertEquals(listOf("a", "c"), project.audibleTracks().map { it.id })

        // Mute + solo na mesma faixa: mute prevalece.
        project.tracks[0].muted = true
        assertEquals(listOf("c"), project.audibleTracks().map { it.id })
    }

    @Test fun projectDurationIsTheLatestClipEnd() {
        // Faixa b começa em 2 s e dura 2 s; faixa c vai até 5,1 s.
        assertEquals(5.1, makeProject().duration, 0.001)
    }

    @Test fun sanitizeClampsVolumePanAndGain() {
        val project = makeProject()
        project.tracks[0].volume = 9.0
        project.tracks[1].volume = -1.0
        project.tracks[0].pan = 4.0
        project.tracks[1].pan = -4.0
        project.tracks[2].clips[0].gain = 99.0
        val restored = RecorderProject.load(project.serialized())
        assertEquals(1.5, restored.tracks[0].volume, 0.0001)
        assertEquals(0.0, restored.tracks[1].volume, 0.0001)
        assertEquals(1.0, restored.tracks[0].pan, 0.0001)
        assertEquals(-1.0, restored.tracks[1].pan, 0.0001)
        assertEquals(2.0, restored.tracks[2].clips[0].gain, 0.0001)
    }

    /** Clipe é janela sobre arquivo: o aparo nunca aponta para além da gravação. */
    @Test fun sanitizeKeepsClipInsideSource() {
        val project = RecorderProject()
        project.tracks = mutableListOf(
            RecorderProject.Track(name = "T", clips = mutableListOf(clip("x", trimStart = 8.0, duration = 5.0, source = 10.0))),
        )
        project.sanitize()
        val clip = project.tracks[0].clips[0]
        assertEquals(8.0, clip.trimStart, 0.0001)
        assertEquals(2.0, clip.duration, 0.001)
    }

    @Test fun fadesNeverOverlap() {
        val project = RecorderProject()
        project.tracks = mutableListOf(
            RecorderProject.Track(name = "T", clips = mutableListOf(clip("x", duration = 2.0))),
        )
        project.updateClip("x") {
            it.fadeIn = 1.8
            it.fadeOut = 1.8
        }
        val clip = project.tracks[0].clips[0]
        assertTrue(clip.fadeIn + clip.fadeOut <= clip.duration + 0.0001)
        // O envelope no meio do clipe continua audível.
        assertTrue(clip.envelope(clip.duration / 2) > 0)
    }

    @Test fun envelopeAppliesBothFades() {
        val clip = clip("x", duration = 4.0).apply {
            fadeIn = 1.0
            fadeOut = 1.0
        }
        assertEquals(0.0, clip.envelope(0.0), 0.0001)
        assertEquals(0.5, clip.envelope(0.5), 0.001)
        assertEquals(1.0, clip.envelope(2.0), 0.0001)
        assertEquals(0.5, clip.envelope(3.5), 0.001)
        assertEquals(0.0, clip.envelope(4.0), 0.0001)
    }

    /** Dividir tem que ser sem perda: as metades cobrem exatamente o mesmo áudio. */
    @Test fun splitIsLossless() {
        val project = RecorderProject()
        project.tracks = mutableListOf(
            RecorderProject.Track(id = "t", name = "T", clips = mutableListOf(
                clip("x", start = 1.0, trimStart = 0.5, duration = 4.0, source = 10.0),
            )),
        )
        assertTrue(project.splitClip("x", at = 3.0))

        val clips = project.tracks[0].clips
        assertEquals(2, clips.size)
        assertEquals(1.0, clips[0].start, 0.001)
        assertEquals(2.0, clips[0].duration, 0.001)
        assertEquals(3.0, clips[1].start, 0.001)
        assertEquals(2.0, clips[1].duration, 0.001)
        // A segunda metade continua de onde a primeira parou dentro do arquivo.
        assertEquals(2.5, clips[1].trimStart, 0.001)
        assertEquals(clips[0].fileName, clips[1].fileName)
    }

    @Test fun splitOutsideClipIsRejected() {
        val project = RecorderProject()
        project.tracks = mutableListOf(
            RecorderProject.Track(name = "T", clips = mutableListOf(clip("x", start = 1.0, duration = 4.0))),
        )
        assertFalse(project.splitClip("x", at = 0.5))
        assertFalse(project.splitClip("x", at = 9.0))
        assertEquals(1, project.tracks[0].clips.size)
    }

    @Test fun duplicatePlacesCopyAfterOriginal() {
        val project = RecorderProject()
        project.tracks = mutableListOf(
            RecorderProject.Track(name = "T", clips = mutableListOf(clip("x", start = 1.0, duration = 2.0))),
        )
        val newID = project.duplicateClip("x")
        assertNotNull(newID)
        assertEquals(2, project.tracks[0].clips.size)
        val copy = project.tracks[0].clips.first { it.id == newID }
        assertEquals(3.0, copy.start, 0.001)
    }

    @Test fun moveClipKeepsTimelinePosition() {
        val project = RecorderProject()
        project.tracks = mutableListOf(
            RecorderProject.Track(id = "t1", name = "A", clips = mutableListOf(clip("x", start = 2.5, duration = 1.0))),
            RecorderProject.Track(id = "t2", name = "B"),
        )
        project.moveClip("x", toTrack = "t2")
        assertTrue(project.tracks[0].clips.isEmpty())
        assertEquals(1, project.tracks[1].clips.size)
        assertEquals(2.5, project.tracks[1].clips[0].start, 0.001)
    }

    @Test fun clipsStaySortedInTime() {
        val project = RecorderProject()
        project.tracks = mutableListOf(
            RecorderProject.Track(name = "T", clips = mutableListOf(
                clip("late", start = 5.0, duration = 1.0),
                clip("early", start = 1.0, duration = 1.0),
            )),
        )
        project.sanitize()
        assertEquals(listOf("early", "late"), project.tracks[0].clips.map { it.id })
    }

    @Test fun referencedFilesListOnlyWhatIsStillInUse() {
        val project = RecorderProject()
        project.tracks = mutableListOf(
            RecorderProject.Track(name = "A", clips = mutableListOf(clip("x", file = "one.caf", duration = 1.0))),
            RecorderProject.Track(name = "B", clips = mutableListOf(
                clip("y", file = "two.caf", duration = 1.0),
                clip("z", file = "one.caf", start = 3.0, duration = 1.0),
            )),
        )
        assertEquals(setOf("one.caf", "two.caf"), project.referencedFiles())
        project.removeClip("y")
        assertEquals(setOf("one.caf"), project.referencedFiles())
    }
}

/** Port 1:1 do `RecorderHistoryTests`. */
class RecorderHistoryTest {
    private fun project(named: String) = RecorderProject().apply { addTrack(named) }

    @Test fun undoRedoRoundTrip() {
        val history = RecorderHistory()
        var current = project(named = "um")

        history.record(current)
        current = project(named = "dois")
        assertTrue(history.canUndo)

        val undone = history.undo(current)
        assertNotNull(undone)
        assertEquals("um", undone!!.tracks[0].name)
        assertTrue(history.canRedo)

        val redone = history.redo(undone)
        assertNotNull(redone)
        assertEquals("dois", redone!!.tracks[0].name)
    }

    @Test fun newEditClearsPendingRedo() {
        val history = RecorderHistory()
        val first = project(named = "um")
        history.record(first)
        history.undo(project(named = "dois"))
        assertTrue(history.canRedo)

        history.record(first)
        assertFalse(history.canRedo)
    }

    @Test fun undoAtTheStartDoesNothing() {
        val history = RecorderHistory()
        assertFalse(history.canUndo)
        assertNull(history.undo(project(named = "um")))
    }

    @Test fun historyIsBounded() {
        val history = RecorderHistory(limit = 3)
        for (index in 0 until 10) {
            history.record(project(named = "$index"))
        }
        var current = project(named = "atual")
        var steps = 0
        while (true) {
            current = history.undo(current) ?: break
            steps += 1
        }
        assertEquals(3, steps)
    }
}

/**
 * O caminho do take no Android: WAV 16-bit round-trip e o mixdown puro que
 * substitui o render offline do AVAudioEngine.
 */
class RecorderMixTest {
    private val rate = 48000.0

    private fun tone(seconds: Double, amplitude: Float): FloatArray {
        val samples = FloatArray((seconds * rate).toInt())
        for (i in samples.indices) {
            samples[i] = amplitude * kotlin.math.sin(2 * Math.PI * 220.0 * i / rate).toFloat()
        }
        return samples
    }

    @Test fun wavRoundTripKeepsSamplesAndRate() {
        val original = tone(0.25, 0.6f)
        val bytes = WavIO.toByteArray(original, rate.toInt())
        val decoded = WavIO.read(ByteArrayInputStream(bytes))
        assertNotNull(decoded)
        assertEquals(rate.toInt(), decoded!!.sampleRate)
        assertEquals(original.size, decoded.samples.size)
        // 16 bits: erro máximo de meio degrau.
        for (i in original.indices step 997) {
            assertEquals(original[i], decoded.samples[i], 1.5f / 32768f)
        }
        assertEquals(0.25, decoded.durationSeconds, 0.001)
    }

    @Test fun wavRejectsGarbage() {
        assertNull(WavIO.read(ByteArrayInputStream("nem de longe um wav".toByteArray())))
    }

    @Test fun mixdownHonoursEnvelopeVolumeAndPosition() {
        val project = RecorderProject()
        project.tracks = mutableListOf(
            RecorderProject.Track(
                id = "t", name = "T", volume = 0.5,
                clips = mutableListOf(
                    RecorderProject.Clip(
                        id = "x", fileName = "tone.wav", start = 1.0,
                        duration = 1.0, sourceDuration = 1.0, fadeIn = 0.5,
                    ),
                ),
            ),
        )
        val out = RecorderMix.render(project, rate, enhance = false) { tone(1.0, 0.8f) }
        assertNotNull(out)
        val frames = out!!.size / 2
        // 2 s de projeto + 1 s de cauda.
        assertEquals(((project.duration + 1) * rate).toInt(), frames)

        fun peakBetween(fromSeconds: Double, toSeconds: Double): Float {
            var peak = 0f
            var i = (fromSeconds * rate).toInt()
            while (i < (toSeconds * rate).toInt() && i < frames) {
                peak = maxOf(peak, abs(out[2 * i]), abs(out[2 * i + 1]))
                i++
            }
            return peak
        }
        // Antes do clipe: silêncio. No fade: mais baixo que no corpo.
        assertEquals(0f, peakBetween(0.0, 0.95), 1e-6f)
        val duringFade = peakBetween(1.05, 1.20)
        val body = peakBetween(1.6, 1.95)
        assertTrue("fade $duringFade >= corpo $body", duringFade < body)
        // Volume 0,5 × pan central (0,707): pico do corpo ≈ 0,8 × 0,5 × 0,707.
        assertEquals(0.8f * 0.5f * 0.7071f, body, 0.02f)
    }

    @Test fun mixdownPanHardLeftLeavesRightSilent() {
        val project = RecorderProject()
        project.tracks = mutableListOf(
            RecorderProject.Track(
                id = "t", name = "T", pan = -1.0,
                clips = mutableListOf(
                    RecorderProject.Clip(id = "x", fileName = "f", duration = 0.5, sourceDuration = 0.5),
                ),
            ),
        )
        val out = RecorderMix.render(project, rate, enhance = false) { tone(0.5, 0.5f) }
        assertNotNull(out)
        var rightPeak = 0f
        var leftPeak = 0f
        for (i in 0 until out!!.size / 2) {
            leftPeak = maxOf(leftPeak, abs(out[2 * i]))
            rightPeak = maxOf(rightPeak, abs(out[2 * i + 1]))
        }
        assertTrue(leftPeak > 0.3f)
        assertTrue("direita vazou $rightPeak", rightPeak < 0.001f)
    }

    @Test fun mixdownOfEmptyProjectIsNull() {
        assertNull(RecorderMix.render(RecorderProject(), rate, enhance = false) { null })
        // Projeto com trilha mas sem arquivo legível também não exporta.
        val project = RecorderProject()
        project.tracks = mutableListOf(
            RecorderProject.Track(
                name = "T",
                clips = mutableListOf(RecorderProject.Clip(id = "x", fileName = "f", duration = 1.0, sourceDuration = 1.0)),
            ),
        )
        assertNull(RecorderMix.render(project, rate, enhance = false) { null })
    }

    @Test fun mixdownEnhanceTamesLowEnd() {
        // Um tom de 30 Hz (abaixo do passa-alta de 70 Hz) tem que sair bem
        // mais baixo com enhance ligado.
        val project = RecorderProject()
        project.tracks = mutableListOf(
            RecorderProject.Track(
                name = "T",
                clips = mutableListOf(RecorderProject.Clip(id = "x", fileName = "f", duration = 1.0, sourceDuration = 1.0)),
            ),
        )
        val low = FloatArray((1.0 * rate).toInt()) { i ->
            0.8f * kotlin.math.sin(2 * Math.PI * 30.0 * i / rate).toFloat()
        }
        val flat = RecorderMix.render(project, rate, enhance = false) { low }!!
        val shaped = RecorderMix.render(project, rate, enhance = true) { low }!!
        fun rms(buffer: FloatArray): Double {
            // Janela estável no meio do tom, longe do transiente do filtro.
            val from = (0.4 * rate).toInt() * 2
            val to = (0.9 * rate).toInt() * 2
            var sum = 0.0
            for (i in from until to) sum += buffer[i] * buffer[i].toDouble()
            return kotlin.math.sqrt(sum / (to - from))
        }
        assertTrue("enhance não atenuou o grave", rms(shaped) < rms(flat) * 0.35)
    }
}
