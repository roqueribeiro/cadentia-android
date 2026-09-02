package com.levelhard.cadentia.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * O palco do Cadentia: gradiente grafite profundo com UM glow de acento que
 * respira devagar e um underlight dourado quente embaixo — espelho do
 * `PremiumBackground.swift`. Lições que atravessam a plataforma: camada
 * decorativa é fundo, nunca filho de layout; um blob só (quatro blurs
 * animados custavam fluidez de scroll no aparelho); com Reduce Motion o
 * glow fica parado no meio do caminho.
 *
 * Em vez de blur caro, cada mancha é um gradiente radial que morre em
 * transparente — mesmo visual, custo de um draw.
 */
@Composable
fun PremiumBackground(
    accent: Color = CzTokens.gold,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = rememberReduceMotion()
    val breathe: Float = if (reduceMotion) {
        0.13f
    } else {
        val transition = rememberInfiniteTransition(label = "stage-breathe")
        val value by transition.animateFloat(
            initialValue = 0.10f,
            targetValue = 0.16f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 7000),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "glow-alpha",
        )
        value
    }

    val density = LocalDensity.current
    Box(
        modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(CzTokens.stageTop, CzTokens.stageBottom),
                ),
            ),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val glowRadius = with(density) { 300.dp.toPx() }

            // O glow de acento, alto no palco, respirando.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = breathe), Color.Transparent),
                    center = Offset(w / 2f, h * 0.18f),
                    radius = glowRadius,
                ),
                radius = glowRadius,
                center = Offset(w / 2f, h * 0.18f),
            )

            // Underlight dourado — uma elipse achatada perto do chão.
            val underY = h * 0.92f
            val underR = with(density) { 280.dp.toPx() }
            scale(scaleX = 1.9f, scaleY = 1f, pivot = Offset(w / 2f, underY)) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(CzTokens.gold.copy(alpha = 0.05f), Color.Transparent),
                        center = Offset(w / 2f, underY),
                        radius = underR,
                    ),
                    radius = underR,
                    center = Offset(w / 2f, underY),
                )
            }

            // Vinheta: escurece as bordas para o conteúdo assentar.
            val diag = kotlin.math.hypot(w, h)
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.4f)),
                    center = Offset(w / 2f, h / 2f),
                    radius = diag * 0.62f,
                ),
                size = Size(w, h),
            )
        }
    }
}
