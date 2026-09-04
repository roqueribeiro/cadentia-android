package com.levelhard.cadentia.features.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.levelhard.cadentia.I18nMap
import com.levelhard.cadentia.LocalQaFlags
import com.levelhard.cadentia.R
import com.levelhard.cadentia.kit.AppSettings
import com.levelhard.cadentia.kit.MusicNotes
import com.levelhard.cadentia.kit.ToneSynth
import com.levelhard.cadentia.settings.SettingsStore
import com.levelhard.cadentia.ui.CzCard
import com.levelhard.cadentia.ui.CzTokens
import com.levelhard.cadentia.ui.PremiumBackground
import com.levelhard.cadentia.ui.pageTransition
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow

/**
 * Frequência (Studio) — port do `StudioView.swift`: gerador de tom de
 * precisão, 20 Hz a 20 kHz em slider log com steppers finos, quatro formas
 * de onda com osciloscópio ao vivo, modo binaural e sends de reverb/delay.
 * Todo knob aplica ao vivo.
 */
@Composable
fun StudioScreen(store: SettingsStore) {
    val accent = CzTokens.studioPurple
    val qa = LocalQaFlags.current
    val engine = remember { ToneGeneratorEngine() }
    var isPlaying by remember { mutableStateOf(false) }

    val settings by store.settings.collectAsState()
    val studio = settings.studio
    val wave = ToneSynth.Waveform.from(studio.wave) ?: ToneSynth.Waveform.Sine

    fun applyLive(mutate: (AppSettings) -> Unit) {
        store.update(mutate)
        engine.apply(store.settings.value.studio)
    }

    fun setHz(value: Double) {
        val clamped = value.coerceIn(20.0, 20000.0)
        applyLive { it.studio.hz = kotlin.math.round(clamped * 10) / 10 }
    }

    fun toggle() {
        if (isPlaying) {
            engine.stop()
            isPlaying = false
        } else {
            if (engine.start(studio)) isPlaying = true
        }
    }

    // Parado por fora (ligação, outro app, "Parar" na notificação): o botão acompanha.
    engine.sessionLabel = stringResource(R.string.music_tabs_frequency)
    engine.onSessionStopped = { isPlaying = false }

    // QA: autoplay para screenshot do osciloscópio vivo (o -qa-studio-autoplay).
    LaunchedEffect(Unit) {
        if (qa.studioAutoplay && !isPlaying) toggle()
    }
    DisposableEffect(Unit) {
        onDispose { engine.shutdown() }
    }

    Box(Modifier.fillMaxSize().pageTransition()) {
        PremiumBackground(accent = accent)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.widthIn(max = 560.dp),
            ) {
                Oscilloscope(
                    wave = wave,
                    active = isPlaying,
                    accent = accent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .testTag("studio.scope"),
                )

                // ---- leitura ----
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = if (studio.hz < 100) {
                                String.format(Locale.ROOT, "%.1f", studio.hz)
                            } else {
                                String.format(Locale.ROOT, "%.0f", studio.hz)
                            },
                            fontSize = 56.sp,
                            fontWeight = FontWeight.Bold,
                            color = CzTokens.textPrimary,
                        )
                        Text(
                            text = "Hz", // i18n-verbatim: unidade física
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = CzTokens.textTertiary,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    MusicNotes.noteFromFrequency(studio.hz)?.let { note ->
                        Text(
                            text = "≈ ${note.name}${note.octave} (${if (note.cents > 0) "+" else ""}${note.cents}¢)",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = accent,
                        )
                    }
                }

                // ---- controles de frequência ----
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Slider log: mesmo curso por oitava, 20 Hz – 20 kHz.
                    Slider(
                        value = (log2(studio.hz / 20) / log2(1000.0)).toFloat(),
                        onValueChange = { setHz(20 * 1000.0.pow(it.toDouble())) },
                        valueRange = 0f..1f,
                        colors = sliderColors(accent),
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    ) {
                        for ((hz, key) in presets) {
                            val active = abs(studio.hz - hz) < 0.5
                            Chip(
                                text = stringResource(I18nMap.res(key)),
                                active = active,
                                accent = accent,
                            ) { setHz(hz) }
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        for (delta in listOf(-10, -1, +1, +10)) {
                            Chip(
                                text = if (delta > 0) "+$delta" else "$delta",
                                active = false,
                                accent = accent,
                            ) { setHz(studio.hz + delta) }
                        }
                        Spacer(Modifier.weight(1f))
                        // O atalho A440: o motivo da tela para muito músico.
                        Chip(
                            text = "A4 · 440", // i18n-verbatim: nome de nota
                            active = true,
                            accent = accent,
                        ) { setHz(440.0) }
                    }
                }

                // ---- onda + play + volume ----
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    ) {
                        for (candidate in ToneSynth.Waveform.entries) {
                            Chip(
                                text = stringResource(I18nMap.res(candidate.nameKey)),
                                active = wave == candidate,
                                accent = accent,
                            ) { applyLive { it.studio.wave = candidate.id } }
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .background(accent, RoundedCornerShape(50))
                                .clickable { toggle() }
                                .padding(horizontal = 22.dp, vertical = 11.dp)
                                .testTag("studio.toggle"),
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = stringResource(
                                    if (isPlaying) R.string.music_metronome_stop else R.string.music_metronome_start,
                                ),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                color = Color.Black,
                            )
                        }
                        Icon(
                            imageVector = Icons.Filled.VolumeUp,
                            contentDescription = null,
                            tint = CzTokens.textTertiary,
                            modifier = Modifier.size(16.dp),
                        )
                        Slider(
                            value = studio.volume.toFloat(),
                            onValueChange = { value -> applyLive { it.studio.volume = value.toDouble() } },
                            valueRange = 0f..1f,
                            colors = sliderColors(accent),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                // ---- binaural ----
                CzCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(16.dp),
                    ) {
                        ToggleRow(
                            icon = Icons.Filled.Headphones,
                            text = stringResource(R.string.music_frequency_binaural_mode),
                            checked = studio.binauralEnabled,
                            accent = accent,
                        ) { value -> applyLive { it.studio.binauralEnabled = value } }
                        if (studio.binauralEnabled) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(
                                    text = String.format(Locale.ROOT, "+%.0f Hz", studio.binauralOffset),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = accent,
                                    modifier = Modifier.width(58.dp),
                                )
                                Slider(
                                    value = studio.binauralOffset.toFloat(),
                                    onValueChange = { value ->
                                        applyLive { it.studio.binauralOffset = kotlin.math.round(value).toDouble() }
                                    },
                                    valueRange = 1f..40f,
                                    colors = sliderColors(accent),
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            Text(
                                text = stringResource(R.string.music_frequency_binaural_hint),
                                fontSize = 11.sp,
                                color = CzTokens.textTertiary,
                            )
                        }
                    }
                }

                // ---- efeitos ----
                CzCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.padding(16.dp),
                    ) {
                        ToggleRow(
                            icon = null,
                            text = stringResource(R.string.music_frequency_reverb),
                            checked = studio.reverbEnabled,
                            accent = accent,
                        ) { value -> applyLive { it.studio.reverbEnabled = value } }
                        if (studio.reverbEnabled) {
                            LabeledSlider(
                                label = stringResource(R.string.music_frequency_mix),
                                value = studio.reverbMix.toFloat(),
                                range = 0f..1f,
                                accent = accent,
                            ) { value -> applyLive { it.studio.reverbMix = value.toDouble() } }
                        }
                        ToggleRow(
                            icon = null,
                            text = stringResource(R.string.music_frequency_delay),
                            checked = studio.delayEnabled,
                            accent = accent,
                        ) { value -> applyLive { it.studio.delayEnabled = value } }
                        if (studio.delayEnabled) {
                            LabeledSlider(
                                label = stringResource(R.string.music_frequency_delay_time),
                                value = studio.delayTimeMs.toFloat(),
                                range = 50f..1000f,
                                accent = accent,
                            ) { value -> applyLive { it.studio.delayTimeMs = value.toDouble() } }
                            LabeledSlider(
                                label = stringResource(R.string.music_frequency_delay_feedback),
                                value = studio.delayFeedback.toFloat(),
                                range = 0f..0.9f,
                                accent = accent,
                            ) { value -> applyLive { it.studio.delayFeedback = value.toDouble() } }
                            LabeledSlider(
                                label = stringResource(R.string.music_frequency_mix),
                                value = studio.delayMix.toFloat(),
                                range = 0f..1f,
                                accent = accent,
                            ) { value -> applyLive { it.studio.delayMix = value.toDouble() } }
                        }
                    }
                }
            }
        }
    }
}

private val presets = listOf(
    60.0 to "music.frequency.presets.bass",
    432.0 to "music.frequency.presets.concert432",
    440.0 to "music.frequency.presets.a4",
    528.0 to "music.frequency.presets.miracle",
    1000.0 to "music.frequency.presets.reference1k",
    8000.0 to "music.frequency.presets.treble8k",
)

@Composable
private fun sliderColors(accent: Color) = SliderDefaults.colors(
    thumbColor = accent,
    activeTrackColor = accent,
    inactiveTrackColor = CzTokens.surface,
)

@Composable
private fun Chip(text: String, active: Boolean, accent: Color, onClick: () -> Unit) {
    Text(
        text = text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        color = if (active) accent else CzTokens.textSecondary,
        modifier = Modifier
            .background(
                if (active) accent.copy(alpha = 0.18f) else CzTokens.surface,
                RoundedCornerShape(50),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
private fun ToggleRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    text: String,
    checked: Boolean,
    accent: Color,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = CzTokens.textSecondary,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            text = text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = CzTokens.textSecondary,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
            colors = SwitchDefaults.colors(
                checkedTrackColor = accent,
                checkedThumbColor = Color.White,
            ),
        )
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    accent: Color,
    onValue: (Float) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = label.uppercase(),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = CzTokens.textTertiary,
            modifier = Modifier.width(76.dp),
        )
        Slider(
            value = value,
            onValueChange = onValue,
            valueRange = range,
            colors = sliderColors(accent),
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Osciloscópio da forma gerada — desenha exatamente a matemática que o
 * gerador renderiza, com varredura de fósforo enquanto toca (port 1:1 do
 * `OscilloscopeView`; o iOS também desenha a equação, não o PCM).
 */
@Composable
private fun Oscilloscope(
    wave: ToneSynth.Waveform,
    active: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    var phase by remember { mutableDoubleStateOf(0.0) }
    LaunchedEffect(active) {
        while (active) {
            withFrameMillis { millis -> phase = millis / 1000.0 * 1.2 }
        }
    }

    Canvas(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(CzTokens.radiusMD))
            .border(1.dp, CzTokens.hairline, RoundedCornerShape(CzTokens.radiusMD)),
    ) {
        val midY = size.height / 2

        // Gratícula.
        for (fraction in listOf(0.25f, 0.5f, 0.75f)) {
            drawLine(
                color = Color.White.copy(alpha = if (fraction == 0.5f) 0.2f else 0.07f),
                start = Offset(0f, size.height * fraction),
                end = Offset(size.width, size.height * fraction),
                strokeWidth = 1.dp.toPx(),
                pathEffect = if (fraction == 0.5f) {
                    PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx()))
                } else {
                    null
                },
            )
        }

        // 4 ciclos, fase andando enquanto toca (varredura de fósforo).
        val cycles = 4.0
        val sweep = if (active) phase else 0.0
        val path = Path()
        val steps = 220
        for (i in 0..steps) {
            val x = i.toFloat() / steps * size.width
            val samplePhase = i.toDouble() / steps * cycles + sweep
            val y = midY - (wave.sample(samplePhase)).toFloat() * (size.height * 0.38f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path,
            color = if (active) accent else accent.copy(alpha = 0.35f),
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        if (active) {
            drawPath(path, color = accent.copy(alpha = 0.5f), style = Stroke(width = 5.dp.toPx()))
        }
    }
}
