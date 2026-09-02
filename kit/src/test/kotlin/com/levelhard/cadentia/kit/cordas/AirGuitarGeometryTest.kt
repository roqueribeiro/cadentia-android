package com.levelhard.cadentia.kit.cordas

import com.levelhard.cadentia.kit.ChordLibrary
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A máquina de cruzamentos do modo câmera — port do `AirGuitarGeometryTests`.
 * São os testes que impedem um refactor bem-intencionado de trazer de volta os
 * bugs que o repo cordas já pagou: a corda que nunca mais soa depois de um
 * gesto abortado, a nota que toca duas vezes porque a previsão e a mão real
 * contaram, e a batida que chega como um acorde só em vez de seis cordas.
 */
class AirGuitarGeometryTest {
    private fun placed(strings: Int = 6): AirGuitarGeometry {
        val geometry = AirGuitarGeometry()
        geometry.placeForTesting(body = Point(200.0, 600.0), axis = Vector(0.0, -1.0), length = 300.0, stringCount = strings)
        geometry.setNowForTesting(0.0)
        return geometry
    }

    @Test
    fun theBassSitsOnTopAndTheTrebleBelow() {
        val geometry = placed()
        assertTrue(geometry.stringU(0) > 0)
        assertTrue(geometry.stringU(5) < 0)
        assertTrue(abs(geometry.stringU(0) + geometry.stringU(5)) < 1e-9)
    }

    @Test
    fun everyPassSoundsEveryString() {
        val geometry = placed()
        geometry.cross(-0.3, 0.3, 0.04, isReal = true)
        val plucks = geometry.pendingPlucksForTesting
        assertEquals("uma passada tem que tocar as seis", 6, plucks.size)
        assertEquals("vindo de baixo, a prima é a primeira", 5, plucks.first().string)
        assertEquals("e o bordão é a última", 0, plucks.last().string)
    }

    @Test
    fun theOtherDirectionReversesTheOrder() {
        val geometry = placed()
        geometry.cross(0.3, -0.3, 0.04, isReal = true)
        val plucks = geometry.pendingPlucksForTesting
        assertEquals(6, plucks.size)
        assertEquals(0, plucks.first().string)
        assertEquals(5, plucks.last().string)
    }

    @Test
    fun theStringsLandSpreadInsideTheFrameAndInOrder() {
        val geometry = placed()
        val dt = 0.045
        geometry.cross(-0.3, 0.3, dt, isReal = true)
        val delays = geometry.pendingPlucksForTesting.map { it.delay }
        assertEquals(6, delays.size)
        assertEquals("a primeira corda é a referência", 0.0, delays[0], 0.0)
        for (i in 1 until delays.size) assertTrue("os instantes têm que subir", delays[i] > delays[i - 1])
        assertTrue("e caber dentro do intervalo do quadro", delays.last() < dt)
    }

    @Test
    fun thePredictionFiresAndTheRealCrossingIsSilent() {
        val geometry = placed()
        geometry.cross(-0.3, 0.3, 0.02, isReal = false)
        assertEquals("a previsão toca as seis", 6, geometry.pendingPlucksForTesting.size)
        geometry.clearPendingForTesting()
        geometry.setNowForTesting(1.0)
        geometry.cross(-0.3, 0.3, 0.04, isReal = true)
        assertTrue("o cruzamento real consome a dívida em silêncio", geometry.pendingPlucksForTesting.isEmpty())
        assertTrue("e rearma tudo", geometry.armedForTesting.all { it })
    }

    @Test
    fun anAbortedGestureRearmsTheString() {
        val geometry = placed()
        geometry.cross(-0.3, 0.3, 0.02, isReal = false)
        assertTrue("a previsão desarmou tudo", geometry.armedForTesting.none { it })
        geometry.clearPendingForTesting()
        geometry.setNowForTesting(1.0)
        geometry.cross(-0.4, -0.5, 0.04, isReal = true)
        assertTrue("gesto abortado tem que devolver as cordas", geometry.armedForTesting.all { it })
    }

    @Test
    fun aStringDoesNotSoundTwiceInsideTheRetriggerWindow() {
        val geometry = placed()
        geometry.setNowForTesting(0.0)
        geometry.cross(-0.3, 0.3, 0.04, isReal = true)
        geometry.clearPendingForTesting()
        geometry.setNowForTesting(0.03)
        geometry.cross(0.3, -0.3, 0.04, isReal = true)
        assertTrue("30 ms é o mesmo gesto", geometry.pendingPlucksForTesting.isEmpty())
        geometry.setNowForTesting(0.5)
        geometry.cross(-0.3, 0.3, 0.04, isReal = true)
        assertTrue("meio segundo depois é outra passada", geometry.pendingPlucksForTesting.isNotEmpty())
    }

    @Test
    fun mutedStringsAreSkipped() {
        val geometry = placed()
        geometry.setPlayableForTesting(listOf(false, true, true, false, true, true))
        geometry.cross(-0.3, 0.3, 0.04, isReal = true)
        assertEquals(setOf(1, 2, 4, 5), geometry.pendingPlucksForTesting.map { it.string }.toSet())
    }

    @Test
    fun aPassThatTouchesNoStringMakesNoSound() {
        val geometry = placed()
        geometry.cross(0.4, 0.9, 0.04, isReal = true)
        assertTrue(geometry.pendingPlucksForTesting.isEmpty())
    }

    @Test
    fun fasterHandsPlayHarder() {
        val geometry = placed()
        geometry.cross(-0.05, 0.05, 0.12, isReal = true)
        val slow = geometry.pendingPlucksForTesting.maxOfOrNull { it.velocity } ?: 0.0
        geometry.clearPendingForTesting()
        geometry.setNowForTesting(1.0)
        geometry.cross(-0.05, 0.05, 0.03, isReal = true)
        val fast = geometry.pendingPlucksForTesting.maxOfOrNull { it.velocity } ?: 0.0
        assertTrue("os dois têm que cair dentro da escala", slow > 0.15 && fast < 1.0)
        assertTrue("a velocidade do gesto é a dinâmica", fast > slow * 1.5)
    }

    @Test
    fun theViolaCrossesTenStrings() {
        val geometry = placed(strings = 10)
        geometry.cross(-0.6, 0.6, 0.05, isReal = true)
        assertEquals(10, geometry.pendingPlucksForTesting.size)
    }

    @Test
    fun hysteresisIsExpressedInTime() {
        assertTrue(AirGuitarGeometry.SHAPE_HOLD > 0.1 && AirGuitarGeometry.SHAPE_HOLD < 0.2)
        assertTrue(AirGuitarGeometry.MODE_HOLD > AirGuitarGeometry.SHAPE_HOLD)
        assertTrue(AirGuitarGeometry.LOOKAHEAD > 0 && AirGuitarGeometry.LOOKAHEAD < 0.1)
    }

    @Test
    fun theSeatedPostureSpreadsTheStringsWider() {
        assertTrue(AirGuitarPosture.seated.spacing > AirGuitarPosture.standing.spacing)
        assertEquals(AirGuitarPosture.seated, AirGuitarPosture.standing.flipped)
    }
}

/** Port do `HandChordMappingTests`. */
class HandChordMappingTest {
    @Test
    fun everyMaskLandsOnAShapeAndNeverOnNothing() {
        val names = CordaChords.sixStringSet
        for (mask in 0..15) {
            assertTrue("a máscara $mask caiu fora da lista", HandChordMapping.nearestShape(mask) in HandChordMapping.shapes)
            assertNotNull("a máscara $mask ficou sem acorde", HandChordMapping.chord(mask, names))
        }
    }

    @Test
    fun aShortSongStillGivesEveryShapeAChord() {
        val short = listOf("C", "G", "Am", "F")
        for (mask in 0..15) assertNotNull("música curta deixou a máscara $mask muda", HandChordMapping.chord(mask, short))
    }

    @Test
    fun theLegendSlotNamesTheChordThatWillSound() {
        for (names in listOf(CordaChords.sixStringSet, CordaChords.violaSet, listOf("C", "G", "Am", "F"))) {
            for (mask in 0..15) {
                val slot = HandChordMapping.shapeIndex(mask)
                assertTrue("slot fora da lista de formas", slot < HandChordMapping.shapes.size)
                assertEquals("a legenda mostrou um acorde e o som tocou outro (máscara $mask)", HandChordMapping.chord(mask, names), names[slot % names.size])
            }
        }
    }

    @Test
    fun everyShapeHasItsOwnSlotInTheLegend() {
        val slots = (0..15).map { HandChordMapping.shapeIndex(it) }.toSet()
        assertEquals("forma sem slot na legenda", HandChordMapping.shapes.size, slots.size)
    }

    @Test
    fun aListedShapeIsItsOwnNearest() {
        for (shape in HandChordMapping.shapes) assertEquals(shape, HandChordMapping.nearestShape(shape))
    }

    @Test
    fun theSameFingerCountWinsBeforeTheSmallerDifference() {
        assertEquals(2, HandChordMapping.fingerCount(HandChordMapping.nearestShape(0b0101)))
    }

    @Test
    fun onlyShapesAHandActuallyMakesAreOffered() {
        assertEquals(9, HandChordMapping.shapes.size)
        assertFalse(0b0100 in HandChordMapping.shapes)
        assertTrue("o chifrinho existe", 0b1001 in HandChordMapping.shapes)
    }

    @Test
    fun theViolaHasItsOwnShapesByCourse() {
        val viola = CordaInstrument.viola
        val frets = CordaChords.frets("G", viola)
        assertEquals("dez cordas, uma casa cada", 10, frets?.size)
        assertEquals(frets!![0], frets[1])
        assertEquals(6, CordaChords.frets("G", CordaInstrument.violao)?.size)
        assertNull(CordaChords.frets("NaoExiste", CordaInstrument.violao))
    }

    @Test
    fun everySixStringChordComesFromTheSharedLibrary() {
        for (name in CordaChords.sixStringSet) {
            assertTrue("$name não está no ChordLibrary gerado do web", ChordLibrary.all.any { it.id == name })
        }
    }
}

/** A calibração decide o eixo do instrumento uma vez — port do `AirGuitarCalibrationTests`. */
class AirGuitarCalibrationTest {
    private val view = Size(400.0, 800.0)

    private fun hand(centre: Point, chirality: HandChirality): HandLandmarks {
        val points = (0 until 21).map { slot ->
            val finger = maxOf(0, (slot - 1) / 4)
            val along = ((slot - 1) % 4 + 1) * 46 * 0.32
            val across = (finger - 1.5) * 46 * 0.30
            if (slot == 0) centre else Point(centre.x + across, centre.y - along)
        }
        return HandLandmarks(points, chirality)
    }

    private fun settle(geometry: AirGuitarGeometry, neck: Point, pick: Point): AirGuitarGeometry.Frame {
        var frame = AirGuitarGeometry.Frame()
        var time = 0.0
        for (i in 0 until 90) {
            time += 1.0 / 30
            frame = geometry.update(
                listOf(hand(neck, HandChirality.Left), hand(pick, HandChirality.Right)),
                time, CordaInstrument.violao, view,
            ) { List(6) { true } }
        }
        return frame
    }

    /** Relatado do aparelho: "o violão fica em pé". */
    @Test
    fun handsStackedOneAboveTheOtherNeverCalibrate() {
        val geometry = AirGuitarGeometry()
        val frame = settle(geometry, Point(200.0, 250.0), Point(200.0, 560.0))
        assertEquals("pose em pé não pode virar um violão", AirGuitarGeometry.Calibration.Aiming, frame.calibration)
    }

    @Test
    fun handsOnTheDiagonalDoCalibrate() {
        val geometry = AirGuitarGeometry()
        val frame = settle(geometry, Point(120.0, 260.0), Point(285.0, 560.0))
        assertEquals("a pose de tocar tem que calibrar", AirGuitarGeometry.Calibration.Ready, frame.calibration)
        assertTrue(abs(frame.axis.dx) >= AirGuitarGeometry.MINIMUM_TILT)
    }

    @Test
    fun handednessComesOutOfTheChirality() {
        val geometry = AirGuitarGeometry()
        val frame = settle(geometry, Point(120.0, 260.0), Point(285.0, 560.0))
        assertEquals(PlayerHandedness.Right, frame.handedness)
        assertEquals(HandChirality.Left, geometry.resolvedNeckChirality)
    }

    @Test
    fun aLeftHandedPlayerIsReadAsLeftHanded() {
        val geometry = AirGuitarGeometry()
        var frame = AirGuitarGeometry.Frame()
        var time = 0.0
        for (i in 0 until 90) {
            time += 1.0 / 30
            frame = geometry.update(
                listOf(hand(Point(285.0, 260.0), HandChirality.Right), hand(Point(120.0, 560.0), HandChirality.Left)),
                time, CordaInstrument.violao, view,
            ) { List(6) { true } }
        }
        assertEquals(AirGuitarGeometry.Calibration.Ready, frame.calibration)
        assertEquals("a mão do braço é a direita: canhoto", PlayerHandedness.Left, frame.handedness)
    }

    @Test
    fun sayingItOutLoudOverrulesTheGuess() {
        val geometry = AirGuitarGeometry()
        settle(geometry, Point(120.0, 260.0), Point(285.0, 560.0))
        assertEquals(PlayerHandedness.Right, geometry.resolvedHandedness)
        geometry.setHandedness(PlayerHandedness.Left)
        assertEquals(HandChirality.Right, geometry.resolvedNeckChirality)
        assertEquals(PlayerHandedness.Left, geometry.resolvedHandedness)
    }
}

/** Port do `GuitarProfileTests`. */
class GuitarProfileTest {
    @Test
    fun theWaistIsNarrowerThanBothBouts() {
        for (profile in listOf(GuitarProfile.acoustic, GuitarProfile.electric)) {
            val waist = profile.halfWidth(profile.waist)
            assertTrue("cintura x bojo inferior", waist < profile.halfWidth(profile.lowerBout))
            assertTrue("cintura x bojo superior", waist < profile.halfWidth(profile.upperBout))
            assertTrue("o bojo inferior vem antes", profile.lowerBout < profile.waist)
            assertTrue("e o superior depois", profile.waist < profile.upperBout)
        }
    }

    @Test
    fun theLowerBoutIsTheWidestPartAndTheTailIsAPoint() {
        val profile = GuitarProfile.acoustic
        assertEquals("o fundo fecha", 0.0, profile.halfWidth(0.0), 0.0)
        assertTrue(profile.halfWidth(profile.lowerBout) > profile.halfWidth(profile.upperBout))
        for (step in 0..100) {
            val width = profile.halfWidth(step / 100.0)
            assertTrue("largura negativa vira um nó no desenho", width >= 0)
            assertTrue("a spline não pode estourar as estações", width <= 1.02)
        }
    }

    @Test
    fun theOutlineClosesAndHasBothSides() {
        val outline = GuitarProfile.acoustic.outline(samples = 40)
        assertEquals(82, outline.size)
        assertTrue(outline.any { it.y > 0.5 })
        assertTrue(outline.any { it.y < -0.5 })
    }
}

/** Port do `AirGuitarModelTests`. */
class AirGuitarModelTest {
    private fun model(posture: AirGuitarPosture = AirGuitarPosture.standing, strings: Int = 6) = AirGuitarModel(posture, strings)

    @Test
    fun theLandmarksComeInTheOrderAnInstrumentHasThem() {
        for (posture in listOf(AirGuitarPosture.standing, AirGuitarPosture.seated)) {
            val m = model(posture)
            assertTrue("o fundo vem antes do cavalete", m.tail < m.bridge)
            assertTrue("e o cavalete antes da boca", m.bridge < m.soundhole)
            assertTrue("e a boca antes do encontro com o braço", m.soundhole < m.neckJoin)
            assertTrue("e o braço antes da pestana", m.neckJoin < m.nut)
            assertTrue("e a pestana antes da mão", m.nut < m.head)
        }
    }

    @Test
    fun theBodyKeepsAGuitarsAspectRatio() {
        for (posture in listOf(AirGuitarPosture.standing, AirGuitarPosture.seated)) {
            val m = model(posture)
            val ratio = m.bodyLength / (m.bodyHalf * 2)
            assertTrue("mais comprido que largo, como um violão", ratio >= AirGuitarModel.MINIMUM_ASPECT - 0.001)
            assertTrue("e não uma tábua", ratio < 1.9)
        }
    }

    @Test
    fun theBodyLengthFollowsTheScaleAndNotTheStrings() {
        val m = model()
        assertTrue(
            "o corpo mede-se pela escala",
            abs(m.bodyLength - m.scaleLength * AirGuitarModel.BODY_OVER_SCALE) < 0.001 ||
                m.bodyLength > m.scaleLength * AirGuitarModel.BODY_OVER_SCALE,
        )
    }

    @Test
    fun theBodyAndTheNeckAreWiderThanTheStrings() {
        for (posture in listOf(AirGuitarPosture.standing, AirGuitarPosture.seated)) {
            for (strings in listOf(6, 10)) {
                val m = model(posture, strings)
                assertTrue("corda pendurada fora do braço", m.neckHalfAtNut > m.stringHalf)
                assertTrue("o braço abre indo para o corpo", m.neckHalfAtJoin > m.neckHalfAtNut)
                assertTrue("o corpo é mais largo que o braço", m.bodyHalf > m.neckHalfAtJoin)
            }
        }
    }

    @Test
    fun nothingAcrossCanChangeWithoutTheRest() {
        val narrow = model(AirGuitarPosture.standing)
        val wide = model(AirGuitarPosture.seated)
        val factor = wide.stringHalf / narrow.stringHalf
        assertTrue("sentado abre as cordas", factor > 1)
        assertTrue(abs(wide.bodyHalf / narrow.bodyHalf - factor) < 0.001)
        assertTrue(abs(wide.headHalf / narrow.headHalf - factor) < 0.001)
        assertTrue(abs(wide.neckHalfAtNut / narrow.neckHalfAtNut - factor) < 0.001)
    }

    @Test
    fun theDrawnBridgeSitsWhereTheStringsStart() {
        val posture = AirGuitarPosture.standing
        val m = model(posture)
        assertTrue(abs(m.bridge - (posture.bridge + 0.10)) < 1e-9)
        assertTrue(abs(m.nut - posture.nut) < 1e-9)
        assertTrue(abs(m.bodyFraction(m.bridge) - AirGuitarModel.BRIDGE_ALONG_BODY) < 0.001)
    }

    @Test
    fun theWholeInstrumentStaysAroundTwoNeckLengths() {
        for (posture in listOf(AirGuitarPosture.standing, AirGuitarPosture.seated)) {
            val m = model(posture)
            assertTrue("menos que isso não parece um violão", m.totalLength > 1.6)
            assertTrue("mais que isso não cabe em tela nenhuma", m.totalLength < 2.3)
        }
    }

    @Test
    fun theNeckNarrowsEvenlyFromTheBodyToTheNut() {
        val m = model()
        assertTrue(abs(m.neckHalf(m.neckJoin) - m.neckHalfAtJoin) < 1e-9)
        assertTrue(abs(m.neckHalf(m.nut) - m.neckHalfAtNut) < 1e-9)
        val middle = m.neckHalf((m.neckJoin + m.nut) / 2)
        assertTrue(middle < m.neckHalfAtJoin && middle > m.neckHalfAtNut)
    }
}

/** Port do `AirGuitarFitTests`. */
class AirGuitarFitTest {
    private val view = Size(402.0, 636.0)

    @Test
    fun theWholeInstrumentStaysInsideTheView() {
        val model = AirGuitarModel(AirGuitarPosture.seated, 6)
        val anchor = Point(260.0, 400.0)
        var degrees = 0.0
        while (degrees <= 60.0) {
            val radians = degrees * Math.PI / 180
            val axis = Vector(-sin(radians), -cos(radians))
            val room = model.fittingLength(axis, anchor, view)
            assertTrue("sempre tem que sobrar algum comprimento", room > 0)
            val perpendicular = Vector(-axis.dy, axis.dx)
            for (along in doubleArrayOf(model.tail, model.head)) {
                for (across in doubleArrayOf(-model.bodyHalf, model.bodyHalf)) {
                    val x = anchor.x + (axis.dx * along + perpendicular.dx * across) * room
                    val y = anchor.y + (axis.dy * along + perpendicular.dy * across) * room
                    assertTrue("saiu pela lateral em $degrees°", x >= -1 && x <= view.width + 1)
                    assertTrue("saiu por cima ou por baixo", y >= -1 && y <= view.height + 1)
                }
            }
            degrees += 15.0
        }
    }

    @Test
    fun aPoseThatFitsIsLeftAlone() {
        val model = AirGuitarModel(AirGuitarPosture.standing, 6)
        val room = model.fittingLength(Vector(-0.40, -0.92), Point(242.0, 332.0), view)
        assertTrue("a pose do guia não pode ser encolhida", room > 240)
    }
}
