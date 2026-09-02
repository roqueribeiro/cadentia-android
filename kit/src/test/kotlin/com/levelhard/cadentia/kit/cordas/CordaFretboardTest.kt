package com.levelhard.cadentia.kit.cordas

import com.levelhard.cadentia.kit.ChordLibrary
import com.levelhard.cadentia.kit.MusicNotes
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port do `FretboardLayoutTests`. */
class FretboardLayoutTest {
    private val size = Size(390.0, 780.0)

    private fun layout(instrument: CordaInstrument = CordaInstrument.violao, frets: Int = 5, shift: Int = 0) =
        FretboardLayout(size = size, instrument = instrument, visibleFrets = frets, shift = shift)

    @Test
    fun theStringsFitWithRoomToPlayAtBothEdges() {
        val l = layout()
        assertEquals(6, l.stringX.size)
        assertTrue("cabe dedo do lado do bordão", l.stringX[0] > FretboardLayout.RAIL_WIDTH + 20)
        assertTrue("e do lado da prima", size.width - l.stringX[5] > 30)
        for (i in 1 until l.stringX.size) assertTrue("as cordas em ordem", l.stringX[i] > l.stringX[i - 1])
    }

    /** A batida tem que parar antes da barra de abas. */
    @Test
    fun theBridgeKeepsTheStrumOffTheTabBar() {
        val l = layout()
        assertTrue(l.strumBottom < size.height)
        assertEquals(FretboardLayout.BRIDGE_HEIGHT, size.height - l.strumBottom, 0.0)
        assertEquals(l.strumBottom, l.bridgeRect.minY, 0.0)
        assertTrue("ainda sobra área de rasgar", l.strumBottom > l.neckHeight)
    }

    @Test
    fun theBridgeNeverEatsTheStrummingArea() {
        for (height in listOf(260.0, 300.0, 420.0)) {
            val short = FretboardLayout(size = Size(390.0, height), instrument = CordaInstrument.violao)
            assertTrue("altura $height ficou sem rasgado", short.strumBottom > short.neckHeight)
        }
    }

    @Test
    fun withoutTheRailTheStringsTakeTheWholeWidth() {
        val railed = layout()
        val bare = FretboardLayout(size = size, instrument = CordaInstrument.violao, hasRail = false)
        assertEquals(FretboardLayout.RAIL_WIDTH, railed.railWidth, 0.0)
        assertEquals(0.0, bare.railWidth, 0.0)
        assertTrue("sem trilho as cordas ganham espaço", bare.stringX[0] < railed.stringX[0])
    }

    @Test
    fun theFretsUseRealScaleSpacing() {
        val l = layout()
        assertEquals(6, l.fretY.size)
        assertEquals(0.0, l.fretY[0], 0.0)
        var previous = Double.MAX_VALUE
        for (k in 1 until l.fretY.size) {
            val gap = l.fretY[k] - l.fretY[k - 1]
            assertTrue("as casas têm que apertar descendo o braço", gap < previous)
            previous = gap
        }
    }

    @Test
    fun fewerFretsMeanAMoreRealisticScale() {
        val tight = layout(frets = 3).measurements
        val loose = layout(frets = 8).measurements
        assertTrue(tight.equivalentScale > loose.equivalentScale)
        assertTrue("3 casas chegam perto de um violão de verdade", tight.equivalentScale > 400)
    }

    @Test
    fun theSpacingMatchesARealGuitarAtTheBridge() {
        val m = layout().measurements
        assertTrue("${m.spacing} mm entre cordas", m.spacing > 8 && m.spacing < 14)
        assertTrue(m.bundleWidth > 40 && m.bundleWidth < 70)
        assertEquals(58.0, m.real.atBridge, 0.0)
    }

    @Test
    fun theViolaKeepsThePairsOfACourseTogether() {
        val l = layout(CordaInstrument.viola)
        assertEquals(10, l.stringX.size)
        val insideCourse = abs(l.stringX[1] - l.stringX[0])
        val betweenCourses = abs(l.stringX[2] - l.stringX[1])
        assertTrue(betweenCourses > insideCourse * 2)
    }

    @Test
    fun theRailOnlyOffersPositionsThatExist() {
        assertEquals(listOf(0, 3, 5, 7, 9, 12), layout(frets = 5).railMarks)
        val viola = FretboardLayout(size = size, instrument = CordaInstrument.viola, visibleFrets = 8)
        assertTrue(viola.railMarks.all { it <= 15 - 8 })
    }

    @Test
    fun hitTestingFindsTheRightStringAndFret() {
        val l = layout()
        assertEquals(3, l.stringAt(l.stringX[3]))
        assertEquals(3, l.stringAt(l.stringX[3] + 2))
        assertEquals(0, l.fretAt(0.0))
        assertEquals(5, l.fretAt(l.neckHeight))
        assertEquals(2, l.fretAt((l.fretY[1] + l.fretY[2]) / 2))
    }
}

/** Port do `ChordTransposeTests`. */
class ChordTransposeTest {
    @Test
    fun theShapeTravelsAndKeepsItsIntervals() {
        val frets = mutableListOf(-1, 3, 2, 0, 1, 0) // C
        val moved = ChordTranspose.transpose(frets, 2, maxFret = 17)
        assertEquals(2, moved)
        assertEquals("só o que estava pisado anda", listOf(-1, 5, 4, 0, 3, 0), frets)
    }

    @Test
    fun theShapeNeverWalksThroughTheNut() {
        val frets = mutableListOf(-1, 3, 2, 0, 1, 0)
        val moved = ChordTranspose.transpose(frets, -10, maxFret = 17)
        assertEquals("a casa 1 já está no limite, então não anda", 0, moved)
        assertEquals(listOf(-1, 3, 2, 0, 1, 0), frets)
    }

    @Test
    fun nothingPressedMeansNothingToMove() {
        val frets = mutableListOf(0, 0, 0, 0, 0, 0)
        assertEquals(0, ChordTranspose.transpose(frets, 3, maxFret = 17))
    }

    @Test
    fun theWindowChasesTheShape() {
        val high = listOf(-1, 9, 8, 0, 7, 0)
        val shift = ChordTranspose.windowFollowing(high, shift = 0, visibleFrets = 5, maxFret = 17)
        assertTrue("a janela tem que ir atrás da mão", shift >= 4)
        assertTrue(shift <= 17 - 5)
    }

    @Test
    fun jumpingToAPositionTakesTheChordAlong() {
        val frets = mutableListOf(-1, 3, 2, 0, 1, 0)
        val position = ChordTranspose.jump(7, frets, visibleFrets = 5, maxFret = 17)
        assertEquals(7, position)
        assertEquals("a casa mais baixa da forma vai pro topo", 8, frets.filter { it > 0 }.minOrNull())
    }
}

/** Port do `NailCaptureTests`. */
class NailCaptureTest {
    private val layout = FretboardLayout(size = Size(390.0, 780.0), instrument = CordaInstrument.violao)

    /** Relatado do aparelho: "toca uma vez e nunca mais". */
    @Test
    fun tappingTheSameStringAgainPlaysItAgain() {
        val nail = NailCapture()
        nail.reset(6)
        val x = layout.stringX[2]
        for (attempt in 1..4) {
            val plucks = nail.touchDown(x, 500.0, attempt.toDouble(), nail = 0.8, muted = false, layout = layout)
            assertEquals("o toque $attempt não soou", 1, plucks.size)
            assertEquals(2, plucks.first().string)
            nail.touchUp(x, 500.0, attempt + 0.05)
        }
    }

    @Test
    fun aChoppedSweepIsStitchedWithoutStrikingTwice() {
        val nail = NailCapture()
        nail.reset(6)
        val start = layout.stringX[0] - 20
        val middle = (layout.stringX[2] + layout.stringX[3]) / 2
        nail.touchDown(start, 500.0, 0.0, nail = 0.8, muted = false, layout = layout)
        val swept = nail.sweep(start, middle, 0.03, nail = 0.8, muted = false, layout = layout)
        assertTrue("a varrida tinha que pegar a terceira", swept.any { it.string == 2 })
        nail.touchUp(middle, 500.0, 0.03)
        val stitched = nail.touchDown(middle + 4, 500.0, 0.09, nail = 0.8, muted = false, layout = layout)
        assertFalse("a emenda tocou de novo a corda que acabou de soar", stitched.any { it.string == 2 })
    }

    @Test
    fun aSweepPlaysEveryStringItCrosses() {
        val nail = NailCapture()
        nail.reset(6)
        val plucks = nail.sweep(layout.stringX[0] - 20, layout.stringX[5] + 20, 0.06, nail = 0.8, muted = false, layout = layout)
        assertEquals(6, plucks.size)
        assertEquals(0, plucks.first().string)
        assertEquals(5, plucks.last().string)
        assertEquals(0.0, plucks[0].delay, 0.0)
        assertTrue("cada corda no seu instante", plucks[5].delay > plucks[0].delay)
    }

    /** Refratário por POSIÇÃO: tremor não sai da corda, passada de verdade sai. */
    @Test
    fun theRefractoryIsByPositionAndNotByTime() {
        val nail = NailCapture()
        nail.reset(6)
        val target = layout.stringX[3]
        nail.sweep(target - 5, target + 5, 0.02, nail = 0.8, muted = false, layout = layout)
        val tremble = nail.sweep(target + 5, target - 5, 0.02, nail = 0.8, muted = false, layout = layout)
        assertTrue("tremor não pode virar nota", tremble.isEmpty())
        nail.sweep(target - 5, target - layout.laneHalfGap * 2, 0.03, nail = 0.8, muted = false, layout = layout)
        val back = nail.sweep(target - layout.laneHalfGap * 2, target + 5, 0.03, nail = 0.8, muted = false, layout = layout)
        assertTrue("a volta de verdade tem que soar", back.any { it.string == 3 })
    }

    @Test
    fun aBrokenPassIsStitchedBackTogether() {
        val nail = NailCapture()
        nail.reset(6)
        nail.touchUp(layout.stringX[2] - 15, 600.0, 1.0)
        val plucks = nail.touchDown(layout.stringX[3] + 15, 610.0, 1.05, nail = 0.8, muted = false, layout = layout)
        assertEquals("a corda do vão tem que soar, não sumir", 2, plucks.size)
        assertEquals("e na ordem em que a unha passou", listOf(2, 3), plucks.map { it.string })
    }

    @Test
    fun aLostContactTooLongAgoIsANewGesture() {
        val nail = NailCapture()
        nail.reset(6)
        nail.touchUp(layout.stringX[2] - 15, 600.0, 1.0)
        val plucks = nail.touchDown(layout.stringX[3] + 15, 610.0, 2.0, nail = 0.8, muted = false, layout = layout)
        assertEquals("um segundo depois é outro toque, e só ele soa", listOf(3), plucks.map { it.string })
    }

    @Test
    fun aThinContactSoundsLikeANailAndAWideOneLikeFlesh() {
        assertTrue(NailCapture.nailness(6.0, enabled = true) > 0.9)
        assertTrue(NailCapture.nailness(30.0, enabled = true) < 0.2)
        assertEquals(0.8, NailCapture.nailness(null, enabled = true), 0.0)
        assertEquals(0.35, NailCapture.nailness(6.0, enabled = false), 0.0)
    }

    @Test
    fun theGestureSpeedIsTheDynamic() {
        assertTrue(NailCapture.velocity(200.0) < NailCapture.velocity(2500.0))
        assertTrue(NailCapture.velocity(0.0) >= 0.12)
        assertTrue(NailCapture.velocity(99999.0) <= 1.0)
    }
}

/** Port do `HandSmootherTests`. */
class HandSmootherTest {
    private fun fullHand(value: Double = 10.0): MutableList<Point?> =
        (0 until 21).map<Int, Point?> { Point(value + it, value) }.toMutableList()

    @Test
    fun aCompleteHandPassesThrough() {
        val smoother = HandSmoother()
        val hand = smoother.smooth(fullHand(), 0.0)
        assertNotNull(hand)
        assertEquals(Point(10.0, 10.0), hand!![HandJoint.Wrist])
        assertEquals(Point(30.0, 10.0), hand[HandJoint.LittleTip])
    }

    @Test
    fun aBlinkingJointIsHeld() {
        val smoother = HandSmoother()
        smoother.smooth(fullHand(), 0.0)
        val missing = fullHand()
        missing[HandJoint.IndexTip.index] = null
        val hand = smoother.smooth(missing, 0.05)
        assertEquals("segurou o último bom", Point(18.0, 10.0), hand!![HandJoint.IndexTip])
    }

    /** UMA PONTA DE DEDO FORA DO QUADRO NÃO É UMA MÃO SAINDO. */
    @Test
    fun aMissingFingertipInheritsItsKnuckleInsteadOfKillingTheHand() {
        val smoother = HandSmoother()
        smoother.smooth(fullHand(), 0.0)
        val missing = fullHand()
        missing[HandJoint.IndexTip.index] = null
        val hand = smoother.smooth(missing, 1.0)
        assertNotNull("a mão inteira não pode morrer por uma ponta de dedo", hand)
        assertEquals("a ponta herda a junta de onde ela sai", hand!![HandJoint.IndexDIP], hand[HandJoint.IndexTip])
    }

    @Test
    fun aHandWithNoWristIsGone() {
        val smoother = HandSmoother()
        smoother.smooth(fullHand(), 0.0)
        val missing = fullHand()
        missing[HandJoint.Wrist.index] = null
        assertNull(smoother.smooth(missing, 1.0))
    }

    @Test
    fun holdingHalfTheHandIsNotAHand() {
        val smoother = HandSmoother()
        smoother.smooth(fullHand(), 0.0)
        val mostlyMissing = fullHand()
        for (i in 0 until 12) mostlyMissing[i] = null
        assertNull(smoother.smooth(mostlyMissing, 0.05))
    }

    @Test
    fun aHandWithoutTwentyOneJointsIsRefused() {
        val smoother = HandSmoother()
        assertNull(smoother.smooth(List(20) { Point.zero }, 0.0))
    }
}

/** Port do `ChordPadGridTests`. */
class ChordPadGridTest {
    private val size = Size(390.0, 780.0)

    @Test
    fun theColumnsDivideTheChordsEvenlyWhenTheyCan() {
        assertEquals(3, FretboardLayout.padColumns(15))
        assertEquals(2, FretboardLayout.padColumns(10))
        assertEquals(3, FretboardLayout.padColumns(9))
        assertEquals(3, FretboardLayout.padColumns(12))
    }

    @Test
    fun aShortScreenTradesColumnsForRowHeight() {
        assertEquals(4, FretboardLayout.padColumns(15, availableHeight = 190.0))
        assertEquals(4, FretboardLayout.padColumns(10, availableHeight = 190.0))
        assertEquals(3, FretboardLayout.padColumns(15, availableHeight = 400.0))
    }

    @Test
    fun thePadsLeaveMoreScreenToTheStringsThanTheNeckDoes() {
        val neck = FretboardLayout(size = size, instrument = CordaInstrument.violao)
        val pads = FretboardLayout(size = size, instrument = CordaInstrument.violao, hasRail = false, padCount = 15)
        assertTrue("os pads pedem menos que o braço", pads.neckHeight < neck.neckHeight)
        assertTrue("sobra mais para rasgar", pads.strumBottom - pads.neckHeight > neck.strumBottom - neck.neckHeight)
        assertTrue("alvo de toque decente", (pads.padGrid.rect(0)?.height ?: 0.0) >= 44)
    }

    @Test
    fun everyPadFindsItselfBackFromItsOwnCentre() {
        val layout = FretboardLayout(size = size, instrument = CordaInstrument.viola, hasRail = false, padCount = CordaChords.violaSet.size)
        val grid = layout.padGrid
        assertEquals(10, grid.count)
        for (index in 0 until grid.count) {
            val rect = grid.rect(index)
            assertNotNull("pad $index ficou sem retângulo", rect)
            assertEquals(index, grid.index(Point(rect!!.midX, rect.midY)))
        }
    }

    @Test
    fun thePadsStayInsideTheirAreaAndNeverOverlap() {
        val layout = FretboardLayout(size = size, instrument = CordaInstrument.viola, hasRail = false, padCount = 10)
        val grid = layout.padGrid
        val placed = ArrayList<Rect>()
        for (index in 0 until grid.count) {
            val rect = grid.rect(index) ?: continue
            assertTrue("pad $index escapou da área", rect.minX >= grid.area.minX - 1e-9 && rect.maxX <= grid.area.maxX + 1e-9 && rect.minY >= grid.area.minY - 1e-9 && rect.maxY <= grid.area.maxY + 1e-9)
            for (other in placed) {
                val intersects = other.minX < rect.maxX && rect.minX < other.maxX && other.minY < rect.maxY && rect.minY < other.maxY
                assertFalse("pads sobrepostos", intersects)
            }
            placed.add(rect)
        }
        assertEquals(10, placed.size)
    }

    @Test
    fun aShortLastRowIsCentred() {
        val layout = FretboardLayout(size = size, instrument = CordaInstrument.violao, hasRail = false, padCount = 11)
        val grid = layout.padGrid
        val first = grid.rect(9)!!
        val last = grid.rect(10)!!
        assertTrue("fileira curta centrada", abs((first.minX + last.maxX) / 2 - grid.area.midX) < 1.5)
        assertTrue("não fica encostada na esquerda", first.minX > grid.area.minX + 1)
    }

    @Test
    fun thePadsNeverRunUnderTheRail() {
        val layout = FretboardLayout(size = size, instrument = CordaInstrument.violao, padCount = 15)
        val grid = layout.padGrid
        for (index in 0 until grid.count) {
            val rect = grid.rect(index) ?: continue
            assertTrue("pad $index por baixo do trilho", rect.minX >= layout.railWidth)
        }
    }
}

/**
 * Port do `CameraFrameMappingTests`, na convenção do Android: o landmark
 * normalizado do MediaPipe tem origem no canto SUPERIOR esquerdo (a mesma da
 * tela), então a identidade leva (0,0) para o canto de cima.
 */
class CameraFrameMappingTest {
    private val image = Size(720.0, 1280.0)
    private val view = Size(390.0, 780.0)

    @Test
    fun theCornersOfTheImageLandOutsideOrOnTheEdgesOfTheView() {
        val map = CameraFrameMapping.identity
        val topLeft = map.viewPoint(Point(0.0, 0.0), image, view)
        val bottomRight = map.viewPoint(Point(1.0, 1.0), image, view)
        assertTrue(topLeft.x <= 0.5)
        assertTrue(topLeft.y <= 0.5)
        assertTrue(bottomRight.x >= view.width - 0.5)
        assertTrue(bottomRight.y >= view.height - 0.5)
    }

    @Test
    fun aHalfTurnSendsEachCornerToTheOppositeOne() {
        val corner = Point(0.0, 0.0)
        val a = CameraFrameMapping.identity.viewPoint(corner, image, view)
        val b = CameraFrameMapping.turnedAround.viewPoint(corner, image, view)
        assertTrue("espelhado na horizontal", abs(a.x + b.x - view.width) < 0.5)
        assertTrue("e na vertical", abs(a.y + b.y - view.height) < 0.5)
    }

    @Test
    fun theCentreOfTheImageIsAlwaysTheCentreOfTheView() {
        val middle = Point(0.5, 0.5)
        for (map in CameraFrameMapping.all) {
            val place = map.viewPoint(middle, image, view)
            assertTrue(abs(place.x - view.width / 2) < 0.5)
            assertTrue(abs(place.y - view.height / 2) < 0.5)
        }
    }

    /** A imagem 16:9 deitada numa view 9:16 é "cover": estica até cobrir e sobra dos dois lados igual. */
    @Test
    fun aLandscapeImageCoversAPortraitViewSymmetrically() {
        val landscape = Size(1280.0, 720.0)
        val left = CameraFrameMapping.identity.viewPoint(Point(0.0, 0.5), landscape, view)
        val right = CameraFrameMapping.identity.viewPoint(Point(1.0, 0.5), landscape, view)
        assertTrue("a imagem sobra para fora da view", left.x < 0 && right.x > view.width)
        assertTrue("simétrico", abs(left.x + right.x - view.width) < 0.5)
    }

    @Test
    fun cyclingVisitsAllFourAndReturns() {
        var map = CameraFrameMapping.identity
        val seen = ArrayList<CameraFrameMapping>()
        for (i in 0 until 4) {
            seen.add(map)
            map = map.next
        }
        assertEquals("o ciclo tem que fechar", CameraFrameMapping.identity, map)
        assertEquals("quatro estados distintos", 4, seen.map { it.symbol }.toSet().size)
    }
}

/** O baixo entrou no Cordas como instrumento, não como tela — port do `BaixoTests`. */
class BaixoTest {
    @Test
    fun tuningIsStandardBass() {
        val baixo = CordaInstrument.baixo
        assertEquals(4, baixo.stringCount)
        assertEquals(listOf(28, 33, 38, 43), baixo.strings.map { it.midi })
        assertEquals(12, CordaInstrument.violao.strings[0].midi - baixo.strings[0].midi)
    }

    @Test
    fun padsPlayTheRoot() {
        val baixo = CordaInstrument.baixo
        val c = CordaChords.frets("C", baixo)
        assertEquals(4, c?.size)
        assertEquals("só uma corda soa; o resto é abafado", 1, c!!.count { it >= 0 })
        val sounding = baixo.strings.zip(c).first { it.second >= 0 }
        assertEquals("a nota que soa tem que ser dó", 0, (sounding.first.midi + sounding.second) % 12)
    }

    @Test
    fun qualityDoesNotChangeTheRoot() {
        val baixo = CordaInstrument.baixo
        assertEquals(CordaChords.frets("A", baixo), CordaChords.frets("Am", baixo))
    }

    /** Os 77 acordes da biblioteca caem numa casa que existe, e na nota certa. */
    @Test
    fun everyChordLandsOnItsRoot() {
        val baixo = CordaInstrument.baixo
        for (chord in ChordLibrary.all) {
            val frets = CordaChords.frets(chord.id, baixo)
            assertNotNull("${chord.id} não devolveu forma", frets)
            assertEquals(baixo.stringCount, frets!!.size)
            val sounding = baixo.strings.zip(frets).filter { it.second >= 0 }
            assertEquals("${chord.id} faz soar ${sounding.size} cordas", 1, sounding.size)
            val (spec, fret) = sounding.first()
            assertTrue("${chord.id} pede a casa $fret", fret >= 0 && fret <= baixo.frets)
            assertEquals("${chord.id} soa ${(spec.midi + fret) % 12} em vez da fundamental", MusicNotes.pitchClass(chord.root), (spec.midi + fret) % 12)
        }
    }

    @Test
    fun capoTransposesTheRoot() {
        val baixo = CordaInstrument.baixo
        for (chord in ChordLibrary.all.take(20)) {
            val open = CordaChords.frets(chord.id, baixo) ?: continue
            val capoed = CordaChords.frets(chord.id, baixo, capo = 3) ?: continue
            val openNote = baixo.strings.zip(open).firstOrNull { it.second >= 0 } ?: continue
            val capoNote = baixo.strings.zip(capoed).firstOrNull { it.second >= 0 } ?: continue
            val rise = (capoNote.first.midi + capoNote.second) - (openNote.first.midi + openNote.second)
            assertEquals("${chord.id} subiu $rise semitons com capo 3", 3, rise)
        }
    }
}
