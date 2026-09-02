package com.levelhard.cadentia.features.recorder

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.levelhard.cadentia.kit.RecorderProject
import com.levelhard.cadentia.ui.CzTokens
import java.util.Locale

/** Geometria da linha do tempo, compartilhada por régua, lanes e playhead. */
data class TimelineLayout(
    /** Zoom horizontal, em pixels (dp) por segundo. */
    val pixelsPerSecond: Double,
    /** Posição da linha do tempo na borda esquerda da área de lanes. */
    val offset: Double,
) {
    fun x(forTime: Double): Double = (forTime - offset) * pixelsPerSecond
    fun time(forX: Double): Double = offset + forX / pixelsPerSecond

    companion object {
        const val MIN_ZOOM = 6.0
        const val MAX_ZOOM = 420.0
        val laneHeight = 62.dp
        val headerWidth = 96.dp
        val rulerHeight = 24.dp
    }
}

/** Cores de trilha: um clipe que se vê de relance vale mais que um rótulo. */
object TrackPalette {
    val colors = listOf(
        Color(0xFF5EE3FF), // ciano
        Color(0xFFFF9F0A), // laranja
        Color(0xFF30D97E), // verde
        Color(0xFFAF52DE), // roxo
        Color(0xFFE2B457), // dourado
        Color(0xFFFF6B8A), // rosa
    )

    fun color(index: Int): Color = colors[kotlin.math.abs(index) % colors.size]
}

/**
 * Régua de tempo: o espaçamento dos ticks acompanha o zoom para os rótulos
 * nunca colidirem nem rarearem até a inutilidade.
 */
@Composable
fun TimelineRuler(layout: TimelineLayout, modifier: Modifier = Modifier) {
    val textMeasurer = rememberTextMeasurer()
    Canvas(modifier = modifier.height(TimelineLayout.rulerHeight)) {
        val candidates = listOf(0.25, 0.5, 1.0, 2.0, 5.0, 10.0, 15.0, 30.0, 60.0, 120.0)
        // Mira um rótulo a cada ~64 pontos.
        val step = candidates.firstOrNull { it * layout.pixelsPerSecond >= 64 } ?: 300.0

        // O layout mede em dp; o canvas, em px.
        val widthDp = size.width / 1.dp.toPx()
        val start = maxOf(0.0, layout.offset)
        val end = layout.time(widthDp.toDouble())
        var time = kotlin.math.floor(start / step) * step
        while (time <= end) {
            val x = (layout.x(time) * 1.dp.toPx()).toFloat()
            if (x >= -40f && x <= size.width + 40f) {
                drawLine(
                    color = Color.White.copy(alpha = 0.22f),
                    start = Offset(x, size.height - 6.dp.toPx()),
                    end = Offset(x, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
                val seconds = time.toInt()
                val label = if (step < 1) {
                    String.format(Locale.ROOT, "%d:%04.1f", seconds / 60, time - (seconds / 60 * 60))
                } else {
                    String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
                }
                val laidOut = textMeasurer.measure(
                    label,
                    TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = 0.45f)),
                )
                drawText(laidOut, topLeft = Offset(x + 3.dp.toPx(), 2.dp.toPx()))
            }
            time += step
        }
    }
}

/** Um clipe: forma de onda, triângulos de fade e a borda de seleção. */
@Composable
fun ClipView(
    revision: Int,
    clip: RecorderProject.Clip,
    color: Color,
    isSelected: Boolean,
    layout: TimelineLayout,
    peaks: WaveformPeaks?,
    modifier: Modifier = Modifier,
) {
    @Suppress("UNUSED_EXPRESSION") revision // modelo mutável: sem isto o strong skipping pula a recomposição
    val density = LocalDensity.current
    val width = with(density) { maxOf(6.0, clip.duration * layout.pixelsPerSecond).dp }
    Canvas(
        modifier = modifier
            .width(width)
            .height(TimelineLayout.laneHeight - 10.dp),
    ) {
        drawRoundRect(
            color = color.copy(alpha = if (isSelected) 0.34f else 0.2f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()),
        )

        // Forma de onda.
        if (peaks != null && size.width > 1f) {
            val middle = size.height / 2
            val columns = size.width.toInt()
            val path = Path()
            for (column in 0 until maxOf(columns, 1)) {
                val fraction = column.toDouble() / maxOf(columns, 1)
                val from = clip.trimStart + fraction * clip.duration
                val to = from + clip.duration / maxOf(columns, 1)
                val peak = peaks.peak(from, to)
                val h = maxOf(0.5f, peak * middle * 0.92f)
                val x = column + 0.5f
                path.moveTo(x, middle - h)
                path.lineTo(x, middle + h)
            }
            drawPath(path, color = color.copy(alpha = 0.95f), style = Stroke(width = 1f))
        }

        // Fades desenhados como os triângulos que são: o comprimento se lê
        // sem abrir painel nenhum.
        if (clip.fadeIn > 0 || clip.fadeOut > 0) {
            val overlay = Path()
            if (clip.fadeIn > 0) {
                val fadeWidth = minOf(size.width.toDouble(), clip.fadeIn / clip.duration * size.width).toFloat()
                overlay.moveTo(0f, 0f)
                overlay.lineTo(fadeWidth, 0f)
                overlay.lineTo(0f, size.height)
                overlay.close()
            }
            if (clip.fadeOut > 0) {
                val fadeWidth = minOf(size.width.toDouble(), clip.fadeOut / clip.duration * size.width).toFloat()
                overlay.moveTo(size.width, 0f)
                overlay.lineTo(size.width - fadeWidth, 0f)
                overlay.lineTo(size.width, size.height)
                overlay.close()
            }
            drawPath(overlay, color = Color.Black.copy(alpha = 0.45f))
        }

        drawRoundRect(
            color = if (isSelected) color else color.copy(alpha = 0.5f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()),
            style = Stroke(width = (if (isSelected) 2 else 1).dp.toPx()),
        )
    }
}

/** Cabeçalho da trilha: identidade e os três interruptores de um take. */
@Composable
fun TrackHeaderView(
    revision: Int,
    track: RecorderProject.Track,
    isRecordTarget: Boolean,
    onArm: () -> Unit,
    onMute: () -> Unit,
    onSolo: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    @Suppress("UNUSED_EXPRESSION") revision // modelo mutável: sem isto o strong skipping pula a recomposição
    val color = TrackPalette.color(track.colorIndex)
    Box(
        modifier = Modifier
            .width(TimelineLayout.headerWidth)
            .height(TimelineLayout.laneHeight)
            .background(
                CzTokens.surface.copy(alpha = if (isRecordTarget) 0.14f else 0.07f),
                RoundedCornerShape(8.dp),
            ),
    ) {
        // Alvo de gravação tem que ser óbvio antes do REC.
        if (isRecordTarget) {
            Box(
                Modifier
                    .width(2.dp)
                    .height(TimelineLayout.laneHeight)
                    .background(CzTokens.danger, RoundedCornerShape(1.dp)),
            )
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenSettings),
            ) {
                Box(Modifier.size(6.dp).background(color, CircleShape))
                Text(
                    text = track.name,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    color = CzTokens.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Filled.Tune,
                    contentDescription = null,
                    tint = CzTokens.textTertiary,
                    modifier = Modifier.size(10.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                SwitchButton("R", active = track.armed, color = CzTokens.danger, onClick = onArm)
                SwitchButton("M", active = track.muted, color = CzTokens.textSecondary, onClick = onMute)
                SwitchButton("S", active = track.soloed, color = CzTokens.gold, onClick = onSolo)
            }
        }
    }
}

@Composable
private fun SwitchButton(label: String, active: Boolean, color: Color, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(width = 20.dp, height = 18.dp)
            .background(if (active) color else CzTokens.surface, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick),
    ) {
        Text(
            text = label, // i18n-verbatim: siglas R/M/S, iguais nos 10
            fontSize = 9.sp,
            fontWeight = FontWeight.Black,
            color = if (active) Color.Black else CzTokens.textTertiary,
        )
    }
}

/** Relógio mm:ss.d do transporte. */
fun timeLabel(seconds: Double): String {
    val whole = maxOf(0.0, seconds)
    return String.format(
        Locale.ROOT, "%02d:%02d.%01d",
        whole.toInt() / 60, whole.toInt() % 60, ((whole - kotlin.math.floor(whole)) * 10).toInt(),
    )
}

