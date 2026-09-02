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
)

val LocalQaFlags = compositionLocalOf { QaFlags() }
