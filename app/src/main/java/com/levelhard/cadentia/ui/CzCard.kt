package com.levelhard.cadentia.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * A superfície canônica do Cadentia — espelho do `CZCard.swift`. No iOS é
 * Liquid Glass; aqui é vidro honesto: branco a 5% sobre o palco com traço
 * hairline de 1 px. (Blur de verdade em Compose custa um RenderNode por card
 * e não paga a fluidez que ele tira — o palco escuro já dá a leitura.)
 */
@Composable
fun CzCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = CzTokens.radiusLG,
    content: @Composable () -> Unit,
) {
    val shape: Shape = RoundedCornerShape(cornerRadius)
    Surface(
        modifier = modifier,
        shape = shape,
        color = CzTokens.surface,
        contentColor = CzTokens.textPrimary,
        border = BorderStroke(1.dp, CzTokens.hairline),
    ) {
        Box { content() }
    }
}
