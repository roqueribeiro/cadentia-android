package com.levelhard.cadentia.features.tuner

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.levelhard.cadentia.ui.CzTokens
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * O mostrador do Cadentia — port do `TunerRingGauge`: arco de 150° com zonas
 * vermelho→âmbar→verde, ponteiro brilhando SOBRE o anel (sem pivô central,
 * que "lia como inacabada") e a nota detectada grande dentro do arco.
 */
@Composable
fun TunerRingGauge(
    cents: Int,
    note: String,
    octave: String,
    frequencyLabel: String?,
    active: Boolean,
    isTuned: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val sweep = 75.0f // graus para cada lado, 150° no total

    val animatedCents by animateFloatAsState(
        targetValue = cents.coerceIn(-50, 50).toFloat(),
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f),
        label = "tuner-pointer",
    )

    val zoneColor = when {
        !active -> Color.White.copy(alpha = 0.35f)
        abs(cents) <= 5 -> accent
        abs(cents) <= 20 -> CzTokens.warnAmber
        else -> CzTokens.danger
    }

    val textMeasurer = rememberTextMeasurer()

    BoxWithConstraints(modifier) {
        val widthDp = maxWidth
        val heightDp = maxHeight
        val centerYFromBottomDp = 34.dp
        val radiusDp = min(widthDp.value / 2f - 30f, heightDp.value - 92f).dp

        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height - centerYFromBottomDp.toPx())
            val radius = radiusDp.toPx()
            val stroke = 14.dp.toPx()

            fun degrees(cents: Float): Float = cents / 50f * sweep

            fun drawZone(fromCents: Float, toCents: Float, color: Color, cap: StrokeCap, width: Float) {
                val start = -90f + degrees(fromCents)
                drawArc(
                    color = color,
                    startAngle = start,
                    sweepAngle = degrees(toCents) - degrees(fromCents),
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(width = width, cap = cap),
                )
            }

            // Trilho + zonas graduadas (bordas vermelhas → âmbar → núcleo).
            drawZone(-50f, 50f, Color.White.copy(alpha = 0.08f), StrokeCap.Round, stroke)
            drawZone(-50f, -20f, CzTokens.danger.copy(alpha = 0.35f), StrokeCap.Butt, stroke)
            drawZone(20f, 50f, CzTokens.danger.copy(alpha = 0.35f), StrokeCap.Butt, stroke)
            drawZone(-20f, -5f, CzTokens.warnAmber.copy(alpha = 0.4f), StrokeCap.Butt, stroke)
            drawZone(5f, 20f, CzTokens.warnAmber.copy(alpha = 0.4f), StrokeCap.Butt, stroke)
            drawZone(-5f, 5f, accent.copy(alpha = 0.85f), StrokeCap.Butt, stroke)

            // Ticks a cada 10 cents por dentro do anel; o do centro em accent.
            for (tick in -50..50 step 10) {
                val rad = Math.toRadians(degrees(tick.toFloat()) - 90.0)
                val isCenter = tick == 0
                val outer = radius - 12.dp.toPx()
                val inner = outer - (if (isCenter) 12.dp else 7.dp).toPx()
                drawLine(
                    color = if (isCenter) accent else Color.White.copy(alpha = 0.3f),
                    start = Offset(
                        center.x + inner * cos(rad).toFloat(),
                        center.y + inner * sin(rad).toFloat(),
                    ),
                    end = Offset(
                        center.x + outer * cos(rad).toFloat(),
                        center.y + outer * sin(rad).toFloat(),
                    ),
                    strokeWidth = (if (isCenter) 3.dp else 1.5.dp).toPx(),
                    cap = StrokeCap.Round,
                )
            }

            // Dicas ♭ / ♯ nas pontas do arco.
            for ((symbol, atCents) in listOf("♭" to -58f, "♯" to 58f)) {
                val rad = Math.toRadians(degrees(atCents) - 90.0)
                val measured = textMeasurer.measure(
                    symbol,
                    TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                )
                drawText(
                    measured,
                    color = Color.White.copy(alpha = 0.4f),
                    topLeft = Offset(
                        center.x + radius * cos(rad).toFloat() - measured.size.width / 2f,
                        center.y + radius * sin(rad).toFloat() - measured.size.height / 2f,
                    ),
                )
            }

            // O ponteiro cavalgando o anel, com glow na cor da zona.
            val pointerRad = Math.toRadians(degrees(animatedCents) - 90.0)
            val px = center.x + radius * cos(pointerRad).toFloat()
            val py = center.y + radius * sin(pointerRad).toFloat()
            if (active) {
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(zoneColor.copy(alpha = 0.55f), Color.Transparent),
                        center = Offset(px, py),
                        radius = 22.dp.toPx(),
                    ),
                    radius = 22.dp.toPx(),
                    center = Offset(px, py),
                )
            }
            rotate(degrees = degrees(animatedCents), pivot = Offset(px, py)) {
                drawRoundRect(
                    color = zoneColor.copy(alpha = if (active) 1f else 0.5f),
                    topLeft = Offset(px - 3.dp.toPx(), py - 15.dp.toPx()),
                    size = Size(6.dp.toPx(), 30.dp.toPx()),
                    cornerRadius = CornerRadius(3.dp.toPx()),
                )
            }
        }

        // Nota + cents dentro do arco (composables para animação de cor/fonte).
        val noteCenterFromTop = heightDp - centerYFromBottomDp - (radiusDp * 0.32f)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = noteCenterFromTop - 70.dp),
        ) {
            Text(
                text = if (active) "%+d¢".format(cents) else " ", // i18n-verbatim: número
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = zoneColor,
                modifier = Modifier
                    .padding(bottom = 2.dp),
            )
            androidx.compose.foundation.layout.Row(
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    text = note, // i18n-verbatim: nome de nota
                    fontSize = 76.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isTuned) accent else CzTokens.textPrimary,
                )
                Text(
                    text = octave, // i18n-verbatim: número
                    fontSize = 30.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = CzTokens.textSecondary,
                    modifier = Modifier.padding(start = 3.dp, bottom = 12.dp),
                )
            }
            Text(
                text = frequencyLabel ?: " ", // i18n-verbatim: número + Hz
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = CzTokens.textTertiary,
            )
        }
    }
}

/**
 * Polilinha rolante de ~10 s de cents, colorida pela zona (amostragem a
 * 15 Hz enquanto o sinal está ativo) — port do `TuningGraphView`.
 */
@Composable
fun TuningGraphView(
    cents: Int,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val windowSeconds = 10.0
    val maxCents = 50.0

    val samples = remember { mutableStateListOf<Pair<Double, Double>>() } // (t, cents)
    val currentCents by rememberUpdatedState(cents)
    val currentActive by rememberUpdatedState(active)

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000L / 15)
            val now = System.nanoTime() / 1e9
            if (!currentActive) {
                if (samples.isNotEmpty()) samples.clear()
                continue
            }
            samples.add(now to currentCents.coerceIn(-50, 50).toDouble())
            val cutoff = now - windowSeconds
            while (samples.isNotEmpty() && samples.first().first < cutoff) {
                samples.removeAt(0)
            }
        }
    }

    val zoneColor = when {
        abs(cents) <= 5 -> CzTokens.tunerGreen
        abs(cents) <= 20 -> CzTokens.warnAmber
        else -> CzTokens.danger
    }

    Canvas(modifier) {
        val width = size.width
        val height = size.height

        fun centsToY(value: Double): Float {
            val padding = 8.dp.toPx()
            val usable = height - 2 * padding
            return (padding + (maxCents - value) / (2 * maxCents) * usable).toFloat()
        }

        // Fundo + faixas de tolerância (±5 verde, ±20 âmbar).
        drawRoundRect(
            color = Color.White.copy(alpha = 0.03f),
            cornerRadius = CornerRadius(8.dp.toPx()),
        )
        drawRect(
            color = CzTokens.tunerGreen.copy(alpha = 0.06f),
            topLeft = Offset(0f, centsToY(5.0)),
            size = Size(width, centsToY(-5.0) - centsToY(5.0)),
        )
        for ((hi, lo) in listOf(20.0 to 5.0, -5.0 to -20.0)) {
            drawRect(
                color = CzTokens.warnAmber.copy(alpha = 0.04f),
                topLeft = Offset(0f, centsToY(hi)),
                size = Size(width, centsToY(lo) - centsToY(hi)),
            )
        }
        // Linhas de referência: zero tracejada + ±5.
        drawLine(
            color = Color.White.copy(alpha = 0.25f),
            start = Offset(0f, centsToY(0.0)),
            end = Offset(width, centsToY(0.0)),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx())),
        )
        for (ref in listOf(5.0, -5.0)) {
            drawLine(
                color = CzTokens.tunerGreen.copy(alpha = 0.3f),
                start = Offset(0f, centsToY(ref)),
                end = Offset(width, centsToY(ref)),
                strokeWidth = 0.5.dp.toPx(),
            )
        }

        // Polilinha + ponto atual.
        val now = System.nanoTime() / 1e9
        val windowStart = now - windowSeconds
        val points = samples.map { (t, value) ->
            Offset(((t - windowStart) / windowSeconds * width).toFloat(), centsToY(value))
        }
        if (points.size >= 2) {
            val path = androidx.compose.ui.graphics.Path()
            path.moveTo(points[0].x, points[0].y)
            for (point in points.drop(1)) path.lineTo(point.x, point.y)
            drawPath(
                path,
                color = zoneColor,
                style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round),
            )
        }
        points.lastOrNull()?.let { last ->
            drawCircle(color = zoneColor, radius = 3.dp.toPx(), center = last)
            drawCircle(
                color = Color.White.copy(alpha = 0.9f),
                radius = 3.dp.toPx(),
                center = last,
                style = Stroke(width = 1.dp.toPx()),
            )
        }
    }
}
