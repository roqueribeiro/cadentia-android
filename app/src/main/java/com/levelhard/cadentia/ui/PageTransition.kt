package com.levelhard.cadentia.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Transição de entrada de tela: cada aba respira para dentro (fade + subida
 * suave) ao ser selecionada — espelho do `PageTransition.swift`. Reduce
 * Motion → no-op.
 */
@Composable
fun Modifier.pageTransition(): Modifier {
    val reduceMotion = rememberReduceMotion()
    if (reduceMotion) return this

    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }

    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = spring(
            dampingRatio = 0.85f,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "page-transition",
    )
    val density = LocalDensity.current
    return graphicsLayer {
        alpha = progress
        translationY = (1f - progress) * with(density) { 14.dp.toPx() }
    }
}
