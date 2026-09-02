package com.levelhard.cadentia.features.metronome

import android.Manifest
import android.content.pm.PackageManager
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.levelhard.cadentia.R
import com.levelhard.cadentia.audio.PolyphonicSampler
import com.levelhard.cadentia.kit.MetronomeClick
import com.levelhard.cadentia.settings.SettingsStore
import com.levelhard.cadentia.ui.CzCard
import com.levelhard.cadentia.ui.CzTokens
import com.levelhard.cadentia.ui.PremiumBackground
import com.levelhard.cadentia.ui.pageTransition
import kotlin.math.cos
import kotlin.math.sin

/**
 * O metrônomo — port do `MetronomeView.swift`: herói de BPM com o dial de
 * batidas, slider com passos ±, play/tap-tempo, ajustes (compasso,
 * subdivisão, som, volume, polirritmia), detector de BPM e practice timer.
 * Cliques sample-accurate; o pulso também chega pela vibração.
 */
@Composable
fun MetronomeScreen(store: SettingsStore) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val settings by store.settings.collectAsState()
    val accent = CzTokens.metronomeAmber

    val sampler = remember { PolyphonicSampler() }
    val engine = remember { MetronomeEngine(sampler) }
    val bpmDetector = remember { BpmDetectorModel() }
    val practiceTimer = remember { PracticeTimerModel() }
    val detectorState by bpmDetector.state.collectAsState()
    val timerState by practiceTimer.state.collectAsState()

    var isRunning by remember { mutableStateOf(false) }
    var currentBeat by remember { mutableIntStateOf(-1) }
    var tapTimes by remember { mutableStateOf(listOf<Double>()) }

    val beatsPerBar = settings.metronome.timeSignature.substringBefore("/").toIntOrNull() ?: 4

    fun setBpm(value: Int) {
        val clamped = value.coerceIn(40, 240)
        store.update { it.metronome.bpm = clamped }
        engine.bpm = clamped
    }

    fun stopIfRunning() {
        if (!isRunning) return
        engine.stop()
        isRunning = false
        currentBeat = -1
    }

    fun toggle() {
        if (isRunning) {
            stopIfRunning()
            return
        }
        engine.bpm = settings.metronome.bpm
        engine.subdivision = settings.metronome.subdivision
        engine.beatsPerBar = beatsPerBar
        engine.volume = settings.metronome.volume
        engine.sound = MetronomeClick.Sound.from(settings.metronome.sound) ?: MetronomeClick.Sound.Click
        engine.polyrhythm = parsePolyrhythm(settings.metronome.polyrhythm)
        engine.onBeat = { beat ->
            currentBeat = beat
            // O haptic cavalga a batida visual: o pulso chega pela mão mesmo
            // com o volume baixo.
            view.performHapticFeedback(
                if (beat == 0) HapticFeedbackConstants.CONFIRM
                else HapticFeedbackConstants.CLOCK_TICK,
            )
        }
        isRunning = engine.start(scope)
    }

    /** Tap tempo: média dos últimos toques (máx. 6; história zera após 2 s). */
    fun tap() {
        val now = System.nanoTime() / 1e9
        var times = tapTimes
        if (times.isNotEmpty() && now - times.last() > 2.0) times = emptyList()
        times = (times + now).takeLast(6)
        tapTimes = times
        if (times.size < 2) return
        val intervals = times.zipWithNext { a, b -> b - a }
        val avg = intervals.average()
        if (avg > 0) setBpm(Math.round(60 / avg).toInt())
    }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) bpmDetector.start(scope) }

    fun startDetector() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) bpmDetector.start(scope) else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    DisposableEffect(Unit) {
        onDispose {
            engine.stop()
            bpmDetector.stop()
            practiceTimer.stop()
        }
    }

    Box(Modifier.fillMaxSize().pageTransition()) {
        PremiumBackground(accent = accent)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Herói: dial de batidas ao redor do BPM gigante.
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(230.dp)) {
                    BeatDial(
                        beats = beatsPerBar,
                        currentBeat = if (isRunning) currentBeat else -1,
                        accent = accent,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "${settings.metronome.bpm}", // i18n-verbatim: número
                            fontSize = 64.sp,
                            fontWeight = FontWeight.Bold,
                            color = CzTokens.textPrimary,
                        )
                        Text(
                            text = stringResource(R.string.music_metronome_bpm).uppercase(),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = CzTokens.textTertiary,
                        )
                    }
                }

                // Controles de BPM.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    StepButton(Icons.Filled.Remove, R.string.music_metronome_decrement) {
                        setBpm(settings.metronome.bpm - 1)
                    }
                    Slider(
                        value = settings.metronome.bpm.toFloat(),
                        onValueChange = { setBpm(Math.round(it)) },
                        valueRange = 40f..240f,
                        colors = SliderDefaults.colors(
                            thumbColor = accent,
                            activeTrackColor = accent,
                            inactiveTrackColor = CzTokens.surface,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    StepButton(Icons.Filled.Add, R.string.music_metronome_increment) {
                        setBpm(settings.metronome.bpm + 1)
                    }
                }

                // Play / tap.
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ActionButton(
                        textRes = if (isRunning) R.string.music_metronome_stop else R.string.music_metronome_start,
                        icon = if (isRunning) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        prominent = true,
                        accent = accent,
                    ) { toggle() }
                    ActionButton(
                        textRes = R.string.music_metronome_tap,
                        icon = Icons.Filled.TouchApp,
                        prominent = false,
                        accent = accent,
                    ) { tap() }
                }

                // Ajustes.
                CzCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                        modifier = Modifier.padding(18.dp),
                    ) {
                        PanelTitle(R.string.music_common_settings, Icons.Filled.Tune)

                        Setting(R.string.music_metronome_time_signature) {
                            ChipRow(
                                options = listOf("2/4", "3/4", "4/4", "5/4", "6/8", "7/8"),
                                label = { it }, // i18n-verbatim: fração musical
                                isActive = { it == settings.metronome.timeSignature },
                                accent = accent,
                            ) { sig ->
                                store.update { it.metronome.timeSignature = sig }
                                engine.beatsPerBar = sig.substringBefore("/").toIntOrNull() ?: 4
                            }
                        }

                        Setting(R.string.music_metronome_subdivision) {
                            val subdivisions = listOf(1 to "♩", 2 to "♪", 4 to "♬", 3 to "♪³")
                            ChipRow(
                                options = subdivisions,
                                label = { it.second }, // i18n-verbatim: glifo musical
                                isActive = { it.first == settings.metronome.subdivision },
                                accent = accent,
                            ) { (id, _) ->
                                store.update { it.metronome.subdivision = id }
                                engine.subdivision = id
                            }
                        }

                        Setting(R.string.music_metronome_sound) {
                            ChipRow(
                                options = MetronomeClick.Sound.entries.toList(),
                                label = { soundLabel(it) },
                                isActive = { it.id == settings.metronome.sound },
                                accent = accent,
                            ) { sound ->
                                store.update { it.metronome.sound = sound.id }
                                engine.sound = sound
                            }
                        }

                        Setting(R.string.music_metronome_volume) {
                            Slider(
                                value = settings.metronome.volume.toFloat(),
                                onValueChange = { value ->
                                    store.update { it.metronome.volume = value.toDouble() }
                                    engine.volume = value.toDouble()
                                },
                                valueRange = 0f..1f,
                                colors = SliderDefaults.colors(
                                    thumbColor = accent,
                                    activeTrackColor = accent,
                                    inactiveTrackColor = CzTokens.surface,
                                ),
                            )
                        }

                        Setting(R.string.music_metronome_polyrhythm) {
                            ChipRow(
                                options = listOf("off", "3:2", "4:3", "5:4", "7:4"),
                                label = { if (it == "off") stringResource(R.string.music_metronome_polyrhythm_off) else it },
                                isActive = { it == settings.metronome.polyrhythm },
                                accent = accent,
                            ) { value ->
                                store.update { it.metronome.polyrhythm = value }
                                engine.polyrhythm = parsePolyrhythm(value)
                            }
                        }
                    }
                }

                // Detector de BPM.
                CzCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.padding(18.dp),
                    ) {
                        PanelTitle(R.string.music_metronome_bpm_detector, Icons.Filled.Mic)
                        Text(
                            text = stringResource(R.string.music_metronome_bpm_detector_hint),
                            fontSize = 12.sp,
                            color = CzTokens.textTertiary,
                        )
                        if (detectorState.active) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Text(
                                        text = detectorState.detected?.toString() ?: "—", // i18n-verbatim: número
                                        fontSize = 34.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = CzTokens.textPrimary,
                                    )
                                    Text(
                                        text = stringResource(R.string.music_metronome_bpm),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = CzTokens.textTertiary,
                                        modifier = Modifier.padding(start = 5.dp, bottom = 6.dp),
                                    )
                                }
                                Spacer(Modifier.weight(1f))
                                ActionButton(
                                    textRes = R.string.music_metronome_apply_bpm,
                                    icon = null,
                                    prominent = true,
                                    accent = accent,
                                    enabled = detectorState.detected != null,
                                ) {
                                    detectorState.detected?.let { setBpm(it) }
                                    bpmDetector.stop()
                                }
                                ActionButton(
                                    textRes = R.string.music_metronome_stop,
                                    icon = Icons.Filled.Stop,
                                    prominent = false,
                                    accent = accent,
                                ) { bpmDetector.stop() }
                            }
                        } else {
                            ActionButton(
                                textRes = R.string.music_metronome_start_listening,
                                icon = Icons.Filled.Mic,
                                prominent = false,
                                accent = accent,
                            ) { startDetector() }
                        }
                    }
                }

                // Practice timer.
                CzCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.padding(18.dp),
                    ) {
                        PanelTitle(R.string.music_metronome_practice_timer, Icons.Filled.HourglassEmpty)
                        ChipRow(
                            options = listOf(5, 10, 15, 30, 45, 60),
                            label = { it.toString() }, // i18n-verbatim: número
                            isActive = { it == settings.metronome.practiceTimerMinutes },
                            accent = accent,
                        ) { minutes ->
                            store.update { it.metronome.practiceTimerMinutes = minutes }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (timerState.running) {
                                    timerState.label // i18n-verbatim: relógio
                                } else {
                                    "%02d:00".format(settings.metronome.practiceTimerMinutes) // i18n-verbatim: relógio
                                },
                                fontSize = 34.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (timerState.running) accent else CzTokens.textPrimary,
                            )
                            Spacer(Modifier.weight(1f))
                            if (timerState.running) {
                                ActionButton(
                                    textRes = R.string.music_metronome_stop_timer,
                                    icon = Icons.Filled.Stop,
                                    prominent = false,
                                    accent = accent,
                                ) { practiceTimer.stop() }
                            } else {
                                ActionButton(
                                    textRes = R.string.music_metronome_start_timer,
                                    icon = Icons.Filled.PlayArrow,
                                    prominent = true,
                                    accent = accent,
                                ) {
                                    practiceTimer.start(scope, settings.metronome.practiceTimerMinutes) {
                                        stopIfRunning()
                                        engine.playAlert()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun parsePolyrhythm(value: String): Pair<Int, Int>? {
    val parts = value.split(":").mapNotNull { it.toIntOrNull() }
    if (parts.size != 2 || parts[1] <= 0) return null
    return parts[0] to parts[1]
}

@Composable
private fun soundLabel(sound: MetronomeClick.Sound): String = stringResource(
    when (sound) {
        MetronomeClick.Sound.Click -> R.string.music_metronome_sounds_click
        MetronomeClick.Sound.Woodblock -> R.string.music_metronome_sounds_woodblock
        MetronomeClick.Sound.Cowbell -> R.string.music_metronome_sounds_cowbell
        MetronomeClick.Sound.Beep -> R.string.music_metronome_sounds_beep
    },
)

@Composable
private fun PanelTitle(textRes: Int, icon: ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Icon(icon, contentDescription = null, tint = CzTokens.textSecondary, modifier = Modifier.size(16.dp))
        Text(
            text = stringResource(textRes),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = CzTokens.textSecondary,
        )
    }
}

@Composable
private fun Setting(labelRes: Int, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(labelRes).uppercase(),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = CzTokens.textTertiary,
        )
        content()
    }
}

@Composable
private fun <T> ChipRow(
    options: List<T>,
    label: @Composable (T) -> String,
    isActive: (T) -> Boolean,
    accent: Color,
    onPick: (T) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(end = 2.dp),
    ) {
        for (option in options) {
            val active = isActive(option)
            Surface(
                onClick = { onPick(option) },
                shape = CircleShape,
                color = if (active) accent.copy(alpha = 0.18f) else CzTokens.surface,
                contentColor = if (active) accent else CzTokens.textSecondary,
            ) {
                Text(
                    text = label(option),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }
    }
}

@Composable
private fun StepButton(icon: ImageVector, labelRes: Int, onClick: () -> Unit) {
    val label = stringResource(labelRes)
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = CzTokens.surface,
        contentColor = CzTokens.textPrimary,
        modifier = Modifier.size(40.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ActionButton(
    textRes: Int,
    icon: ImageVector?,
    prominent: Boolean,
    accent: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = if (prominent) accent.copy(alpha = if (enabled) 1f else 0.4f) else CzTokens.surface,
        contentColor = if (prominent) CzTokens.stageBottom else CzTokens.textPrimary,
        border = if (prominent) null else androidx.compose.foundation.BorderStroke(1.dp, CzTokens.hairline),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 11.dp),
        ) {
            icon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(16.dp)) }
            Text(
                text = stringResource(textRes),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

/**
 * Anel de pontos de batida ao redor do herói de BPM — port do `BeatDial`.
 * A batida 1 leva o acento; a ativa pulsa com glow.
 */
@Composable
private fun BeatDial(
    beats: Int,
    currentBeat: Int,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val pulse by animateFloatAsState(
        targetValue = if (currentBeat >= 0) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.6f),
        label = "beat-pulse",
    )
    Canvas(modifier) {
        val radius = size.minDimension / 2f - 10.dp.toPx()
        val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)

        drawCircle(
            color = CzTokens.hairline,
            radius = radius,
            center = center,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
        )

        val count = maxOf(beats, 1)
        for (beat in 0 until count) {
            val angle = (beat.toDouble() / count) * 2 * Math.PI - Math.PI / 2
            val isActive = beat == currentBeat
            val isDownbeat = beat == 0
            val pos = androidx.compose.ui.geometry.Offset(
                center.x + radius * cos(angle).toFloat(),
                center.y + radius * sin(angle).toFloat(),
            )
            val dotColor = when {
                isActive -> accent
                isDownbeat -> accent.copy(alpha = 0.45f)
                else -> Color.White.copy(alpha = 0.22f)
            }
            if (isActive) {
                drawCircle(
                    brush = androidx.compose.ui.graphics.Brush.radialGradient(
                        listOf(accent.copy(alpha = 0.6f * pulse), Color.Transparent),
                        center = pos,
                        radius = 18.dp.toPx(),
                    ),
                    radius = 18.dp.toPx(),
                    center = pos,
                )
            }
            drawCircle(
                color = dotColor,
                radius = (if (isActive) 10.dp else 6.dp).toPx(),
                center = pos,
            )
        }
    }
}
