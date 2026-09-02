package com.levelhard.cadentia.features.drums

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import com.levelhard.cadentia.I18nMap
import com.levelhard.cadentia.LocalQaFlags
import com.levelhard.cadentia.R
import com.levelhard.cadentia.kit.AppSettings
import com.levelhard.cadentia.kit.DrumPattern
import com.levelhard.cadentia.kit.DrumSynth
import com.levelhard.cadentia.settings.SettingsStore
import com.levelhard.cadentia.ui.CzCard
import com.levelhard.cadentia.ui.CzTokens
import com.levelhard.cadentia.ui.PremiumBackground
import com.levelhard.cadentia.ui.pageTransition
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A bateria, performance primeiro — port do `DrumsView.swift`: 9 pads
 * GRANDES que disparam no toque para baixo (finger drumming, não botão),
 * cada slot configurável para qualquer dos 16 sons; kit + reverb + volume
 * moldam o som; sequencer de 16 passos com 25 grooves embaixo. Tudo persiste.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DrumsScreen(store: SettingsStore) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val qa = LocalQaFlags.current
    val settings by store.settings.collectAsState()
    val accent = CzTokens.danger // regra 87: a identidade da bateria é vermelha

    val sequencer = remember { DrumSequencer() }
    var isPlaying by remember { mutableStateOf(false) }
    var currentStep by remember { mutableIntStateOf(-1) }
    var showPresets by remember { mutableStateOf(false) }
    var editingSlot by remember { mutableStateOf<Int?>(null) }
    var isEditingPads by remember { mutableStateOf(false) }
    var litSlots by remember { mutableStateOf(setOf<Int>()) }

    val drums = settings.drums
    /** Pads com passo ativo, na ordem da grade — as linhas do sequencer. */
    val patternPads = DrumSynth.padIDs.filter { drums.pattern[it]?.contains(true) == true }

    fun syncEngine(current: AppSettings.Drums = store.settings.value.drums) {
        sequencer.kit = current.kit
        sequencer.bpm = current.bpm
        sequencer.volume = current.volume.toFloat()
        sequencer.pattern = current.pattern
        sequencer.onStep = { step -> currentStep = step }
        sequencer.sampler.setReverb(current.reverbEnabled, current.reverbMix.toFloat())
    }

    fun stopIfPlaying() {
        if (!isPlaying) return
        sequencer.stop()
        isPlaying = false
        currentStep = -1
    }

    LaunchedEffect(Unit) {
        syncEngine()
        sequencer.prewarm()
    }
    DisposableEffect(Unit) {
        onDispose {
            sequencer.shutdown()
        }
    }

    Box(Modifier.fillMaxSize().pageTransition()) {
        PremiumBackground(accent = accent)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Cabeçalho: kits + alternador de edição de pads.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    for (kit in DrumSynth.kitIDs) {
                        Chip(
                            text = stringResource(I18nMap.res(DrumSynth.kitNameKey(kit))),
                            active = drums.kit == kit,
                            accent = accent,
                        ) {
                            store.update { it.drums.kit = kit }
                            sequencer.kit = kit
                            sequencer.prewarm()
                        }
                    }
                    IconChip(
                        icon = Icons.Filled.Tune,
                        active = isEditingPads,
                        accent = accent,
                        label = stringResource(R.string.music_common_settings),
                    ) { isEditingPads = !isEditingPads }
                }

                // Chassi de hardware em volta da matriz de pads.
                Column(
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(26.dp))
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0xFF26262E), Color(0xFF17171C)),
                            ),
                        )
                        .padding(11.dp),
                ) {
                    for (rowIdx in 0 until 3) {
                        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            for (colIdx in 0 until 3) {
                                val slot = rowIdx * 3 + colIdx
                                val pad = drums.padLayout.getOrNull(slot) ?: "kick"
                                PerformancePad(
                                    pad = pad,
                                    color = padColor(pad),
                                    isLit = slot in litSlots,
                                    isEditing = isEditingPads,
                                    modifier = Modifier
                                        .weight(1f)
                                        .zIndex(if (slot in litSlots) 1f else 0f),
                                    onHit = {
                                        sequencer.hitPad(pad)
                                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                        litSlots = litSlots + slot
                                        scope.launch {
                                            delay(130)
                                            litSlots = litSlots - slot
                                        }
                                    },
                                    onEdit = { editingSlot = slot },
                                )
                            }
                        }
                    }
                }

                // Padrões embaixo dos pads; transporte só existe com padrão.
                Surface(
                    onClick = { showPresets = true },
                    shape = CircleShape,
                    color = if (patternPads.isEmpty()) accent.copy(alpha = 0.16f) else CzTokens.surface,
                    contentColor = if (patternPads.isEmpty()) accent else CzTokens.textSecondary,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 11.dp),
                    ) {
                        Icon(Icons.Filled.GridView, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(
                            text = stringResource(R.string.music_drums_patterns),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }
                }

                if (patternPads.isNotEmpty()) {
                    SequencerGrid(
                        patternPads = patternPads,
                        pattern = drums.pattern,
                        isPlaying = isPlaying,
                        currentStep = currentStep,
                        accent = accent,
                    ) { pad, step ->
                        store.update {
                            val steps = (it.drums.pattern[pad] ?: List(16) { false }).toMutableList()
                            steps[step] = !steps[step]
                            it.drums.pattern = if (steps.contains(true)) {
                                it.drums.pattern + (pad to steps)
                            } else {
                                it.drums.pattern - pad
                            }
                        }
                        sequencer.pattern = store.settings.value.drums.pattern
                    }

                    // Transporte.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            onClick = {
                                if (isPlaying) {
                                    stopIfPlaying()
                                } else {
                                    syncEngine()
                                    isPlaying = sequencer.start(scope)
                                }
                            },
                            shape = CircleShape,
                            color = accent,
                            contentColor = CzTokens.stageBottom,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(7.dp),
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                            ) {
                                Icon(
                                    if (isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    text = stringResource(
                                        if (isPlaying) R.string.music_metronome_stop else R.string.music_metronome_start,
                                    ),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                )
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        RoundStepButton(Icons.Filled.Remove) {
                            setBpm(store, sequencer, drums.bpm - 5)
                        }
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        ) {
                            Text(
                                text = "${drums.bpm}", // i18n-verbatim: número
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = CzTokens.textPrimary,
                            )
                            Text(
                                text = "BPM", // i18n-verbatim: sigla
                                fontSize = 9.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = CzTokens.textTertiary,
                            )
                        }
                        RoundStepButton(Icons.Filled.Add) {
                            setBpm(store, sequencer, drums.bpm + 5)
                        }
                    }
                }

                // Painel de mix: volume + sala de reverb.
                CzCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(14.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                Icons.Filled.VolumeUp,
                                contentDescription = null,
                                tint = CzTokens.textTertiary,
                                modifier = Modifier.size(16.dp),
                            )
                            Slider(
                                value = drums.volume.toFloat(),
                                onValueChange = { value ->
                                    store.update { it.drums.volume = value.toDouble() }
                                    sequencer.volume = value
                                },
                                valueRange = 0f..1f,
                                colors = sliderColors(accent),
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.music_frequency_reverb),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = CzTokens.textSecondary,
                            )
                            Switch(
                                checked = drums.reverbEnabled,
                                onCheckedChange = { value ->
                                    store.update { it.drums.reverbEnabled = value }
                                    sequencer.sampler.setReverb(value, store.settings.value.drums.reverbMix.toFloat())
                                },
                                colors = SwitchDefaults.colors(checkedTrackColor = accent),
                            )
                            if (drums.reverbEnabled) {
                                Slider(
                                    value = drums.reverbMix.toFloat(),
                                    onValueChange = { value ->
                                        store.update { it.drums.reverbMix = value.toDouble() }
                                        sequencer.sampler.setReverb(true, value)
                                    },
                                    valueRange = 0f..1f,
                                    colors = sliderColors(accent),
                                )
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }

    // Sheet de grooves.
    if (showPresets) {
        DrumPresetSheet(accent = accent, onDismiss = { showPresets = false }) { pattern ->
            store.update {
                it.drums.pattern = pattern.pads
                it.drums.bpm = pattern.bpm
            }
            syncEngine()
            showPresets = false
        }
    }

    // Sheet de troca de som do pad.
    editingSlot?.let { slot ->
        PadSoundPicker(
            kit = drums.kit,
            accent = accent,
            current = drums.padLayout.getOrNull(slot) ?: "kick",
            onDismiss = { editingSlot = null },
        ) { sound ->
            store.update {
                val layout = it.drums.padLayout.toMutableList()
                if (slot < layout.size) layout[slot] = sound
                it.drums.padLayout = layout
            }
            editingSlot = null
        }
    }
}

private fun setBpm(store: SettingsStore, sequencer: DrumSequencer, value: Int) {
    val clamped = value.coerceIn(40, 240)
    store.update { it.drums.bpm = clamped }
    sequencer.bpm = clamped
}

@Composable
private fun sliderColors(accent: Color) = SliderDefaults.colors(
    thumbColor = accent,
    activeTrackColor = accent,
    inactiveTrackColor = CzTokens.surface,
)

/** Família do instrumento → cor do pad (agrupamento visual de hardware). */
private fun padColor(pad: String): Color = when (pad) {
    "kick" -> CzTokens.danger
    "snare", "clap", "rim" -> CzTokens.metronomeAmber
    "hihat-c", "hihat-o", "shaker" -> CzTokens.gold
    "crash", "ride" -> CzTokens.recorderCyan
    "tom-low", "tom-mid", "tom-high" -> Color(0xFFFF6B9D)
    else -> CzTokens.studioPurple // congas/cowbell
}

/**
 * Pad de bateria de hardware, edge-lit — port do `PerformancePad`: face de
 * borracha fosca, luz de LED vazando da borda de baixo (brilho fraco parado
 * → labareda no hit), afunda como borracha. Dispara no toque PARA BAIXO.
 */
@Composable
private fun PerformancePad(
    pad: String,
    color: Color,
    isLit: Boolean,
    isEditing: Boolean,
    modifier: Modifier = Modifier,
    onHit: () -> Unit,
    onEdit: () -> Unit,
) {
    val label = stringResource(I18nMap.res(DrumSynth.labelKey(pad)))
    val shape = RoundedCornerShape(19.dp)
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .scale(if (isLit) 0.96f else 1f)
            .semantics { contentDescription = label }
            .pointerInput(isEditing) {
                awaitEachGesture {
                    awaitFirstDown()
                    if (isEditing) onEdit() else onHit()
                    waitForUpOrCancellation()
                }
            },
    ) {
        // Glow externo quando aceso (vaza além do pad; o zIndex do chamador
        // impede o pad de baixo de cobrir a luz).
        if (isLit) {
            Canvas(Modifier.fillMaxSize().scale(1.6f)) {
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(color.copy(alpha = 0.4f), Color.Transparent),
                    ),
                )
            }
        }
        Box(Modifier.fillMaxSize().clip(shape)) {
            // Face de borracha fosca.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color(0xFF2B2B33), Color(0xFF1A1A1F)))),
            )
            // Specular suave do alto-esquerdo.
            Canvas(Modifier.fillMaxSize()) {
                drawRect(
                    brush = Brush.radialGradient(
                        listOf(Color.White.copy(alpha = 0.05f), Color.Transparent),
                        center = Offset(size.width * 0.28f, size.height * 0.15f),
                        radius = size.width,
                    ),
                )
            }
            // Luz de LED por baixo: sobe da borda inferior.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.5f to color.copy(alpha = if (isLit) 0.12f else 0.02f),
                            1f to color.copy(alpha = if (isLit) 0.75f else 0.12f),
                        ),
                    ),
            )
            // Linha emissora do LED na borda de baixo.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Box(
                    Modifier
                        .padding(bottom = 8.dp)
                        .size(width = 62.dp, height = 3.5.dp)
                        .clip(CircleShape)
                        .background(color.copy(alpha = if (isLit) 1f else 0.5f)),
                )
            }
            // Aro: assento escuro.
            Box(
                Modifier
                    .fillMaxSize()
                    .border(1.5.dp, Color.Black.copy(alpha = 0.9f), shape),
            )
            // Micro-rótulo (maiúsculas, sussurrado; aceso no modo edição).
            Box(Modifier.fillMaxSize().padding(top = 10.dp, start = 6.dp, end = 6.dp), contentAlignment = Alignment.TopCenter) {
                Text(
                    text = label.uppercase(),
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = Color.White.copy(alpha = if (isEditing) 0.9f else 0.30f),
                )
            }
            if (isEditing) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.SwapHoriz,
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SequencerGrid(
    patternPads: List<String>,
    pattern: Map<String, List<Boolean>>,
    isPlaying: Boolean,
    currentStep: Int,
    accent: Color,
    onToggle: (String, Int) -> Unit,
) {
    CzCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(12.dp),
        ) {
            for (pad in patternPads) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(I18nMap.res(DrumSynth.labelKey(pad))),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = padColor(pad).copy(alpha = 0.9f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(58.dp),
                    )
                    for (step in 0 until 16) {
                        val active = pattern[pad]?.getOrNull(step) == true
                        val isNow = isPlaying && step == currentStep
                        Box(
                            Modifier
                                .weight(1f)
                                .height(22.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(
                                    when {
                                        active && isNow -> accent
                                        active -> accent.copy(alpha = 0.6f)
                                        else -> CzTokens.surface
                                    },
                                )
                                .clickable { onToggle(pad, step) },
                        ) {
                            if (step % 4 == 0 && !active) {
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .background(Color.White.copy(alpha = 0.05f)),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DrumPresetSheet(
    accent: Color,
    onDismiss: () -> Unit,
    onPick: (DrumPattern) -> Unit,
) {
    var category by remember { mutableStateOf("rock") }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = CzTokens.stageTop,
    ) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                text = stringResource(R.string.music_drums_patterns_title),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = CzTokens.textPrimary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                for (cat in DrumPattern.categories) {
                    Chip(
                        text = stringResource(I18nMap.res("music.drums.categories.$cat")),
                        active = category == cat,
                        accent = accent,
                    ) { category = cat }
                }
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                for (pattern in DrumPattern.byCategory(category)) {
                    Surface(
                        onClick = { onPick(pattern) },
                        shape = RoundedCornerShape(CzTokens.radiusMD),
                        color = CzTokens.surface,
                        contentColor = CzTokens.textPrimary,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        ) {
                            Text(
                                text = stringResource(I18nMap.res(pattern.nameKey)),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "${pattern.bpm} BPM", // i18n-verbatim: número + sigla
                                fontSize = 12.sp,
                                color = CzTokens.textTertiary,
                            )
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = CzTokens.textTertiary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PadSoundPicker(
    kit: String,
    accent: Color,
    current: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val preview = remember { com.levelhard.cadentia.audio.PolyphonicSampler() }
    DisposableEffect(Unit) {
        onDispose { preview.stop() }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = CzTokens.stageTop,
    ) {
        Text(
            text = stringResource(R.string.music_metronome_sound),
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = CzTokens.textPrimary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(16.dp),
        ) {
            items(DrumSynth.padIDs) { pad ->
                val color = padColor(pad)
                Surface(
                    onClick = {
                        if (preview.startIfNeeded()) {
                            val rate = preview.sampleRate
                            preview.play("preview/$kit/$pad") {
                                DrumSynth.renderStereo(
                                    kit, pad, velocity = 0.95f, variation = 0,
                                    sampleRate = rate, gain = 0.8f,
                                ).interleaved()
                            }
                        }
                        onPick(pad)
                    },
                    shape = RoundedCornerShape(CzTokens.radiusMD),
                    color = color.copy(alpha = if (pad == current) 0.18f else 0.08f),
                    contentColor = if (pad == current) accent else CzTokens.textPrimary,
                    border = BorderStroke(1.dp, color.copy(alpha = 0.4f)),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
                    ) {
                        Text(
                            text = stringResource(I18nMap.res(DrumSynth.labelKey(pad))),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (pad == current) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = accent,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Chip(text: String, active: Boolean, accent: Color, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (active) accent.copy(alpha = 0.18f) else CzTokens.surface,
        contentColor = if (active) accent else CzTokens.textSecondary,
        border = BorderStroke(1.dp, CzTokens.hairline),
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun IconChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    accent: Color,
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (active) accent.copy(alpha = 0.18f) else CzTokens.surface,
        contentColor = if (active) accent else CzTokens.textSecondary,
        border = BorderStroke(1.dp, CzTokens.hairline),
    ) {
        Icon(
            icon,
            contentDescription = label,
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp).size(16.dp),
        )
    }
}

@Composable
private fun RoundStepButton(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = CzTokens.surface,
        contentColor = CzTokens.textPrimary,
        modifier = Modifier.size(34.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
        }
    }
}
