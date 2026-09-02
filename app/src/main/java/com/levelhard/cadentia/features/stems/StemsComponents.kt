package com.levelhard.cadentia.features.stems

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.levelhard.cadentia.R
import com.levelhard.cadentia.ui.CzCard
import com.levelhard.cadentia.ui.CzTokens
import java.util.Locale
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/** Cor de cada instrumento — as mesmas do mixer, para o olho ligar as duas coisas. */
val stemColors = mapOf(
    "drums" to Color(0xFFFF453A),
    "bass" to Color(0xFF6366F1),
    "other" to Color(0xFFE2B457),
    "vocals" to Color(0xFF30D97E),
)

/**
 * Barra deslizante fina — port do `ThinSlider`: trilha fina, cursor pequeno
 * que cresce sob o dedo, alvo de toque de 28 dp, e o trecho em loop pintado
 * NELA (a barra é o mapa da música).
 */
@Composable
fun ThinSlider(
    value: Double,
    accent: Color,
    modifier: Modifier = Modifier,
    live: Boolean = false,
    loopRange: ClosedFloatingPointRange<Double>? = null,
    onChange: (Double) -> Unit,
    onCommit: (() -> Unit)? = null,
) {
    var dragging by remember { mutableStateOf<Double?>(null) }
    var widthPx by remember { mutableDoubleStateOf(1.0) }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        dragging = (offset.x / widthPx).coerceIn(0.0, 1.0)
                        if (live) onChange(dragging!!)
                    },
                    onDragEnd = {
                        dragging?.let { target ->
                            onChange(target)
                            onCommit?.invoke()
                        }
                        dragging = null
                    },
                    onDragCancel = { dragging = null },
                ) { change, _ ->
                    change.consume()
                    dragging = (change.position.x / widthPx).coerceIn(0.0, 1.0)
                    if (live) dragging?.let(onChange)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val target = (offset.x / widthPx).coerceIn(0.0, 1.0)
                    onChange(target)
                    onCommit?.invoke()
                }
            },
    ) {
        widthPx = size.width.toDouble()
        val middle = size.height / 2
        val shown = (dragging ?: value).coerceIn(0.0, 1.0).toFloat()
        val knob = if (dragging == null) 12.dp.toPx() else 17.dp.toPx()

        drawRoundRect(
            color = CzTokens.textPrimary.copy(alpha = 0.14f),
            topLeft = Offset(0f, middle - 2.5f.dp.toPx()),
            size = Size(size.width, 5.dp.toPx()),
            cornerRadius = CornerRadius(2.5f.dp.toPx()),
        )
        loopRange?.let { loop ->
            val a = loop.start.coerceIn(0.0, 1.0).toFloat()
            val b = loop.endInclusive.coerceIn(0.0, 1.0).toFloat()
            drawRoundRect(
                color = accent.copy(alpha = 0.30f),
                topLeft = Offset(size.width * a, middle - 4.5f.dp.toPx()),
                size = Size(maxOf(size.width * (b - a), 3.dp.toPx()), 9.dp.toPx()),
                cornerRadius = CornerRadius(4.5f.dp.toPx()),
            )
        }
        drawRoundRect(
            color = accent,
            topLeft = Offset(0f, middle - 2.5f.dp.toPx()),
            size = Size(size.width * shown, 5.dp.toPx()),
            cornerRadius = CornerRadius(2.5f.dp.toPx()),
        )
        drawCircle(
            color = CzTokens.textPrimary,
            radius = knob / 2,
            center = Offset(size.width * shown, middle),
        )
    }
}

/**
 * A onda que ocupa o lugar da capa — port do `StemWaveform`: 48 bandas do
 * espectro real, interpoladas do espectro anterior para o atual NO RITMO DA
 * TELA (o dado chega ~40×/s; o olho vê movimento contínuo), cor misturada
 * das faixas audíveis com peso à quarta do nível, silhueta respirando no
 * repouso.
 */
@Composable
fun StemWaveform(
    spectrum: FloatArray,
    levels: Map<String, Float>,
    tint: Color,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    var leaving by remember { mutableStateOf(FloatArray(0)) }
    var arriving by remember { mutableStateOf(spectrum) }
    var arrivedAtNanos by remember { mutableStateOf(0L) }
    var clockNanos by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        while (true) {
            androidx.compose.runtime.withFrameNanos { now ->
                clockNanos = now
            }
        }
    }
    LaunchedEffect(spectrum) {
        leaving = currentBars(leaving, arriving, arrivedAtNanos, clockNanos)
        arriving = spectrum
        arrivedAtNanos = clockNanos
    }

    val color = blendedColor(levels, tint)
    Canvas(modifier = modifier.fillMaxWidth()) {
        val bars = if (arriving.any { it > 0.001f }) {
            currentBars(leaving, arriving, arrivedAtNanos, clockNanos)
        } else {
            restingBars(clockNanos, isPlaying)
        }
        if (bars.isEmpty()) return@Canvas

        val middle = size.height / 2
        val slot = size.width / bars.size
        val barWidth = maxOf(slot * 0.62f, 1.5f)
        val path = Path()
        for ((index, magnitude) in bars.withIndex()) {
            val height = maxOf(magnitude * middle * 0.92f, 1.5f)
            val x = slot * index + (slot - barWidth) / 2
            path.addRoundRect(
                RoundRect(
                    rect = Rect(x, middle - height, x + barWidth, middle + height),
                    cornerRadius = CornerRadius(barWidth / 2),
                ),
            )
        }
        drawPath(
            path,
            brush = Brush.verticalGradient(
                listOf(color.copy(alpha = 0.5f), color, color.copy(alpha = 0.5f)),
            ),
        )
        drawRect(
            color = color.copy(alpha = 0.22f),
            topLeft = Offset(0f, middle - 0.5f),
            size = Size(size.width, 1f),
        )
    }
}

private const val BLEND_SECONDS = 0.055

private fun currentBars(leaving: FloatArray, arriving: FloatArray, arrivedAtNanos: Long, nowNanos: Long): FloatArray {
    if (leaving.size != arriving.size) return arriving
    val progress = ((nowNanos - arrivedAtNanos) / 1e9 / BLEND_SECONDS).coerceIn(0.0, 1.0)
    val eased = (1 - (1 - progress).pow(3)).toFloat()
    return FloatArray(arriving.size) { leaving[it] + (arriving[it] - leaving[it]) * eased }
}

/** Silhueta em repouso: envelope + detalhe irregular, respirando devagar. */
private fun restingBars(nowNanos: Long, breathing: Boolean): FloatArray {
    val seconds = nowNanos / 1e9
    val breath = if (breathing) 0.80 + 0.20 * (0.5 + 0.5 * sin(seconds * 0.9)) else 1.0
    return FloatArray(48) { index ->
        val position = index / 47.0
        val envelope = sin(position * PI)
        val detail = 0.55 + 0.45 * sin(position * 11.3 + 0.7) * kotlin.math.cos(position * 4.1)
        (0.10 + 0.82 * envelope * detail * breath).toFloat()
    }
}

/** Média simples entre as 4 cores dá cinza; o peso à quarta deixa o mais alto puxar. */
private fun blendedColor(levels: Map<String, Float>, tint: Color): Color {
    val audible = levels.filterValues { it > 0.02f }
    if (audible.isEmpty()) return tint
    var red = 0.0
    var green = 0.0
    var blue = 0.0
    var total = 0.0
    for ((name, level) in audible) {
        val color = stemColors[name] ?: continue
        val weight = level.toDouble().pow(4)
        red += color.red * weight
        green += color.green * weight
        blue += color.blue * weight
        total += weight
    }
    if (total <= 0) return tint
    return Color((red / total).toFloat(), (green / total).toFloat(), (blue / total).toFloat())
}

// ---- mixer ----

/** O mixer numa folha — port do `StemMixerSheet`: faders HORIZONTAIS, chave e velocidade. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StemMixerSheet(
    engine: StemPlayerEngine,
    accent: Color,
    revision: Int,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
) {
    @Suppress("UNUSED_EXPRESSION") revision
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = CzTokens.stageTop) {
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = stringResource(R.string.cadentia_stems_tracks),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = CzTokens.textTertiary,
            )
            for (track in engine.tracks) {
                MixerRow(revision = revision, engine = engine, track = track, accent = accent, onChanged = onChanged)
            }
            StepperCard(
                title = stringResource(R.string.cadentia_stems_key),
                hint = stringResource(R.string.cadentia_stems_key_hint),
                value = if (engine.semitones > 0) "+${engine.semitones}" else "${engine.semitones}",
                isDefault = engine.semitones == 0,
                accent = accent,
                canDecrease = engine.semitones > -12,
                canIncrease = engine.semitones < 12,
                tag = "stems.key",
                onDecrease = { engine.semitones -= 1; onChanged() },
                onIncrease = { engine.semitones += 1; onChanged() },
            )
            StepperCard(
                title = stringResource(R.string.cadentia_stems_speed),
                hint = stringResource(R.string.cadentia_stems_speed_hint),
                value = speedLabel(engine.speed),
                isDefault = kotlin.math.abs(engine.speed - 1) < 0.001,
                accent = accent,
                canDecrease = engine.speed > 0.5 + 0.001,
                canIncrease = engine.speed < 1.5 - 0.001,
                tag = "stems.speed",
                onDecrease = { engine.speed = stepSpeed(engine.speed, -0.05); onChanged() },
                onIncrease = { engine.speed = stepSpeed(engine.speed, +0.05); onChanged() },
            )
        }
    }
}

fun speedLabel(speed: Double): String =
    String.format(Locale.ROOT, "%.2fx", speed).replace('.', ',')

/** Grade de 5% com trava no 1,0x — não existe um 0,99x que alguém quisesse. */
fun stepSpeed(current: Double, delta: Double): Double {
    val next = Math.round((current + delta) * 20) / 20.0
    if ((current - 1) * (next - 1) < 0 && kotlin.math.abs(next - 1) < 0.05) return 1.0
    return next.coerceIn(0.5, 1.5)
}

@Composable
private fun MixerRow(
    revision: Int,
    engine: StemPlayerEngine,
    track: StemPlayerEngine.Track,
    accent: Color,
    onChanged: () -> Unit,
) {
    @Suppress("UNUSED_EXPRESSION") revision // modelo mutável: sem isto o strong skipping pula a recomposição
    val color = stemColors[track.id] ?: accent
    val anySolo = engine.tracks.any { it.isSoloed }
    val audible = if (anySolo) track.isSoloed else !track.isMuted
    val level = engine.levels[track.id] ?: 0f
    val title = stringResource(
        when (track.id) {
            "drums" -> R.string.cadentia_stems_drums
            "bass" -> R.string.cadentia_stems_bass
            "vocals" -> R.string.cadentia_stems_vocals
            else -> R.string.cadentia_stems_other
        },
    )

    CzCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(8.dp).background(color, CircleShape))
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (audible) CzTokens.textPrimary else CzTokens.textTertiary,
                    modifier = Modifier.width(90.dp),
                )
                // Medidor vivo da faixa.
                Box(
                    Modifier
                        .weight(1f)
                        .height(4.dp)
                        .background(CzTokens.surface, RoundedCornerShape(2.dp)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(fraction = level.coerceIn(0f, 1f))
                            .height(4.dp)
                            .background(color.copy(alpha = if (audible) 0.9f else 0.25f), RoundedCornerShape(2.dp)),
                    )
                }
                ToggleTag(
                    text = "S", // i18n-verbatim: sigla de solo, igual nos 10
                    active = track.isSoloed,
                    color = CzTokens.gold,
                ) {
                    engine.toggleSolo(track.id)
                    onChanged()
                }
                ToggleTag(
                    text = "M", // i18n-verbatim: sigla de mudo, igual nos 10
                    active = track.isMuted,
                    color = CzTokens.danger,
                ) {
                    engine.toggleMute(track.id)
                    onChanged()
                }
            }
            ThinSlider(
                value = track.volume.toDouble(),
                accent = color,
                live = true,
                onChange = { engine.setVolume(it.toFloat(), track.id) },
                onCommit = onChanged,
            )
        }
    }
}

@Composable
private fun ToggleTag(text: String, active: Boolean, color: Color, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(width = 26.dp, height = 22.dp)
            .background(if (active) color else CzTokens.surface, RoundedCornerShape(5.dp))
            .clickable(onClick = onClick),
    ) {
        Text(
            text = text,
            fontSize = 10.sp,
            fontWeight = FontWeight.Black,
            color = if (active) Color.Black else CzTokens.textTertiary,
        )
    }
}

@Composable
private fun StepperCard(
    title: String,
    hint: String,
    value: String,
    isDefault: Boolean,
    accent: Color,
    canDecrease: Boolean,
    canIncrease: Boolean,
    tag: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    CzCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .padding(12.dp)
                .testTag(tag),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = CzTokens.textPrimary,
                )
                Text(
                    text = hint,
                    fontSize = 10.sp,
                    color = CzTokens.textTertiary,
                )
            }
            StepButton(icon = true, enabled = canDecrease, onClick = onDecrease)
            Text(
                text = value,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = if (isDefault) CzTokens.textSecondary else accent,
                modifier = Modifier.width(52.dp),
            )
            StepButton(icon = false, enabled = canIncrease, onClick = onIncrease)
        }
    }
}

@Composable
private fun StepButton(icon: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .size(30.dp)
            .background(CzTokens.surface, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Icon(
            imageVector = if (icon) Icons.Filled.Remove else Icons.Filled.Add,
            contentDescription = null, // PENDÊNCIA a11y: sem chave "diminuir/aumentar" no catálogo
            tint = if (enabled) CzTokens.textSecondary else CzTokens.textTertiary.copy(alpha = 0.4f),
            modifier = Modifier.size(14.dp),
        )
    }
}
