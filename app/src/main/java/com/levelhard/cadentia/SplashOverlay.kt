package com.levelhard.cadentia

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.levelhard.cadentia.ui.CzTokens
import com.levelhard.cadentia.ui.PremiumBackground
import com.levelhard.cadentia.ui.WordmarkFamily
import com.levelhard.cadentia.ui.rememberReduceMotion
import kotlinx.coroutines.delay

/**
 * Abertura animada: o ring gauge se desenha em dourado sobre o palco, o
 * wordmark surge, e tudo se dissolve no app (~1,4 s) — espelho do
 * `SplashOverlay` do iOS. A tela de launch do Android é estática; é aqui
 * que a marca se move. Reduce Motion pula direto para o quadro final.
 */
@Composable
fun SplashOverlay(onFinish: () -> Unit) {
    val reduceMotion = rememberReduceMotion()
    var ringTarget by remember { mutableStateOf(if (reduceMotion) 1f else 0f) }
    var showWordmark by remember { mutableStateOf(reduceMotion) }

    val ringProgress by animateFloatAsState(
        targetValue = ringTarget,
        animationSpec = tween(durationMillis = 700),
        label = "splash-ring",
    )
    val wordmarkProgress by animateFloatAsState(
        targetValue = if (showWordmark) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.8f),
        label = "splash-wordmark",
    )

    LaunchedEffect(Unit) {
        ringTarget = 1f
        delay(350)
        showWordmark = true
        delay(if (reduceMotion) 500 else 1050)
        onFinish()
    }

    Box(Modifier.fillMaxSize()) {
        PremiumBackground(accent = CzTokens.gold)
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(110.dp)) {
                    val stroke = 7.dp.toPx()
                    // -90° para começar no topo, como o trim do iOS.
                    rotate(-90f) {
                        drawArc(
                            brush = Brush.sweepGradient(
                                colors = listOf(
                                    CzTokens.gold.copy(alpha = 0.25f),
                                    CzTokens.gold,
                                ),
                                center = Offset(size.width / 2f, size.height / 2f),
                            ),
                            startAngle = 0f,
                            sweepAngle = 360f * ringProgress,
                            useCenter = false,
                            style = Stroke(width = stroke, cap = StrokeCap.Round),
                        )
                    }
                    // Halo dourado por trás do anel.
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(CzTokens.gold.copy(alpha = 0.25f), Color.Transparent),
                        ),
                        radius = size.minDimension * 0.9f,
                    )
                }
                Icon(
                    painter = painterResource(R.drawable.ic_tab_tuner),
                    contentDescription = null,
                    tint = CzTokens.gold,
                    modifier = Modifier
                        .size(42.dp)
                        .graphicsLayer {
                            alpha = wordmarkProgress
                            scaleX = 0.7f + 0.3f * wordmarkProgress
                            scaleY = 0.7f + 0.3f * wordmarkProgress
                        },
                )
            }
            Text(
                text = "Cadentia", // i18n-verbatim: wordmark
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = WordmarkFamily,
                color = CzTokens.textPrimary,
                modifier = Modifier
                    .graphicsLayer {
                        alpha = wordmarkProgress
                        translationY = (1f - wordmarkProgress) * 8.dp.toPx()
                    },
            )
        }
    }
}
