package com.levelhard.cadentia.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.sp

/**
 * Tema do Cadentia: Material 3 **somente escuro** (o app é `.dark` no iOS e
 * não existe variante clara para manter), com a personalidade da marca por
 * cima — dourado como primário, palco grafite como fundo.
 */
private val CadentiaColors = darkColorScheme(
    primary = CzTokens.gold,
    onPrimary = CzTokens.stageBottom,
    secondary = CzTokens.tunerGreen,
    onSecondary = CzTokens.stageBottom,
    background = CzTokens.stageBottom,
    onBackground = CzTokens.textPrimary,
    surface = CzTokens.stageTop,
    onSurface = CzTokens.textPrimary,
    surfaceVariant = CzTokens.stageTop,
    onSurfaceVariant = CzTokens.textSecondary,
    outline = CzTokens.hairline,
    error = CzTokens.danger,
)

/** Números em mostrador não podem dançar: dígito tabular por padrão. */
private val Numeric = TextStyle(
    fontFeatureSettings = "tnum",
    textMotion = TextMotion.Static,
)

private val CadentiaTypography = Typography(
    displayLarge = Numeric.copy(fontSize = 56.sp, fontWeight = FontWeight.Bold),
    displayMedium = Numeric.copy(fontSize = 44.sp, fontWeight = FontWeight.Bold),
    headlineLarge = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
)

/** A serifa do wordmark ("Cadentia" na splash e no rodapé do Mais). */
val WordmarkFamily = FontFamily.Serif

@Composable
fun CadentiaTheme(content: @Composable () -> Unit) {
    // O tema do sistema é ignorado de propósito: o palco é sempre escuro.
    MaterialTheme(
        colorScheme = CadentiaColors,
        typography = CadentiaTypography,
        content = content,
    )
}
