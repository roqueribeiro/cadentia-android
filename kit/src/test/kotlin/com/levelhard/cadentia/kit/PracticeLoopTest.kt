package com.levelhard.cadentia.kit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O loop de estudo veio do primeiro feedback público do app; estas regras são
 * o contrato dele — port 1:1 do `PracticeLoopTests.swift`.
 */
class PracticeLoopTest {
    /**
     * Quem toca marca o fim antes do começo o tempo todo (ouviu o solo acabar,
     * só então pensou no início). A ordem dos pontos não pode importar.
     */
    @Test fun acceptsPointsInEitherOrder() {
        val forward = PracticeLoop.of(start = 2.0, end = 6.0)!!
        val backward = PracticeLoop.of(start = 6.0, end = 2.0)!!
        assertEquals(forward, backward)
        assertEquals(2.0, forward.start, 0.0)
        assertEquals(6.0, forward.end, 0.0)
    }

    /**
     * Menos de meio segundo não é trecho, é soluço: o player voltaria antes de
     * qualquer coisa soar.
     */
    @Test fun refusesLoopsTooShortToHear() {
        assertNull(PracticeLoop.of(start = 3.0, end = 3.2))
        assertNull(PracticeLoop.of(start = 3.0, end = 3.0))
        assertNotNull(PracticeLoop.of(start = 3.0, end = 3.5))
    }

    @Test fun containsIsHalfOpen() {
        val loop = PracticeLoop.of(start = 2.0, end = 6.0)!!
        assertTrue(loop.contains(2.0))
        assertTrue(loop.contains(5.999))
        assertFalse("o fim dispara a volta, não pertence ao trecho", loop.contains(6.0))
        assertFalse(loop.contains(1.9))
    }

    /**
     * Um loop salvo pode ter vindo de outra edição do arquivo: ajustado à
     * duração real, ou sobra um trecho válido ou não há loop.
     */
    @Test fun clampSurvivesShorterSongsOrDies() {
        val loop = PracticeLoop.of(start = 10.0, end = 20.0)!!
        val clamped = loop.clamped(toDuration = 14.0)!!
        assertEquals(14.0, clamped.end, 0.0)
        assertNull("sobrou menos que o mínimo audível", loop.clamped(toDuration = 10.2))
    }
}
