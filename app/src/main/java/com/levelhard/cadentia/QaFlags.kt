package com.levelhard.cadentia

import androidx.compose.runtime.compositionLocalOf

/**
 * Ajudantes de QA lidos dos extras do intent — os mesmos nomes dos launch
 * args do iOS, para o harness de QA falar uma língua só:
 *
 *   adb shell am start -n com.levelhard.cadentia.debug/com.levelhard.cadentia.MainActivity \
 *     -e qa-tab metronome --ez qa-no-splash true --ez qa-tuner-silent true --ez qa-reset true
 */
data class QaFlags(
    val tab: String? = null,
    val noSplash: Boolean = false,
    /** Screenshots de CI rodam sem permissão de mic: suprime a auto-ativação. */
    val tunerSilent: Boolean = false,
    val reset: Boolean = false,
    /** Tablaturas: abre já em modo de edição com a célula (2,4) marcada. */
    val edit: Boolean = false,
    /** Tablaturas: abre com o catálogo de bases por cima. */
    val showCatalog: Boolean = false,
    /** Frequência: começa tocando ao abrir (screenshot do osciloscópio vivo). */
    val studioAutoplay: Boolean = false,
    /** Cordas: `frets`, `chords` ou `camera` (o `-qa-cordas-mode` do iOS). */
    val cordasMode: String? = null,
    /** Cordas: `violao`, `guitarra`, `viola` ou `baixo`. */
    val cordasInstrument: String? = null,
    /** Cordas: `true` = braço sem mão direita (`-qa-cordas-hands-free`); `false` = com batida (`-qa-cordas-strummed`). */
    val cordasHandsFree: Boolean? = null,
    /** Cordas: abre com o painel por cima, expandido. */
    val cordasPanel: Boolean = false,
    /** Cordas: toca um acorde em cada instrumento sem dedo e loga `QA-CORDAS-SELFTEST-OK`. */
    val cordasSelftest: Boolean = false,
    /** Cordas: mostra o treinador mesmo com `qa-tab` (que normalmente o cala). */
    val cordasCoach: Boolean = false,
    /** Cordas: câmera trocada por uma pose gravada — sem permissão, sem mão. */
    val cordasReplay: Boolean = false,
    /** Estudo: abre já no instrumento (`violao`, `guitarra`, `viola`, `baixo`). */
    val studyInstrument: String? = null,
)

val LocalQaFlags = compositionLocalOf { QaFlags() }
