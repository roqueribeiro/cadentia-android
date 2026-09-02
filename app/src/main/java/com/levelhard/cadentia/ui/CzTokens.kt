package com.levelhard.cadentia.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Tokens do Cadentia — palco escuro premium: grafite profundo, cromo dourado
 * quente e UM acento saturado por feature (verde do afinador, âmbar do
 * metrônomo). Espelho 1:1 do `CZTokens.swift` do CadentiaUI; número em tela é
 * sempre tabular (ver [CadentiaTheme]).
 */
object CzTokens {
    // Marca
    /** Dourado quente — cromo, momentos de marca. */
    val gold = Color(0xFFE2B457)
    /** Acento do afinador (e o verde universal de "afinado"). */
    val tunerGreen = Color(0xFF30D97E)
    /** Acento do metrônomo. */
    val metronomeAmber = Color(0xFFFF9F0A)
    val danger = Color(0xFFFF453A)
    /** Zona de alerta de desafinação. */
    val warnAmber = Color(0xFFFF9500)

    // Acentos por feature (do RootView do iOS)
    val studioPurple = Color(0xFFAF52DE)
    val tabIndigo = Color(0xFF6366F1)
    val recorderCyan = Color(0xFF5EE3FF)
    val stemsTeal = Color(0xFF2BD9C4)

    // Palco (fundo)
    val stageTop = Color(0xFF12101C)
    val stageBottom = Color(0xFF07070B)

    // Texto sobre escuro
    val textPrimary = Color.White.copy(alpha = 0.96f)
    val textSecondary = Color.White.copy(alpha = 0.72f)
    val textTertiary = Color.White.copy(alpha = 0.55f)

    // Superfícies
    val hairline = Color.White.copy(alpha = 0.08f)
    val surface = Color.White.copy(alpha = 0.05f)

    // Raios
    val radiusSM = 8.dp
    val radiusMD = 14.dp
    val radiusLG = 22.dp
}
