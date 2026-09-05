package com.levelhard.cadentia.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * O `Slider` do sistema do iOS, que é o que o metrônomo, a bateria, a
 * Frequência, o gravador e a tablatura usam lá: trilho fino de 4 pt, parte
 * percorrida na cor do acento, botão redondo BRANCO de 27 pt com sombra. O
 * Slider padrão do Material 3 (trilho de 16 dp, cursor em barra vertical,
 * ponto de fim) era a diferença mais repetida na comparação tela a tela de
 * 05/09. O mixer de stems tem o seu próprio `ThinSlider`, também como no iOS.
 *
 * Mesma assinatura do `Slider` do M3 para a troca ser mecânica.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CzSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    accent: Color,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val inactive = CzTokens.textPrimary.copy(alpha = 0.14f)
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.height(32.dp),
        enabled = enabled,
        valueRange = valueRange,
        steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        interactionSource = interaction,
        thumb = {
            Box(
                Modifier
                    .size(27.dp)
                    .shadow(4.dp, CircleShape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
                    .background(if (enabled) Color.White else Color.White.copy(alpha = 0.6f), CircleShape),
            )
        },
        track = { state: SliderState ->
            val fraction = ((state.value - state.valueRange.start) /
                (state.valueRange.endInclusive - state.valueRange.start)).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(4.dp)) {
                Box(Modifier.fillMaxWidth().height(4.dp).background(inactive, CircleShape))
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(4.dp)
                        .align(Alignment.CenterStart)
                        .background(if (enabled) accent else accent.copy(alpha = 0.5f), CircleShape),
                )
            }
        },
    )
}

/** Cores para quem ainda chama o `Slider` cru (folhas antigas); prefira [CzSlider]. */
@Composable
fun czSliderColors(accent: Color) = SliderDefaults.colors(
    thumbColor = Color.White,
    activeTrackColor = accent,
    inactiveTrackColor = CzTokens.textPrimary.copy(alpha = 0.14f),
)
