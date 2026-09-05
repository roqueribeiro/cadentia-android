package com.levelhard.cadentia.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * O `Toggle` do iOS: botão branco sempre do mesmo tamanho, trilho na cor do
 * acento ligado e cinza translúcido desligado, sem borda. O Switch do
 * Material 3 desligado encolhe o botão e o pinta de cinza escuro, e é isso
 * que denunciava a plataforma nas telas de bateria, Frequência, Som e Cordas
 * (comparação tela a tela de 05/09).
 */
@Composable
fun CzSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    accent: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        // Com conteúdo no botão o M3 usa o botão grande nos dois estados;
        // vazio, só o tamanho muda — e é o tamanho constante que queremos.
        thumbContent = { Spacer(Modifier.size(SwitchDefaults.IconSize)) },
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = accent,
            checkedBorderColor = Color.Transparent,
            checkedIconColor = Color.Transparent,
            uncheckedThumbColor = Color.White,
            uncheckedTrackColor = CzTokens.textPrimary.copy(alpha = 0.16f),
            uncheckedBorderColor = Color.Transparent,
            uncheckedIconColor = Color.Transparent,
            disabledCheckedThumbColor = Color.White.copy(alpha = 0.6f),
            disabledCheckedTrackColor = accent.copy(alpha = 0.4f),
            disabledUncheckedThumbColor = Color.White.copy(alpha = 0.6f),
            disabledUncheckedTrackColor = CzTokens.textPrimary.copy(alpha = 0.08f),
        ),
    )
}
