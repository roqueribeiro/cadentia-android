package com.levelhard.cadentia.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Hero de feature em pé de obra: o palco com o acento da feature, o ícone
 * com halo e o título. É o que cada tela mostra até a feature de verdade
 * chegar — bonito o bastante para não parecer quebrado, honesto o bastante
 * para não fingir que está pronto.
 */
@Composable
fun FeatureHero(
    titleRes: Int,
    icon: Painter,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        PremiumBackground(accent = accent)
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(148.dp)) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(accent.copy(alpha = 0.35f), Color.Transparent),
                        ),
                    )
                }
                Icon(
                    painter = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(56.dp),
                )
            }
            Text(
                text = stringResource(titleRes),
                style = MaterialTheme.typography.headlineLarge,
                color = CzTokens.textPrimary,
            )
        }
    }
}
